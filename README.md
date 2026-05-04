# 可证安全图像隐写通信系统

跨平台图像隐写通信应用，基于 **Pulsar** 与 **SparSample** 双算法后端实现可证安全的消息嵌入与提取，支持用户注册登录、好友管理、实时聊天（可选普通/隐写模式）。

## 系统架构

```
┌──────────┐     ┌─────────────────────┐     ┌─────────────┐
│  Android │────▶│                     │◀────│  Web (Vue)  │
│ (Kotlin) │ WS  │       Server        │  WS │             │
└──────────┘     │     (FastAPI)       │     └─────────────┘
                 │                     │
                 │  ┌──────┐ ┌──────┐ │
                 │  │Pulsar│ │SparS.│ │   两套后端并行
                 │  └──────┘ └──────┘ │   chat 默认 SparSample
                 └─────────────────────┘   工具页用户可选
```

| 组件 | 技术栈 | 路径 |
|------|--------|------|
| Server | Python, FastAPI, SQLite, WebSocket | `server/` |
| Android | Kotlin, Jetpack Compose, Room, OkHttp | `android/` |
| Web | Vue 3, TypeScript, Pinia, IndexedDB, Tailwind 3 | `web/` |
| 算法 A | **Pulsar**（diffusers + DDIMScheduler + SageMath） | `pulsar/`（外部） |
| 算法 B | **SparSample**（guided-diffusion + P2-weighting IDDPM） | `server/services/sparsample/` |

## 功能特性

### 隐写服务
- **双算法并行**：Pulsar（HuggingFace 预训练 DDPM，4 个数据集模型）与 SparSample（用户提供的 P2 训练权重）
- **独立工具页**：UI 提供算法 + 模型级联下拉，独立 embed / extract
- **聊天隐写模式**：密钥自动由 `sender_invite_code + receiver_invite_code` 拼接；后端算法由 server `CHAT_DEFAULT_ALGORITHM` 决定，前端零感知
- **容量检查**：API 支持实时容量查询

### 用户与通信
- 用户注册/登录（JWT）；邀请码添加好友
- 单端登录：新设备登录自动踢掉旧设备
- WebSocket 实时消息 + 离线消息队列
- 消息回执：发送中 → 已发送 → 已送达 → 已读
- 好友请求接受/拒绝/屏蔽

### 设计理念
- **服务器为纯桥梁**：账号 + 消息转发 + 临时队列，不存储关系链和聊天内容
- **数据存储在客户端**：Android Room / Web IndexedDB 本地持久化
- **可迁移**：路径、密钥均支持环境变量覆盖，无硬编码绝对路径

---

## 部署环境

### 必备组件

| 组件 | 版本/来源 | 备注 |
|------|-----------|------|
| Python | 3.10 – 3.13 | SageMath 环境内 |
| SageMath | 9.x 或 10.x | Pulsar 的纠错码调用 `sage` 二进制 |
| Conda / Mamba | 任意 | 推荐 Miniforge/Mambaforge |
| Node.js | 18+ | Web 构建 |
| npm | 9+ | Web 包管理 |
| JDK | 17（项目声明 VERSION_11 兼容） | Android 构建 |
| Android Studio | Giraffe+ | Gradle 8 / AGP 8+ |
| CUDA（可选） | 11+ | GPU 加速 diffusion；CPU 也可运行，速度差约 10× |

### 推荐 conda 环境

约定使用名为 `sage` 的 conda 环境（server 启动依赖 SageMath 的 `sage` 命令）：

```bash
# 1. 创建含 SageMath 的环境
mamba create -n sage -c conda-forge sage python=3.12

# 2. 激活并安装 Python 依赖
mamba activate sage
pip install -r server/requirements.txt

# 3. 安装 Pulsar / SparSample 运行时依赖
pip install torch torchvision diffusers tqdm bitarray pypng reedsolo reedmuller scipy
```

> **注意**：Pulsar 通过 subprocess 调用 `sage` 命令做纠错码计算，故 SageMath 必须可执行。验证：`which sage && sage --version`。

---

## 快速开始

### 1. 克隆仓库 + Pulsar 子项目

```bash
git clone https://github.com/Lazyaair/stego_system_image.git bishe
cd bishe

# Pulsar 源码（外部 submodule，独立克隆到项目根下的 pulsar/）
git clone <pulsar-repo-url> pulsar
```

> 若 Pulsar 放在别处，设置环境变量 `PULSAR_PATH=/abs/path/to/pulsar` 即可。

### 2. 准备模型

