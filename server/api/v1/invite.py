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


@router.get("/user-code/{user_id}")
async def get_user_code(user_id: str, user=Depends(get_current_user)):
    """根据 user_id 获取该用户的 invite code（需要认证）"""
    db = await get_db()
    rows = await db.execute_fetchall(
        "SELECT code FROM invite_codes WHERE user_id = ?", (user_id,)
    )
    if rows:
        code = rows[0][0]
    else:
        # Auto-generate if user has no code
        code = generate_code()
        await db.execute(
            "INSERT INTO invite_codes (code, user_id) VALUES (?, ?)",
            (code, user_id),
        )
        await db.commit()

    # Get username
    user_rows = await db.execute_fetchall(
        "SELECT username FROM users WHERE id = ?", (user_id,)
    )
    if not user_rows:
        raise HTTPException(status_code=404, detail="User not found")

    return {"code": code, "user_id": user_id, "username": user_rows[0][0]}


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
