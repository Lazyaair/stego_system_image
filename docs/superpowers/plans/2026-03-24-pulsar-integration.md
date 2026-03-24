# Pulsar 算法集成 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Pulsar 隐写算法完整集成到 Server，并更新 Web/Android 前端以支持新的 API

**Architecture:** Server 引入 pulsar 模块作为核心算法，提供模型选择、容量检查、嵌入、提取四个 API；前端移除载体图像选择，添加模型选择和容量验证

**Tech Stack:** Python + FastAPI + Pulsar + SageMath, Vue.js 3, Kotlin + Jetpack Compose

**Spec:** `docs/superpowers/specs/2026-03-24-stego-system-design.md`

---

## File Structure

### Server
```
server/
├── main.py                      # 修改：注册新路由
├── api/v1/stego.py              # 修改：重写所有端点
├── services/
│   └── pulsar_service.py        # 新建：Pulsar 服务封装
└── requirements.txt             # 修改：添加 pulsar 依赖路径
```

### Web
```
web/src/
├── api/stego.ts                 # 修改：更新 API 接口
├── views/EmbedView.vue          # 修改：移除图片选择，添加模型选择
└── views/ExtractView.vue        # 修改：添加模型选择
```

### Android
```
android/app/src/main/java/com/stegoapp/app/
├── api/
│   ├── StegoApi.kt              # 修改：更新 API 接口
│   └── ApiResponse.kt           # 修改：添加新响应类型
└── ui/screens/
    ├── EmbedScreen.kt           # 修改：移除图片选择，添加模型选择
    └── ExtractScreen.kt         # 修改：添加模型选择
```

---

## Chunk 1: Server - Pulsar 服务封装

### Task 1: 创建 Pulsar 服务类

**Files:**
- Create: `server/services/__init__.py`
- Create: `server/services/pulsar_service.py`

- [ ] **Step 1: 创建 services 目录和 __init__.py**

```bash
mkdir -p server/services
touch server/services/__init__.py
```

- [ ] **Step 2: 创建 pulsar_service.py**

```python
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
```

- [ ] **Step 3: Commit**

```bash
git add server/services/
git commit -m "feat(server): add PulsarService for algorithm integration"
```

---

### Task 2: 更新 Server API 端点

**Files:**
- Modify: `server/api/v1/stego.py`

- [ ] **Step 1: 重写 stego.py**

```python
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
```

- [ ] **Step 2: Commit**

```bash
git add server/api/v1/stego.py
git commit -m "feat(server): update API endpoints for Pulsar integration"
```

---

### Task 3: 更新 Server 依赖

**Files:**
- Modify: `server/requirements.txt`

- [ ] **Step 1: 更新 requirements.txt**

在现有内容后添加 Pulsar 所需依赖（这些依赖应在 sage 环境中已安装）：

```text
fastapi>=0.109.0
uvicorn[standard]>=0.27.0
python-multipart>=0.0.6
pillow>=10.3.0
# Pulsar dependencies (should be installed in sage environment)
# torch
# diffusers
# tqdm
# bitarray
# pypng
# reedsolo
# reedmuller
```

- [ ] **Step 2: Commit**

```bash
git add server/requirements.txt
git commit -m "docs(server): add Pulsar dependency notes"
```

---

## Chunk 2: Web 前端更新

### Task 4: 更新 Web API 服务层

**Files:**
- Modify: `web/src/api/stego.ts`

- [ ] **Step 1: 更新 stego.ts**

```typescript
import apiClient from './index'

export interface Model {
  id: string
  name: string
  default: boolean
}

export interface ModelsResponse {
  models: Model[]
}

export interface CapacityResponse {
  valid: boolean
  message_length: number
  max_capacity: number
  error?: string
}

export interface EmbedResponse {
  status: string
  stego_image?: string
  model?: string
  message_length?: number
  error?: string
  max_capacity?: number
  is_demo: boolean
}

export interface ExtractResponse {
  status: string
  secret_message?: string
  model?: string
  error?: string
  is_demo: boolean
}

export const stegoApi = {
  async getModels(): Promise<ModelsResponse> {
    const response = await apiClient.get<ModelsResponse>('/api/v1/stego/models')
    return response.data
  },

  async checkCapacity(message: string, key: string, model: string): Promise<CapacityResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<CapacityResponse>('/api/v1/stego/capacity', formData)
    return response.data
  },

  async embed(message: string, key: string, model: string): Promise<EmbedResponse> {
    const formData = new FormData()
    formData.append('message', message)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<EmbedResponse>('/api/v1/stego/embed', formData)
    return response.data
  },

  async extract(stegoImage: File, key: string, model: string): Promise<ExtractResponse> {
    const formData = new FormData()
    formData.append('stego_image', stegoImage)
    formData.append('key', key)
    formData.append('model', model)

    const response = await apiClient.post<ExtractResponse>('/api/v1/stego/extract', formData)
    return response.data
  },

  validateKey(key: string): { valid: boolean; error?: string } {
    if (!key) {
      return { valid: false, error: '密钥不能为空' }
    }
    if (key.length > 64) {
      return { valid: false, error: '密钥长度不能超过 64 字符' }
    }
    return { valid: true }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add web/src/api/stego.ts
git commit -m "feat(web): update API service for Pulsar integration"
```

