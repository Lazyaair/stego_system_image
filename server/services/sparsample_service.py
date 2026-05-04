"""SparSample 隐写算法服务封装。

镜像 PulsarService 的 classmethod 接口,对上层暴露相同的:
  get_models / get_capacity / check_capacity / embed / extract / validate_key

内部模型存放于 ./models/sparsample/*.pt,模块导入时扫描注册。
所有 P2 模型共用 FFHQ_P2_CONFIG 架构。
"""
import io
import os
import random
import threading
from pathlib import Path
from typing import Any, Dict, Tuple

import numpy as np
import torch
from PIL import Image

from .sparsample import core
from .sparsample.core import (
    DEFAULT_BLOCK_SIZE,
    DEFAULT_NUM_STEPS,
    DEFAULT_TOTAL_STEPS,
    NUM_PIXELS,
    SPARSAMPLE_MODELS_DIR,
    CapacityError,
    apply_determinism,
    compute_last_step_distribution,
    derive_seeds,
    make_respaced_betas,
    pack_message,
    sample_to_x1,
    setup_determinism_env,
    sparsample_embed,
    sparsample_extract,
    unpack_message,
)

KNOWN_NAMES = {
    "ffhq_p2": "FFHQ (人脸)",
    "afhqdog_p2": "AFHQ Dog (狗)",
    "flower_p2": "Flower (花)",
    "cub_p2": "CUB (鸟)",
}

DEFAULT_MODEL_ID = "ffhq_p2"


def _scan_models() -> Dict[str, dict]:
    """启动时扫描 ./models/sparsample/*.pt,只注册实际存在的文件。"""
    result: Dict[str, dict] = {}
    if not SPARSAMPLE_MODELS_DIR.is_dir():
        return result
    for path in sorted(SPARSAMPLE_MODELS_DIR.glob("*.pt")):
        model_id = path.stem
        result[model_id] = {
            "id": model_id,
            "name": KNOWN_NAMES.get(model_id, model_id),
            "path": str(path),
            "default": model_id == DEFAULT_MODEL_ID,
        }
    # 如果 ffhq_p2 不存在,把第一个找到的标记为 default
    if result and not any(m["default"] for m in result.values()):
        first_id = next(iter(result))
        result[first_id]["default"] = True
    return result


