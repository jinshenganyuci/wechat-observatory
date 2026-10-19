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
