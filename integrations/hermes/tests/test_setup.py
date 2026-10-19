import json
from pathlib import Path
import subprocess
import sys
import os
import sqlite3
from unittest.mock import Mock

import pytest


SETUP = Path(__file__).parents[1] / "observatory" / "setup.py"
sys.path.insert(0, str(SETUP.parent.parent))
from observatory import setup


@pytest.mark.parametrize("changes", [
    {"admin_wxid": "*"}, {"admin_wxid": "wxid_bot"}, {"admin_wxid": "filehelper"},
    {"admin_wxid": "group@chatroom"}, {"owner_wxid": "nickname 中文"},
    {"base_url": "https://password@example.com"}, {"base_url": "https://example.com/?password=x"},
    {"base_url": "https://example.com:wrong"}, {"device": "x" * 129},
])
def test_invalid_binding_is_rejected(changes):
    values = dict(base_url="http://127.0.0.1:18189", device="phone-a", owner_wxid="wxid_bot", admin_wxid="wxid_admin")
    values.update(changes)
    with pytest.raises(ValueError):
        setup.connection_settings(**values)


def test_native_writers_preserve_other_channels_and_hide_secret(tmp_path):
    # A fresh process isolates Hermes' config/profile caches and never reads the
    # developer's own Hermes home. Use the real upstream writers, not mocks.
    script = '''
import json, os, sys
from pathlib import Path
from hermes_cli.config import save_config, read_raw_config, get_env_value
sys.path.insert(0, str(Path(os.environ["SETUP_FILE"]).parent.parent))
from observatory import setup as module
original = {"model": {"provider": "custom:existing", "default": "original-model"},
            "gateway": {"delivery_ledger": True, "standalone": False},
            "platforms": {"telegram": {"enabled": True, "token": "${TELEGRAM_BOT_TOKEN}"},
                          "feishu": {"enabled": True, "extra": {"app_id": "existing-app"}}}}
save_config(original, strip_defaults=False)
module.configure("http://127.0.0.1:18189/", "phone-a", "wxid_bot", "wxid_admin", "unit-test-admin-password")
updated = read_raw_config()
assert updated["model"] == original["model"]
for name in ("telegram", "feishu"):
    assert updated["platforms"][name] == original["platforms"][name]
extra = updated["platforms"]["observatory"]["extra"]
assert updated["gateway"] == original["gateway"]
assert extra["allow_from"] == extra["allow_admin_from"] == ["wxid_admin"]
assert extra["unauthorized_dm_behavior"] == "pair"
assert "model" not in extra["user_allowed_commands"]
assert "unit-test-admin-password" not in (Path(os.environ["HERMES_HOME"]) / "config.yaml").read_text()
assert get_env_value("OBSERVATORY_ADMIN_PASSWORD") == "unit-test-admin-password"
assert (Path(os.environ["HERMES_HOME"]) / ".env").stat().st_mode & 0o077 == 0
# Blank password preserves the existing secret and reconfiguration is idempotent.
module.configure("http://127.0.0.1:18189", "phone-a", "wxid_bot", "wxid_admin")
assert get_env_value("OBSERVATORY_ADMIN_PASSWORD") == "unit-test-admin-password"
# Existing isolated profiles keep their explicit legacy settings too.
save_config({"gateway": {"standalone": True, "delivery_ledger": False}})
module.configure("http://127.0.0.1:18189", "phone-a", "wxid_bot", "wxid_admin")
assert read_raw_config()["gateway"] == {"standalone": True, "delivery_ledger": False}
print(json.dumps({"ok": True}))
'''
    env = {**os.environ, "HERMES_HOME": str(tmp_path), "SETUP_FILE": str(SETUP)}
    env.pop("OBSERVATORY_ADMIN_PASSWORD", None)
    result = subprocess.run([sys.executable, "-c", script], env=env, text=True, capture_output=True, check=True)
    assert json.loads(result.stdout.splitlines()[-1]) == {"ok": True}


def binding_fixture(tmp_path, monkeypatch, *, status="pending", outbox_id=None, wal=False):
    from observatory.client import Settings
    import hermes_constants
    monkeypatch.setattr(hermes_constants, "get_hermes_home", lambda: tmp_path)
    old = setup.connection_settings("http://127.0.0.1:18189", "phone-a", "wxid_bot", "wxid_admin")
    scope = Settings(old["base_url"], old["device"], old["owner_wxid"], "unused").scope
    folder = tmp_path / "observatory"
    folder.mkdir()
    path = folder / (scope + ".sqlite3")
    db = sqlite3.connect(path)
    if wal:
        db.execute("PRAGMA journal_mode=WAL")
    db.execute("CREATE TABLE outgoing(status TEXT, outbox_id INTEGER)")
    db.execute("INSERT INTO outgoing VALUES (?, ?)", (status, outbox_id))
    db.commit()
    return {"platforms": {"observatory": {"extra": old}}}, old, path, db


