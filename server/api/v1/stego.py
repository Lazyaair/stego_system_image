from fastapi import APIRouter, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import base64

from services.pulsar_service import PulsarService, DEFAULT_MODEL

router = APIRouter(prefix="/api/v1/stego", tags=["Steganography"])


@router.get("/models")
async def get_models():
    """获取可用模型列表"""
    models = PulsarService.get_models()
    return JSONResponse(content={"models": models})


@router.post("/capacity")
async def check_capacity(
    message: str = Form(...),
    key: str = Form(...),
    model: str = Form(DEFAULT_MODEL)
):
    """检查消息容量"""
    # 验证密钥
    key_check = PulsarService.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    try:
        key_bytes = key.encode("utf-8")
        message_bytes = message.encode("utf-8")
        result = PulsarService.check_capacity(message_bytes, model, key_bytes)
        return JSONResponse(content=result)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"容量检查失败: {str(e)}")


@router.post("/embed")
async def embed_message(
    message: str = Form(...),
    key: str = Form(...),
    model: str = Form(DEFAULT_MODEL)
):
    """
    消息嵌入接口
    输入：秘密消息、密钥、模型
    输出：生成的含密图像 (base64)
    """
    # 验证密钥
    key_check = PulsarService.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    if not message:
        raise HTTPException(status_code=400, detail="秘密消息不能为空")

    try:
        key_bytes = key.encode("utf-8")
        message_bytes = message.encode("utf-8")

        # 检查容量
        capacity_check = PulsarService.check_capacity(message_bytes, model, key_bytes)
        if not capacity_check["valid"]:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "error",
                    "error": capacity_check["error"],
                    "max_capacity": capacity_check["max_capacity"]
                }
            )

        # 嵌入消息
        png_data = PulsarService.embed(message_bytes, model, key_bytes)
        stego_base64 = base64.b64encode(png_data).decode("utf-8")

        return JSONResponse(content={
            "status": "success",
            "stego_image": f"data:image/png;base64,{stego_base64}",
            "model": model,
            "message_length": len(message_bytes),
            "is_demo": False
        })
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"嵌入失败: {str(e)}")


@router.post("/extract")
async def extract_message(
    stego_image: UploadFile = File(...),
    key: str = Form(...),
    model: str = Form(DEFAULT_MODEL)
):
    """
    消息提取接口
    输入：含密图像、密钥、模型
    输出：提取的秘密消息
    """
    # 验证密钥
    key_check = PulsarService.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    if not stego_image.content_type or not stego_image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传有效的图像文件")

    try:
        key_bytes = key.encode("utf-8")
        image_data = await stego_image.read()

        # 提取消息
        message_bytes = PulsarService.extract(image_data, model, key_bytes)

        # 尝试解码为字符串，去除尾部空字节
        message_str = message_bytes.rstrip(b'\x00').decode("utf-8", errors="replace")

        return JSONResponse(content={
            "status": "success",
            "secret_message": message_str,
            "model": model,
            "is_demo": False
        })
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"提取失败: {str(e)}")
