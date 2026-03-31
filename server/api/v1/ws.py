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
