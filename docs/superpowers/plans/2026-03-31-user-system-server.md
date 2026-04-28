# User System Server Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user authentication, invite codes, WebSocket messaging, and offline message queue to the FastAPI server.

**Architecture:** Extend the existing FastAPI server with SQLite (via aiosqlite), JWT auth, WebSocket hub for real-time messaging, and an in-memory message queue with SQLite-backed persistence. All new routes mount alongside the existing stego router.

**Tech Stack:** FastAPI, SQLite (aiosqlite), python-jose (JWT), passlib (bcrypt), WebSocket (built-in FastAPI)

**Spec:** `docs/superpowers/specs/2026-03-31-user-system-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `server/config.py` | App configuration constants |
| Create | `server/database.py` | SQLite connection and table creation |
| Create | `server/models/__init__.py` | Package init |
| Create | `server/models/user.py` | User dataclass |
| Create | `server/models/invite.py` | InviteCode dataclass |
| Create | `server/services/auth_service.py` | Password hashing, JWT create/verify |
| Create | `server/services/queue_service.py` | Offline message queue |
| Create | `server/services/ws_manager.py` | WebSocket connection manager |
| Create | `server/dependencies.py` | FastAPI dependencies (get_current_user) |
| Create | `server/api/v1/auth.py` | Auth endpoints (register, login, me) |
| Create | `server/api/v1/invite.py` | Invite code endpoints |
| Create | `server/api/v1/ws.py` | WebSocket endpoint |
| Modify | `server/main.py` | Add routers, lifespan, DB init |
| Modify | `server/requirements.txt` | Add new dependencies |

---

## Chunk 1: Foundation (Config, Database, Auth Service)

### Task 1: Dependencies and Configuration

**Files:**
- Modify: `server/requirements.txt`
- Create: `server/config.py`

- [ ] **Step 1: Update requirements.txt**

Add to `server/requirements.txt`:

```
aiosqlite>=0.19.0
python-jose[cryptography]>=3.3.0
passlib[bcrypt]>=1.7.4
pydantic-settings>=2.1.0
websockets>=12.0
```

- [ ] **Step 2: Create config.py**

```python
import os
import secrets

# JWT
JWT_SECRET = os.getenv("JWT_SECRET", secrets.token_hex(32))
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_DAYS = int(os.getenv("JWT_EXPIRE_DAYS", "7"))

# Database
DATABASE_PATH = os.getenv("DATABASE_PATH", "stego.db")

# Message Queue
QUEUE_DEFAULT_TTL = int(os.getenv("QUEUE_DEFAULT_TTL", "86400"))  # seconds

# Invite
INVITE_CODE_LENGTH = int(os.getenv("INVITE_CODE_LENGTH", "8"))

# Revoke
REVOKE_TIME_LIMIT = int(os.getenv("REVOKE_TIME_LIMIT", "120"))  # seconds
```

- [ ] **Step 3: Install dependencies**

Run: `cd server && pip install aiosqlite "python-jose[cryptography]" "passlib[bcrypt]" pydantic-settings websockets`

- [ ] **Step 4: Commit**

```bash
git add server/requirements.txt server/config.py
git commit -m "feat(server): add config and dependencies for user system"
```

---

### Task 2: Database Setup

**Files:**
- Create: `server/database.py`

- [ ] **Step 1: Create database.py with SQLite setup**

```python
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
```

- [ ] **Step 2: Verify database creation works**

Run: `cd server && python -c "import asyncio; from database import init_db, close_db; asyncio.run(init_db()); asyncio.run(close_db()); print('OK')"`
Expected: `OK`

- [ ] **Step 3: Commit**

```bash
git add server/database.py
git commit -m "feat(server): add SQLite database setup with user tables"
```

---

### Task 3: User Model and Auth Service

**Files:**
- Create: `server/models/__init__.py`
- Create: `server/models/user.py`
- Create: `server/services/auth_service.py`

- [ ] **Step 1: Create models package**

`server/models/__init__.py` (empty file):
```python
```

`server/models/user.py`:
```python
from dataclasses import dataclass
from datetime import datetime


@dataclass
class User:
    id: str
    username: str
    password_hash: str
    created_at: datetime | None = None
```

- [ ] **Step 2: Create auth service**

`server/services/auth_service.py`:
```python
import uuid
from datetime import datetime, timedelta, timezone

from jose import jwt, JWTError
from passlib.context import CryptContext