class SparSampleService:
    """SparSample 隐写算法服务,sparsample_embed/extract 路径的包装。"""

    MODELS: Dict[str, dict] = _scan_models()
    _lock = threading.Lock()
    _model_cache: Dict[str, Tuple[Any, str]] = {}
    _state_cache: Dict[str, dict] = {}

    # ------------------------------------------------------------------
    # Public API — mirrors PulsarService
    # ------------------------------------------------------------------
    @classmethod
    def get_models(cls) -> list:
        return [
            {"id": m["id"], "name": m["name"], "default": m.get("default", False)}
            for m in cls.MODELS.values()
        ]

    @classmethod
    def validate_key(cls, key: str) -> dict:
        if not key:
            return {"valid": False, "error": "密钥不能为空"}
        if len(key) > 64:
            return {"valid": False, "error": "密钥长度不能超过 64 字符"}
        try:
            key.encode("utf-8")
        except UnicodeEncodeError:
            return {"valid": False, "error": "密钥包含无效字符"}
        return {"valid": True}

    @classmethod
    def get_capacity(cls, model_id: str, key: bytes) -> int:
        with cls._lock:
            state = cls._get_state(model_id, key)
        return state["max_capacity"]

    @classmethod
    def check_capacity(cls, message: bytes, model_id: str, key: bytes) -> dict:
        max_capacity = cls.get_capacity(model_id, key)
        message_length = len(message)
        valid = message_length <= max_capacity
        result = {
            "valid": valid,
            "message_length": message_length,
            "max_capacity": max_capacity,
        }
        if not valid:
            result["error"] = (
                f"消息长度 ({message_length} bytes) 超出最大容量 ({max_capacity} bytes)"
            )
        return result

    @classmethod
    def embed(cls, message: bytes, model_id: str, key: bytes) -> bytes:
        with cls._lock:
            state = cls._get_state(model_id, key)
            msg_length = len(message)
            if msg_length > state["max_capacity"]:
                raise ValueError(
                    f"消息长度 ({msg_length} bytes) 超出最大容量 ({state['max_capacity']} bytes)"
                )
            msg_str = message.decode("utf-8")
            bits, num_blocks = pack_message(msg_str, DEFAULT_BLOCK_SIZE)
            random.seed(state["sparsample_seed"])
            pixel_flat = sparsample_embed(
                state["mu_flat"],
                state["sigma_flat"],
                bits,
                num_blocks,
                DEFAULT_BLOCK_SIZE,
            )
            arr = pixel_flat.reshape(3, 256, 256).transpose(1, 2, 0).astype(np.uint8)
            buf = io.BytesIO()
            Image.fromarray(arr, mode="RGB").save(buf, format="PNG")
            return buf.getvalue()

    @classmethod
    def extract(cls, image_data: bytes, model_id: str, key: bytes) -> bytes:
        with cls._lock:
            state = cls._get_state(model_id, key)
            img = np.array(
                Image.open(io.BytesIO(image_data)).convert("RGB"), dtype=np.uint8
            )
            if img.shape != (256, 256, 3):
                raise ValueError(f"期望 256x256 RGB PNG,实际 {img.shape}")
            pixel_flat = img.transpose(2, 0, 1).reshape(-1)
            random.seed(state["sparsample_seed"])
            bits = sparsample_extract(
                state["mu_flat"],
                state["sigma_flat"],
                pixel_flat,
                DEFAULT_BLOCK_SIZE,
            )
            msg = unpack_message(bits)
            return msg.encode("utf-8")

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------
    @classmethod
    def _device(cls) -> str:
        return "cuda" if torch.cuda.is_available() else "cpu"

    @classmethod
    def _get_model(cls, model_id: str):
        """Lazy-load weights per model_id;缓存常驻。"""
        if model_id not in cls.MODELS:
            raise ValueError(f"未知模型: {model_id}")
        if model_id not in cls._model_cache:
            path = cls.MODELS[model_id]["path"]
            cls._model_cache[model_id] = core.load_model(path, cls._device())
        return cls._model_cache[model_id]

    @classmethod
    def _get_state(cls, model_id: str, key: bytes) -> dict:
        """首次对给定 (model, key) 运行 diffusion 获取 x_1/mu/sigma 并缓存。"""
        cache_key = f"{model_id}:{key.hex()}"
        if cache_key in cls._state_cache:
            return cls._state_cache[cache_key]

        setup_determinism_env()
        key_str = key.decode("utf-8")
        diffusion_seed, sparsample_seed = derive_seeds(key_str)
        device = cls._device()
        model, v_range = cls._get_model(model_id)

        apply_determinism(diffusion_seed, sparsample_seed)
        betas, timestep_map = make_respaced_betas(
            DEFAULT_NUM_STEPS, DEFAULT_TOTAL_STEPS, device=device
        )
        apply_determinism(diffusion_seed, sparsample_seed)
        x_1 = sample_to_x1(model, betas, timestep_map, device, v_range)
        mu_flat, sigma_flat = compute_last_step_distribution(
            model, x_1, betas, timestep_map, device, v_range
        )

        max_capacity = cls._estimate_capacity(mu_flat, sigma_flat, sparsample_seed)

        state = {
            "x_1": x_1,
            "mu_flat": mu_flat,
            "sigma_flat": sigma_flat,
            "max_capacity": max_capacity,
            "diffusion_seed": diffusion_seed,
            "sparsample_seed": sparsample_seed,
        }
        cls._state_cache[cache_key] = state
        return state

    @classmethod
    def _estimate_capacity(
        cls, mu_flat: np.ndarray, sigma_flat: np.ndarray, sparsample_seed: int
    ) -> int:
        """跑一次 "塞满" 的 dummy embed,捕获 CapacityError 得到 blocks_done 估算上限。"""
        block_size = DEFAULT_BLOCK_SIZE
        dummy_num_blocks = NUM_PIXELS
        dummy_bits = "0" * (dummy_num_blocks * block_size)
        random.seed(sparsample_seed)
        try:
            sparsample_embed(
                mu_flat, sigma_flat, dummy_bits, dummy_num_blocks, block_size
            )
            blocks_done = dummy_num_blocks
        except CapacityError as e:
            blocks_done = e.blocks_done
        max_payload_bits = blocks_done * block_size - 32
        return max(0, max_payload_bits // 8)


DEFAULT_MODEL = DEFAULT_MODEL_ID
