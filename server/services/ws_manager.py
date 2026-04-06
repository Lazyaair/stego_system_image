import asyncio
import json
from fastapi import WebSocket


class ConnectionManager:
    def __init__(self):
        # user_id -> WebSocket
        self.active: dict[str, WebSocket] = {}

    async def connect(self, user_id: str, ws: WebSocket):
        await ws.accept()
        # Kick existing connection if any (single device)
        old = self.active.get(user_id)
        if old:
            try:
                await old.send_text(json.dumps({
                    "type": "kicked",
                    "payload": {"reason": "Logged in on another device"}
                }))
            except Exception:
                pass

            # Force close after 5s as backup (in case client doesn't disconnect)
            async def _force_close():
                await asyncio.sleep(5)
                try:
                    await old.close(code=4001, reason="Connected from another device")
                except Exception:
                    pass
            asyncio.create_task(_force_close())

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
                data = json.dumps(message)
                await ws.send_text(data)
                return True
            except Exception as e:
                print(f"[STEGO-DEBUG] send_to_user FAILED for {user_id}: {type(e).__name__}: {e}")
                self.disconnect(user_id, ws)
                return False
        print(f"[STEGO-DEBUG] send_to_user: {user_id} not online")
        return False

    async def broadcast_to_users(self, user_ids: list[str], message: dict):
        for uid in user_ids:
            await self.send_to_user(uid, message)


manager = ConnectionManager()