from config import JWT_SECRET, JWT_ALGORITHM, JWT_EXPIRE_DAYS
from database import get_db

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


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
```

- [ ] **Step 3: Commit**

```bash
git add server/models/ server/services/auth_service.py
git commit -m "feat(server): add user model and auth service with JWT"
```

---

### Task 4: Auth Dependencies

**Files:**
- Create: `server/dependencies.py`

- [ ] **Step 1: Create dependencies.py**

```python
from fastapi import Header, HTTPException, Query

from services.auth_service import decode_token, get_user_by_id


async def get_current_user(authorization: str = Header(None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Not authenticated")
    token = authorization.split(" ", 1)[1]
    payload = decode_token(token)
    if not payload:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = await get_user_by_id(payload["sub"])
    if not user:
        raise HTTPException(status_code=401, detail="User not found")
    return user


async def get_current_user_ws(token: str = Query(None)) -> dict | None:
    """WebSocket auth via query param. Returns None if invalid."""
    if not token:
        return None
    payload = decode_token(token)
    if not payload:
        return None
    return await get_user_by_id(payload["sub"])
```

- [ ] **Step 2: Commit**

```bash
git add server/dependencies.py
git commit -m "feat(server): add auth dependencies for route protection"
```

---

## Chunk 2: Auth and Invite API

### Task 5: Auth API Endpoints

**Files:**
- Create: `server/api/v1/auth.py`

- [ ] **Step 1: Create auth router**

```python
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, field_validator

from dependencies import get_current_user
from services.auth_service import create_user, authenticate_user

router = APIRouter(prefix="/api/v1/auth", tags=["Auth"])


class RegisterRequest(BaseModel):
    username: str
    password: str

    @field_validator("username")
    @classmethod
    def validate_username(cls, v: str) -> str:
        v = v.strip()
        if len(v) < 3 or len(v) > 32:
            raise ValueError("Username must be 3-32 characters")
        if not v.isalnum() and not all(c.isalnum() or c == '_' for c in v):
            raise ValueError("Username can only contain letters, numbers, underscores")
        return v

    @field_validator("password")
    @classmethod
    def validate_password(cls, v: str) -> str:
        if len(v) < 6:
            raise ValueError("Password must be at least 6 characters")
        return v


class LoginRequest(BaseModel):
    username: str
    password: str


@router.post("/register", status_code=201)
async def register(req: RegisterRequest):
    try:
        result = await create_user(req.username, req.password)
        return result
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))


@router.post("/login")
async def login(req: LoginRequest):
    try:
        result = await authenticate_user(req.username, req.password)
        return result
    except ValueError as e:
        raise HTTPException(status_code=401, detail=str(e))


@router.get("/me")
async def me(user: dict = Depends(get_current_user)):
    return user


@router.post("/logout")
async def logout(user: dict = Depends(get_current_user)):
    # Stateless JWT - client simply discards token
    return {"message": "Logged out"}
```

- [ ] **Step 2: Test auth endpoints manually**

Start server, then test with curl:
```bash
# Register
curl -X POST http://localhost:8000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123"}'

# Login
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123"}'

# Me (use token from login response)
curl http://localhost:8000/api/v1/auth/me \
  -H "Authorization: Bearer <token>"
```

- [ ] **Step 3: Commit**

```bash
git add server/api/v1/auth.py
git commit -m "feat(server): add auth API endpoints (register, login, me)"
```

---

### Task 6: Invite Code System

**Files:**
- Create: `server/models/invite.py`
- Create: `server/api/v1/invite.py`

- [ ] **Step 1: Create invite model**

`server/models/invite.py`:
```python
from dataclasses import dataclass
from datetime import datetime


@dataclass
class InviteCode:
    code: str
    user_id: str
    created_at: datetime | None = None
```

- [ ] **Step 2: Create invite router**

`server/api/v1/invite.py`:
```python
import secrets
import string

from fastapi import APIRouter, Depends, HTTPException

from config import INVITE_CODE_LENGTH
from database import get_db
from dependencies import get_current_user

router = APIRouter(prefix="/api/v1/invite", tags=["Invite"])

ALPHABET = string.ascii_letters + string.digits


def generate_code(length: int = INVITE_CODE_LENGTH) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


@router.get("/my-code")
async def get_my_code(user: dict = Depends(get_current_user)):
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT code, created_at FROM invite_codes WHERE user_id = ?",
        (user["user_id"],),
    )
    if rows:
        return {
            "code": rows[0][0],
            "link": f"stegoapp://add/{rows[0][0]}",
            "created_at": rows[0][1],
        }
    # Auto-create if none exists
    code = generate_code()
    await db.execute(
        "INSERT INTO invite_codes (code, user_id) VALUES (?, ?)",
        (code, user["user_id"]),
    )
    await db.commit()
    return {"code": code, "link": f"stegoapp://add/{code}", "created_at": None}


