import json
import uuid
from datetime import datetime, timedelta, timezone

from config import QUEUE_DEFAULT_TTL
from database import get_db


async def enqueue_message(to_user_id: str, payload: dict, ttl: int = QUEUE_DEFAULT_TTL):
    db = await get_db()
    msg_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc)
    expires = now + timedelta(seconds=ttl)
    await db.execute(
        "INSERT INTO message_queue (id, to_user_id, payload, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
        (msg_id, to_user_id, json.dumps(payload), now.isoformat(), expires.isoformat()),
    )
    await db.commit()


async def dequeue_messages(user_id: str) -> list[dict]:
    db = await get_db()
    now = datetime.now(timezone.utc).isoformat()
    # Fetch non-expired messages
    rows = await db.execute_fetchall(
        "SELECT id, payload FROM message_queue WHERE to_user_id = ? AND expires_at > ? ORDER BY created_at",
        (user_id, now),
    )
    messages = []
    ids_to_delete = []
    for row in rows:
        messages.append(json.loads(row[1]))
        ids_to_delete.append(row[0])
    # Delete delivered messages
    if ids_to_delete:
        placeholders = ",".join("?" for _ in ids_to_delete)
        await db.execute(
            f"DELETE FROM message_queue WHERE id IN ({placeholders})",
            ids_to_delete,
        )
        await db.commit()
    return messages


async def cleanup_expired():
    db = await get_db()
    now = datetime.now(timezone.utc).isoformat()
    await db.execute("DELETE FROM message_queue WHERE expires_at <= ?", (now,))
    await db.commit()