---

### Task 5: 更新 Web 嵌入页面

**Files:**
- Modify: `web/src/views/EmbedView.vue`

- [ ] **Step 1: 重写 EmbedView.vue**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { stegoApi, type Model } from '../api/stego'

const models = ref<Model[]>([])
const selectedModel = ref('')
const message = ref('')
const key = ref('')
const stegoImage = ref<string>('')
const isLoading = ref(false)
const isCheckingCapacity = ref(false)
const capacityInfo = ref<{ valid: boolean; max_capacity: number; error?: string } | null>(null)
const error = ref('')

onMounted(async () => {
  try {
    const res = await stegoApi.getModels()
    models.value = res.models
    const defaultModel = res.models.find(m => m.default)
    if (defaultModel) {
      selectedModel.value = defaultModel.id
    }
  } catch (e: any) {
    error.value = '获取模型列表失败'
  }
})

async function checkCapacity() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!message.value) {
    error.value = '请输入秘密消息'
    return
  }

  isCheckingCapacity.value = true
  error.value = ''

  try {
    const result = await stegoApi.checkCapacity(message.value, key.value, selectedModel.value)
    capacityInfo.value = result
    if (!result.valid) {
      error.value = result.error || '消息超出容量'
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '容量检查失败'
  } finally {
    isCheckingCapacity.value = false
  }
}

async function handleEmbed() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!message.value) {
    error.value = '请输入秘密消息'
    return
  }

  isLoading.value = true
  error.value = ''
  stegoImage.value = ''

  try {
    const result = await stegoApi.embed(message.value, key.value, selectedModel.value)
    if (result.status === 'success' && result.stego_image) {
      stegoImage.value = result.stego_image
    } else {
      error.value = result.error || '嵌入失败'
      if (result.max_capacity) {
        error.value += ` (最大容量: ${result.max_capacity} bytes)`
      }
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '请求失败'
  } finally {
    isLoading.value = false
  }
}

function downloadImage() {
  if (!stegoImage.value) return

  const link = document.createElement('a')
  link.href = stegoImage.value
  link.download = 'stego_image.png'
  link.click()
}
</script>

<template>
  <div class="embed-view">
    <h1>消息嵌入</h1>

    <div class="form-group">
      <label>选择模型</label>
      <select v-model="selectedModel">
        <option v-for="m in models" :key="m.id" :value="m.id">
          {{ m.name }}
        </option>
      </select>
    </div>

    <div class="form-group">
      <label>秘密消息</label>
      <textarea
        v-model="message"
        rows="4"
        placeholder="输入要隐藏的秘密消息..."
        @blur="checkCapacity"
      ></textarea>
      <div v-if="capacityInfo" class="capacity-info" :class="{ invalid: !capacityInfo.valid }">
        消息长度: {{ message.length }} bytes / 最大容量: {{ capacityInfo.max_capacity }} bytes
      </div>
    </div>

    <div class="form-group">
      <label>密钥 (1-64 字符)</label>
      <input
        type="password"
        v-model="key"
        placeholder="输入密钥，用于加密和解密"
        maxlength="64"
      />
    </div>

    <div class="actions">
      <button
        @click="checkCapacity"
        :disabled="isCheckingCapacity || !message || !key"
        class="btn-secondary"
      >
        {{ isCheckingCapacity ? '检查中...' : '检查容量' }}
      </button>
      <button
        @click="handleEmbed"
        :disabled="isLoading || !message || !key"
        class="btn-primary"
      >
        {{ isLoading ? '生成中...' : '生成含密图像' }}
      </button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="stegoImage" class="result">
      <h3>生成结果：</h3>
      <img :src="stegoImage" class="preview" />
      <button @click="downloadImage" class="btn-download">下载图像</button>
    </div>
  </div>
