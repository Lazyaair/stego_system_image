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