```
bishe/models/
├── pulsar/                        # Pulsar 模型目录(diffusers 格式)
│   ├── celebahq/                  # 可选:从 HF Hub 提前下载;不放时自动从 Hub 拉
│   │   ├── config.json
│   │   └── diffusion_pytorch_model.safetensors
│   ├── church/
│   ├── bedroom/
│   └── cat/
└── sparsample/                    # SparSample 模型目录(P2-weighting)
    ├── ffhq_p2.pt                 # 必备(chat 默认模型)
    ├── afhqdog_p2.pt              # 可选
    ├── flower_p2.pt               # 可选
    └── cub_p2.pt                  # 可选
```

- Pulsar 4 个 google 模型：若 `./models/pulsar/<id>/` 不存在，`UNet2DModel.from_pretrained` 会自动从 HF Hub 下载到 `~/.cache/huggingface/`
- SparSample P2 权重：用户自行训练/获取 `.pt` 文件，放到 `./models/sparsample/` 下；server 启动时扫描注册，仅识别存在的文件

可通过 `PULSAR_MODELS_DIR` 环境变量覆盖 Pulsar 模型位置。

### 3. 启动 Server

```bash
mamba activate sage
cd server
python main.py          # 默认 http://localhost:8000
```

首次启动会在 `server/.jwt_secret` 生成持久化 JWT secret（0600 权限，已加入 .gitignore）。后续重启不会作废已签发的 token。

### 4. 启动 Web

```bash
cd web
npm install
npm run dev             # 默认 http://localhost:5173
```

### 5. 构建 Android

用 Android Studio 打开 `android/` 目录同步 Gradle，即可运行。模拟器环境下默认 API 地址 `http://10.0.2.2:8000`（映射宿主机 localhost）。真机需在 `android/app/src/main/java/com/stegoapp/app/api/ApiClient.kt` 改为宿主 LAN IP。

### 6. 切换 chat 后端算法（可选）

编辑 `server/api/v1/stego.py`：

```python
CHAT_DEFAULT_ALGORITHM = "sparsample"   # 或 "pulsar"
```

重启 server 即可，前端零发版。

---

## 环境变量（可选覆盖）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `JWT_SECRET` | 自动生成并持久化到 `server/.jwt_secret` | 覆盖后以本变量为准 |
| `JWT_EXPIRE_DAYS` | `7` | Token 有效期 |
| `DATABASE_PATH` | `stego.db`（server 当前目录） | SQLite 文件路径 |
| `QUEUE_DEFAULT_TTL` | `86400`（秒） | 离线消息保留时长 |
| `INVITE_CODE_LENGTH` | `8` | 邀请码长度 |
| `REVOKE_TIME_LIMIT` | `120`（秒） | 消息可撤回时限 |
| `PULSAR_PATH` | `./pulsar`（相对项目根） | Pulsar 源码位置 |
| `PULSAR_MODELS_DIR` | `./models/pulsar` | Pulsar 模型目录 |

---

## API 端点

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/auth/me` | 当前用户信息（前端启动时验证 token 用） |

### 邀请码
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/invite/my-code` | 自己的邀请码 |
| POST | `/api/v1/invite/reset` | 重置邀请码 |
| GET | `/api/v1/invite/lookup/{code}` | 码 → 用户 |
| GET | `/api/v1/invite/user-code/{user_id}` | 用户 ID → 码 |

