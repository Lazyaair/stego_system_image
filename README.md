# 可证安全图像隐写通信系统

基于 Pulsar 算法的跨平台图像隐写通信应用。支持用户注册登录、好友管理、实时聊天，消息可选择以普通文本或隐写图像方式发送。

## 系统架构

```
┌──────────┐     ┌──────────┐     ┌─────────────────┐
│  Android  │────▶│          │◀────│     Web (Vue)    │
│ (Kotlin)  │ WS  │  Server  │  WS │                  │
└──────────┘     │ (FastAPI) │     └─────────────────┘
                 │          │
                 │  Pulsar  │
                 │ Algorithm │
                 └──────────┘
```

| 组件 | 技术栈 | 路径 |
|------|--------|------|
| Server | Python, FastAPI, SQLite, WebSocket | `server/` |
| Android | Kotlin, Jetpack Compose, Room, OkHttp | `android/` |
| Web | Vue.js 3, TypeScript, Pinia, IndexedDB | `web/` |
| 算法 | Pulsar (Python + SageMath) | `pulsar/` |

## 功能特性

### 隐写服务
- **消息嵌入**: 将秘密消息嵌入到模型生成的图像中
- **消息提取**: 从隐写图像中提取隐藏消息
- **容量检查**: 检测可嵌入的最大消息长度
- 基于 Pulsar 算法，实现信息论意义上的可证安全隐写

### 用户与通信
- **用户注册/登录**: 用户名 + 密码，JWT 认证
- **单端登录**: 新设备登录自动踢掉旧设备，旧端保留聊天记录
- **邀请码**: 每个用户拥有唯一邀请码，可随时重置
- **好友管理**: 通过邀请码添加联系人，支持好友请求的接受/拒绝/屏蔽
- **实时聊天**: WebSocket 实时消息收发，支持离线消息队列
- **消息回执**: 发送中 → 已发送 → 已送达 → 已读 状态追踪
- **隐写模式**: 聊天中可切换普通/隐写发送模式

### 设计理念
- **服务器为纯桥梁**: 仅负责账号管理、消息转发和临时队列，不存储好友关系和聊天记录
- **数据存储在客户端**: 联系人、消息、黑名单等数据完全存储在本地（Android: Room, Web: IndexedDB）
- **端到端信任最小化**: 服务器无法查看聊天内容（未来可扩展端到端加密）

## 快速开始

### 环境要求

- Python 3.10+ (SageMath 环境)
- Node.js 18+
- Android Studio (API 24+)

### 1. 启动 Server

```bash
# 激活 SageMath 环境
mamba activate sage

# 安装依赖
cd server
pip install -r requirements.txt

# 启动服务
python main.py
```

Server 默认运行在 `http://localhost:8000`。

### 2. 启动 Web 前端

```bash
cd web
npm install
npm run dev
```

Web 前端默认运行在 `http://localhost:5173`。

### 3. 构建 Android 应用

用 Android Studio 打开 `android/` 目录，同步 Gradle，连接设备或启动模拟器后运行。

默认 API 地址为 `http://10.0.2.2:8000`（Android 模拟器访问宿主机）。如需修改，编辑 `android/app/src/main/java/com/stegoapp/app/api/ApiClient.kt`。

## API 端点

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 用户注册 |
| POST | `/api/v1/auth/login` | 用户登录 |
| GET | `/api/v1/auth/me` | 获取当前用户信息 |

### 邀请码

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/invite/my-code` | 获取自己的邀请码 |
| POST | `/api/v1/invite/reset` | 重置邀请码 |
| GET | `/api/v1/invite/lookup/{code}` | 通过邀请码查询用户 |

### 隐写服务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/stego/embed` | 嵌入消息到图像 |
| POST | `/api/v1/stego/extract` | 从图像提取消息 |
| POST | `/api/v1/stego/check-capacity` | 检查嵌入容量 |

### WebSocket

| 路径 | 说明 |
|------|------|
| `WS /ws?token=<jwt>` | 实时通信端点 |

**消息类型**: `chat`, `ack`, `delivered`, `read`, `revoke`, `typing`, `kicked`

## 项目结构

```
bishe/
├── server/                     # FastAPI 后端
│   ├── main.py                 # 应用入口
│   ├── config.py               # 配置
│   ├── database.py             # SQLite 数据库
│   ├── dependencies.py         # 依赖注入（JWT 认证）
│   ├── api/v1/
│   │   ├── auth.py             # 认证路由
│   │   ├── invite.py           # 邀请码路由
│   │   ├── stego.py            # 隐写服务路由
│   │   └── ws.py               # WebSocket 端点
│   └── services/
│       ├── auth_service.py     # 认证服务
│       ├── pulsar_service.py   # Pulsar 算法集成
│       ├── queue_service.py    # 离线消息队列
│       └── ws_manager.py       # WebSocket 连接管理
│
├── web/                        # Vue.js 前端
│   └── src/
│       ├── api/                # API 客户端 + WebSocket
│       ├── db/                 # IndexedDB 本地存储
│       ├── stores/             # Pinia 状态管理
│       ├── views/              # 页面组件
│       └── components/         # 通用组件
│
├── android/                    # Android 应用
│   └── app/src/main/java/com/stegoapp/app/
│       ├── api/                # Retrofit API + 拦截器
│       ├── data/
│       │   ├── local/          # Room 数据库 + DataStore
│       │   └── remote/         # OkHttp WebSocket
│       └── ui/
│           ├── screens/        # Compose 页面
│           ├── viewmodel/      # ViewModel 层
│           └── navigation/     # 导航图
│
├── pulsar/                     # Pulsar 隐写算法
│   ├── pulsar.py               # 核心算法
│   ├── prg.py                  # 伪随机生成器
│   └── coding.py               # 编码工具
│
└── docs/                       # 设计文档与计划
```

## 通信流程

### 好友添加
```
用户A                    Server                    用户B
  │  输入B的邀请码         │                          │
  │ ─── lookup code ────▶ │                          │
  │ ◀── user info ─────── │                          │
  │  本地保存B为联系人      │                          │
  │ ─── first_contact ──▶ │ ─── friend request ───▶ │
  │                        │                    接受/拒绝
```

### 消息收发
```
用户A                    Server                    用户B
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
  │ ◀── {"type":"kicked"}  │  发现A已连接，发kicked   │
  │  收到kicked            │                          │
  │  清除token，跳转登录    │  5s后强制关闭A的旧连接     │
  │  保留本地聊天记录       │  绑定B为活跃连接           │
```

## 许可证

本项目仅用于学术研究目的。Pulsar 算法部分遵循其原始许可证。
