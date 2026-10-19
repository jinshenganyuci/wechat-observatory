"""Fixed Observatory HTTP API; never follows redirects carrying the admin secret."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import stat
import time
import unicodedata
from urllib.parse import urlsplit

import aiohttp

MAX_FILE_BYTES = 300 * 1024 * 1024
HISTORY_PAGE_SIZE = 50
MAX_JSON_BYTES = 8 * 1024 * 1024
MEDIA_EXTENSIONS = frozenset("jpg jpeg bmp png tif tiff heic heif webp avi flv m4v mkv mov mp4 mts webm wmv".split())


class BridgeError(Exception):
    def __init__(self, code: str, *, status: int = 0):
        self.code, self.status = code, status
        super().__init__(f"Observatory: {code}" + (f" (HTTP {status})" if status else ""))


@dataclass(frozen=True)
class Settings:
    base_url: str
    device: str
    owner_wxid: str
    password: str = field(repr=False)

    def __post_init__(self):
        parsed = urlsplit(self.base_url)
        if (parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username
                or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("base_url must be an HTTP(S) origin/path without credentials, query or fragment")
        _ = parsed.port
        if not self.device.strip() or not self.owner_wxid.strip() or not self.password:
            raise ValueError("base_url, device, owner_wxid and OBSERVATORY_ADMIN_PASSWORD are required")

    @property
    def scope(self):
        text = "\n".join((self.base_url.rstrip("/"), self.device, self.owner_wxid))
        return hashlib.sha256(text.encode()).hexdigest()[:24]


def file_description(path: str, name: str | None = None) -> dict:
    source = Path(path)
    name = name or source.name
    if (not name or len(name.encode("utf-8")) > 240 or name.strip() != name
            or name in {".", ".."} or any(c in name for c in "/\\")
            or any(unicodedata.category(c) == "Cc" for c in name)):
        raise ValueError("invalid attachment filename")
    if Path(name).suffix.lower().lstrip(".") in MEDIA_EXTENSIONS:
        raise ValueError("image/video attachments are not supported; use an ordinary document")
    sha = hashlib.sha256()
    with os.fdopen(os.open(source, os.O_RDONLY | os.O_NONBLOCK), "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size <= 0 or info.st_size > MAX_FILE_BYTES:
            raise ValueError("attachment must be a nonempty ordinary file, at most 300 MiB")
        size = 0
        while chunk := stream.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_FILE_BYTES:
                raise ValueError("attachment exceeds 300 MiB")
            sha.update(chunk)
    if size != info.st_size:
        raise ValueError("attachment changed while reading")
    return {"path": str(source.resolve()), "name": name, "size": size, "sha256": sha.hexdigest()}


class Client:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._session: aiohttp.ClientSession | None = None
        self._contact_names: dict[str, tuple[float, str]] = {}

    async def session(self):
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={"X-Bridge-Password": self.settings.password},
                timeout=aiohttp.ClientTimeout(total=30, connect=10),
                connector=aiohttp.TCPConnector(limit=8))
        return self._session

    def url(self, endpoint: str):
        return self.settings.base_url.rstrip("/") + endpoint

    async def _json(self, method, endpoint, **kwargs):
        async with (await self.session()).request(method, self.url(endpoint), allow_redirects=False, **kwargs) as response:
            raw = bytearray()
            async for chunk in response.content.iter_chunked(65536):
                raw.extend(chunk)
                if len(raw) > MAX_JSON_BYTES:
                    raise BridgeError("response_too_large")
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeError):
                raise BridgeError("invalid_response", status=response.status) from None
            if response.status != 200:
                # Never include server-supplied arbitrary messages or secrets in logs.
                code = str(body.get("code", "request_failed")) if isinstance(body, dict) else "request_failed"
                if not code.replace("_", "").isalnum():
                    code = "request_failed"
                raise BridgeError(code, status=response.status)
            return body

    async def ensure_idempotency(self):
        # Older backends silently ignore Idempotency-Key. Never discover that by
        # retrying a POST: require the explicit, read-only server capability first.
        body = await self._json("GET", "/api/modules/status")
        if body.get("capabilities", {}).get("text_idempotency") is not True:
            raise BridgeError("server_text_idempotency_required_upgrade_observatory")
        return body

    async def generation(self):
        body = await self.ensure_idempotency()
        matches = [m for m in body.get("modules", []) if m.get("device") == self.settings.device]
        if len(matches) != 1 or matches[0].get("device_wxid") != self.settings.owner_wxid:
            raise BridgeError("configured_account_mismatch")
        generation = matches[0].get("account_generation")
        if not isinstance(generation, int) or generation <= 0:
            raise BridgeError("account_generation_unavailable")
        return generation

    async def history(self, after: int | None):
        params = {"device": self.settings.device, "owner_wxid": self.settings.owner_wxid,
                  "limit": 1 if after is None else HISTORY_PAGE_SIZE}
        if after is not None:
            params["after_id"] = after
        return (await self._json("GET", "/api/messages", params=params)).get("messages", [])

    async def contact_name(self, wxid: str) -> str:
        """Best-effort display label; never an authorization or mention identity."""
        now = time.monotonic()
        cached = self._contact_names.get(wxid)
        if cached and cached[0] > now:
            return cached[1]
        name = wxid
        try:
            body = await self._json("GET", "/api/module-contacts", params={
                "device": self.settings.device, "owner_wxid": self.settings.owner_wxid,
                "wxid": wxid, "limit": 1}, timeout=aiohttp.ClientTimeout(total=3, connect=2))
            contacts = body.get("contacts", []) if isinstance(body, dict) else []
            for contact in contacts if isinstance(contacts, list) else []:
                if (not isinstance(contact, dict) or contact.get("device") != self.settings.device
                        or contact.get("owner_wxid") != self.settings.owner_wxid
                        or contact.get("wxid") != wxid or contact.get("deleted")):
                    continue
                label = contact.get("remark") or contact.get("nickname")
                if isinstance(label, str):
                    name = " ".join("".join(" " if unicodedata.category(c).startswith("C") else c
                                            for c in label).split())[:160] or wxid
                break
        except (BridgeError, aiohttp.ClientError, TimeoutError):
            pass  # A missing name must not lose an otherwise valid message.
        if len(self._contact_names) >= 256:
            self._contact_names.pop(next(iter(self._contact_names)))
        self._contact_names[wxid] = (now + 300, name)
        return name

    async def events(self, cursor: int | None):
        headers = {"Last-Event-ID": str(cursor)} if cursor is not None else {}
        timeout = aiohttp.ClientTimeout(total=None, connect=10, sock_read=65)
        async with (await self.session()).get(self.url("/api/live/events"),
                params={"device": self.settings.device}, headers=headers, timeout=timeout,
                allow_redirects=False) as response:
            if response.status != 200:
                raise BridgeError("stream_rejected", status=response.status)
            kind, event_id, parts, size = "message", None, [], 0
            async for raw in response.content:
                size += len(raw)
                if size > 2 * 1024 * 1024:
                    raise BridgeError("stream_event_too_large")
                line = raw.decode("utf-8").rstrip("\r\n")
                if not line:
                    if parts:
                        yield kind, event_id, json.loads("\n".join(parts))
                    kind, event_id, parts, size = "message", None, [], 0
                elif line.startswith("event:"):
                    kind = line[6:].strip()
                elif line.startswith("id:"):
                    event_id = int(line[3:].strip())
                elif line.startswith("data:"):
                    parts.append(line[5:].lstrip(" "))

    async def submit(self, payload: dict, key: str):
        headers = {"Idempotency-Key": key}
        if payload["kind"] == "text":
            body = {k: v for k, v in payload.items() if k != "kind"}
            return await self._json("POST", "/api/send/text", json=body, headers=headers)
        attachment = payload["attachment"]
        current = await asyncio.to_thread(file_description, attachment["path"], attachment["name"])
        if current != attachment:
            raise BridgeError("attachment_changed_do_not_resend")
        # aiohttp streams the descriptor, without loading the 300 MiB limit into memory.
        with open(attachment["path"], "rb") as stream:
            form = aiohttp.FormData()
            for field_name in ("device", "owner_wxid", "account_generation", "wxid"):
                form.add_field(field_name, str(payload[field_name]))
            form.add_field("file", stream, filename=attachment["name"], content_type="application/octet-stream")
            return await self._json("POST", "/api/send/file", data=form, headers=headers,
                                    timeout=aiohttp.ClientTimeout(total=1200, connect=10, sock_read=1200))

    async def outbox(self, outbox_id: int):
        return await self._json("GET", f"/api/outbox/{int(outbox_id)}", params={
            "device": self.settings.device, "owner_wxid": self.settings.owner_wxid})

    async def close(self):
        if self._session:
            await self._session.close()
