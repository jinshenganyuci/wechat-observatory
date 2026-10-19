"""Small, profile-local checkpoint and outbound intent journal (no credentials).

Ingress checkpoints mean *claimed for Hermes admission*, not LLM completion. A
crash after claiming can lose an in-memory queued turn; it cannot replay /new.
Outbound intents are committed before HTTP and never change their generation.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


class State:
    def __init__(self, path: Path):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=10)
        os.chmod(path, 0o600)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS ingress (
                event_key TEXT PRIMARY KEY, sequence INTEGER NOT NULL, claimed_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS outgoing (
                intent TEXT PRIMARY KEY, idem TEXT UNIQUE NOT NULL, fingerprint TEXT NOT NULL,
                payload TEXT NOT NULL, outbox_id INTEGER, created_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending', error TEXT NOT NULL DEFAULT '');
            CREATE TABLE IF NOT EXISTS bundles (
                identity TEXT PRIMARY KEY, target TEXT NOT NULL, fingerprint TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS intent_aliases (
                alias TEXT PRIMARY KEY, intent TEXT NOT NULL REFERENCES outgoing(intent));
        """)
        # Development installs made before the status column are upgraded in place.
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(outgoing)")}
        if "status" not in columns:
            self.db.execute("ALTER TABLE outgoing ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'")
            self.db.execute("ALTER TABLE outgoing ADD COLUMN error TEXT NOT NULL DEFAULT ''")
            self.db.commit()
        # Old versions independently replayed these rows. Native Hermes now owns
        # reply recovery: retain unknown requests for inspection. Migrate once,
        # not whenever a standalone sender opens the live adapter's journal.
        self.db.execute("BEGIN IMMEDIATE")
        try:
            if self.db.execute("INSERT OR IGNORE INTO meta VALUES ('native_delivery_v3', 1)").rowcount:
                self.db.execute("UPDATE outgoing SET status='needs_review', "
                                "error=CASE WHEN error='' THEN "
                                "'Legacy send outcome unknown; original request retained' ELSE error END "
                                "WHERE status='pending' AND outbox_id IS NULL")
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise

    @property
    def cursor(self) -> int | None:
        row = self.db.execute("SELECT value FROM meta WHERE key='cursor'").fetchone()
        return row[0] if row else None

    def initialize(self, cursor: int) -> None:
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO meta VALUES ('cursor', ?)", (cursor,))

    def claim(self, sequence: int, event_key: str | None) -> bool:
        """Commit cursor and dedup atomically, BEFORE calling Hermes handle_message."""
        with self.db:
            if self.cursor is None or sequence <= self.cursor:
                return False
            accepted = False
            if event_key:
                accepted = bool(self.db.execute(
                    "INSERT OR IGNORE INTO ingress VALUES (?, ?, ?)",
                    (event_key, sequence, int(time.time()))).rowcount)
            self.db.execute("UPDATE meta SET value=? WHERE key='cursor'", (sequence,))
            # Retain event keys beyond the server's normal history retention.
            self.db.execute("DELETE FROM ingress WHERE claimed_at < ?", (int(time.time()) - 90 * 86400,))
            return accepted

    def find_intent(self, intent: str):
        row = self.db.execute("SELECT * FROM outgoing WHERE intent=? OR intent="
                              "(SELECT intent FROM intent_aliases WHERE alias=?)", (intent, intent)).fetchone()
        return dict(row) if row else None

    def unresolved(self) -> list[dict]:
        return [dict(row) for row in self.db.execute(
            "SELECT * FROM outgoing WHERE outbox_id IS NULL "
            "AND status IN ('pending','needs_review') ORDER BY created_at")]

    def prepare(self, intent: str | None, fingerprint: str, payload: dict) -> dict:
        # BEGIN IMMEDIATE also serializes a standalone sender against the live gateway.
        self.db.execute("BEGIN IMMEDIATE")
        try:
            existing = self.find_intent(intent) if intent else None
            if existing:
                if existing["fingerprint"] != fingerprint:
                    raise ValueError("send identity already belongs to different content")
                self.db.commit()
                return existing
            # Equal text is not an identity. Unanchored invocations stay distinct.
            intent = intent or "notification:" + uuid.uuid4().hex
            self.db.execute("INSERT INTO outgoing (intent,idem,fingerprint,payload,outbox_id,created_at) VALUES (?, ?, ?, ?, NULL, ?)",
                            (intent, "hermes-" + uuid.uuid4().hex, fingerprint,
                             canonical(payload), int(time.time())))
            self.db.commit()
            return self.find_intent(intent)
        except BaseException:
            self.db.rollback()
            raise

    def accepted(self, intent: str, outbox_id: int) -> None:
        with self.db:
            self.db.execute("UPDATE outgoing SET outbox_id=?,status='accepted',payload='{}' WHERE intent=?", (outbox_id, intent))

    def rejected(self, intent: str, error: str) -> None:
        with self.db:
            self.db.execute("UPDATE outgoing SET status='rejected',error=?,payload='{}' WHERE intent=?", (error, intent))

    def needs_review(self, intent: str, error: str) -> None:
        with self.db:
            self.db.execute("UPDATE outgoing SET status='needs_review',error=? "
                            "WHERE intent=? AND outbox_id IS NULL", (error, intent))

    def bundle(self, identity: str | None, target: str, fingerprint: str) -> str:
        self.db.execute("BEGIN IMMEDIATE")
        try:
            if identity:
                row = self.db.execute("SELECT * FROM bundles WHERE identity=?", (identity,)).fetchone()
                if row and (row["target"] != target or row["fingerprint"] != fingerprint):
                    raise ValueError("notification identity already belongs to a different manifest")
            else:
                row = None  # A new standalone invocation is not a retry by content.
            if row:
                self.db.commit()
                return row["identity"]
            identity = identity or "bundle:" + uuid.uuid4().hex
            self.db.execute("INSERT INTO bundles VALUES (?, ?, ?, 0)", (identity, target, fingerprint))
            self.db.commit()
            return identity
        except BaseException:
            self.db.rollback()
            raise

    def finish_bundle(self, identity: str) -> None:
        with self.db:
            self.db.execute("UPDATE bundles SET done=1 WHERE identity=?", (identity,))

    def close(self) -> None:
        self.db.close()