@router.post("/reset")
async def reset_code(user: dict = Depends(get_current_user)):
    db = await get_db()
    await db.execute(
        "DELETE FROM invite_codes WHERE user_id = ?",
        (user["user_id"],),
    )
    code = generate_code()
    await db.execute(
        "INSERT INTO invite_codes (code, user_id) VALUES (?, ?)",
        (code, user["user_id"]),
    )
    await db.commit()
    return {"code": code, "link": f"stegoapp://add/{code}", "created_at": None}


@router.get("/{code}")
async def lookup_code(code: str):
    db = await get_db()
    rows = await db.execute_fetchall(
        """SELECT u.id, u.username FROM invite_codes ic
           JOIN users u ON ic.user_id = u.id
           WHERE ic.code = ?""",
        (code,),
    )
    if not rows:
        raise HTTPException(status_code=404, detail="Invalid invite code")
    return {"user_id": rows[0][0], "username": rows[0][1]}
```

- [ ] **Step 3: Commit**

```bash
git add server/models/invite.py server/api/v1/invite.py
git commit -m "feat(server): add invite code system"
```

---

## Chunk 3: WebSocket and Message Queue

### Task 7: WebSocket Connection Manager

**Files:**
- Create: `server/services/ws_manager.py`

- [ ] **Step 1: Create WebSocket manager**

```python
import json
from fastapi import WebSocket


class ConnectionManager:
    def __init__(self):
        # user_id -> WebSocket
        self.active: dict[str, WebSocket] = {}

    async def connect(self, user_id: str, ws: WebSocket):
        await ws.accept()
        # Close existing connection if any (single device)
        old = self.active.get(user_id)
        if old:
            try:
                await old.close(code=4001, reason="Connected from another device")
            except Exception:
                pass
        self.active[user_id] = ws

    def disconnect(self, user_id: str, ws: WebSocket):
        if self.active.get(user_id) is ws:
            del self.active[user_id]

    def is_online(self, user_id: str) -> bool:
        return user_id in self.active

    async def send_to_user(self, user_id: str, message: dict) -> bool:
        ws = self.active.get(user_id)
        if ws:
            try:
                await ws.send_text(json.dumps(message))
                return True
            except Exception:
                self.disconnect(user_id, ws)
                return False
        return False

    async def broadcast_to_users(self, user_ids: list[str], message: dict):
        for uid in user_ids:
            await self.send_to_user(uid, message)


manager = ConnectionManager()
```

- [ ] **Step 2: Commit**

```bash
git add server/services/ws_manager.py
git commit -m "feat(server): add WebSocket connection manager"
```

---

### Task 8: Message Queue Service

**Files:**
- Create: `server/services/queue_service.py`

- [ ] **Step 1: Create queue service**

```python
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
```

- [ ] **Step 2: Commit**

```bash
git add server/services/queue_service.py
git commit -m "feat(server): add offline message queue service"
```

---

### Task 9: WebSocket Endpoint

**Files:**
- Create: `server/api/v1/ws.py`

- [ ] **Step 1: Create WebSocket endpoint**

```python
import json
import time
import uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from dependencies import get_current_user_ws
from services.ws_manager import manager
from services.queue_service import enqueue_message, dequeue_messages

router = APIRouter()


@router.websocket("/ws")
async def websocket_endpoint(ws: WebSocket, token: str = None):
    # Authenticate
    user = await get_current_user_ws(token)
    if not user:
        await ws.close(code=4003, reason="Authentication failed")
        return

    user_id = user["user_id"]
    await manager.connect(user_id, ws)

    try:
        # Deliver queued messages on connect
        queued = await dequeue_messages(user_id)
        for msg in queued:
            await ws.send_text(json.dumps(msg))

        # Main message loop
        while True:
            data = await ws.receive_text()
            try:
                message = json.loads(data)
            except json.JSONDecodeError:
                await ws.send_text(json.dumps({
                    "type": "error",
                    "payload": {"message": "Invalid JSON"}
                }))
                continue

            await handle_message(user_id, user["username"], message)
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(user_id, ws)


