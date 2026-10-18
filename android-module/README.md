# WeChat Observatory LSPosed Module

This is the companion module for `wechat-observatory`.

It runs inside WeChat through LSPosed and posts message DB insert events to the
gateway. The gateway stores WeChat events and queues admin-created send actions
for the module to receive and send from inside the WeChat process.

This fork targets WeChat for Android **8.0.79 / 3200 arm64**. See the [0.1.21 upgrade notes](../docs/compatibility/wechat-8079-0121.md).
WeChat internals change between releases; validate message observation, contact
sync, media upload, and text send after upgrading WeChat.

Version 0.1.21 keeps the 0.1.18 send flow and private configuration storage. Only read-only account queries gain a bounded wait; control-frame checks use the last validated binding, while business sends still validate the live account. It does not add a module background service, wake lock, power-event reconnect monitor or ACK retry coordinator.

Version 0.1.23 adds ordinary file notifications through the existing outbox. A
separate serial file worker downloads and verifies the attachment, persists an
intent, obtains a one-time server handoff grant, and calls the verified WeChat
8.0.79 native file sender. It leaves the WebSocket reader free to answer control
frames. See [file API, duplicate prevention and cleanup](../docs/file-send.md).
Private staging is a sibling directory `no_backup/observatory-outgoing`, not
part of the configuration directory. Native WeChat attachment copies are kept.

## Hook Point

The module scopes to `com.tencent.mm` and hooks:

```text
com.tencent.wcdb.database.SQLiteDatabase.insertWithOnConflict(
  String table,
  String nullColumnHack,
  ContentValues values,
  int conflictAlgorithm
)
```

For `table == "message"`, it reads:

- `talker`
- `content`
- `isSend`
- `msgId`
- `createTime`
- `type`

Then it posts an event to:

```text
POST <bridge_url>/webhook/lsposed/message
```

Text messages are posted as their normalized text. Non-text message rows are
also posted with a stable placeholder such as `[图片]`, `[语音]`, `[视频]`,
`[表情]`, `[位置]`, or `[链接/文件]`, and WeChat's numeric `message_type` is
preserved. When the module can find the underlying WeChat media file, it uploads
`media_base64` plus `media_kind`, `media_mime`, `media_name`, and `media_size`
so the gateway can render an actual preview or player.

## Runtime Config

Install the module, enable it in LSPosed with WeChat selected, then force-stop
and reopen WeChat. Open **WeChat settings → Observatory 网关** and enter your
server base URL and API Key. Save applies immediately. WebSocket is enabled by
default; disabling its switch retains the HTTP-only transport.

Configuration is owned by the WeChat main process under
`Context.getNoBackupFilesDir()/observatory/observatory.properties`. The path is
resolved from WeChat's context for the current Android user. Reads and atomic
writes run off the main thread, and message handling uses an in-memory snapshot.
The module launcher only explains installation and opens WeChat when requested.
No module process startup, Binder configuration service, Provider, storage
permission, public-folder selection, or periodic file polling is required.

Upgrading from 0.1.17 or earlier requires entering the address and API Key again
inside WeChat. Old module-private/public files are never read as fallbacks.
Any former `Documents/WeChatObservatory` folder can be removed manually after
checking the new setup.

Use **停用并删除配置** inside WeChat before uninstalling the module. It deletes
only this version's dedicated configuration files and discards the runtime
snapshot/session. Reads after deletion leave the directory absent. Uninstalling
the module alone cannot remove files owned by WeChat; reinstall the module to
clear them, or remove the dedicated directory with root. Never clear WeChat's
entire application data just to remove Observatory configuration.

The existing advanced settings use their defaults on this fresh configuration;
normal edits preserve keys already present in this private store. Account wxid
is always obtained from the running WeChat account, never operator input.

`contact_sync_interval_ms=0` disables friend sync. The default is 10 minutes.
`contact_sync_limit` caps one snapshot upload. `contact_include_chatrooms=1`
includes chatrooms and is the default.

