"""Configure only this platform through Hermes' existing configuration writers."""
from __future__ import annotations

import getpass
import re
import sqlite3
from contextlib import closing
from urllib.parse import urlsplit

SECRET_NAME = "OBSERVATORY_ADMIN_PASSWORD"
_IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]{1,191}\Z")


def connection_settings(base_url: str, device: str, owner_wxid: str, admin_wxid: str) -> dict:
    base_url = base_url.strip().rstrip("/")
    url = urlsplit(base_url)
    if (url.scheme not in {"http", "https"} or not url.hostname or url.username
            or url.password or url.query or url.fragment):
        raise ValueError("服务地址须为完整 http(s) 地址，不能包含密码、查询参数或片段。")
    # Validate a malformed port now, rather than after saving credentials.
    _ = url.port
    identifiers = {"device": device.strip(), "owner_wxid": owner_wxid.strip(),
                   "admin_wxid": admin_wxid.strip()}
    for name, value in identifiers.items():
        if not _IDENTIFIER.fullmatch(value):
            raise ValueError(f"{name} 须为实际 ID，不能使用昵称、群聊 ID 或通配符。")
    if len(identifiers["device"]) > 128:
        raise ValueError("device 最多 128 个字符。")
    if identifiers["admin_wxid"] in {identifiers["owner_wxid"], "filehelper"}:
        raise ValueError("管理员须为给机器人发私聊的另一个微信账号。")
    return {
        "base_url": base_url,
        "device": identifiers["device"],
        "owner_wxid": identifiers["owner_wxid"],
        "allow_from": [identifiers["admin_wxid"]],
        "allow_admin_from": [identifiers["admin_wxid"]],
        "group_admin_mentions": True,
        "group_allow_from": [identifiers["admin_wxid"]],
        "group_allow_admin_from": [identifiers["admin_wxid"]],
        "user_allowed_commands": ["new", "status", "stop"],
        "unauthorized_dm_behavior": "pair",
    }


def reject_pending_binding_change(config: dict, settings: dict) -> None:
    """Read the old journal only; never initialize, migrate or remove a database."""
    previous = config.get("platforms", {}).get("observatory", {}).get("extra", {})
    keys = ("base_url", "device", "owner_wxid")
    if not all(isinstance(previous.get(key), str) and previous[key].strip() for key in keys):
        return  # No complete previous binding on a first installation.
    from .client import Settings
    from hermes_constants import get_hermes_home
    try:
        old = Settings(*(previous[key] for key in keys), password="scope-only")
    except ValueError:
        return  # An invalid/incomplete old binding could not have sent requests.
    new = Settings(*(settings[key] for key in keys), password="scope-only")
    if old.scope == new.scope:
        return  # Password/admin changes can help recover pending requests.
    path = get_hermes_home() / "observatory" / (old.scope + ".sqlite3")
    if not path.is_file():
        return
    try:
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)) as db:
            db.execute("PRAGMA query_only=ON")
            pending = db.execute("SELECT 1 FROM outgoing WHERE status IN ('pending','needs_review') "
                                 "AND outbox_id IS NULL LIMIT 1").fetchone()
    except (sqlite3.Error, OSError) as exc:
        raise ValueError("无法只读核实旧发送状态，暂不能更改绑定；请先停止此 profile 的 gateway 并检查旧任务。") from exc
    if pending:
        raise ValueError("旧绑定仍有未确认发送，不能更改服务地址、设备或账号；请保留原配置并先处理旧任务，已有任务 ID 可在看台查询。")


def configure(base_url: str, device: str, owner_wxid: str, admin_wxid: str,
              password: str = "") -> None:
    from hermes_cli.config import (
        get_env_value, require_readable_config_before_write, save_config, save_env_value,
    )

    settings = connection_settings(base_url, device, owner_wxid, admin_wxid)
    if "\n" in password or "\r" in password:
        raise ValueError("管理密码不能包含换行。")
    if not password and not get_env_value(SECRET_NAME):
        raise ValueError("需要 Observatory 后端的管理密码（不是手机 API Key）。")
    # Validate the existing document before writing either config or secret.
    current = require_readable_config_before_write()
    reject_pending_binding_change(current, settings)
    previous = current.get("platforms", {}).get("observatory", {}).get("extra", {})
    if previous:
        # Reconfiguring a deployed DM channel must not silently enable groups
        # or replace an operator's narrower native group access lists.
        settings["group_admin_mentions"] = previous.get("group_admin_mentions", False)
        for key in ("group_allow_from", "group_allow_admin_from"):
            if key in previous:
                settings[key] = previous[key]
    if password:
        save_env_value(SECRET_NAME, password)
    save_config({"platforms": {"observatory": {
        "enabled": True,
        "typing_indicator": False,
        "gateway_restart_notification": False,
        "extra": settings,
    }}}, merge_existing=True)


def interactive_setup() -> None:
    from hermes_cli.config import get_config_path, require_readable_config_before_write

    config = require_readable_config_before_write()
    current = config.get("platforms", {}).get("observatory", {}).get("extra", {})
    admins = current.get("allow_admin_from") or []
    if isinstance(admins, str):
        admins = admins.split(",")

    def ask(label: str, default: str = "") -> str:
        value = input(f"{label}" + (f" [{default}]" if default else "") + ": ").strip()
        return value or default

    print("Observatory 微信渠道：私聊及管理员在群内真实 @ 机器人的文字，可与飞书共用当前 profile。")
    print("使用真实 wxid；管理员应是向机器人发消息的另一个账号。")
    try:
        base_url = ask("后端服务地址", str(current.get("base_url", "")))
        device = ask("设备 ID", str(current.get("device", "")))
        owner = ask("机器人微信账号 wxid", str(current.get("owner_wxid", "")))
        admin = ask("绑定管理员 wxid", str(admins[0]) if admins else "")
        password = getpass.getpass("后端管理密码（输入隐藏；留空保留已配置密码）: ")
        configure(base_url, device, owner, admin, password)
    except (ValueError, EOFError, KeyboardInterrupt) as exc:
        print(f"未完成配置：{exc or '已取消'}")
        return
    print(f"已保存到 {get_config_path()}。管理员可聊天及使用 /new、/model。")
    print("其他账号须通过 hermes pairing approve observatory <配对码> 批准后才能聊天。")
    print("新安装默认允许管理员在已加入群内 @ 机器人；普通用户的私聊配对不授予群聊触发权限。")
    print("已保留当前 profile 的投递账本、其他渠道及模型设置。")
    print("请在完成旧微信状态迁移后重启该 profile 的 gateway；本向导不会重启现有连接。")
