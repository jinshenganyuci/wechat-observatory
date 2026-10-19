"""Real Hermes delivery lifecycle, with a synthetic in-memory bridge only.

Run against the supported Hermes build carrying the opt-in delivery-ID contract.
No gateway service, model, phone, provider key or real channel is started here.
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
from observatory import adapter as plugin
from observatory.adapter import ObservatoryAdapter
from observatory.state import digest


class Bridge:
    """Commit before simulating lost responses, as the real API may do."""

    def __init__(self):
        self.calls = []
        self.tasks = {}
        self.generation_value = 1
        self.lose = 0
        self.crash = False

    async def generation(self):
        return self.generation_value

    async def ensure_idempotency(self):
        return None

    async def submit(self, payload, key):
        self.calls.append((json.loads(json.dumps(payload)), key))
        replay = key in self.tasks
        self.tasks.setdefault(key, len(self.tasks) + 100)
        if self.crash:
            self.crash = False
            raise asyncio.CancelledError("crash after backend commit, before local acknowledgement")
        if self.lose:
            self.lose -= 1
            raise TimeoutError("backend committed; response was lost")
        return {"ok": True, "outbox_id": self.tasks[key], "idempotent_replay": replay}

    async def close(self):
        return None


@pytest.fixture
def lane(tmp_path, monkeypatch):
    # Patch only storage location and HTTP transport. Native ledger gates,
    # producer, claims, redelivery and recovery-marker decisions remain real.
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.delenv("OBSERVATORY_ADMIN_PASSWORD", raising=False)
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
    })
    bridge = Bridge()
    adapters = []

    def new_adapter():
        adapter = ObservatoryAdapter(config)
        adapter.client = bridge
        adapters.append(adapter)
        return adapter

    token = plugin._reply_identity.set(None)
    value = SimpleNamespace(home=tmp_path, config=config, bridge=bridge, new_adapter=new_adapter)
    value.adapter = new_adapter()
    try:
        yield value
    finally:
        plugin._reply_identity.reset(token)
        for adapter in adapters:
            adapter.state.close()


def event_for(adapter, event_id="event-one"):
    return MessageEvent(text="a synthetic question", message_id=event_id,
                        source=adapter.build_source(chat_id=adapter._chat_id("alice"), user_id="alice"))


def session_key(event):
    return "agent:main:observatory:dm:" + event.source.chat_id


async def final(adapter, event, text="same answer"):
    result, _owner = await adapter.send_final_ledgered(
        event, session_key(event), text, {}, reply_to=event.message_id)
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
    runner.session_store = None
    runner._async_session_store = MagicMock()
    runner._async_session_store._store = None
    runner._async_session_store.clear_resume_pending = AsyncMock()
    runner._running = False
    return runner


@pytest.mark.asyncio
@pytest.mark.parametrize("crash_before_local_ack", [False, True])
async def test_backend_accept_then_crash_recovers_one_original_task(lane, crash_before_local_ack):
    adapter, bridge = lane.adapter, lane.bridge
    event = event_for(adapter)
    if crash_before_local_ack:
        bridge.crash = True
        with pytest.raises(asyncio.CancelledError):
            await final(adapter, event)
    else:
        with patch.object(ledger, "mark_delivered", side_effect=asyncio.CancelledError):
            with pytest.raises(asyncio.CancelledError):
                await final(adapter, event)
    assert len(bridge.tasks) == 1
    assert rows()[0]["state"] == "attempting"
    original = bridge.calls[0]
    bridge.generation_value = 9
    restarted = lane.new_adapter()
    orphan()
    assert await runner_for(restarted)._redeliver_pending_obligations() == 1
    assert rows()[0]["state"] == "delivered"
    assert len(bridge.tasks) == 1
    assert all(call == original for call in bridge.calls)
    assert not restarted.state.unresolved()


@pytest.mark.asyncio
async def test_timeout_and_local_recovery_before_native_sweep_keep_original_key(lane):
    lane.bridge.lose = 2
    assert not (await final(lane.adapter, event_for(lane.adapter))).success
    assert rows()[0]["state"] == "failed"
    lane.bridge.generation_value = 99
    restarted = lane.new_adapter()
    await restarted.recover_pending()
    orphan()
    assert await runner_for(restarted)._redeliver_pending_obligations() == 1
    assert len(lane.bridge.tasks) == 1
    assert {body["account_generation"] for body, _key in lane.bridge.calls} == {1}
    assert len({key for _body, key in lane.bridge.calls}) == 1


@pytest.mark.asyncio
async def test_native_pending_reply_with_no_previous_http_really_sends(lane):
    event = event_for(lane.adapter)
    oid = ledger.compute_obligation_id(session_key(event), event.message_id, "never sent")
    ledger.record_obligation(obligation_id=oid, session_key=session_key(event), platform="observatory",
                             chat_id=event.source.chat_id, thread_id=None, content="never sent")
    orphan()
    assert await runner_for(lane.adapter)._redeliver_pending_obligations() == 1
    assert len(lane.bridge.tasks) == 1
    assert lane.bridge.calls[0][0]["text"] == "never sent"
    assert rows()[0]["state"] == "delivered"


@pytest.mark.asyncio
async def test_equal_text_in_distinct_turns_is_not_content_deduplicated(lane):
    first = await final(lane.adapter, event_for(lane.adapter, "one"))
    second = await final(lane.adapter, event_for(lane.adapter, "two"))
    assert first.success and second.success
    assert first.message_id != second.message_id
    assert len(lane.bridge.tasks) == len(rows()) == 2


@pytest.mark.asyncio
async def test_delivery_id_cannot_be_reused_with_changed_content(lane):
    metadata = {"delivery_id": "synthetic-stable-delivery"}
    first = await lane.adapter.send("alice", "original", metadata=metadata)
    changed = await lane.adapter.send("alice", "changed", metadata=metadata)
    assert first.success and not changed.success
    assert "different content" in changed.error
    assert len(lane.bridge.calls) == len(lane.bridge.tasks) == 1


@pytest.mark.asyncio
async def test_queued_turn_identity_survives_native_recovery(lane):
    event = event_for(lane.adapter, "queue-opener")
    event.ledger_message_id = "queue-tail"
    with patch.object(ledger, "mark_delivered", side_effect=asyncio.CancelledError):
        with pytest.raises(asyncio.CancelledError):
            await final(lane.adapter, event)
    assert rows()[0]["obligation_id"] == ledger.compute_obligation_id(
        session_key(event), "queue-tail", "same answer")
    orphan()
    assert await runner_for(lane.new_adapter())._redeliver_pending_obligations() == 1
    assert len(lane.bridge.tasks) == 1


@pytest.mark.asyncio
async def test_native_text_and_prepared_file_recover_independently_without_duplicate_caption(lane):
    event = event_for(lane.adapter)
    await lane.adapter.on_processing_start(event)
    assert (await final(lane.adapter, event, "file caption")).success
    attachment = lane.home / "synthetic.txt"
    attachment.write_text("small synthetic attachment", encoding="utf-8")
    lane.bridge.crash = True
    with pytest.raises(asyncio.CancelledError):
        await lane.adapter._deliver_media_attachments(
            event, [(str(attachment), False)], [], force_document_attachments=True,
            human_delay=0, metadata={}, record_delivery=lambda _result: None)
    assert len(lane.bridge.tasks) == 2
    assert len(lane.adapter.state.unresolved()) == 1
    restarted = lane.new_adapter()
    await restarted.recover_pending()
    orphan()
    assert await runner_for(restarted)._redeliver_pending_obligations() == 0
    assert not restarted.state.unresolved()
    assert len(lane.bridge.tasks) == 2
    assert sum(body["kind"] == "text" for body, _key in lane.bridge.calls) == 1
    file_calls = [(body, key) for body, key in lane.bridge.calls if body["kind"] == "file"]
    assert len(file_calls) == 2 and file_calls[0] == file_calls[1]
    assert attachment.read_text(encoding="utf-8") == "small synthetic attachment"


@pytest.mark.asyncio
@pytest.mark.parametrize("accepted", [False, True])
async def test_legacy_reply_is_aliased_without_replacing_its_backend_task(lane, accepted):
    event = event_for(lane.adapter)
    logical = {"kind": "text", "wx_ids": ["alice"], "text": "same answer"}
    fingerprint = digest(logical)
    legacy = "reply:" + digest([event.source.chat_id, event.message_id, fingerprint])
    record = lane.adapter.state.prepare(legacy, fingerprint, {
        **logical, "device": "synthetic", "owner_wxid": "wxid_synthetic_bot", "account_generation": 1})
    lane.bridge.tasks[record["idem"]] = 100
    if accepted:
        lane.adapter.state.accepted(legacy, 100)
    with patch.object(ledger, "mark_delivered", side_effect=asyncio.CancelledError):
        with pytest.raises(asyncio.CancelledError):
            await final(lane.adapter, event)
    orphan()
    assert await runner_for(lane.new_adapter())._redeliver_pending_obligations() == 1
    assert len(lane.bridge.tasks) == 1
    assert all(key == record["idem"] for _body, key in lane.bridge.calls)
    assert lane.adapter.state.find_intent(legacy)["outbox_id"] == 100


@pytest.mark.asyncio
async def test_ledger_write_failure_prevents_http_and_keeps_turn_marker(lane):
    event = event_for(lane.adapter)
    event._turn_marker_handoff = True
    event._gateway_active_turn_token = "active-token"
    runner = MagicMock()
    runner._delivery_adapter_for.return_value = lane.adapter
    runner._clear_durable_active_turn = AsyncMock()
    lane.adapter.gateway_runner = runner
    with patch.object(ledger, "record_obligation", side_effect=OSError("synthetic disk failure")):
        result = await final(lane.adapter, event)
    assert not result.success
    assert "delivery_ledger_unavailable" in result.error
    assert not lane.bridge.calls
    runner._clear_durable_active_turn.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("initial", [False, True])
async def test_live_policy_change_refuses_new_sends_and_restart_recovers_original_request(lane, initial):
    config_path = lane.home / "config.yaml"
    config_path.write_text(f"gateway:\n  delivery_ledger: {str(initial).lower()}\n", encoding="utf-8")
    adapter = lane.new_adapter()
    assert adapter.idempotent_delivery is initial
    metadata = {"delivery_id": "before-policy-edit"} if initial else None
    lane.bridge.lose = 2
    assert not (await adapter.send("alice", "uncertain", reply_to="original-turn", metadata=metadata)).success
    original = lane.bridge.calls[0]
    assert len(lane.bridge.tasks) == 1

    config_path.write_text(f"gateway:\n  delivery_ledger: {str(not initial).lower()}\n", encoding="utf-8")
    refused = await adapter.send("alice", "a new request", reply_to="new-turn")
    assert not refused.success and "restart the gateway" in refused.error
    assert len(lane.bridge.calls) == 2
    assert adapter.idempotent_delivery is initial

    lane.bridge.generation_value = 99
    restarted = lane.new_adapter()
    assert restarted.idempotent_delivery is (not initial)
    await restarted.recover_pending()
    assert not restarted.state.unresolved()
    assert len(lane.bridge.tasks) == 1
    assert all(call == original for call in lane.bridge.calls)


@pytest.mark.asyncio
async def test_feishu_in_same_runner_keeps_its_native_recovery_semantics(lane):
    from plugins.platforms.feishu.adapter import FeishuAdapter

    # Construct the real adapter without connecting its SDK. Only transport is
    # mocked; inherited capability, routing and recovery decisions remain real.
    feishu = FeishuAdapter(PlatformConfig(enabled=True))
    feishu.send = AsyncMock(return_value=SendResult(success=True, message_id="feishu-message"))
    assert feishu.idempotent_delivery is False
    ledger.record_obligation(obligation_id="feishu-owed", session_key="agent:main:feishu:dm:test",
        platform="feishu", chat_id="synthetic-feishu-chat", thread_id="synthetic-thread", content="Feishu answer")
    ledger.mark_attempting("feishu-owed")
    with patch.object(ledger, "mark_delivered", side_effect=asyncio.CancelledError):
        with pytest.raises(asyncio.CancelledError):
            await final(lane.adapter, event_for(lane.adapter))
    orphan()
    assert await runner_for(lane.new_adapter(), feishu)._redeliver_pending_obligations() == 2
    feishu.send.assert_awaited_once_with(chat_id="synthetic-feishu-chat",
        content=ledger.RECOVERED_MARKER + "Feishu answer", metadata={"thread_id": "synthetic-thread"})
    assert len(lane.bridge.tasks) == 1
    assert all(row["state"] == "delivered" for row in rows())
    assert BasePlatformAdapter.idempotent_delivery is False


@pytest.mark.asyncio
async def test_persisted_unledgered_transcript_reply_recovers_without_regeneration(lane):
    from gateway.session import SessionStore
    from hermes_state import SessionDB

    store = SessionStore(sessions_dir=lane.home / "sessions", config=GatewayConfig())
    if store._db is not None:
        store._db.close()
    store._db = SessionDB(db_path=lane.home / "state.db")
    runner = runner_for(lane.adapter)
    runner.session_store = store
    runner._async_session_store = None
    source = event_for(lane.adapter).source
    try:
        entry = store.get_or_create_session(source)
        store.mark_turn_active(entry.session_key)
        store.append_to_transcript(entry.session_id, {"role": "user", "content": "synthetic question"})
        store.append_to_transcript(entry.session_id, {"role": "assistant", "content": "persisted answer"})
        assert await runner._ledger_crash_left_replies(3600) == 1
        assert await runner._redeliver_pending_obligations() == 1
        assert len(lane.bridge.tasks) == 1
        assert lane.bridge.calls[0][0]["text"] == "persisted answer"
        assert await runner._ledger_crash_left_replies(3600) == 0
        assert await runner._redeliver_pending_obligations() == 0
        assert len(lane.bridge.tasks) == 1
    finally:
        store._db.close()
