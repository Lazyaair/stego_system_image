import sys
import os

# 添加 pulsar 项目路径
PULSAR_PATH = os.path.expanduser("~/bishe/pulsar")
if PULSAR_PATH not in sys.path:
    sys.path.insert(0, PULSAR_PATH)

import pulsar as pulsar_module
from typing import Optional, Dict, Any
import threading

# 模型配置
MODELS = {
    "celebahq": {
        "id": "celebahq",
        "name": "CelebA-HQ (人脸)",
        "repo": "google/ddpm-celebahq-256",
        "default": True
    },
    "church": {
        "id": "church",
        "name": "Church (教堂)",
        "repo": "google/ddpm-church-256",
        "default": False
    },
    "bedroom": {
        "id": "bedroom",
        "name": "Bedroom (卧室)",
        "repo": "google/ddpm-bedroom-256",
        "default": False
    },
    "cat": {
        "id": "cat",
        "name": "Cat (猫)",
        "repo": "google/ddpm-cat-256",
        "default": False
    }
}

DEFAULT_MODEL = "celebahq"


class PulsarService:
    """Pulsar 隐写算法服务封装"""

    _instances: Dict[str, Any] = {}
    _lock = threading.Lock()

    @classmethod
    def get_models(cls) -> list:
        """获取可用模型列表"""
        return [
            {
                "id": m["id"],
                "name": m["name"],
                "default": m["default"]
            }
            for m in MODELS.values()
        ]

    @classmethod
    def get_instance(cls, model_id: str, key: bytes) -> Any:
        """
        获取或创建 Pulsar 实例
        注意：每个 (model, key) 组合需要独立的实例
        """
        if model_id not in MODELS:
            raise ValueError(f"未知模型: {model_id}")

        cache_key = f"{model_id}:{key.hex()}"

        with cls._lock:
            if cache_key not in cls._instances:
                repo = MODELS[model_id]["repo"]
                instance = pulsar_module.Pulsar(
                    seed=key,
                    repo=repo,
                    benchmarks=False
                )
                # 预估区域（计算容量）
                instance.estimate_regions(n_hist_bins=100, n_to_gen=1, end_to_end=True)
                cls._instances[cache_key] = instance

            return cls._instances[cache_key]

    @classmethod
    def get_capacity(cls, model_id: str, key: bytes) -> int:
        """获取指定模型和密钥的最大消息容量"""
        instance = cls.get_instance(model_id, key)
        return instance.max_message_len

    @classmethod
    def check_capacity(cls, message: bytes, model_id: str, key: bytes) -> dict:
        """检查消息是否超出容量"""
        max_capacity = cls.get_capacity(model_id, key)
        message_length = len(message)
        valid = message_length <= max_capacity

        result = {
            "valid": valid,
            "message_length": message_length,
            "max_capacity": max_capacity
        }

        if not valid:
            result["error"] = f"消息长度 ({message_length} bytes) 超出最大容量 ({max_capacity} bytes)"

        return result

    @classmethod
    def embed(cls, message: bytes, model_id: str, key: bytes) -> bytes:
        """
        嵌入消息，返回含密图像的 PNG 字节数据
        """
        import tempfile

        instance = cls.get_instance(model_id, key)

        # 检查容量
        check = cls.check_capacity(message, model_id, key)
        if not check["valid"]:
            raise ValueError(check["error"])

        # 生成含密图像
        results = instance.generate_with_regions(message)
        last = instance.scheduler.num_inference_steps - 1
        hidden_sample = results["samples"][last]["hidden"]

        # 保存到临时文件并读取
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            temp_path = f.name

        try:
            instance.save_sample(hidden_sample, temp_path)
            with open(temp_path, "rb") as f:
                png_data = f.read()
        finally:
            os.unlink(temp_path)

        return png_data

    @classmethod
    def extract(cls, image_data: bytes, model_id: str, key: bytes) -> bytes:
        """
        从含密图像提取消息
        """
        import tempfile

        instance = cls.get_instance(model_id, key)

        # 保存图像到临时文件
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            f.write(image_data)
            temp_path = f.name

        try:
            # 加载图像
            hidden_sample = instance.load_sample(temp_path)
            # 提取消息
            message = instance.reveal_with_regions(hidden_sample)
        finally:
            os.unlink(temp_path)

        return message

    @classmethod
    def validate_key(cls, key: str) -> dict:
        """验证密钥格式"""
        if not key:
            return {"valid": False, "error": "密钥不能为空"}
        if len(key) > 64:
            return {"valid": False, "error": "密钥长度不能超过 64 字符"}

        try:
            key.encode("utf-8")
        except UnicodeEncodeError:
            return {"valid": False, "error": "密钥包含无效字符"}

        return {"valid": True}
