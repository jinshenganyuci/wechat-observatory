"""Harness tests: no sockets, SQL, subprocesses, provider, or phone access."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("observatory_lab", Path(__file__).with_name("observatory_lab.py"))
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cfg = dict(base_url=lab.BASE, database=lab.DATABASE, device=lab.DEVICE,
                        owner_wxid=lab.OWNER, admin_password="test-admin",
                        module_api_key="test-key", account_session="test-session")
        for field, name in (("CONFIG", "config.json"), ("PHONE_STATE", "phone.json"),
                            ("EVENTS", "events.json"), ("CURSOR", "cursor.json")):
            p = patch.object(lab, field, self.root / name)
            p.start(); self.addCleanup(p.stop)
        lab.private_json(lab.CONFIG, self.cfg)
        p = patch.object(lab, "trace")
        self.trace = p.start(); self.addCleanup(p.stop)

    def test_config_rejects_live_or_different_scope(self):
        for name, value in (("base_url", "http://127.0.0.1:18088"),
                            ("database", "observatory_phone_test"),
                            ("device", "phone-test"), ("owner_wxid", "someone-else")):
            with self.subTest(name=name):
                lab.private_json(lab.CONFIG, dict(self.cfg, **{name: value}))
                with self.assertRaises(lab.LabError): lab.config()

    def test_external_path_is_rejected_before_io(self):
        with patch.object(lab.OPENER, "open") as opened:
            for path in ("https://example.com", "//example.com", "relative", "/http://example.com"):
                with self.assertRaises(lab.LabError): lab.request(path)
            opened.assert_not_called()

    def test_redirect_is_rejected(self):
        with self.assertRaises(lab.LabError):
            lab.NoRedirect().redirect_request(None, None, 302, "", {}, "http://127.0.0.1:18088")

    def test_vectors_keep_group_self_and_stranger_distinct(self):
        direct = lab.make_event("turn1", 1, 100)
        group = lab.make_event("group", 2, 100)
        sent = lab.make_event("self", 3, 100)
        stranger = lab.make_event("unauthorized", 4, 100)
        self.assertEqual((direct["from"], direct["to"], direct["direction"], direct["chat_kind"]),
                         (lab.ADMIN, lab.OWNER, "recv", "direct"))
        self.assertEqual((group["from"], group["sender"], group["room_id"]), (lab.ROOM, lab.ADMIN, lab.ROOM))
        self.assertEqual((sent["from"], sent["direction"]), (lab.OWNER, "sent"))
        self.assertEqual(stranger["from"], lab.STRANGER)
        self.assertIn(lab.STRANGER, lab.RECIPIENTS)  # Native pairing may reply without invoking the LLM.
        with self.assertRaises(lab.LabError):
            lab.validate_delivery(dict(device="phone-test", owner_wxid=lab.OWNER, wxid=lab.STRANGER))

    def test_event_repeat_is_exact_and_is_not_a_new_source_id(self):
        with patch.object(lab, "request", return_value={"ok": True, "result": {"published": True}}) as send:
            first = lab.emit("turn1")
            second = lab.emit("turn1", repeat=True)
        self.assertEqual(first, second)
        self.assertEqual(send.call_args_list[0], send.call_args_list[1])

    def test_repeat_without_original_cannot_send(self):
        with patch.object(lab, "request") as send:
            with self.assertRaises(lab.LabError): lab.emit("turn1", repeat=True)
            send.assert_not_called()

    def test_sse_uses_durable_cursor_and_validates_scope(self):
        lab.private_json(lab.CURSOR, dict(device=lab.DEVICE, owner_wxid=lab.OWNER, cursor=12))
        message = dict(device=lab.DEVICE, owner_wxid=lab.OWNER, event_key="evt_v2_test", id="42", direction="recv")
        stream = io.BytesIO(('event: ready\ndata: {"cursor":12}\n\nid: 13\nevent: message\ndata: '
                             + json.dumps(message) + '\n\n').encode())
        with patch.object(lab.OPENER, "open", return_value=stream) as opened:
            lab.sse(resume=True)
        self.assertEqual(opened.call_args[0][0].get_header("Last-event-id"), "12")
        self.assertEqual(json.loads(lab.CURSOR.read_text())["cursor"], 13)
        message["owner_wxid"] = "another-owner"
        stream = io.BytesIO(('id: 14\nevent: message\ndata: ' + json.dumps(message) + '\n\n').encode())
        with patch.object(lab.OPENER, "open", return_value=stream):
            with self.assertRaises(lab.LabError): lab.sse(resume=True)
        self.assertEqual(json.loads(lab.CURSOR.read_text())["cursor"], 13)

    def test_lost_response_retries_exact_request_once(self):
        lab.private_json(lab.PHONE_STATE, {"generation": 7})
        conn = Mock(); conn.getresponse.return_value.status = 200
        with patch.object(lab.http.client, "HTTPConnection", return_value=conn) as factory, \
                patch.object(lab, "request", return_value={"outbox_id": 55, "idempotent_replay": True}) as retry:
            lab.lost_response()
        factory.assert_called_once_with(lab.HOST, lab.PORT, timeout=10)
        original = conn.request.call_args
        self.assertEqual(json.loads(original.kwargs["body"]), retry.call_args[0][1])
        self.assertEqual(original.kwargs["headers"]["Idempotency-Key"], retry.call_args[0][3]["Idempotency-Key"])
        self.assertEqual(retry.call_count, 1)
        conn.getresponse.return_value.read.assert_not_called()

    def test_unknown_first_status_does_not_create_a_new_request(self):
        lab.private_json(lab.PHONE_STATE, {"generation": 1})
        conn = Mock(); conn.getresponse.return_value.status = 503
        with patch.object(lab.http.client, "HTTPConnection", return_value=conn), patch.object(lab, "request") as retry:
            with self.assertRaises(lab.LabError): lab.lost_response()
            retry.assert_not_called()

    def test_private_config_mode(self):
        self.assertEqual(lab.CONFIG.stat().st_mode & 0o777, 0o600)

    def file_item(self):
        return dict(id=77, device=lab.DEVICE, owner_wxid=lab.OWNER, wxid=lab.ADMIN,
                    kind="file", lease_token="test-lease", created_at="2026-10-19T00:00:00Z",
                    attachment=dict(id="test-file", name="fixture.txt", size=12, sha256="a" * 64))

    def test_file_grant_then_ack_and_repeated_delivery_never_begins_twice(self):
        item = self.file_item(); state = {"acks": {}}
        calls = []
        def op(job, operation):
            calls.append(operation)
            if operation == "begin":
                self.assertEqual(json.loads(lab.PHONE_STATE.read_text())["acks"]["77"]["phase"], "intent")
            return {"ok": True, "granted": True}
        with patch.object(lab, "file_operation", side_effect=op), patch.object(lab, "download_small_file") as download, \
                patch.object(lab, "request", return_value={"ok": True, "items": [{"id": 77, "status": "sent"}]}) as ack:
            lab.deliver_file(item, state)
            lab.deliver_file(item, state)
        self.assertEqual(calls, ["renew", "begin"])
        download.assert_called_once_with(item)
        self.assertEqual(ack.call_count, 2)
        self.assertTrue(state["acks"]["77"]["acknowledged"])

    def test_lost_begin_response_recovery_never_submits_again(self):
        item = self.file_item(); state = {"acks": {}}
        def op(job, operation):
            if operation == "begin": raise lab.LabError("lost_response")
            return {"ok": True}
        with patch.object(lab, "file_operation", side_effect=op), patch.object(lab, "download_small_file"):
            with self.assertRaises(lab.LabError): lab.deliver_file(item, state)
        state = json.loads(lab.PHONE_STATE.read_text())
        remote = dict(item, native_started_at="2026-10-19T00:00:01Z", status="native_started")
        with patch.object(lab, "file_operation", return_value={"item": remote}) as operations, \
                patch.object(lab, "download_small_file") as download, \
                patch.object(lab, "request", return_value={"ok": True, "items": [{"id": 77, "status": "unknown"}]}) as ack:
            lab.deliver_file(item, state)
        self.assertEqual([c.args[1] for c in operations.call_args_list], ["status"])
        download.assert_not_called()
        self.assertEqual(ack.call_args.args[1]["items"][0]["status"], "unknown")

    def test_ungranted_intent_is_abandoned_not_retried(self):
        item = self.file_item(); state = {"acks": {}}
        with patch.object(lab, "file_operation", side_effect=[{"ok": True}, {"ok": True, "granted": False},
                {"item": dict(item, status="leased")}, {"ok": True, "item": dict(item, status="cancelled")}]) as operations, \
                patch.object(lab, "download_small_file"), patch.object(lab, "request") as ack:
            lab.deliver_file(item, state)
        self.assertEqual([c.args[1] for c in operations.call_args_list], ["renew", "begin", "status", "abandon"])
        ack.assert_not_called()
        self.assertEqual(state["acks"]["77"]["phase"], "cancelled")

    def test_large_media_and_other_scope_rejected_before_file_io(self):
        for name, size in (("a.txt", 1024), ("a.jpg", 10), ("../a.txt", 10), ("a.txt", 0)):
            item = self.file_item();item["attachment"].update(name=name,size=size)
            with patch.object(lab, "file_operation") as op:
                with self.assertRaises(lab.LabError): lab.deliver_file(item, {"acks": {}})
                op.assert_not_called()

    def test_outbox_inspection_is_read_only_and_rejects_scope_mix(self):
        item = self.file_item()
        with patch.object(lab, "request", return_value={"item": item}) as get:
            self.assertEqual(lab.outbox(77), [item])
            self.assertEqual(len(get.call_args.args), 1)
            self.assertTrue(get.call_args.kwargs["admin"])
        with patch.object(lab, "request", return_value={"items": [dict(item, owner_wxid="other")]}):
            with self.assertRaises(lab.LabError): lab.outbox()


if __name__ == "__main__": unittest.main()
