import uuid
from datetime import datetime, timedelta, timezone

import hashlib
import hmac

from jose import jwt, JWTError

from config import JWT_SECRET, JWT_ALGORITHM, JWT_EXPIRE_DAYS
from database import get_db


def hash_password(password: str) -> str:
    salt = uuid.uuid4().hex
    hashed = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 100000).hex()
    return f"{salt}${hashed}"


def verify_password(plain: str, stored: str) -> bool:
    salt, hashed = stored.split("$", 1)
    check = hashlib.pbkdf2_hmac("sha256", plain.encode(), salt.encode(), 100000).hex()
    return hmac.compare_digest(hashed, check)


def create_token(user_id: str, username: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(days=JWT_EXPIRE_DAYS)
    payload = {
        "sub": user_id,
        "username": username,
        "exp": expire,
        "iat": datetime.now(timezone.utc),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_token(token: str) -> dict | None:
    try:
        return jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except JWTError:
        return None


async def create_user(username: str, password: str) -> dict:
    db = await get_db()
    user_id = str(uuid.uuid4())
    password_hash = hash_password(password)
    try:
        await db.execute(
            "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
            (user_id, username, password_hash),
        )
        await db.commit()
    except Exception:
        raise ValueError("Username already exists")
    token = create_token(user_id, username)
    return {"user_id": user_id, "username": username, "token": token}


async def authenticate_user(username: str, password: str) -> dict:
    db = await get_db()
    row = await db.execute_fetchall(
        "SELECT id, username, password_hash FROM users WHERE username = ?",
        (username,),
    )
    if not row:
        raise ValueError("Invalid username or password")
    user = row[0]
    if not verify_password(password, user[2]):
        raise ValueError("Invalid username or password")
    token = create_token(user[0], user[1])
    return {"user_id": user[0], "username": user[1], "token": token}


async def get_user_by_id(user_id: str) -> dict | None:
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT id, username, created_at FROM users WHERE id = ?",
        (user_id,),
    )
    if not rows:
        return None
    row = rows[0]
    return {"user_id": row[0], "username": row[1], "created_at": row[2]}