async def handle_message(from_user_id: str, from_username: str, message: dict):
    msg_type = message.get("type")
    payload = message.get("payload", {})
    to_user_id = payload.get("to_user_id")

    if not to_user_id:
        return

    if msg_type == "chat":
        # Ensure sender info is set server-side
        payload["from_user_id"] = from_user_id
        payload["from_username"] = from_username
        if not message.get("id"):
            message["id"] = str(uuid.uuid4())
        if not message.get("timestamp"):
            message["timestamp"] = int(time.time())

        outgoing = {
            "type": "chat",
            "id": message["id"],
            "timestamp": message["timestamp"],
            "payload": payload,
        }

        # Send ack to sender
        await manager.send_to_user(from_user_id, {
            "type": "ack",
            "id": message["id"],
            "timestamp": message["timestamp"],
        })

        # Try to deliver directly
        delivered = await manager.send_to_user(to_user_id, outgoing)
        if not delivered:
            # Queue for offline delivery
            await enqueue_message(to_user_id, outgoing)

    elif msg_type in ("delivered", "read", "revoke"):
        # Forward status messages to recipient
        forward = {
            "type": msg_type,
            "id": message.get("id", str(uuid.uuid4())),
            "timestamp": message.get("timestamp", int(time.time())),
            "payload": {
                **payload,
                "from_user_id": from_user_id,
            },
        }
        delivered = await manager.send_to_user(to_user_id, forward)
        if not delivered:
            await enqueue_message(to_user_id, forward)

    elif msg_type == "typing":
        # Forward typing indicator (no queuing for offline)
        await manager.send_to_user(to_user_id, {
            "type": "typing",
            "payload": {"from_user_id": from_user_id},
        })
```

- [ ] **Step 2: Commit**

```bash
git add server/api/v1/ws.py
git commit -m "feat(server): add WebSocket endpoint with message routing"
```

---

## Chunk 4: Integration

### Task 10: Update main.py

**Files:**
- Modify: `server/main.py`

- [ ] **Step 1: Rewrite main.py with all routers and lifecycle**

Replace entire `server/main.py` with:

```python
import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from database import init_db, close_db
from services.queue_service import cleanup_expired


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    await init_db()

    # Background task: cleanup expired queue messages every 5 minutes
    async def periodic_cleanup():
        while True:
            await asyncio.sleep(300)
            try:
                await cleanup_expired()
            except Exception:
                pass

    task = asyncio.create_task(periodic_cleanup())

    yield

    # Shutdown
    task.cancel()
    await close_db()


app = FastAPI(title="Stego API", version="0.2.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Import and register routers
from api.v1.stego import router as stego_router
from api.v1.auth import router as auth_router
from api.v1.invite import router as invite_router
from api.v1.ws import router as ws_router

app.include_router(stego_router)
app.include_router(auth_router)
app.include_router(invite_router)
app.include_router(ws_router)


@app.get("/health")
async def health():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

- [ ] **Step 2: Start server and verify all endpoints**

Run: `cd server && python main.py`

Verify:
```bash
# Health
curl http://localhost:8000/health

# Register
curl -X POST http://localhost:8000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"pass123"}'

# Login
curl -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"pass123"}'

# Get invite code (use token from above)
curl http://localhost:8000/api/v1/invite/my-code \
  -H "Authorization: Bearer <token>"

# Stego models (existing endpoint still works)
curl http://localhost:8000/api/v1/stego/models
```

- [ ] **Step 3: Commit**

```bash
git add server/main.py
git commit -m "feat(server): integrate auth, invite, and WebSocket routers"
```

---

### Task 11: Add User Public Info Endpoint

**Files:**
- Modify: `server/api/v1/auth.py`

- [ ] **Step 1: Add public user info endpoint**

Add to the end of `server/api/v1/auth.py`:

```python
@router.get("/user/{user_id}/public")
async def get_user_public(user_id: str):
    from services.auth_service import get_user_by_id
    user = await get_user_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return {"user_id": user["user_id"], "username": user["username"]}
```

Note: This endpoint is under `/api/v1/auth/user/{user_id}/public`. Alternatively, we could create a separate user router, but since there's only one endpoint, keeping it in auth is simpler.

- [ ] **Step 2: Commit**

```bash
git add server/api/v1/auth.py
git commit -m "feat(server): add public user info endpoint"
```

---

## Summary

After completing all tasks, the server will have:

| Feature | Endpoint |
|---------|----------|
| Register | `POST /api/v1/auth/register` |
| Login | `POST /api/v1/auth/login` |
| Current user | `GET /api/v1/auth/me` |
| Logout | `POST /api/v1/auth/logout` |
| Public user info | `GET /api/v1/auth/user/{id}/public` |
| My invite code | `GET /api/v1/invite/my-code` |
| Reset invite | `POST /api/v1/invite/reset` |
| Lookup invite | `GET /api/v1/invite/{code}` |
| WebSocket | `WS /ws?token=<jwt>` |
| Stego (existing) | `/api/v1/stego/*` |
