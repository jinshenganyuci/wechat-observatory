"""Official Hermes platform adapter; Hermes owns conversation and reply recovery."""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging

import aiohttp
from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType
from gateway.platforms._shared import extra_or_secret
from hermes_constants import get_hermes_home

from .client import BridgeError, Client, Settings, HISTORY_PAGE_SIZE, file_description
from .state import State, digest

log = logging.getLogger(__name__)
_plugin_context = None


def settings_from_config(config) -> Settings:
    extra = config.extra or {}
    return Settings(str(extra.get("base_url", "")).rstrip("/"),
                    str(extra.get("device", "")), str(extra.get("owner_wxid", "")),
                    str(extra_or_secret(extra, "admin_password", "OBSERVATORY_ADMIN_PASSWORD")))


def validate_config(config) -> bool:
    try:
        settings_from_config(config)
        return True
    except (ValueError, TypeError):
        return False


def valid_target(target: str) -> bool:
    return bool(target and target.strip() == target and len(target) <= 256
                and not any(c.isspace() or ord(c) < 32 for c in target)
                and not any(c in target for c in "/\\?#"))


class ObservatoryAdapter(BasePlatformAdapter):
    # Own the length decision: v0.22.2 otherwise truncates cron text and adds
    # a changing audit-path footer. send() rejects >8000 without splitting.
    splits_long_messages = True

    def __init__(self, config):
        super().__init__(config, Platform("observatory"))
        self.settings = settings_from_config(config)
        self.client = Client(self.settings)
        self.state = State(get_hermes_home() / "observatory" / (self.settings.scope + ".sqlite3"))
        self._reader = None
        self._ready = asyncio.Event()
        self._send_lock = asyncio.Lock()
        self._receive_active = False

    @property
    def send_path_degraded(self):
        return not self._receive_active

    def _chat_id(self, peer):
        return self.settings.scope + ":" + peer

    def _peer(self, chat_id):
        if ":" in chat_id:
            prefix, peer = chat_id.split(":", 1)
            if prefix != self.settings.scope:
                raise ValueError("chat belongs to a different Observatory account binding")
        else:
            peer = chat_id
        if not valid_target(peer):
            raise ValueError("invalid WeChat target")
        return peer

    async def connect(self, *, is_reconnect=False):
        if self._reader and not self._reader.done():
            return True
        if not self._acquire_platform_lock("observatory-account", self.settings.scope, "Observatory account"):
            return False
        try:
            await self.client.generation()
            self._running = True
            self._ready.clear()
            self._reader = asyncio.create_task(self._receive_loop(), name="observatory-events")
            await asyncio.wait_for(self._ready.wait(), timeout=25)
            return not self.has_fatal_error
        except (Exception, asyncio.CancelledError):
            await self.disconnect()
            raise

    async def disconnect(self):
        self._running = False
        if self._reader:
            self._reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reader
            self._reader = None
        self._receive_active = False
        await self.client.close()
        self._release_platform_lock()
        self._mark_disconnected()

    async def _receive_loop(self):
        delay = 1
        while self._running:
            try:
                # History is ordered by sequence_id. event.id is a WeChat/source id,
                # never a durable stream cursor. The first stream ready pins HEAD.
                cursor = self.state.cursor
                if cursor is not None:
                    while True:
                        rows = await self.client.history(cursor)
                        rows.sort(key=lambda row: int(row["sequence_id"]))
                        for row in rows:
                            await self._ingest(row, int(row["sequence_id"]), historical=True)
                        if len(rows) < HISTORY_PAGE_SIZE:
                            break
                        next_cursor = self.state.cursor
                        if next_cursor <= cursor:
                            raise BridgeError("history_cursor_not_advancing")
                        cursor = next_cursor
                else:
                    await self.client.history(None)  # Validate access; intentionally discard old messages.
                async for kind, sequence, event in self.client.events(self.state.cursor):
                    if kind == "ready":
                        if "cursor" not in event:
                            raise BridgeError("durable_event_stream_required")
                        self.state.initialize(int(event["cursor"]))
                        self._receive_active = True
                        self._mark_connected()
                        self._ready.set()
                        delay = 1
                    elif kind == "message":
                        if sequence is None:
                            raise BridgeError("durable_event_id_required")
                        await self._ingest(event, sequence)
                    elif kind == "error":
                        raise BridgeError("event_stream_error")
                raise BridgeError("event_stream_closed")
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._receive_active = False
                if isinstance(exc, BridgeError) and exc.status in {401, 403}:
                    self._set_fatal_error("observatory_auth", "Observatory credentials were rejected", retryable=False)
                    self._ready.set()
                    return
                self._mark_degraded()
                log.warning("Observatory receive interrupted (%s); reconnecting", type(exc).__name__)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30)

    async def _ingest(self, row, sequence, *, historical=False):
        peer = row.get("from_wxid") or row.get("from") or ""
        sender = row.get("sender_wxid") or row.get("sender") or peer
        target = row.get("to_wxid") or row.get("to") or ""
        chat = row.get("chat_id") or peer
        text = row.get("text")
        allowed = (row.get("device") == self.settings.device
                   and row.get("owner_wxid") == self.settings.owner_wxid
                   and row.get("direction") == "recv" and not row.get("is_send")
                   and row.get("message_type") == 1 and row.get("chat_kind") == "direct"
                   and not row.get("room_id") and "@chatroom" not in chat.lower()
                   and peer == sender == chat and peer != self.settings.owner_wxid
                   and (not target or target == self.settings.owner_wxid)
                   and valid_target(peer) and isinstance(text, str) and bool(text.strip())
                   and row.get("raw_provider") != "module_ack")
        # Old slash commands are intentionally not re-executed by history catch-up.
        if historical and isinstance(text, str) and text.lstrip().startswith("/"):
            allowed = False
        key = row.get("event_key")
        if not isinstance(key, str) or not key:
            allowed = False  # Old bridges without durable event keys are unsupported.
        if not self.state.claim(sequence, key if allowed else None):
            return
        source = self.build_source(chat_id=self._chat_id(peer), chat_name=peer,
                                   chat_type="dm", user_id=peer, user_name=peer, message_id=key)
        event = MessageEvent(text=text, source=source, message_type=MessageType.TEXT,
                             message_id=key, user_id=peer, user_name=peer,
                             raw_message={"event_key": key, "sequence_id": sequence})
        # Intentionally no custom allowlist: native Hermes auth/pairing must run.
        await self.handle_message(event)

    async def get_chat_info(self, chat_id):
        peer = self._peer(chat_id)
        return {"name": peer, "type": "group" if peer.endswith("@chatroom") else "dm"}

    def _identity(self, chat_id, reply_to, fingerprint):
        # When Hermes supplies a cron execution context, both live and
        # standalone sends can use it. Recovery does not always supply one.
        if _plugin_context:
            execution = _plugin_context.current_cron_execution()
            if execution and execution.execution_id:
                return "cron:" + digest([execution.profile, execution.execution_id, chat_id, fingerprint])
        if reply_to:
            return "reply:" + digest([chat_id, reply_to, fingerprint])
        return None

    def _send_retry_is_final(self, result):
        # Upstream's platform-specific refusal hook (also used by Photon).
        # We already send plain text and retry the SAME HTTP request here;
        # changing the body cannot repair a rejection or an unknown outcome.
        # Hermes' durable ledger still owns subsequent reply recovery.
        return True

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        if not content or len(content) > 8000:
            return SendResult(success=False, error="Observatory text must contain 1–8000 characters")
        try:
            peer = self._peer(chat_id)
            return await self._send_payload(chat_id, {"kind": "text", "wx_ids": [peer], "text": content},
                                            reply_to)
        except Exception as exc:
            detail = str(exc) if isinstance(exc, (ValueError, BridgeError)) else type(exc).__name__
            return SendResult(success=False, error=f"Observatory send refused: {detail}")

    async def send_document(self, chat_id, file_path, caption=None, file_name=None,
                            reply_to=None, metadata=None, **kwargs):
        try:
            peer = self._peer(chat_id)
            attachment = await asyncio.to_thread(file_description, file_path, file_name)
            if caption:
                caption_result = await self.send(chat_id, caption, reply_to=reply_to, metadata=metadata)
                if not caption_result.success:
                    return caption_result
            return await self._send_payload(chat_id, {"kind": "file", "wxid": peer, "attachment": attachment},
                                            reply_to)
        except Exception as exc:
            detail = str(exc) if isinstance(exc, (ValueError, BridgeError)) else type(exc).__name__
            return SendResult(success=False, error=f"Observatory document refused: {detail}")

    async def _send_payload(self, chat_id, content, reply_to=None):
        # Local source path is not part of the logical file's identity.
        logical = dict(content)
        if "attachment" in logical:
            logical["attachment"] = {k: v for k, v in content["attachment"].items() if k != "path"}
        fingerprint = digest(logical)
        identity = self._identity(chat_id, reply_to, fingerprint)
        async with self._send_lock:
            previous = self.state.find_intent(identity) if identity else None
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise ValueError("send identity already belongs to different content")
                record = previous
            else:
                generation = await self.client.generation()
                payload = {**content, "device": self.settings.device, "owner_wxid": self.settings.owner_wxid,
                           "account_generation": generation}
                record = self.state.prepare(identity, fingerprint, payload)
            return await self._submit_record(record)

    async def _submit_record(self, record):
        if record["outbox_id"]:
            return SendResult(success=True, message_id=str(record["outbox_id"]),
                              raw_response={"status": "accepted", "idempotent_replay": True})
        if record["status"] == "rejected":
            return SendResult(success=False, error=record["error"], raw_response={"status": "rejected"})
        # A downgraded/replaced server must not
        # accept an old retry while silently ignoring its idempotency header.
        await self.client.ensure_idempotency()
        payload = json.loads(record["payload"])
        for attempt in range(2):
            try:
                result = await self.client.submit(payload, record["idem"])
                outbox_id = result.get("outbox_id")
                if not result.get("ok") or not isinstance(outbox_id, int) or outbox_id <= 0:
                    raise BridgeError("invalid_send_receipt")
                self.state.accepted(record["intent"], outbox_id)
                return SendResult(success=True, message_id=str(outbox_id),
                                  raw_response={"status": "accepted", "idempotent_replay": result.get("idempotent_replay", False)})
            except (BridgeError, aiohttp.ClientError, TimeoutError) as exc:
                if isinstance(exc, BridgeError) and exc.status and exc.status < 500:
                    # These responses prove this exact request was rejected. An
                    # idempotency conflict/auth failure stays unresolved for review.
                    rejected = exc.status in {400, 413, 415, 422} or (exc.status == 409 and exc.code in {
                            "account_session_conflict", "account_generation_conflict", "module_unavailable",
                            "module_offline", "owner_mismatch", "file_task_conflict"})
                    rejected = rejected or (payload["kind"] == "text" and exc.status == 409 and exc.code == "send_failed")
                    if rejected:
                        self.state.rejected(record["intent"], str(exc))
                    else:
                        self.state.needs_review(record["intent"], str(exc))
                    return SendResult(success=False, error=str(exc), raw_response={"intent": record["intent"],
                        "status": "rejected" if rejected else "needs_review"})
                if attempt == 0:
                    await asyncio.sleep(0.25)
        error = "Observatory send outcome unknown; original request/key retained for review"
        self.state.needs_review(record["intent"], error)
        return SendResult(success=False, error=error,
                          raw_response={"intent": record["intent"], "status": "needs_review"}, retryable=False)


