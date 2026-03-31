import aiosqlite
from config import DATABASE_PATH

_db: aiosqlite.Connection | None = None


async def get_db() -> aiosqlite.Connection:
    global _db
    if _db is None:
        raise RuntimeError("Database not initialized")
    return _db


async def init_db():
    global _db
    _db = await aiosqlite.connect(DATABASE_PATH)
    _db.row_factory = aiosqlite.Row
    await _db.execute("PRAGMA journal_mode=WAL")
    await _db.execute("PRAGMA foreign_keys=ON")

    await _db.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id          TEXT PRIMARY KEY,
            username    TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS invite_codes (
            code        TEXT PRIMARY KEY,
            user_id     TEXT UNIQUE NOT NULL REFERENCES users(id),
            created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS message_queue (
            id          TEXT PRIMARY KEY,
            to_user_id  TEXT NOT NULL REFERENCES users(id),
            payload     TEXT NOT NULL,
            created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
            expires_at  DATETIME NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_queue_expires ON message_queue(expires_at);
        CREATE INDEX IF NOT EXISTS idx_queue_user ON message_queue(to_user_id);
    """)
    await _db.commit()


async def close_db():
    global _db
    if _db:
        await _db.close()
        _db = None
