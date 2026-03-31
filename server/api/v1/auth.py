from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, field_validator

from dependencies import get_current_user
from services.auth_service import create_user, authenticate_user, get_user_by_id

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
        if not all(c.isalnum() or c == '_' for c in v):
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
    return {"message": "Logged out"}


@router.get("/user/{user_id}/public")
async def get_user_public(user_id: str):
    user = await get_user_by_id(user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    return {"user_id": user["user_id"], "username": user["username"]}