async def standalone_sender(config, chat_id, message, *, thread_id=None, media_files=None, force_document=False):
    if thread_id:
        return {"error": "Observatory does not support threads"}
    try:
        message.encode("utf-8")
    except UnicodeEncodeError:
        return {"error": "Notification text contains invalid Unicode; nothing was sent. Correct the text and try again."}
    if len(message) > 8000:
        return {"error": "Observatory notification text exceeds 8000 characters; nothing was sent. Shorten the notification."}
    execution = _plugin_context.current_cron_execution() if _plugin_context else None
    if execution and execution.execution_id:
        # Hermes' cron live lane strips the caption before calling send().
        message = message.strip()
    adapter = ObservatoryAdapter(config)
    try:
        files = []
        for item in media_files or []:
            path, is_voice = item if isinstance(item, (list, tuple)) else (item, False)
            if is_voice:
                return {"error": "Observatory supports ordinary files, not voice messages"}
            files.append(await asyncio.to_thread(file_description, path))
        if not message and not files:
            return {"error": "empty notification"}
        manifest = digest([message, [{k: v for k, v in f.items() if k != "path"} for f in files]])
        identity = None
        if execution and execution.execution_id:
            identity = "cron-bundle:" + digest([execution.profile, execution.execution_id, chat_id])
        bundle = adapter.state.bundle(identity, chat_id, manifest)
        result = None
        if message:
            result = await adapter.send(chat_id, message, reply_to=bundle + ":text")
            if not result.success:
                if (result.raw_response or {}).get("status") == "rejected":
                    adapter.state.finish_bundle(bundle)
                return {"error": result.error}
        for index, attachment in enumerate(files):
            result = await adapter.send_document(chat_id, attachment["path"],
                reply_to=bundle + ":file:" + str(index))
            if not result.success:
                if (result.raw_response or {}).get("status") == "rejected":
                    adapter.state.finish_bundle(bundle)
                return {"error": result.error}
        adapter.state.finish_bundle(bundle)
        return {"success": True, "message_id": result.message_id, "media_delivered": bool(files)}
    except (ValueError, BridgeError) as exc:
        return {"error": str(exc)}
    finally:
        await adapter.client.close()
        adapter.state.close()


def register(ctx):
    global _plugin_context
    _plugin_context = ctx
    from .setup import interactive_setup
    ctx.register_platform("observatory", "WeChat Observatory", ObservatoryAdapter,
                          check_fn=lambda: True, validate_config=validate_config,
                          setup_fn=interactive_setup, standalone_sender_fn=standalone_sender,
                          # Keep each notification whole for the durable manifest;
                          # standalone_sender rejects over 8000 before any send.
                          allowed_users_env="OBSERVATORY_ALLOWED_USERS", max_message_length=0,
                          parse_target_ref_fn=lambda ref: (ref, None) if valid_target(ref) else None,
                          validate_target_ref_fn=valid_target, display_tier="low",
                          cron_deliver_env_var="OBSERVATORY_HOME_CHANNEL",
                          allow_update_command=False, trusted_inbound=False,
                          platform_hint="Private WeChat text conversation through Observatory. File sends support ordinary documents.")
