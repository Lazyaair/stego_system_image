from typing import Optional, Tuple, Type

from fastapi import APIRouter, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
import base64

from services.pulsar_service import PulsarService
from services.sparsample_service import SparSampleService

router = APIRouter(prefix="/api/v1/stego", tags=["Steganography"])


# ---------------------------------------------------------------------------
# 算法注册表
# ---------------------------------------------------------------------------
# 修改 CHAT_DEFAULT_ALGORITHM 可一行切换 chat 默认后端,前端无需发版。
CHAT_DEFAULT_ALGORITHM = "sparsample"

ALGORITHMS = {
    "pulsar": {
        "id": "pulsar",
        "name": "Pulsar (DDIM)",
        "service": PulsarService,
    },
    "sparsample": {
        "id": "sparsample",
        "name": "SparSample (IDDPM P2)",
        "service": SparSampleService,
    },
}


def _resolve(
    algorithm: Optional[str], model: Optional[str]
) -> Tuple[str, str, Type]:
    """解析 (algorithm, model) 到 (algorithm_id, model_id, service_cls)。

    algorithm 为空 → CHAT_DEFAULT_ALGORITHM;
    model 为空 → 该算法的 default 模型。
    未知 algorithm / model 抛 ValueError(会被上层转 400)。
    """
    algo_id = algorithm or CHAT_DEFAULT_ALGORITHM
    if algo_id not in ALGORITHMS:
        raise ValueError(f"未知算法: {algo_id}")
    service = ALGORITHMS[algo_id]["service"]
    models = service.get_models()
    if not models:
        raise ValueError(f"算法 {algo_id} 没有可用的模型")
    if model:
        if not any(m["id"] == model for m in models):
            raise ValueError(f"算法 {algo_id} 未注册模型: {model}")
        model_id = model
    else:
        model_id = next((m["id"] for m in models if m.get("default")), models[0]["id"])
    return algo_id, model_id, service


# ---------------------------------------------------------------------------
# 元信息端点
# ---------------------------------------------------------------------------
@router.get("/algorithms")
async def get_algorithms():
    """一次性返回所有算法及其模型列表。前端构建级联下拉。"""
    result = []
    for algo_id, meta in ALGORITHMS.items():
        models = meta["service"].get_models()
        result.append({
            "id": algo_id,
            "name": meta["name"],
            "default": algo_id == CHAT_DEFAULT_ALGORITHM,
            "chat_default": algo_id == CHAT_DEFAULT_ALGORITHM,
            "models": models,
        })
    return JSONResponse(content={"algorithms": result})


@router.get("/models")
async def get_models(algorithm: Optional[str] = None):
    """获取指定算法的模型列表。不指定算法时返回 chat 默认算法的模型(向后兼容)。"""
    algo_id = algorithm or CHAT_DEFAULT_ALGORITHM
    if algo_id not in ALGORITHMS:
        raise HTTPException(status_code=400, detail=f"未知算法: {algo_id}")
    models = ALGORITHMS[algo_id]["service"].get_models()
    return JSONResponse(content={"models": models})


# ---------------------------------------------------------------------------
# 业务端点
# ---------------------------------------------------------------------------
@router.get("/max-capacity")
async def get_max_capacity(
    key: str,
    model: Optional[str] = None,
    algorithm: Optional[str] = None,
):
    """获取最大消息容量(无需提供消息内容)。"""
    try:
        algo_id, model_id, service = _resolve(algorithm, model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    key_check = service.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    try:
        key_bytes = key.encode("utf-8")
        max_capacity = await run_in_threadpool(service.get_capacity, model_id, key_bytes)
        return JSONResponse(content={"max_capacity": max_capacity})
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"容量查询失败: {str(e)}")


@router.post("/capacity")
async def check_capacity(
    message: str = Form(...),
    key: str = Form(...),
    model: Optional[str] = Form(None),
    algorithm: Optional[str] = Form(None),
):
    """检查消息容量。"""
    try:
        algo_id, model_id, service = _resolve(algorithm, model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    key_check = service.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    try:
        key_bytes = key.encode("utf-8")
        message_bytes = message.encode("utf-8")
        result = await run_in_threadpool(
            service.check_capacity, message_bytes, model_id, key_bytes
        )
        return JSONResponse(content=result)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"容量检查失败: {str(e)}")


@router.post("/embed")
async def embed_message(
    message: str = Form(...),
    key: str = Form(...),
    model: Optional[str] = Form(None),
    algorithm: Optional[str] = Form(None),
):
    """消息嵌入接口。"""
    try:
        algo_id, model_id, service = _resolve(algorithm, model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    key_check = service.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    if not message:
        raise HTTPException(status_code=400, detail="秘密消息不能为空")

    try:
        key_bytes = key.encode("utf-8")
        message_bytes = message.encode("utf-8")

        capacity_check = await run_in_threadpool(
            service.check_capacity, message_bytes, model_id, key_bytes
        )
        if not capacity_check["valid"]:
            return JSONResponse(
                status_code=400,
                content={
                    "status": "error",
                    "error": capacity_check["error"],
                    "max_capacity": capacity_check["max_capacity"],
                },
            )

        png_data = await run_in_threadpool(
            service.embed, message_bytes, model_id, key_bytes
        )
        stego_base64 = base64.b64encode(png_data).decode("utf-8")

        return JSONResponse(content={
            "status": "success",
            "stego_image": f"data:image/png;base64,{stego_base64}",
            "algorithm": algo_id,
            "model": model_id,
            "message_length": len(message_bytes),
            "is_demo": False,
        })
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"嵌入失败: {str(e)}")


@router.post("/extract")
async def extract_message(
    stego_image: UploadFile = File(...),
    key: str = Form(...),
    model: Optional[str] = Form(None),
    algorithm: Optional[str] = Form(None),
):
    """消息提取接口。"""
    try:
        algo_id, model_id, service = _resolve(algorithm, model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    key_check = service.validate_key(key)
    if not key_check["valid"]:
        raise HTTPException(status_code=400, detail=key_check["error"])

    if not stego_image.content_type or not stego_image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传有效的图像文件")

    try:
        key_bytes = key.encode("utf-8")
        image_data = await stego_image.read()

        message_bytes = await run_in_threadpool(
            service.extract, image_data, model_id, key_bytes
        )
        message_str = message_bytes.rstrip(b"\x00").decode("utf-8", errors="replace")

        return JSONResponse(content={
            "status": "success",
            "secret_message": message_str,
            "algorithm": algo_id,
            "model": model_id,
            "is_demo": False,
        })
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"提取失败: {str(e)}")
