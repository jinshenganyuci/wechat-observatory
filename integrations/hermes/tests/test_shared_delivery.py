"""Stock Hermes auth, sessions and recovery with bounded transport/runner fixtures.

No gateway service, real account, model or phone is contacted. These tests retain
upstream's uncertain-delivery boundary instead of asserting a patched guarantee.
"""
from __future__ import annotations

import asyncio
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from gateway import delivery_ledger as ledger
from gateway.config import GatewayConfig, PlatformConfig
from gateway.platform_registry import PlatformEntry, platform_registry
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent
from gateway.run import GatewayRunner
from gateway.session import build_session_key
from observatory import adapter as plugin
from observatory.adapter import ObservatoryAdapter


class Bridge:
    """A backend that can commit and then lose its HTTP response."""

    def __init__(self):
        self.calls = []
        self.tasks = {}
        self.generation_value = 1
        self.lose = 0

    async def generation(self):
        return self.generation_value

    async def ensure_idempotency(self):
        return None

    async def history(self, cursor):
        return []

    async def events(self, cursor):
        yield "ready", None, {"cursor": 10 if cursor is None else cursor}
        await asyncio.Event().wait()

    async def submit(self, payload, key):
        self.calls.append((json.loads(json.dumps(payload)), key))
        replay = key in self.tasks
        self.tasks.setdefault(key, len(self.tasks) + 100)
        if self.lose:
            self.lose -= 1
            raise TimeoutError("backend committed; HTTP response was lost")
        return {"ok": True, "outbox_id": self.tasks[key], "idempotent_replay": replay}

    async def close(self):
        return None

    async def contact_name(self, wxid):
        return "Synthetic group" if wxid.endswith("@chatroom") else "Synthetic " + wxid