The module treats a message observed through either the WCDB insert hook or
the fallback poll as historical when its WeChat `createTime` is more than
`stale_message_grace_ms` old. It reports at most
`stale_message_replay_limit` such rows during one WeChat process lifetime, then
skips further stale rows. Newly created messages are never counted against
that cap. Defaults are 15 minutes and 500 rows; leave these values unchanged
unless a controlled recovery needs a different bound.

## Outbox Protocol

When WeChat loads, the module:

1. Registers the current WeChat identity with `/module/register` when
   `api_key` is configured and the current WeChat `wxid` has been detected.
2. Uses the WebSocket outbox channel by default, tied to the current account session.
3. Attempts to send each returned item from inside WeChat.
4. Reports results with `POST /module/outbox/ack`.

With `outbox_websocket_enabled=1` (the default), the module uses
`GET /module/outbox/ws?api_key=...&device=...&wxid=...` for lower delivery
latency. If that connection drops, the module falls back to the same HTTP
polling and ACK endpoints.

The outbox worker runs independently from registration, contact sync, and
inbound message polling. Account/session changes invalidate an old connection
and its pending deliveries in either transport mode.

The send hook is intentionally isolated in `HookEntry.sendText`. For WeChat
Android `8.0.75`, it uses the lower-level send builder path:

```text
w11.s1.a(toUser) -> w11.r1.g/e/h(...) -> w11.r1.a().a()
```

If that builder path is unavailable, it falls back to
`tg3.t1.a() -> dk5.s5.fj(...)`. Live validation showed that path can hit
WeChat's service-locator initialization guard from the module worker thread, so
the builder is the safer primary path. Both paths are WeChat-process calls; the
module does not parse or execute commands.

The final boolean returned by `w11.n1.a()` is NetScene queue admission, not
builder construction. The builder has already inserted one local message at
that point. When the queue is busy, the module waits on its outbox worker and
retries the same prepared request on WeChat's main thread. It never rebuilds the
message or switches to a fallback after a request exists, which prevents a
queue-busy result from creating duplicate outgoing rows.

On WeChat Android `8.0.75`, the installed app can run through Tinker. The send
adapter must resolve WeChat classes with the patched runtime loader returned by
`Application.getClassLoader()`, which can be a `DelegateLastClassLoader` over
`/data/user/0/com.tencent.mm/tinker/.../tinker_classN.apk`. Using the base
`PathClassLoader` can load duplicate native libraries and leave WeChat kernel
services uninitialized.

After changing WeChat versions, validate the send path on a test account before
using it with real conversations. The module should ACK `sent` only after the
WeChat-process send call returns without an error.

The builder request exposes its local message ID on the current compatibility
target. The module includes that value as `chat_record_id` when it can read it;
the field remains optional for fallback paths and future WeChat versions.

## Contact Snapshot

The module remembers the WeChat WCDB object from `message` or `rcontact` table
writes. On the worker loop it best-effort reads:

```sql
SELECT username,nickname,conRemark,alias,type,verifyFlag
FROM rcontact
WHERE username IS NOT NULL AND username <> ''
LIMIT ?
```

It uploads ordinary friends plus the special `filehelper` conversation to:

```text
POST <bridge_url>/module/contacts/snapshot
```

with payload:

```json
{
  "api_key": "wg_dev_key",
  "device": "phone-a",
  "wxid": "wxid_current",
  "complete": true,
  "contacts": [
    {
      "wxid": "wxid_friend",
      "nickname": "Friend",
      "remark": "Remark",
      "alias": "alias",
      "type": 3,
      "verify_flag": 0,
      "chatroom": false,
      "deleted": false
    }
  ]
}
```

If the database has not been captured yet, or the query returns no friends, the
module skips upload and keeps message/outbox behavior running. `filehelper` is
kept even when WeChat stores it as a system/non-friend row, and a blank nickname
is reported as `文件传输助手`. The module does not parse or execute business
commands.

When reinstalling the module under the current Vector/LSPosed runtime, verify
the module row in `/data/adb/lspd/config/modules_config.db` still points at the
new installed `base.apk`, keep the scope on `com.tencent.mm`, and reboot before
validating send behavior. Android changes the APK path after reinstall.