### 隐写服务
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/stego/algorithms` | **一次性拉取所有算法及其模型**（前端级联下拉） |
| GET | `/api/v1/stego/models?algorithm=` | 指定算法的模型列表（兼容） |
| GET | `/api/v1/stego/max-capacity?key=&algorithm=&model=` | 最大容量（chat 不传 algo/model 走默认） |
| POST | `/api/v1/stego/capacity` | Form: `message`、`key`、可选 `algorithm`、`model` |
| POST | `/api/v1/stego/embed` | Form: `message`、`key`、可选 `algorithm`、`model` |
| POST | `/api/v1/stego/extract` | multipart: `stego_image`、`key`、可选 `algorithm`、`model` |

**参数省略约定**：
- `algorithm` 省略 → 使用 server 的 `CHAT_DEFAULT_ALGORITHM`
- `model` 省略 → 使用该算法的默认模型
- chat 场景前端两者都不传

### WebSocket
| 路径 | 说明 |
|------|------|
| `WS /ws?token=<jwt>` | 实时通信端点 |

**消息类型**：`chat`, `ack`, `delivered`, `read`, `revoke`, `typing`, `kicked`, `auth_failed`

---

## 项目结构

```
bishe/
├── server/                            # FastAPI 后端
│   ├── main.py                        # 应用入口
│   ├── config.py                      # 配置 + 持久化 JWT_SECRET
│   ├── database.py                    # SQLite 初始化
│   ├── dependencies.py                # HTTP/WS 鉴权依赖
│   ├── .jwt_secret                    # 自动生成(gitignored)
│   ├── sage/                          # SageMath 脚本(Pulsar subprocess 使用)
│   ├── api/v1/
│   │   ├── auth.py  invite.py  stego.py  ws.py
│   └── services/
│       ├── auth_service.py
│       ├── pulsar_service.py          # Pulsar 服务封装 + 本地路径 fallback
│       ├── sparsample_service.py      # SparSample 服务封装
│       ├── sparsample/                # SparSample 算法代码(移植自 test/sparsample/)
│       │   ├── core.py
│       │   └── guided_diffusion/
│       ├── queue_service.py
│       └── ws_manager.py
│
├── web/                               # Vue 3 前端
│   └── src/
│       ├── api/                       # axios + WebSocket 客户端
│       ├── db/                        # IndexedDB 封装
│       ├── stores/                    # Pinia store
│       ├── views/                     # 页面组件
│       └── components/
│
├── android/                           # Android 应用
│   └── app/src/main/java/com/stegoapp/app/
│       ├── api/                       # Retrofit + WS + DTO
│       ├── data/local/                # Room + DataStore
│       └── ui/
│           ├── screens/               # Compose 页面
│           ├── viewmodel/
│           └── navigation/
│
├── pulsar/                            # 外部 Pulsar 源码(gitignored)
├── test/sparsample/                   # SparSample 原始 demo(端到端验证用)
├── models/                            # 模型权重目录(gitignored)
│   ├── pulsar/<id>/                   # diffusers 格式
│   └── sparsample/*.pt                # P2-weighting 权重
└── README.md
```

---

## 通信流程

### 好友添加
```
用户A                    Server                   用户B
  │ 输入B的邀请码         │                          │
  │ ─── lookup code ────▶ │                          │
  │ ◀── user info ─────── │                          │
  │ 本地保存B为联系人      │                          │
  │ ─── first_contact ──▶ │ ─── friend request ───▶ │
  │                        │                    接受/拒绝
```

### 消息收发
```
用户A                    Server                   用户B
  │ ─── chat message ───▶ │                          │
  │ ◀── ack ───────────── │                          │
  │                        │ ─── chat message ─────▶ │
  │                        │ ◀── delivered receipt ── │
  │ ◀── delivered ──────── │                          │
  │                        │ ◀── read receipt ─────── │
  │ ◀── read ───────────── │                          │
```

### 单端登录
```
设备A (已登录)            Server                  设备B (新登录)
  │                        │ ◀── WS connect ─────── │
  │ ◀── {"type":"kicked"}  │  发现A已连接，发 kicked  │
  │ 清token、跳登录         │  5s后强制关闭A的旧连接    │
  │ 保留本地聊天记录         │  绑定B为活跃连接          │
```

### Token 失效保护
```
Web 启动                    Server
  │ 有本地 token            │
  │ GET /auth/me ─────────▶ │
  │ ◀── 401(token失效)  ── │
  │ 清 token、跳 /login     │
```

---

## 故障排查

| 现象 | 可能原因 | 解决 |
|------|---------|------|
| Server 启动报 `No module named 'pulsar'` | `pulsar/` 未克隆 | `git clone <pulsar-url> pulsar` 或设 `PULSAR_PATH` |
| Server 启动报 sage 相关错误 | SageMath 未安装或不在 PATH | `mamba activate sage` / 重装 SageMath |
| Pulsar 首次请求很慢 | 网络下载 HF 模型 | 预先 `huggingface-cli download google/ddpm-celebahq-256` |
| SparSample 首次请求 50s | 正常 — 首次 (model,key) 要跑完整 diffusion | 之后 (model,key) 缓存命中仅需 <1s |
| Web 打开直接进页面不提示登录 | 已修复：启动 `GET /auth/me` 自检 | 若问题复现，检查 `/auth/me` 响应 |
| 模型列表为空 | `./models/sparsample/` 无 `.pt` 文件 | 按 "准备模型" 放置权重后重启 |

---

## 许可证

本项目仅用于学术研究目的。Pulsar 算法部分遵循其原始许可证；SparSample / P2-weighting 权重遵循原作者许可证。