@pytest.mark.parametrize("change", [
    {"base_url": "https://new.example.test"}, {"device": "phone-b"}, {"owner_wxid": "wxid_other"},
])
def test_pending_binding_cannot_change(tmp_path, monkeypatch, change):
    config, old, path, db = binding_fixture(tmp_path, monkeypatch, wal=True)
    try:
        # Read the live WAL too, not an immutable snapshot that misses committed pending rows.
        before = path.read_bytes()
        with pytest.raises(ValueError, match="旧绑定仍有未确认发送"):
            setup.reject_pending_binding_change(config, {**old, **change})
        assert path.read_bytes() == before
        assert db.execute("SELECT status FROM outgoing").fetchone()[0] == "pending"
    finally:
        db.close()


@pytest.mark.parametrize("status,outbox_id", [("accepted", 8), ("rejected", None)])
def test_terminal_journal_allows_rebinding(tmp_path, monkeypatch, status, outbox_id):
    config, old, path, db = binding_fixture(tmp_path, monkeypatch, status=status, outbox_id=outbox_id)
    db.close()
    before = path.read_bytes()
    setup.reject_pending_binding_change(config, {**old, "device": "phone-b"})
    assert path.read_bytes() == before


def test_same_binding_password_or_admin_change_does_not_inspect_pending(tmp_path, monkeypatch):
    config, old, path, db = binding_fixture(tmp_path, monkeypatch)
    db.close()
    setup.reject_pending_binding_change(config, {**old, "base_url": old["base_url"] + "/", "allow_admin_from": ["new_admin"]})


def test_missing_or_incomplete_old_binding_creates_no_journal(tmp_path, monkeypatch):
    import hermes_constants
    monkeypatch.setattr(hermes_constants, "get_hermes_home", lambda: tmp_path)
    new = setup.connection_settings("https://example.test", "phone-a", "wxid_bot", "wxid_admin")
    setup.reject_pending_binding_change({}, new)
    setup.reject_pending_binding_change({"platforms": {"observatory": {"extra": {"device": "phone-old"}}}}, new)
    setup.reject_pending_binding_change({"platforms": {"observatory": {"extra": {**new, "device": "phone-old"}}}}, new)
    assert list(tmp_path.iterdir()) == []


def test_unreadable_existing_journal_refuses_rebinding(tmp_path, monkeypatch):
    config, old, path, db = binding_fixture(tmp_path, monkeypatch)
    db.close()
    path.write_bytes(b"not a SQLite database")
    with pytest.raises(ValueError, match="无法只读核实"):
        setup.reject_pending_binding_change(config, {**old, "device": "phone-b"})
    assert path.read_bytes() == b"not a SQLite database"


def test_configure_refusal_happens_before_password_or_config_write(tmp_path, monkeypatch):
    import hermes_cli.config as hermes_config
    config, old, path, db = binding_fixture(tmp_path, monkeypatch)
    db.close()
    monkeypatch.setattr(hermes_config, "require_readable_config_before_write", lambda: config)
    write_config, write_secret = Mock(), Mock()
    monkeypatch.setattr(hermes_config, "save_config", write_config)
    monkeypatch.setattr(hermes_config, "save_env_value", write_secret)
    with pytest.raises(ValueError, match="旧绑定仍有未确认发送"):
        setup.configure(old["base_url"], "phone-b", old["owner_wxid"], "wxid_admin", "replacement-secret")
    write_config.assert_not_called()
    write_secret.assert_not_called()


def test_unpatched_shared_gateway_refuses_before_writing_any_settings(monkeypatch):
    from observatory import adapter
    import hermes_cli.config as hermes_config
    monkeypatch.setattr(adapter, "BasePlatformAdapter", object)
    current = {"gateway": {"delivery_ledger": True},
               "platforms": {"feishu": {"enabled": True}}}
    monkeypatch.setattr(hermes_config, "require_readable_config_before_write", lambda: current)
    write_config, write_secret = Mock(), Mock()
    monkeypatch.setattr(hermes_config, "save_config", write_config)
    monkeypatch.setattr(hermes_config, "save_env_value", write_secret)
    with pytest.raises(ValueError, match="delivery identity support"):
        setup.configure("https://example.test", "phone-a", "wxid_bot", "wxid_admin", "unit-secret")
    write_config.assert_not_called()
    write_secret.assert_not_called()
