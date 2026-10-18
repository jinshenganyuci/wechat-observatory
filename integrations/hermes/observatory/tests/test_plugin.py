"""Run with Hermes v0.22.2 on PYTHONPATH; all traffic uses an ephemeral fake server."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from aiohttp import web
from gateway.config import PlatformConfig
from gateway.platforms.base import BasePlatformAdapter
from gateway.platforms.event import MessageEvent, MessageType
from gateway.platform_registry import PlatformEntry, platform_registry
from observatory import adapter as module
from observatory.adapter import ObservatoryAdapter
from observatory.client import BridgeError, Client, Settings, file_description
from observatory.state import State, digest


class StateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "state.sqlite3"
        self.state = State(self.path)

    def tearDown(self):
        self.state.close()
        self.temp.cleanup()

    def test_claim_survives_restart_and_event_key_reappearing_at_new_sequence(self):
        self.assertIsNone(self.state.cursor)
        self.state.initialize(30)
        self.assertTrue(self.state.claim(31, "event-a"))
        self.state.close()
        self.state = State(self.path)
        self.assertFalse(self.state.claim(32, "event-a"))
        self.assertEqual(self.state.cursor, 32)
        self.assertFalse(self.state.claim(31, "event-b"))

    def test_intent_reuses_original_generation_and_conflicts_fail_closed(self):
        first = self.state.prepare("turn-1", "content", {"wx_ids": ["alice"], "account_generation": 1})
        second = self.state.prepare("turn-1", "content", {"wx_ids": ["alice"], "account_generation": 9})
        self.assertEqual(first, second)
        with self.assertRaisesRegex(ValueError, "different content"):
            self.state.prepare("turn-1", "different", {})

    def test_unknown_blocks_new_identity_but_successful_new_notification_is_distinct(self):
        body = {"wx_ids": ["alice"], "text": "hello"}
        first = self.state.prepare(None, "same", body)
        self.assertEqual(self.state.prepare(None, "same", body)["idem"], first["idem"])
        with self.assertRaisesRegex(ValueError, "unconfirmed"):
            self.state.prepare("new-turn", "different", body)
        self.state.accepted(first["intent"], 7)
        later = self.state.prepare(None, "same", body)
        self.assertNotEqual(first["idem"], later["idem"])
        self.assertEqual(self.state.find_intent(first["intent"])["payload"], "{}")

    def test_notification_manifest_retains_partial_identity_without_permanent_content_dedup(self):
        first = self.state.bundle(None, "alice", "caption-and-two-files")
        self.assertEqual(first, self.state.bundle(None, "alice", "caption-and-two-files"))
        with self.assertRaisesRegex(ValueError, "unfinished notification"):
            self.state.bundle(None, "alice", "different-files")
        self.state.finish_bundle(first)
        self.assertNotEqual(first, self.state.bundle(None, "alice", "caption-and-two-files"))


class FakeClient:
    def __init__(self):
        self.calls = []
        self.generation_value = 1
        self.lose = 0
        self.ids = {}

    async def generation(self):
        return self.generation_value

    async def ensure_idempotency(self):
        pass

    async def submit(self, body, key):
        self.calls.append((dict(body), key))
        replay = key in self.ids
        self.ids.setdefault(key, len(self.ids) + 100)
        if self.lose:
            self.lose -= 1
            raise TimeoutError("simulated response lost AFTER server commit")
        return {"ok": True, "outbox_id": self.ids[key], "idempotent_replay": replay}

    async def close(self):
        pass


class AdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        context = SimpleNamespace(current_cron_execution=lambda: None,
            register_platform=lambda name, label, adapter, **options:
                platform_registry.register(PlatformEntry(name, label, adapter, **options)))
        self.context = patch.object(module, "_plugin_context", context)
        self.context.start()
        module.register(context)
        self.home = patch.object(module, "get_hermes_home", return_value=Path(self.temp.name))
        self.safe = patch.object(module, "require_safe_delivery_config")
        self.home.start()
        self.safe.start()
        self.config = PlatformConfig(enabled=True, extra={"base_url": "http://127.0.0.1:1", "device": "test",
            "owner_wxid": "wxid_bot", "admin_password": "test-secret"})
        self.adapter = ObservatoryAdapter(self.config)
        self.client = self.adapter.client = FakeClient()
        self.adapter.state.initialize(10)

    async def asyncTearDown(self):
        self.adapter.state.close()
        self.safe.stop()
        self.home.stop()
        self.context.stop()
        self.temp.cleanup()

    def event(self, **values):
        return {"device": "test", "owner_wxid": "wxid_bot", "from": "alice", "sender": "alice",
                "to": "wxid_bot", "chat_id": "alice", "chat_kind": "direct", "direction": "recv",
                "message_type": 1, "text": "hello", "event_key": "key-1", "id": "999999999", **values}

    async def test_only_private_received_text_reaches_native_auth_boundary(self):
        self.adapter.handle_message = AsyncMock()
        rejected = [dict(direction="sent"), dict(is_send=1), dict(owner_wxid="other"), dict(device="other"),
                    dict(message_type=3), dict(room_id="room@chatroom"), dict(chat_kind="room"),
                    dict(sender="impostor"), dict(from_wxid="wxid_bot", sender="wxid_bot", chat_id="wxid_bot"),
                    dict(raw_provider="module_ack"), dict(event_key="")]
        sequence = 10
        for changes in rejected:
            sequence += 1
            await self.adapter._ingest(self.event(event_key=str(sequence), **changes) if "event_key" not in changes
                                       else self.event(**changes), sequence)
        self.adapter.handle_message.assert_not_called()
        await self.adapter._ingest(self.event(event_key="allowed"), sequence + 1)
        event = self.adapter.handle_message.call_args.args[0]
        self.assertEqual(event.source.user_id, "alice")
        self.assertEqual(event.source.chat_type, "dm")
        self.assertEqual(event.message_id, "allowed")
        self.assertFalse(event.internal)
        self.assertTrue(event.allow_gateway_control)
        self.assertEqual(self.adapter.state.cursor, sequence + 1)

    async def test_history_control_commands_are_checkpointed_without_reexecution(self):
        self.adapter.handle_message = AsyncMock()
        await self.adapter._ingest(self.event(text="/new"), 11, historical=True)
        self.adapter.handle_message.assert_not_called()
        self.assertEqual(self.adapter.state.cursor, 11)
        await self.adapter._ingest(self.event(text="/new", event_key="fresh"), 12)
        self.assertEqual(self.adapter.handle_message.await_count, 1)

    async def test_claim_precedes_async_admission_even_if_handler_crashes(self):
        self.adapter.handle_message = AsyncMock(side_effect=RuntimeError("admission crashed"))
        with self.assertRaises(RuntimeError):
            await self.adapter._ingest(self.event(), 11)
        self.assertEqual(self.adapter.state.cursor, 11)
        await self.adapter._ingest(self.event(), 12)
        self.assertEqual(self.adapter.handle_message.await_count, 1)

    async def test_response_lost_and_same_reply_retry_create_one_outbox(self):
        self.client.lose = 1
        result = await self.adapter.send("alice", "hello", reply_to="event-1")
        retry = await self.adapter.send("alice", "hello", reply_to="event-1")
        self.assertTrue(result.success)
        self.assertEqual(result.message_id, retry.message_id)
        self.assertEqual(len(self.client.calls), 2)
        self.assertEqual(len(self.client.ids), 1)
        self.assertEqual(self.client.calls[0], self.client.calls[1])

    async def test_unknown_restart_replays_exact_generation_and_body(self):
        self.client.lose = 2
        result = await self.adapter.send("alice", "hello", reply_to="event-1")
        self.assertFalse(result.success)
        self.adapter.state.close()
        self.adapter = ObservatoryAdapter(self.config)
        self.adapter.client = self.client
        self.client.generation_value = 9
        await self.adapter.recover_pending()
        self.assertEqual(len(self.client.ids), 1)
        self.assertEqual({call[0]["account_generation"] for call in self.client.calls}, {1})
        self.assertFalse(self.adapter.state.unresolved())

    async def test_old_server_capability_blocks_existing_journal_recovery(self):
        self.client.lose = 2
        await self.adapter.send("alice", "hello", reply_to="event-1")
        self.client.ensure_idempotency = AsyncMock(side_effect=BridgeError("server_text_idempotency_required_upgrade_observatory"))
        await self.adapter.recover_pending()
        self.assertEqual(len(self.client.calls), 2)
        self.assertEqual(len(self.adapter.state.unresolved()), 1)

    async def test_unknown_blocks_new_turn_and_plaintext_fallback(self):
        self.client.lose = 4
        result = await self.adapter._send_with_retry("alice", "hello", reply_to="event-1")
        self.assertFalse(result.success)
        self.assertEqual(len(self.client.calls), 2)
        another = await self.adapter.send("alice", "changed", reply_to="event-2")
        self.assertFalse(another.success)
        self.assertIn("unconfirmed", another.error)
        self.assertEqual(len(self.client.calls), 2)

    async def test_distinct_anchored_turns_can_send_same_text(self):
        first = await self.adapter.send("alice", "hello", reply_to="event-1")
        second = await self.adapter.send("alice", "hello", reply_to="event-2")
        self.assertNotEqual(first.message_id, second.message_id)

    async def test_cron_occurrence_identity_survives_new_adapter(self):
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-1", profile="wechat"))
        with patch.object(module, "_plugin_context", context):
            first = await self.adapter.send("alice", "notify")
            self.adapter.state.close()
            self.adapter = ObservatoryAdapter(self.config)
            self.adapter.client = self.client
            again = await self.adapter.send("alice", "notify")
        self.assertEqual(first.message_id, again.message_id)
        self.assertEqual(len(self.client.calls), 1)

    async def test_cross_scope_destination_is_rejected_before_network(self):
        result = await self.adapter.send("other-binding:alice", "hello")
        self.assertFalse(result.success)
        self.assertFalse(self.client.calls)

    async def test_ordinary_document_has_content_fingerprint_and_stable_key(self):
        path = Path(self.temp.name) / "notes.txt"
        path.write_text("hello")
        first = await self.adapter.send_document("filehelper", str(path), reply_to="event-1")
        second = await self.adapter.send_document("filehelper", str(path), reply_to="event-1")
        self.assertTrue(first.success)
        self.assertEqual(first.message_id, second.message_id)
        payload = self.client.calls[0][0]
        self.assertEqual(payload["attachment"]["size"], 5)
        self.assertEqual(payload["wxid"], "filehelper")
        self.assertEqual(len(self.client.calls), 1)

    async def test_ledger_enabled_refuses_before_http(self):
        self.safe.stop()
        with patch("gateway.delivery_ledger.ledger_enabled", return_value=True):
            result = await self.adapter.send("alice", "hello")
        self.safe.start()
        self.assertFalse(result.success)
        self.assertIn("gateway.delivery_ledger", result.error)
        self.assertFalse(self.client.calls)

    async def test_permanent_rejection_not_recovered_or_blocking_new_notification(self):
        original = self.client.submit
        self.client.submit = AsyncMock(side_effect=BridgeError("invalid_request", status=400))
        result = await self.adapter.send("alice", "bad", reply_to="bad-turn")
        self.assertFalse(result.success)
        self.assertFalse(self.adapter.state.unresolved())
        await self.adapter.recover_pending()
        self.assertEqual(self.client.submit.await_count, 1)
        self.client.submit = original
        good = await self.adapter.send("alice", "good", reply_to="good-turn")
        self.assertTrue(good.success)
        again = await self.adapter.send("alice", "bad", reply_to="bad-turn")
        self.assertFalse(again.success)
        self.assertEqual(len(self.client.calls), 1)

    async def test_empty_internal_resume_never_calls_agent_or_bridge_but_new_user_turn_can(self):
        source = self.adapter.build_source(chat_id=self.adapter._chat_id("alice"), user_id="alice")
        resume = MessageEvent(text="", source=source, internal=True, message_type=MessageType.TEXT)
        with patch.object(BasePlatformAdapter, "handle_message", new_callable=AsyncMock) as native:
            await self.adapter.handle_message(resume)
            await self.adapter.handle_message(resume)
            native.assert_not_called()
            self.assertFalse(self.client.calls)
            user = MessageEvent(text="continue", source=source, message_id="fresh-event")
            await self.adapter.handle_message(user)
            native.assert_awaited_once_with(user)

    async def test_real_startup_dispatch_releases_sentinel_without_agent_or_post(self):
        from gateway.run import _AGENT_PENDING_SENTINEL
        from gateway.run_startup import GatewayStartupMixin
        source = self.adapter.build_source(chat_id=self.adapter._chat_id("alice"), user_id="alice")
        resume = MessageEvent(text="", source=source, internal=True)
        runner = SimpleNamespace(
            _peek_session_state=lambda key: SimpleNamespace(turn=SimpleNamespace(agent=_AGENT_PENDING_SENTINEL)),
            _release_running_agent_state=Mock())
        with patch.object(BasePlatformAdapter, "handle_message", new_callable=AsyncMock) as native:
            await GatewayStartupMixin._run_startup_resume_event(runner, self.adapter, resume, "session-key")
            native.assert_not_called()
        runner._release_running_agent_state.assert_called_once_with("session-key")
        self.assertFalse(self.client.calls)

    async def test_partial_notification_retry_does_not_duplicate_successful_caption(self):
        path = Path(self.temp.name) / "note.txt"
        path.write_text("file")
        original = self.client.submit
        losses = 2

        async def partial(body, key):
            nonlocal losses
            if body["kind"] == "file" and losses:
                losses -= 1
                self.client.lose = 1
            return await original(body, key)

        self.client.submit = partial
        with patch.object(module, "Client", return_value=self.client):
            first = await module.standalone_sender(self.config, "alice", "caption", media_files=[(str(path), False)])
            second = await module.standalone_sender(self.config, "alice", "caption", media_files=[(str(path), False)])
        self.assertIn("error", first)
        self.assertTrue(second["success"])
        self.assertEqual(sum(call[0]["kind"] == "text" for call in self.client.calls), 1)
        self.assertEqual(len(self.client.ids), 2)

    async def test_cron_live_and_standalone_share_caption_and_file_identity(self):
        path = Path(self.temp.name) / "cron.txt"
        path.write_text("cron attachment")
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-live", profile="wechat"))
        with patch.object(module, "_plugin_context", context), patch.object(module, "Client", return_value=self.client):
            caption = await self.adapter.send("alice", "caption")
            attachment = await self.adapter.send_document("alice", str(path))
            fallback = await module.standalone_sender(self.config, "alice", "caption\n", media_files=[(str(path), False)])
        self.assertTrue(caption.success)
        self.assertTrue(attachment.success)
        self.assertTrue(fallback["success"])
        self.assertEqual(len(self.client.ids), 2)
        self.assertEqual(len(self.client.calls), 2)

    async def test_cron_live_unconfirmed_file_recovers_in_standalone_without_new_caption(self):
        path = Path(self.temp.name) / "cron.txt"
        path.write_text("cron attachment")
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-partial", profile="wechat"))
        with patch.object(module, "_plugin_context", context), patch.object(module, "Client", return_value=self.client):
            self.assertTrue((await self.adapter.send("alice", "caption")).success)
            self.client.lose = 2
            self.assertFalse((await self.adapter.send_document("alice", str(path))).success)
            fallback = await module.standalone_sender(self.config, "alice", "caption", media_files=[(str(path), False)])
        self.assertNotIn("error", fallback)
        self.assertEqual(len(self.client.ids), 2)
        self.assertEqual(sum(body["kind"] == "text" for body, _key in self.client.calls), 1)
        self.assertFalse(self.adapter.state.unresolved())

    async def test_native_cron_live_media_failure_returns_delivered_with_error(self):
        from cron import scheduler_delivery as cron
        path = Path(self.temp.name) / "cron.txt"
        path.write_text("cron attachment")
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-native", profile="wechat"))
        target = SimpleNamespace(job={"id": "cron-test"}, runtime_adapter=self.adapter, chat_id="alice",
            loop=asyncio.get_running_loop(), platform=self.adapter.platform, platform_name="observatory",
            is_relay=False, where="observatory:alice")
        errors = []
        with patch.object(module, "_plugin_context", context), \
                patch.object(cron, "_live_route_metadata", return_value=(None, {}, {})), \
                patch.object(cron, "_seed_live_delivery_sessions"):
            caption = await self.adapter.send("alice", "caption")
            self.client.lose = 2
            with patch.object(cron, "_live_send_text", return_value=(True, False, caption.message_id)):
                delivered = await asyncio.to_thread(cron._deliver_via_live_adapter, target, "caption",
                    [(str(path), False)], target_errors=[], delivery_errors=errors, unverified_targets=[])
        self.assertTrue(delivered)  # _deliver_result only invokes fallback when this is false.
        self.assertEqual(len(errors), 1)
        self.assertIn("media send failed", errors[0])
        self.assertEqual(sum(body["kind"] == "text" for body, _key in self.client.calls), 1)
        self.assertEqual(len(self.adapter.state.unresolved()), 1)

    async def test_notification_rejects_invalid_unicode_without_rewriting_valid_text(self):
        with patch.object(module, "Client", return_value=self.client):
            rejected = await module.standalone_sender(self.config, "alice", "bad\udcfftext")
            self.assertIn("invalid Unicode", rejected["error"])
            self.assertFalse(self.client.calls)
            await module.standalone_sender(self.config, "alice", "Hello 😀")
            self.assertEqual(self.client.calls[0][0]["text"], "Hello 😀")

    async def test_native_cron_standalone_route_without_args_preserves_caption_and_files(self):
        from tools import send_message_tool as native
        path = Path(self.temp.name) / "cron.txt"
        path.write_text("cron attachment")
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-standalone", profile="wechat"))
        with patch.object(module, "_plugin_context", context), patch.object(module, "Client", return_value=self.client), \
                patch.object(native, "_live_adapter", return_value=(None, None)):
            for _ in range(2):
                result = await native._send_to_platform(self.adapter.platform, self.config, "alice", "caption",
                    media_files=[(str(path), False)])
                self.assertTrue(result.get("success"), result)
        self.assertEqual([body["kind"] for body, _key in self.client.calls], ["text", "file"])
        self.assertEqual(len(self.client.ids), 2)

    async def test_native_notification_rejects_long_text_before_any_bundle_or_send(self):
        from tools import send_message_tool as native
        with patch.object(module, "Client", return_value=self.client), \
                patch.object(native, "_live_adapter", return_value=(None, None)):
            result = await native._send_to_platform(self.adapter.platform, self.config, "alice", "x" * 8001)
        self.assertIn("8000", result["error"])
        self.assertFalse(self.client.calls)
        self.assertEqual(self.adapter.state.db.execute("SELECT COUNT(*) FROM bundles").fetchone()[0], 0)
        self.assertEqual(self.adapter.state.db.execute("SELECT COUNT(*) FROM outgoing").fetchone()[0], 0)

    async def test_real_cron_router_preserves_long_text_and_standalone_identity(self):
        from gateway.config import GatewayConfig
        from gateway.delivery import DeliveryRouter, DeliveryTarget
        from tools import send_message_tool as native
        config = GatewayConfig(platforms={self.adapter.platform: self.config})
        router = DeliveryRouter(config, {self.adapter.platform: self.adapter})
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-long", profile="wechat"))
        with patch.object(module, "_plugin_context", context), patch.object(module, "Client", return_value=self.client), \
                patch.object(native, "_live_adapter", return_value=(None, None)), \
                patch("gateway.delivery.get_hermes_home", return_value=Path(self.temp.name)):
            for size in (4001, 8000):
                target = "alice" + str(size)
                text = "x" * size
                live = await router._deliver_to_platform(DeliveryTarget(self.adapter.platform, target), text,
                    {"job_id": "cron-test"})
                self.assertTrue(live.success)
                self.assertEqual(self.client.calls[-1][0]["text"], text)
                fallback = await native._send_to_platform(self.adapter.platform, self.config, target, text)
                self.assertTrue(fallback.get("success"), fallback)
        self.assertEqual(len(self.client.ids), 2)
        self.assertEqual(len(self.client.calls), 2)

    async def test_real_cron_router_and_fallback_reject_8001_before_any_send(self):
        from gateway.config import GatewayConfig
        from gateway.delivery import DeliveryRouter, DeliveryTarget
        from tools import send_message_tool as native
        config = GatewayConfig(platforms={self.adapter.platform: self.config})
        router = DeliveryRouter(config, {self.adapter.platform: self.adapter})
        context = SimpleNamespace(current_cron_execution=lambda: SimpleNamespace(execution_id="run-over-limit", profile="wechat"))
        with patch.object(module, "_plugin_context", context), patch.object(module, "Client", return_value=self.client), \
                patch.object(native, "_live_adapter", return_value=(None, None)), \
                patch("gateway.delivery.get_hermes_home", return_value=Path(self.temp.name)):
            with self.assertRaisesRegex(RuntimeError, "8000"):
                await router._deliver_to_platform(DeliveryTarget(self.adapter.platform, "alice"), "x" * 8001,
                    {"job_id": "cron-test"})
            fallback = await native._send_to_platform(self.adapter.platform, self.config, "alice", "x" * 8001)
            self.assertIn("8000", fallback["error"])
        self.assertFalse(self.client.calls)
        self.assertEqual(self.adapter.state.db.execute("SELECT COUNT(*) FROM bundles").fetchone()[0], 0)
        self.assertEqual(self.adapter.state.db.execute("SELECT COUNT(*) FROM outgoing").fetchone()[0], 0)

    async def test_native_cli_notification_retry_preserves_whole_manifest(self):
        from gateway.config import GatewayConfig
        from tools import send_message_tool as native
        path = Path(self.temp.name) / "cli.txt"
        path.write_text("cli attachment")
        later_path = Path(self.temp.name) / "later.txt"
        later_path.write_text("second cli attachment")
        original = self.client.submit
        losses = 2

        async def lose_file_response(body, key):
            nonlocal losses
            if body["kind"] == "file" and body["attachment"]["name"] == later_path.name and losses:
                losses -= 1
                self.client.lose = 1
            return await original(body, key)

        self.client.submit = lose_file_response
        args = {"target": "observatory:alice", "message": f"caption\nMEDIA:{path}\nMEDIA:{later_path}"}
        config = GatewayConfig(platforms={self.adapter.platform: self.config})
        with patch.object(module, "Client", return_value=self.client), \
                patch.object(native, "_live_adapter", return_value=(None, None)), \
                patch.object(native, "prepare_send_message_platforms"), \
                patch.object(native, "_authorize_relay_target", return_value=None), \
                patch.object(native, "_mirror_sent_message", return_value=False), \
                patch("hermes_cli.plugins.discover_plugins"), \
                patch("gateway.config.load_gateway_config", return_value=config):
            first = json.loads(await asyncio.to_thread(native._handle_send, args))
            second = json.loads(await asyncio.to_thread(native._handle_send, args))
        self.assertIn("error", first)
        self.assertTrue(second.get("success"), second)
        self.assertEqual(sum(body["kind"] == "text" for body, _key in self.client.calls), 1)
        self.assertEqual(len(self.client.ids), 3)
        self.assertEqual(sum(body["kind"] == "file" and body["attachment"]["name"] == path.name
                             for body, _key in self.client.calls), 1)

    async def test_live_notification_to_other_target_uses_original_turn_identity(self):
        from tools import send_message_tool as native
        path = Path(self.temp.name) / "group.txt"
        path.write_text("group attachment")
        token = module._reply_identity.set((self.adapter._chat_id("alice"), "inbound-1"))
        try:
            with patch.object(native, "_live_adapter", return_value=(SimpleNamespace(), self.adapter)):
                for _ in range(2):
                    result = await native._send_to_platform(self.adapter.platform, self.config, "123@chatroom", "caption",
                        media_files=[(str(path), False)])
                    self.assertTrue(result["success"])
        finally:
            module._reply_identity.reset(token)
        self.assertEqual(len(self.client.ids), 2)
        self.assertEqual(len(self.client.calls), 2)

    async def test_first_connection_skips_head_and_reconnects_with_durable_cursor(self):
        handled = []
        self.adapter.state.close()
        fresh_path = Path(self.temp.name) / "fresh.sqlite3"
        self.adapter.state = State(fresh_path)
        self.client.history = AsyncMock(return_value=[self.event(sequence_id=40, text="/new")])
        self.adapter.handle_message = AsyncMock(side_effect=lambda event: handled.append(event.text))

        async def stream(cursor):
            self.assertIsNone(cursor)
            yield "ready", None, {"cursor": 40}
            yield "message", 41, self.event(event_key="live", text="fresh", id="999")
            self.adapter._running = False
            raise asyncio.CancelledError()

        self.client.events = stream
        self.adapter._running = True
        with patch.object(self.adapter, "_mark_connected"), self.assertRaises(asyncio.CancelledError):
            await self.adapter._receive_loop()
        self.assertEqual(handled, ["fresh"])
        self.assertEqual(self.adapter.state.cursor, 41)
        if self.adapter._recovery:
            await self.adapter._recovery


class ClientTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.seen = []
        self.long_history = False
        self.fragment_response = False
        self.capability = True
        self.app = web.Application()
        self.app.router.add_get("/api/modules/status", self.modules)
        self.app.router.add_get("/api/messages", self.history)
        self.app.router.add_get("/api/live/events", self.events)
        self.app.router.add_post("/api/send/text", self.send)
        self.app.router.add_post("/api/send/file", self.send_file)
        self.runner = web.AppRunner(self.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        self.client = Client(Settings(f"http://127.0.0.1:{port}", "test", "bot", "test-secret"))

    async def asyncTearDown(self):
        await self.client.close()
        await self.runner.cleanup()

    async def modules(self, request):
        return web.json_response({"capabilities": {"text_idempotency": self.capability},
            "modules": [{"device": "test", "device_wxid": "bot", "account_generation": 3}]})

    async def history(self, request):
        self.seen.append(dict(request.query))
        if self.long_history:
            rows = [{"sequence_id": i, "text": "x" * 65536} for i in range(1, 51)]
            return web.json_response({"messages": rows})
        return web.json_response({"messages": [{"sequence_id": 45, "id": 999}]})

    async def events(self, request):
        self.seen.append(dict(request.headers))
        return web.Response(text='event: ready\ndata: {"cursor": 44}\n\nid: 45\nevent: message\ndata: {"id":"90000","event_key":"durable"}\n\n',
                            content_type="text/event-stream")

    async def send(self, request):
        self.seen.append((dict(request.headers), await request.json()))
        if self.fragment_response:
            response = web.StreamResponse(headers={"Content-Type": "application/json"})
            await response.prepare(request)
            await response.write(b'{"ok":true,')
            await asyncio.sleep(0.01)
            await response.write(b'"outbox_id":8,"idempotent_replay":false}')
            await response.write_eof()
            return response
        return web.json_response({"ok": True, "outbox_id": 8, "idempotent_replay": False})

    async def send_file(self, request):
        body = await request.post()
        self.seen.append((dict(request.headers), dict(body), body["file"].file.read()))
        return web.json_response({"ok": True, "outbox_id": 9, "idempotent_replay": False})

    async def test_fixed_scope_and_explicit_stream_id(self):
        self.assertEqual(await self.client.generation(), 3)
        self.assertEqual((await self.client.history(44))[0]["sequence_id"], 45)
        events = [event async for event in self.client.events(44)]
        self.assertEqual(events[1][1], 45)
        self.assertEqual(events[1][2]["id"], "90000")
        self.assertEqual(self.seen[0]["after_id"], "44")
        self.assertEqual(self.seen[1]["Last-Event-ID"], "44")
        self.assertEqual(self.seen[1]["X-Bridge-Password"], "test-secret")

    async def test_old_backend_rejected_before_a_post(self):
        self.capability = False
        with self.assertRaisesRegex(BridgeError, "idempotency_required"):
            await self.client.generation()
        self.assertFalse(self.seen)

    async def test_text_request_has_single_recipient_and_idempotency_header(self):
        payload = {"kind": "text", "device": "test", "owner_wxid": "bot", "account_generation": 3,
                   "wx_ids": ["alice"], "text": " exact text "}
        result = await self.client.submit(payload, "hermes-stable-key")
        self.assertEqual(result["outbox_id"], 8)
        headers, body = self.seen[0]
        self.assertEqual(headers["Idempotency-Key"], "hermes-stable-key")
        self.assertEqual(body["text"], " exact text ")
        self.assertNotIn("kind", body)

    async def test_fragmented_json_and_long_bounded_history_page(self):
        self.fragment_response = True
        self.assertEqual((await self.client.submit({"kind": "text", "text": "hi"}, "hermes-fragment"))["outbox_id"], 8)
        self.long_history = True
        rows = await self.client.history(0)
        self.assertEqual(len(rows), 50)
        self.assertGreater(sum(len(row["text"]) for row in rows), 2 * 1024 * 1024)
        self.assertEqual(self.seen[-1]["limit"], "50")
        await self.client.history(None)
        self.assertEqual(self.seen[-1]["limit"], "1")

    async def test_document_upload_contract_streams_small_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "note.txt"
            path.write_bytes(b"ordinary file")
            payload = {"kind": "file", "device": "test", "owner_wxid": "bot", "account_generation": 3,
                       "wxid": "filehelper", "attachment": file_description(str(path))}
            result = await self.client.submit(payload, "hermes-file-key")
            self.assertEqual(result["outbox_id"], 9)
            headers, body, content = self.seen[-1]
            self.assertEqual(headers["Idempotency-Key"], "hermes-file-key")
            self.assertEqual(body["owner_wxid"], "bot")
            self.assertEqual(content, b"ordinary file")
            path.write_text("changed")
            with self.assertRaisesRegex(BridgeError, "attachment_changed"):
                await self.client.submit(payload, "hermes-file-key")


class ValidationTests(unittest.TestCase):
    def test_secret_is_not_in_repr_and_bad_urls_are_rejected(self):
        self.assertNotIn("secret-value", repr(Settings("https://example.test", "d", "o", "secret-value")))
        for url in ("https://user:pass@example.test", "https://example.test?q=1", "file:///tmp/foo"):
            with self.assertRaises(ValueError):
                Settings(url, "d", "o", "p")

    def test_file_constraints_use_small_fixture_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.txt"
            path.write_text("fixture")
            self.assertEqual(file_description(str(path), "😀.txt")["size"], 7)
            for name in ("movie.mp4", "image.JPG", "../escape", "bad\nname"):
                with self.assertRaises(ValueError):
                    file_description(str(path), name)
            with patch("observatory.client.MAX_FILE_BYTES", 6):
                with self.assertRaises(ValueError):
                    file_description(str(path))

    @unittest.skipUnless(hasattr(os, "mkfifo"), "POSIX FIFO fixture")
    def test_fifo_is_rejected_without_waiting_for_writer(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fifo.txt"
            os.mkfifo(path)
            with self.assertRaises(ValueError):
                file_description(str(path))


if __name__ == "__main__":
    unittest.main()