</template>

<style scoped>
.embed-view { max-width: 600px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input,
.form-group textarea,
.form-group select {
  width: 100%;
  padding: 10px;
  box-sizing: border-box;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}
.capacity-info {
  margin-top: 4px;
  font-size: 12px;
  color: #666;
}
.capacity-info.invalid {
  color: #e74c3c;
}
.preview {
  max-width: 100%;
  max-height: 300px;
  margin-top: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
.btn-primary {
  padding: 12px 24px;
  background: #42b883;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.btn-primary:disabled { background: #ccc; }
.btn-secondary {
  padding: 12px 24px;
  background: #fff;
  color: #333;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.btn-secondary:disabled { color: #ccc; }
.btn-download {
  margin-top: 10px;
  padding: 8px 16px;
  background: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}
.error { color: #e74c3c; margin-top: 10px; }
.result { margin-top: 20px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add web/src/views/EmbedView.vue
git commit -m "feat(web): update EmbedView for Pulsar integration"
```

---

### Task 6: 更新 Web 提取页面

**Files:**
- Modify: `web/src/views/ExtractView.vue`

- [ ] **Step 1: 重写 ExtractView.vue**

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { stegoApi, type Model } from '../api/stego'

const models = ref<Model[]>([])
const selectedModel = ref('')
const stegoImageFile = ref<File | null>(null)
const stegoPreview = ref<string>('')
const key = ref('')
const extractedMessage = ref('')
const isLoading = ref(false)
const error = ref('')

onMounted(async () => {
  try {
    const res = await stegoApi.getModels()
    models.value = res.models
    const defaultModel = res.models.find(m => m.default)
    if (defaultModel) {
      selectedModel.value = defaultModel.id
    }
  } catch (e: any) {
    error.value = '获取模型列表失败'
  }
})

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    stegoImageFile.value = file
    stegoPreview.value = URL.createObjectURL(file)
  }
}

async function handleExtract() {
  const keyCheck = stegoApi.validateKey(key.value)
  if (!keyCheck.valid) {
    error.value = keyCheck.error || ''
    return
  }

  if (!stegoImageFile.value) {
    error.value = '请选择含密图像'
    return
  }

  isLoading.value = true
  error.value = ''
  extractedMessage.value = ''

  try {
    const result = await stegoApi.extract(stegoImageFile.value, key.value, selectedModel.value)
    if (result.status === 'success' && result.secret_message) {
      extractedMessage.value = result.secret_message
    } else {
      error.value = result.error || '提取失败'
    }
  } catch (e: any) {
    error.value = e.response?.data?.detail || '请求失败'
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="extract-view">
    <h1>消息提取</h1>

    <div class="form-group">
      <label>选择模型 (需与嵌入时使用的模型相同)</label>
      <select v-model="selectedModel">
        <option v-for="m in models" :key="m.id" :value="m.id">
          {{ m.name }}
        </option>
      </select>
    </div>

    <div class="form-group">
      <label>选择含密图像</label>
      <input type="file" accept="image/png" @change="onFileChange" />
      <img v-if="stegoPreview" :src="stegoPreview" class="preview" />
    </div>

    <div class="form-group">
      <label>密钥 (需与嵌入时使用的密钥相同)</label>
      <input
        type="password"
        v-model="key"
        placeholder="输入密钥"
        maxlength="64"
      />
    </div>

    <button
      @click="handleExtract"
      :disabled="isLoading || !stegoImageFile || !key"
      class="btn-primary"
    >
      {{ isLoading ? '提取中...' : '提取消息' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="extractedMessage" class="result">
      <h3>提取结果：</h3>
      <div class="message-box">{{ extractedMessage }}</div>
    </div>
  </div>
</template>

<style scoped>
.extract-view { max-width: 600px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
.form-group input,
.form-group select {
  width: 100%;
  padding: 10px;
  box-sizing: border-box;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}
.preview {
  max-width: 100%;
  max-height: 300px;
  margin-top: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}
.btn-primary {
  padding: 12px 24px;
  background: #42b883;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.btn-primary:disabled { background: #ccc; }
.error { color: #e74c3c; margin-top: 10px; }
.result { margin-top: 20px; }
.message-box {
  padding: 16px;
  background: #f8f9fa;
  border-radius: 4px;
  border: 1px solid #e9ecef;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: monospace;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add web/src/views/ExtractView.vue
git commit -m "feat(web): update ExtractView for Pulsar integration"
```

---

## Chunk 3: Android 前端更新

### Task 7: 更新 Android API 层

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/api/ApiResponse.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/api/StegoApi.kt`

- [ ] **Step 1: 更新 ApiResponse.kt**

```kotlin
package com.stegoapp.app.api

data class Model(
    val id: String,
    val name: String,
    val default: Boolean
)

data class ModelsResponse(
    val models: List<Model>
)

data class CapacityResponse(
    val valid: Boolean,
    val message_length: Int,
    val max_capacity: Int,
    val error: String?
)

data class EmbedResponse(
    val status: String,
    val stego_image: String?,
    val model: String?,
    val message_length: Int?,
    val error: String?,
    val max_capacity: Int?,
    val is_demo: Boolean
)

data class ExtractResponse(
    val status: String,
    val secret_message: String?,
    val model: String?,
    val error: String?,
    val is_demo: Boolean
)
```

- [ ] **Step 2: 更新 StegoApi.kt**

```kotlin
package com.stegoapp.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface StegoApi {
    @GET("/api/v1/stego/models")
    suspend fun getModels(): Response<ModelsResponse>

    @FormUrlEncoded
    @POST("/api/v1/stego/capacity")
    suspend fun checkCapacity(
        @Field("message") message: String,
        @Field("key") key: String,
        @Field("model") model: String
    ): Response<CapacityResponse>

    @FormUrlEncoded
    @POST("/api/v1/stego/embed")
    suspend fun embed(
        @Field("message") message: String,
        @Field("key") key: String,
        @Field("model") model: String
    ): Response<EmbedResponse>

    @Multipart
    @POST("/api/v1/stego/extract")
    suspend fun extract(
        @Part stego_image: MultipartBody.Part,
        @Part("key") key: RequestBody,
        @Part("model") model: RequestBody
    ): Response<ExtractResponse>
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/api/
git commit -m "feat(android): update API layer for Pulsar integration"
```

---

### Task 8: 更新 Android 嵌入页面

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/EmbedScreen.kt`

- [ ] **Step 1: 重写 EmbedScreen.kt**

```kotlin
package com.stegoapp.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbedScreen() {
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("celebahq") }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCheckingCapacity by remember { mutableStateOf(false) }
    var stegoImageBase64 by remember { mutableStateOf<String?>(null) }
    var capacityInfo by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 加载模型列表
    LaunchedEffect(Unit) {
        try {
            val response = withContext(Dispatchers.IO) {
                ApiClient.stegoApi.getModels()
            }
            if (response.isSuccessful) {
                models = response.body()?.models ?: emptyList()
                val defaultModel = models.find { it.default }
                if (defaultModel != null) {
                    selectedModel = defaultModel.id
                }
            }
        } catch (e: Exception) {
            errorMessage = "获取模型列表失败"
        }
    }

    fun validateKey(): Boolean {
        return when {
            key.isEmpty() -> {
                errorMessage = "密钥不能为空"
                false
            }
            key.length > 64 -> {
                errorMessage = "密钥长度不能超过 64 字符"
                false
            }
            else -> true
        }
    }

    fun checkCapacity() {
        if (!validateKey() || message.isEmpty()) return

        scope.launch {
            isCheckingCapacity = true
            errorMessage = null

            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.checkCapacity(message, key, selectedModel)
                }

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        capacityInfo = "消息长度: ${body.message_length} bytes / 最大容量: ${body.max_capacity} bytes"
                        if (!body.valid) {
                            errorMessage = body.error ?: "消息超出容量"
                        }
                    }
                } else {
                    errorMessage = "容量检查失败"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "网络错误"
            } finally {
                isCheckingCapacity = false
            }
        }
    }

    fun handleEmbed() {
        if (!validateKey() || message.isEmpty()) {
            if (message.isEmpty()) errorMessage = "请输入秘密消息"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            stegoImageBase64 = null

            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.embed(message, key, selectedModel)
                }

                if (response.isSuccessful && response.body()?.status == "success") {
                    stegoImageBase64 = response.body()?.stego_image
                } else {
                    val body = response.body()
                    errorMessage = body?.error ?: "嵌入失败"
                    if (body?.max_capacity != null) {
                        errorMessage += " (最大容量: ${body.max_capacity} bytes)"
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "网络错误"
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "消息嵌入",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 模型选择
        Text("选择模型", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = models.find { it.id == selectedModel }?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            selectedModel = model.id
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 秘密消息输入
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("秘密消息") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            placeholder = { Text("输入要隐藏的秘密消息...") }
        )

        capacityInfo?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 密钥输入
        OutlinedTextField(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            label = { Text("密钥 (1-64 字符)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("输入密钥，用于加密和解密") }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { checkCapacity() },
                enabled = !isCheckingCapacity && message.isNotEmpty() && key.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isCheckingCapacity) "检查中..." else "检查容量")
            }

            Button(
                onClick = { handleEmbed() },
                enabled = !isLoading && message.isNotEmpty() && key.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isLoading) "生成中..." else "生成含密图像")
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = Color.Red)
        }

        stegoImageBase64?.let { base64 ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "生成结果：",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            val imageData = base64.substringAfter("base64,")
            val bytes = Base64.decode(imageData, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "含密图像",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(256.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = "提示：长按图像可保存",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/EmbedScreen.kt
git commit -m "feat(android): update EmbedScreen for Pulsar integration"
```

---

### Task 9: 更新 Android 提取页面

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/ExtractScreen.kt`

- [ ] **Step 1: 重写 ExtractScreen.kt**

```kotlin
package com.stegoapp.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("celebahq") }
    var expanded by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var key by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var extractedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            val inputStream = context.contentResolver.openInputStream(it)
            val file = File(context.cacheDir, "stego_image.png")
            FileOutputStream(file).use { output ->
                inputStream?.copyTo(output)
            }
            selectedImageFile = file
        }
    }

    // 加载模型列表
    LaunchedEffect(Unit) {
        try {
            val response = withContext(Dispatchers.IO) {
                ApiClient.stegoApi.getModels()
            }
            if (response.isSuccessful) {
                models = response.body()?.models ?: emptyList()
                val defaultModel = models.find { it.default }
                if (defaultModel != null) {
                    selectedModel = defaultModel.id
                }
            }
        } catch (e: Exception) {
            errorMessage = "获取模型列表失败"
        }
    }

    fun validateKey(): Boolean {
        return when {
            key.isEmpty() -> {
                errorMessage = "密钥不能为空"
                false
            }
            key.length > 64 -> {
                errorMessage = "密钥长度不能超过 64 字符"
                false
            }
            else -> true
        }
    }

    fun handleExtract() {
        if (!validateKey()) return

        val file = selectedImageFile
        if (file == null) {
            errorMessage = "请先选择含密图像"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            extractedMessage = null

            try {
                val imagePart = MultipartBody.Part.createFormData(
                    "stego_image",
                    file.name,
                    file.asRequestBody("image/png".toMediaTypeOrNull())
                )
                val keyPart = key.toRequestBody("text/plain".toMediaTypeOrNull())
                val modelPart = selectedModel.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = withContext(Dispatchers.IO) {
                    ApiClient.stegoApi.extract(imagePart, keyPart, modelPart)
                }

                if (response.isSuccessful && response.body()?.status == "success") {
                    extractedMessage = response.body()?.secret_message
                } else {
                    errorMessage = response.body()?.error ?: "提取失败"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "网络错误"
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "消息提取",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 模型选择
        Text(
            text = "选择模型 (需与嵌入时使用的模型相同)",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = models.find { it.id == selectedModel }?.name ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            selectedModel = model.id
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 图片选择
        Button(
            onClick = { imagePicker.launch("image/png") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择含密图像")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedImageUri != null) {
            AsyncImage(
                model = selectedImageUri,
                contentDescription = "含密图像",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 密钥输入
        OutlinedTextField(
            value = key,
            onValueChange = { if (it.length <= 64) key = it },
            label = { Text("密钥 (需与嵌入时使用的密钥相同)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 提取按钮
        Button(
            onClick = { handleExtract() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && selectedImageFile != null && key.isNotEmpty()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isLoading) "提取中..." else "提取消息")
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = Color.Red)
        }

        extractedMessage?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "提取结果：",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/stegoapp/app/ui/screens/ExtractScreen.kt
git commit -m "feat(android): update ExtractScreen for Pulsar integration"
```

---

## 完成检查

- [ ] Server 在 sage 环境中可正常启动
- [ ] GET /api/v1/stego/models 返回模型列表
- [ ] POST /api/v1/stego/capacity 正确检查容量
- [ ] POST /api/v1/stego/embed 生成含密图像
- [ ] POST /api/v1/stego/extract 提取消息
- [ ] Web 嵌入页面可选择模型并生成图像
- [ ] Web 提取页面可选择模型并提取消息
- [ ] Android 嵌入页面可选择模型并生成图像
- [ ] Android 提取页面可选择模型并提取消息
- [ ] 所有改动已提交到 git
