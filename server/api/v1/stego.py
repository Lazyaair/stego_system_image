from fastapi import APIRouter, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import base64
import os

router = APIRouter(prefix="/api/v1/stego", tags=["Steganography"])

DEMO_STEGO_PATH = os.path.join(os.path.dirname(__file__), "../../demo_assets/stego_demo.png")

@router.post("/embed")
async def embed_message(
    cover_image: UploadFile = File(...),
    secret_message: str = Form(...),
    key: str = Form(...),
    embed_rate: float = Form(0.5)
):
    """
    消息嵌入接口（演示阶段返回占位图）
    """
    if not cover_image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传有效的图像文件")

    if len(secret_message) == 0:
        raise HTTPException(status_code=400, detail="秘密消息不能为空")

    if not os.path.exists(DEMO_STEGO_PATH):
        raise HTTPException(status_code=500, detail="演示资源未找到")

    with open(DEMO_STEGO_PATH, "rb") as f:
        stego_base64 = base64.b64encode(f.read()).decode("utf-8")

    return JSONResponse(content={
        "status": "success",
        "stego_image": f"data:image/png;base64,{stego_base64}",
        "is_demo": True
    })
