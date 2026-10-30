"""Durable review state. Every mutation is serialized and transactionally fenced."""

import hashlib
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from aiohttp import web

TERMINAL = {"succeeded", "failed", "cancelled"}


class LostLease(Exception):
    pass


class ReviewStore:
    def __init__(self, quota, db_path):
        self.q = quota
        self.db = quota.db
        self.media = Path(str(db_path) + ".media")
        self.media.mkdir(parents=True, exist_ok=True)
        with self.q.lock:
            self.db.executescript("""
                CREATE TABLE IF NOT EXISTS reviews(
                  user_id TEXT, id TEXT, fingerprint TEXT, spec BLOB,
                  state TEXT, percent INTEGER DEFAULT 0, result BLOB, error TEXT,
                  created REAL, terminal REAL, token TEXT, lease_until REAL DEFAULT 0,
                  attempts INTEGER DEFAULT 0, partial_report TEXT DEFAULT '', PRIMARY KEY(user_id,id));
                CREATE TABLE IF NOT EXISTS review_uploads(
                  user_id TEXT, id TEXT, source_id TEXT, digest TEXT, path TEXT,
                  duration REAL, PRIMARY KEY(user_id,id,source_id));
                CREATE TABLE IF NOT EXISTS review_checkpoints(
                  user_id TEXT, id TEXT, stage TEXT, value BLOB,
                  PRIMARY KEY(user_id,id,stage));
                CREATE TABLE IF NOT EXISTS review_events(
                  user_id TEXT, id TEXT, seq INTEGER, value BLOB,
                  PRIMARY KEY(user_id,id,seq));
                CREATE INDEX IF NOT EXISTS review_claim ON reviews(state,lease_until);
            """)

    def pack(self, value):
        return self.q.cipher.encrypt(json.dumps(value, ensure_ascii=False).encode())

    def unpack(self, value):
        return json.loads(self.q.cipher.decrypt(value)) if value else None

    @contextmanager
    def tx(self):
        with self.q.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def row(self, user, rid):
        cur = self.db.execute(
            "SELECT * FROM reviews WHERE user_id=? AND id=?", (user, rid)
        )
        row = cur.fetchone()
        if row is None:
            raise web.HTTPNotFound(text="review_not_found")
        return dict(zip([c[0] for c in cur.description], row, strict=True))

    def snapshot(self, user, rid):
        row = self.row(user, rid)
        seq = self.db.execute(
            "SELECT coalesce(max(seq),0) FROM review_events WHERE user_id=? AND id=?",
            (user, rid),
        ).fetchone()[0]
        return {
            "id": rid,
            "state": row["state"],
            "percent": row["percent"],
            "remaining": self.q.remaining(user),
            "last_event_id": seq,
            "error": row["error"],
            "result": self.unpack(row["result"]),
            "partial_report": self.unpack(row["partial_report"]) or "",
        }

    def get(self, user, rid):
        with self.q.lock:
            return self.snapshot(user, rid)

    def event(self, user, rid):
        value = self.snapshot(user, rid)
        value["last_event_id"] += 1
        self.db.execute(
            "INSERT INTO review_events VALUES(?,?,?,?)",
            (user, rid, value["last_event_id"], self.pack(value)),
        )
        return value

    def create(self, user, spec):
        rid = spec["request_id"]
        digest = hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()
        with self.tx():
            exists = self.db.execute(
                "SELECT fingerprint FROM reviews WHERE user_id=? AND id=?", (user, rid)
            ).fetchone()
            if exists:
                if exists[0] != digest:
                    raise web.HTTPConflict(text="request_id_conflict")
                return self.snapshot(user, rid), False
            if (
                self.db.execute(
                    "SELECT count(*) FROM reviews WHERE user_id=? AND state='draft'",
                    (user,),
                ).fetchone()[0]
                >= 10
            ):
                raise web.HTTPConflict(text="too_many_drafts")
            self.db.execute(
                "INSERT INTO reviews(user_id,id,fingerprint,spec,state,created) VALUES(?,?,?,?,'draft',?)",
                (user, rid, digest, self.pack(spec), time.time()),
            )
            return self.event(user, rid), True

    def source(self, user, rid, sid):
        with self.q.lock:
            spec = self.unpack(self.row(user, rid)["spec"])
            for source in spec["sources"]:
                if source["source_id"] == sid:
                    return source
        raise web.HTTPNotFound(text="source_not_found")

    def upload(self, user, rid, sid, raw, duration):
        digest = hashlib.sha256(raw).hexdigest()
        with self.tx():
            row = self.row(user, rid)
            old = self.db.execute(
                "SELECT digest FROM review_uploads WHERE user_id=? AND id=? AND source_id=?",
                (user, rid, sid),
            ).fetchone()
            if old and old[0] == digest:
                return {"source_id": sid, "uploaded": True}
            if old or row["state"] != "draft":
                raise web.HTTPConflict(text="upload_conflict")
            total = self.db.execute(
                "SELECT coalesce(sum(duration),0) FROM review_uploads WHERE user_id=? AND id=?",
                (user, rid),
            ).fetchone()[0]
            if total + duration > 3600:
                raise web.HTTPRequestEntityTooLarge(
                    max_size=3600, actual_size=total + duration
                )
            name = uuid.uuid4().hex
            path = self.media / name
            encrypted = self.q.cipher.encrypt(raw)
            try:
                with path.open("xb") as output:
                    if os.name == "posix":
                        os.chmod(path, 0o600)
                    output.write(encrypted)
                    output.flush()
                    os.fsync(output.fileno())
                if os.name == "posix":
                    directory = os.open(self.media, os.O_RDONLY)
                    try:
                        os.fsync(directory)
                    finally:
                        os.close(directory)
                self.db.execute(
                    "INSERT INTO review_uploads VALUES(?,?,?,?,?,?)",
                    (user, rid, sid, digest, name, duration),
                )
            except BaseException:
                path.unlink(missing_ok=True)
                raise
            return {"source_id": sid, "uploaded": True}

    def raw(self, user, rid, sid):
        with self.q.lock:
            row = self.db.execute(
                "SELECT path FROM review_uploads WHERE user_id=? AND id=? AND source_id=?",
                (user, rid, sid),
            ).fetchone()
            if not row:
                raise web.HTTPGone(text="sources_expired")
            return self.q.cipher.decrypt((self.media / row[0]).read_bytes())

    def start(self, user, rid, resume=False):
        with self.tx():
            row = self.row(user, rid)
            if row["state"] in {"queued", "running", "succeeded"}:
                return self.snapshot(user, rid)
            if row["state"] != "draft" and not resume:
                raise web.HTTPConflict(text="use_resume")
            if row["terminal"] and time.time() - row["terminal"] >= 86400:
                raise web.HTTPGone(text="sources_expired")
            if self.db.execute(
                "SELECT 1 FROM review_checkpoints WHERE user_id=? AND id=? AND stage='_media_expired'",
                (user, rid),
            ).fetchone():
                raise web.HTTPGone(text="sources_expired")
            spec = self.unpack(row["spec"])
            uploads = self.db.execute(
                "SELECT source_id,duration,path FROM review_uploads WHERE user_id=? AND id=?",
                (user, rid),
            ).fetchall()
            if len(uploads) != len(spec["sources"]):
                if resume and row["state"] == "cancelled":
                    # Cancellation during upload is not media expiry. Return to the
                    # upload phase without reserving credit; retained uploads stay idempotent.
                    self.db.execute(
                        "UPDATE reviews SET state='draft',terminal=NULL,error=NULL,created=? WHERE user_id=? AND id=?",
                        (time.time(), user, rid),
                    )
                    return self.event(user, rid)
                if row["state"] != "draft":
                    raise web.HTTPGone(text="sources_expired")
                raise web.HTTPConflict(text="uploads_incomplete")
            if sum(x[1] for x in uploads) > 3600:
                raise web.HTTPBadRequest(text="duration_exceeded")
            if any(not (self.media / x[2]).is_file() for x in uploads):
                raise web.HTTPGone(text="sources_expired")
            if self.q.remaining(user) <= 0:
                raise web.HTTPPaymentRequired(text="trial_exhausted")
            self.db.execute(
                "INSERT INTO tasks VALUES(?,?,?,'pending',NULL,?)",
                (user, "/review/" + rid, row["fingerprint"], int(time.time())),
            )
            self.db.execute(
                "UPDATE reviews SET state='queued',error=NULL,terminal=NULL,token=NULL,lease_until=0,attempts=0 WHERE user_id=? AND id=?",
                (user, rid),
            )
            return self.event(user, rid)

    def cancel(self, user, rid):
        with self.tx():
            row = self.row(user, rid)
            if row["state"] in {"succeeded", "cancelled"}:
                return self.snapshot(user, rid)
            self.q.refund(user, "/review/" + rid)
            self.db.execute(
                "UPDATE reviews SET state='cancelled',terminal=?,token=NULL,lease_until=0,error=NULL WHERE user_id=? AND id=?",
                (time.time(), user, rid),
            )
            return self.event(user, rid)

    def claim(self, lease_seconds=60):
        with self.tx():
            row = self.db.execute(
                "SELECT user_id,id FROM reviews WHERE state='queued' OR (state='running' AND lease_until<?) ORDER BY created LIMIT 1",
                (time.time(),),
            ).fetchone()
            if not row:
                return None
            user, rid = row
            token = uuid.uuid4().hex
            self.db.execute(
                "UPDATE reviews SET state='running',token=?,lease_until=?,attempts=attempts+1 WHERE user_id=? AND id=?",
                (token, time.time() + lease_seconds, user, rid),
            )
            self.event(user, rid)
            return user, rid, token, self.unpack(self.row(user, rid)["spec"])

    def fence(self, user, rid, token):
        row = self.row(user, rid)
        if (
            row["state"] != "running"
            or row["token"] != token
            or row["lease_until"] <= time.time()
        ):
            raise LostLease()
        return row

    def heartbeat(self, user, rid, token):
        with self.tx():
            self.fence(user, rid, token)
            self.db.execute(
                "UPDATE reviews SET lease_until=? WHERE user_id=? AND id=?",
                (time.time() + 60, user, rid),
            )

    def checkpoint(self, user, rid, token, stage, value=None, percent=None):
        with self.tx():
            self.fence(user, rid, token)
            if value is None:
                row = self.db.execute(
                    "SELECT value FROM review_checkpoints WHERE user_id=? AND id=? AND stage=?",
                    (user, rid, stage),
                ).fetchone()
                return self.unpack(row[0]) if row else None
            self.db.execute(
                "INSERT OR REPLACE INTO review_checkpoints VALUES(?,?,?,?)",
                (user, rid, stage, self.pack(value)),
            )
            if stage.startswith("analysis/"):
                old = self.db.execute(
                    "SELECT value FROM review_checkpoints WHERE user_id=? AND id=? AND stage='partial_committed'",
                    (user, rid),
                ).fetchone()
                partial = self.unpack(old[0]) if old else ""
                partial += ("\n\n" if partial else "") + value["summary"]
                partial += "\n" + "\n".join(
                    f"{x['text']} [{', '.join(x['citations'])}]"
                    for x in value["findings"]
                )
                self.db.execute(
                    "UPDATE reviews SET partial_report=? WHERE user_id=? AND id=?",
                    (self.pack(partial), user, rid),
                )
                self.db.execute(
                    "INSERT OR REPLACE INTO review_checkpoints VALUES(?,?,?,?)",
                    (user, rid, "partial_committed", self.pack(partial)),
                )
            if percent is not None:
                old = self.row(user, rid)["percent"]
                pct = min(99, max(old, int(percent)))
                if pct != old or stage.startswith("analysis/"):
                    self.db.execute(
                        "UPDATE reviews SET percent=? WHERE user_id=? AND id=?",
                        (pct, user, rid),
                    )
                    self.event(user, rid)
            return value

    def preview(self, user, rid, token, text):
        with self.tx():
            self.fence(user, rid, token)
            row = self.row(user, rid)
            if (self.unpack(row["partial_report"]) or "") != text:
                self.db.execute(
                    "UPDATE reviews SET partial_report=? WHERE user_id=? AND id=?",
                    (self.pack(text), user, rid),
                )
                self.event(user, rid)

    def finish(self, user, rid, token, result):
        with self.tx():
            self.fence(user, rid, token)
            self.db.execute(
                "UPDATE tasks SET state='done',result=? WHERE user_id=? AND request_id=? AND state='pending'",
                (self.pack(result), user, "/review/" + rid),
            )
            self.db.execute(
                "UPDATE reviews SET state='succeeded',percent=100,result=?,terminal=?,token=NULL,lease_until=0,error=NULL WHERE user_id=? AND id=?",
                (self.pack(result), time.time(), user, rid),
            )
            if "reportText" in result:
                self.db.execute(
                    "UPDATE reviews SET partial_report=? WHERE user_id=? AND id=?",
                    (self.pack(result["reportText"]), user, rid),
                )
            return self.event(user, rid)

    def fail(self, user, rid, token):
        with self.tx():
            row = self.fence(user, rid, token)
            if row["attempts"] < 3:
                self.db.execute(
                    "UPDATE reviews SET state='queued',token=NULL,lease_until=0 WHERE user_id=? AND id=?",
                    (user, rid),
                )
            else:
                self.q.refund(user, "/review/" + rid)
                self.db.execute(
                    "UPDATE reviews SET state='failed',error='review_unavailable',terminal=?,token=NULL,lease_until=0 WHERE user_id=? AND id=?",
                    (time.time(), user, rid),
                )
            self.event(user, rid)

    def release(self, user, rid, token):
        with self.tx():
            changed = self.db.execute(
                "UPDATE reviews SET state='queued',token=NULL,lease_until=0 WHERE user_id=? AND id=? AND state='running' AND token=?",
                (user, rid, token),
            )
            if changed.rowcount:
                self.event(user, rid)

    def events(self, user, rid, after):
        with self.q.lock:
            self.row(user, rid)
            return [
                self.unpack(x[0])
                for x in self.db.execute(
                    "SELECT value FROM review_events WHERE user_id=? AND id=? AND seq>? ORDER BY seq LIMIT 100",
                    (user, rid, after),
                )
            ]

    def cleanup(self, now=None):
        now = time.time() if now is None else now
        with self.tx():
            drafts = self.db.execute(
                "SELECT user_id,id FROM reviews WHERE state='draft' AND created<?",
                (now - 86400,),
            ).fetchall()
            for user, rid in drafts:
                self.db.execute(
                    "UPDATE reviews SET state='cancelled',terminal=created WHERE user_id=? AND id=?",
                    (user, rid),
                )
                self.event(user, rid)
            expired = self.db.execute(
                "SELECT user_id,id FROM reviews WHERE terminal IS NOT NULL AND terminal<=?",
                (now - 86400,),
            ).fetchall()
            for user, rid in expired:
                for (name,) in self.db.execute(
                    "SELECT path FROM review_uploads WHERE user_id=? AND id=?",
                    (user, rid),
                ):
                    (self.media / name).unlink(missing_ok=True)
                self.db.execute(
                    "DELETE FROM review_uploads WHERE user_id=? AND id=?", (user, rid)
                )
                self.db.execute(
                    "INSERT OR REPLACE INTO review_checkpoints VALUES(?,?,?,?)",
                    (user, rid, "_media_expired", self.pack(True)),
                )
            referenced = {
                x[0] for x in self.db.execute("SELECT path FROM review_uploads")
            }
            for path in self.media.iterdir():
                if path.is_file() and path.name not in referenced:
                    path.unlink()