@pytest.fixture
def lane(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    for name in ("OBSERVATORY_ADMIN_PASSWORD", "OBSERVATORY_ALLOWED_USERS",
                 "GATEWAY_ALLOWED_USERS", "GATEWAY_ALLOW_ALL_USERS"):
        monkeypatch.delenv(name, raising=False)
    (tmp_path / "config.yaml").write_text("gateway:\n  delivery_ledger: true\n", encoding="utf-8")
    monkeypatch.setattr(ledger, "_db_path", lambda: tmp_path / "state.db")
    monkeypatch.setattr(plugin, "get_hermes_home", lambda: tmp_path)
    context = SimpleNamespace(
        current_cron_execution=lambda: None,
        register_platform=lambda name, label, adapter, **options:
            platform_registry.register(PlatformEntry(name, label, adapter, **options)),
    )
    monkeypatch.setattr(plugin, "_plugin_context", context)
    plugin.register(context)
    config = PlatformConfig(enabled=True, extra={
        "base_url": "http://127.0.0.1:1", "device": "synthetic",
        "owner_wxid": "wxid_synthetic_bot", "admin_password": "synthetic-only",
        "allow_from": ["alice"], "allow_admin_from": ["alice"],
        "unauthorized_dm_behavior": "pair",
    })
    bridge, adapters = Bridge(), []

    def new_adapter():
        adapter = ObservatoryAdapter(config)
        adapter.client = bridge
        adapters.append(adapter)
        return adapter

    value = SimpleNamespace(home=tmp_path, config=config, bridge=bridge, new_adapter=new_adapter)
    value.adapter = new_adapter()
    yield value
    for adapter in adapters:
        adapter.state.close()


@pytest.fixture
def native_media(lane, monkeypatch):
    from hermes_cli.plugins import PluginContext

    # Use Hermes' real ContextVar reader, not a fabricated cron identity.
    monkeypatch.setattr(plugin._plugin_context, "current_cron_execution",
                        lambda: PluginContext.current_cron_execution(None))
    monkeypatch.setattr(plugin, "Client", lambda _settings: lane.bridge)
    config = GatewayConfig(platforms={lane.adapter.platform: lane.config})
    monkeypatch.setattr("gateway.config.load_gateway_config", lambda: config)
    # Registration has already run in lane. Avoid discovering unrelated installed
    # plugins; keep native target resolution, media filtering and send policy intact.
    monkeypatch.setattr("hermes_cli.plugins.discover_plugins", lambda: None)
    path = lane.home / "ordinary-report.txt"
    path.write_text("Synthetic ordinary document.\n", encoding="utf-8")
    lane.path = path
    lane.media_policy = hasattr(platform_registry.get("observatory"), "standalone_send")
    return lane


@pytest.mark.parametrize("caption", ["", "Report ready\n"], ids=["file-only", "text-and-file"])
def test_official_cli_media_route(native_media, capsys, caption):
    from hermes_cli.send_cmd import cmd_send

    lane = native_media
    args = SimpleNamespace(to="observatory:filehelper", message=f"{caption}MEDIA:{lane.path}", json=True)
    with pytest.raises(SystemExit) as stopped:
        cmd_send(args)
    result = json.loads(capsys.readouterr().out)
    if not caption and not lane.media_policy:
        # Stock v0.22.2 refuses this before calling any plugin sender.
        assert stopped.value.code == 1
        assert "only media attachments" in result["error"]
        assert not lane.bridge.calls
        return
    assert stopped.value.code == 0, result
    assert result.get("success") and result.get("media_delivered"), result
    assert not result.get("warnings"), result
    kinds = [body["kind"] for body, _key in lane.bridge.calls]
    assert kinds == (["text", "file"] if caption else ["file"])
    attachment = lane.bridge.calls[-1][0]["attachment"]
    assert attachment["name"] == lane.path.name
    assert attachment["size"] == lane.path.stat().st_size


def cron_media_send(lane, caption, execution_id):
    from cron.execution_identity import enter_cron_execution, exit_cron_execution
    from cron.scheduler_delivery import _standalone_send

    job = {"id": "synthetic-file-job", "name": "Synthetic file delivery"}
    target = SimpleNamespace(job=job, platform=lane.adapter.platform, pconfig=lane.config,
                             chat_id="filehelper", thread_id=None, where="observatory:filehelper")
    token = enter_cron_execution(job, execution_id, {"source": "direct"})
    try:
        result, error = _standalone_send(target, caption, [(str(lane.path), False)])
        assert error is None, error
        return result
    finally:
        exit_cron_execution(token)


@pytest.mark.parametrize("caption", ["", "Report ready\n"], ids=["file-only", "text-and-file"])
def test_official_cron_original_execution_does_not_reupload_accepted_file(native_media, caption):
    lane = native_media
    first = cron_media_send(lane, caption, "original-execution")
    if not caption and not lane.media_policy:
        assert "only media attachments" in first["error"]
        assert not lane.bridge.calls
        return
    assert first.get("success") and first.get("media_delivered"), first
    original_calls = list(lane.bridge.calls)
    second = cron_media_send(lane, caption, "original-execution")
    assert second.get("success") and second["message_id"] == first["message_id"], second
    assert lane.bridge.calls == original_calls  # receipt is known: no second upload at all
    assert sum(body["kind"] == "file" for body, _key in original_calls) == 1

    # A later execution is new work, even when its file and caption are identical.
    later = cron_media_send(lane, caption, "later-execution")
    assert later.get("success") and later["message_id"] != first["message_id"], later
    assert sum(body["kind"] == "file" for body, _key in lane.bridge.calls) == 2


def test_official_cron_lost_file_ack_reuses_original_key_then_stops_uploading(native_media):
    lane = native_media
    original_submit = lane.bridge.submit
    losses = 2

    async def lose_file_ack(body, key):
        nonlocal losses
        if body["kind"] == "file" and losses:
            losses -= 1
            lane.bridge.lose = 1
        return await original_submit(body, key)

    lane.bridge.submit = lose_file_ack
    first = cron_media_send(lane, "Report ready", "lost-ack-execution")
    assert first.get("error"), first
    second = cron_media_send(lane, "Report ready", "lost-ack-execution")
    assert second.get("success"), second
    file_calls = [call for call in lane.bridge.calls if call[0]["kind"] == "file"]
    assert len(file_calls) == 3 and all(call == file_calls[0] for call in file_calls)
    assert len(lane.bridge.tasks) == 2  # one caption and one accepted attachment
    assert sum(body["kind"] == "text" for body, _key in lane.bridge.calls) == 1
    accepted_calls = list(lane.bridge.calls)
    assert cron_media_send(lane, "Report ready", "lost-ack-execution").get("success")
    assert lane.bridge.calls == accepted_calls


def event_for(adapter, event_id="event-one", peer="alice"):
    return MessageEvent(text="a synthetic question", message_id=event_id,
                        source=adapter.build_source(chat_id=adapter._chat_id(peer), user_id=peer))


async def final(adapter, event, text="same answer"):
    result, _owner = await adapter.send_final_ledgered(
        event, build_session_key(event.source), text, {}, reply_to=event.message_id)
    return result


def rows():
    with ledger._connect() as db:
        names = [column[0] for column in db.execute("SELECT * FROM delivery_obligations LIMIT 0").description]
        return [dict(zip(names, row)) for row in db.execute("SELECT * FROM delivery_obligations")]


def orphan():
    with ledger._connect() as db:
        db.execute("UPDATE delivery_obligations SET owner_pid=2147483647, owner_started_at=1")


def runner_for(*adapters):
    runner = object.__new__(GatewayRunner)
    runner.adapters = {adapter.platform: adapter for adapter in adapters}
    runner._profile_adapters = {}
    runner._active_profile_name = lambda: "default"
    runner.config = GatewayConfig()
    runner.session_store = None
    runner._async_session_store = MagicMock()
    runner._async_session_store._store = None
    runner._async_session_store.clear_resume_pending = AsyncMock()
    runner._running = False
    runner.pairing_store = None
    return runner


def test_adapter_inherits_stock_lifecycle(lane):
    for name in ("handle_message", "send_final_ledgered", "on_processing_start"):
        assert getattr(ObservatoryAdapter, name) is getattr(BasePlatformAdapter, name)
    assert not hasattr(lane.adapter, "recover_pending")
    assert "idempotent_delivery" not in ObservatoryAdapter.__dict__


@pytest.mark.asyncio
async def test_connect_on_stock_hermes_does_not_replay_old_unknown_records(lane):
    adapter = lane.adapter
    body = {"kind": "text", "wx_ids": ["alice"], "text": "old request", "account_generation": 1}
    old = adapter.state.prepare("old-patch-id", "old-fingerprint", body)
    adapter.state.initialize(10)
    adapter.state.claim(11, "old-inbound")
    adapter.state.db.execute("DELETE FROM meta WHERE key='native_delivery_v3'")
    adapter.state.db.commit()
    restarted = lane.new_adapter()
    saved = restarted.state.find_intent(old["intent"])
    assert saved["status"] == "needs_review"
    assert saved["idem"] == old["idem"] and saved["payload"] == old["payload"]
    assert restarted.state.cursor == 11
    assert not restarted.state.claim(12, "old-inbound")
    try:
        assert await restarted.connect()
        assert restarted.send_path_degraded is False
        assert not lane.bridge.calls
    finally:
        await restarted.disconnect()


@pytest.mark.asyncio
async def test_native_final_reply_uses_enabled_ledger_without_patch(lane):
    result = await final(lane.adapter, event_for(lane.adapter))
    assert result.success
    assert len(lane.bridge.tasks) == 1
    assert rows()[0]["state"] == "delivered"
    assert (lane.home / "config.yaml").read_text() == "gateway:\n  delivery_ledger: true\n"


@pytest.mark.asyncio
async def test_lost_http_ack_retries_exact_request_with_one_backend_task(lane):
    lane.bridge.lose = 1
    result = await final(lane.adapter, event_for(lane.adapter))
    assert result.success
    assert len(lane.bridge.calls) == 2 and lane.bridge.calls[0] == lane.bridge.calls[1]
    assert len(lane.bridge.tasks) == 1


@pytest.mark.asyncio
async def test_explicit_original_reply_identity_can_resolve_unknown_after_restart(lane):
    lane.bridge.lose = 2
    assert not (await lane.adapter.send("alice", "same answer", reply_to="turn-one")).success
    original = lane.bridge.calls[0]
    assert lane.adapter.state.unresolved()[0]["status"] == "needs_review"
    lane.bridge.generation_value = 99
    restarted = lane.new_adapter()
    assert (await restarted.send("alice", "same answer", reply_to="turn-one")).success
    assert len(lane.bridge.tasks) == 1
    assert all(call == original for call in lane.bridge.calls)


@pytest.mark.asyncio
async def test_equal_text_in_distinct_requests_stays_distinct(lane):
    first = await final(lane.adapter, event_for(lane.adapter, "one"))
    second = await final(lane.adapter, event_for(lane.adapter, "two"))
    third = await lane.adapter.send("alice", "same answer")
    fourth = await lane.adapter.send("alice", "same answer")
    assert all(result.success for result in (first, second, third, fourth))
    assert len({result.message_id for result in (first, second, third, fourth)}) == 4
    assert len(lane.bridge.tasks) == 4


@pytest.mark.asyncio
async def test_ingress_replay_reaches_native_admission_once(lane):
    adapter = lane.adapter
    adapter.state.initialize(10)
    row = {"device": "synthetic", "owner_wxid": "wxid_synthetic_bot", "direction": "recv",
           "message_type": 1, "chat_kind": "direct", "from_wxid": "alice",
           "to_wxid": "wxid_synthetic_bot", "text": "/status", "event_key": "inbound-one"}
    adapter.set_message_handler(AsyncMock())
    # Keep the native intake/identity code; stop at its background task boundary.
    with patch.object(adapter, "_start_session_processing", return_value=True) as admitted:
        await adapter._ingest(row, 11)
        await adapter._ingest(row, 12)
    assert admitted.call_count == 1
    assert admitted.call_args.args[0].message_id == "inbound-one"
    assert adapter.state.cursor == 12
    assert not lane.bridge.calls


def test_native_auth_and_session_isolation_remain_in_charge(lane):
    runner = runner_for(lane.adapter)
    alice = event_for(lane.adapter).source
    stranger = event_for(lane.adapter, peer="stranger").source
    assert runner._is_user_authorized(alice)
    assert not runner._is_user_authorized(stranger)
    assert runner._get_unauthorized_dm_behavior(lane.adapter.platform) == "pair"
    first = build_session_key(alice)
    assert first == build_session_key(event_for(lane.adapter, "later").source)
    assert first != build_session_key(stranger)
    assert ":observatory:" in first
    assert build_session_key(alice, profile="separate") != first


@pytest.mark.asyncio
async def test_group_admin_mention_uses_native_auth_context_sessions_and_group_reply(lane):
    from gateway.session import SessionStore
    from gateway.slash_access import policy_for_source
    from hermes_state import SessionDB

    adapter = lane.adapter
    lane.config.extra.update(group_admin_mentions=True, group_allow_from=["alice"],
                             group_allow_admin_from=["alice"])
    adapter.state.initialize(10)
    runner = runner_for(adapter)
    runner.config = GatewayConfig(platforms={adapter.platform: lane.config})
    original_isolation = runner.config.group_sessions_per_user
    captured = []

    async def native_boundary(event):
        # Keep the real native authorization decision. The synthetic handler
        # below only stands in for the model and records admitted events.
        assert runner._is_user_authorized(event.source)
        captured.append(event)

    adapter.handle_message = native_boundary
    def group(room, event_key):
        return {"device": "synthetic", "owner_wxid": "wxid_synthetic_bot", "direction": "recv",
                "message_type": 1, "chat_kind": "room", "from_wxid": room, "chat_id": room,
                "room_id": room, "sender_wxid": "alice", "to_wxid": "wxid_synthetic_bot",
                "mentioned_wxids": ["wxid_synthetic_bot"], "text": "@Bot synthetic question",
                "event_key": event_key}

    await adapter._ingest(group("111@chatroom", "group-a-one"), 11)
    await adapter._ingest(group("222@chatroom", "group-b-one"), 12)
    await adapter._ingest(group("111@chatroom", "group-a-two"), 13)
    assert len(captured) == 3
    first, other, later = captured
    assert first.source.user_id == first.user_id == "alice"
    assert first.source.chat_type == "group" and first.source.thread_id is None
    assert not first.source.role_authorized and not first.internal
    assert policy_for_source(runner.config, first.source).is_admin("alice")
    prompt_text = runner._prefix_inbound_sender_context(first, first.source, first.text)
    for value in ("111@chatroom", '"sender_wxid": "alice"', "Synthetic group", "Synthetic alice"):
        assert value in prompt_text

    store = SessionStore(sessions_dir=lane.home / "sessions", config=runner.config)
    if store._db is not None:
        store._db.close()
    store._db = SessionDB(db_path=lane.home / "state.db")
    try:
        entry_a = store.get_or_create_session(first.source)
        entry_b = store.get_or_create_session(other.source)
        entry_dm = store.get_or_create_session(event_for(adapter).source)
        assert store.get_or_create_session(later.source).session_id == entry_a.session_id
        assert len({entry_a.session_id, entry_b.session_id, entry_dm.session_id}) == 3
        assert adapter._source_session_key(first.source) == entry_a.session_key
        assert runner.config.group_sessions_per_user == original_isolation
        assert "group_sessions_per_user" not in lane.config.extra
    finally:
        store._db.close()
    assert (await final(adapter, first, "synthetic group answer")).success
    assert lane.bridge.calls[-1][0]["wx_ids"] == ["111@chatroom"]


@pytest.mark.asyncio
async def test_paired_dm_user_cannot_trigger_group_and_native_restriction_still_applies(lane):
    adapter = lane.adapter
    lane.config.extra.update(group_admin_mentions=True, group_allow_from=["alice"],
                             group_allow_admin_from=["alice"])
    adapter.state.initialize(10)
    runner = runner_for(adapter)
    runner.config = GatewayConfig(platforms={adapter.platform: lane.config})
    runner.pairing_store = SimpleNamespace(is_approved=lambda platform, user: user == "paired-user")
    pair_source = adapter.build_source(chat_id=adapter._chat_id("111@chatroom"),
                                      chat_type="group", user_id="paired-user")
    assert runner._is_user_authorized(pair_source)  # native pairing alone is a broad grant
    row = {"device": "synthetic", "owner_wxid": "wxid_synthetic_bot", "direction": "recv",
           "message_type": 1, "chat_kind": "room", "from": "111@chatroom", "chat_id": "111@chatroom",
           "room_id": "111@chatroom", "sender": "paired-user", "to": "wxid_synthetic_bot",
           "mentioned_wxids": ["wxid_synthetic_bot"], "text": "@Bot synthetic question", "event_key": "paired-group"}
    handler = adapter.handle_message = AsyncMock()
    await adapter._ingest(row, 11)
    handler.assert_not_awaited()  # plugin's narrower group gate applies before native pairing
    assert not lane.bridge.calls

    # The plugin never bypasses a narrower native allowlist for an admitted admin.
    lane.config.extra["group_allow_from"] = ["someone-else"]
    await adapter._ingest({**row, "sender": "alice", "event_key": "admin-restricted"}, 12)
    event = handler.call_args.args[0]
    assert not runner._is_user_authorized(event.source)
    assert not event.source.role_authorized


@pytest.mark.asyncio
async def test_feishu_and_wechat_use_same_native_recovery_rules(lane):
    from plugins.platforms.feishu.adapter import FeishuAdapter

    feishu = FeishuAdapter(PlatformConfig(enabled=True))
    feishu.send = AsyncMock(return_value=SendResult(success=True, message_id="feishu-message"))
    for oid, platform, chat, text, thread in (
        ("wechat-owed", "observatory", "alice", "WeChat answer", None),
        ("feishu-owed", "feishu", "synthetic-feishu-chat", "Feishu answer", "synthetic-thread"),
    ):
        ledger.record_obligation(obligation_id=oid, session_key=f"agent:main:{platform}:dm:test",
            platform=platform, chat_id=chat, thread_id=thread, content=text)
        ledger.mark_attempting(oid)
    orphan()
    runner = runner_for(lane.adapter, feishu)
    assert await runner._redeliver_pending_obligations() == 2
    assert lane.bridge.calls[0][0]["text"] == ledger.RECOVERED_MARKER + "WeChat answer"
    feishu.send.assert_awaited_once_with(chat_id="synthetic-feishu-chat",
        content=ledger.RECOVERED_MARKER + "Feishu answer", metadata={"thread_id": "synthetic-thread"})
    assert all(row["state"] == "delivered" for row in rows())
    assert await runner._redeliver_pending_obligations() == 0


@pytest.mark.asyncio
async def test_native_crash_recovery_does_not_claim_end_to_end_exactly_once(lane):
    # Backend acceptance is durable, but native Hermes has not recorded success.
    # Stock recovery supplies no original reply identity and changes the text.
    # Document this boundary instead of silently merging legitimate equal sends.
    with patch.object(ledger, "mark_delivered", side_effect=asyncio.CancelledError):
        with pytest.raises(asyncio.CancelledError):
            await final(lane.adapter, event_for(lane.adapter))
    assert len(lane.bridge.tasks) == 1
    orphan()
    assert await runner_for(lane.new_adapter())._redeliver_pending_obligations() == 1
    assert len(lane.bridge.tasks) == 2
    assert lane.bridge.calls[1][0]["text"] == ledger.RECOVERED_MARKER + "same answer"
    assert lane.bridge.calls[0][1] != lane.bridge.calls[1][1]


@pytest.mark.asyncio
async def test_native_persisted_transcript_reply_recovers_without_model(lane):
    from gateway.session import SessionStore
    from hermes_state import SessionDB

    store = SessionStore(sessions_dir=lane.home / "sessions", config=GatewayConfig())
    if store._db is not None:
        store._db.close()
    store._db = SessionDB(db_path=lane.home / "state.db")
    runner = runner_for(lane.adapter)
    runner.session_store, runner._async_session_store = store, None
    try:
        entry = store.get_or_create_session(event_for(lane.adapter).source)
        store.mark_turn_active(entry.session_key)
        store.append_to_transcript(entry.session_id, {"role": "user", "content": "synthetic question"})
        store.append_to_transcript(entry.session_id, {"role": "assistant", "content": "persisted answer"})
        assert await runner._ledger_crash_left_replies(3600) == 1
        assert await runner._redeliver_pending_obligations() == 1
        assert lane.bridge.calls[0][0]["text"] == ledger.RECOVERED_MARKER + "persisted answer"
        assert await runner._ledger_crash_left_replies(3600) == 0
        assert await runner._redeliver_pending_obligations() == 0
        assert len(lane.bridge.tasks) == 1
    finally:
        store._db.close()
