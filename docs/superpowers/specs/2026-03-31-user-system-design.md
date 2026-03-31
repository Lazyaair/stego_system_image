# 用户体系与好友关系管理系统 - 设计文档

## 概述

为可证安全图像隐写系统添加完整的用户体系和好友关系管理，实现类似 Signal 的安全通信功能。用户可以与好友进行双方安全隐秘的通信，支持普通文本和隐写图像两种消息模式。

## 设计目标

- **隐私优先**：服务器仅作为桥梁，不存储好友关系和聊天记录
- **安全通信**：支持隐写消息，提供额外的隐蔽性
- **混合通信**：在线实时推送 + 离线消息暂存
- **完整功能**：已读回执、消息撤回、阅后即焚

## 技术栈

| 组件 | 技术 |
|------|------|
| Server | Python + FastAPI + WebSocket |
| Android | Kotlin + Jetpack Compose + Room |
| Web | Vue.js 3 + TypeScript + IndexedDB |
| 认证 | JWT Token |
| 数据库 | SQLite (Server) |
| 消息队列 | 内存实现（保留 Redis 接口） |

---

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端层                                 │
├─────────────────┬─────────────────┬─────────────────────────────┤
│   Android App   │    Web App      │         本地管理             │
│   (Kotlin)      │    (Vue.js)     │  - 联系人列表                │
│   - Room DB     │    - IndexedDB  │  - 聊天记录                  │
│                 │                 │  - 黑名单                    │
│                 │                 │  - 生成分享链接(含公开身份)   │
└────────┬────────┴────────┬────────┴─────────────────────────────┘
         │                 │
         │   HTTPS + WSS   │
         ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Server (FastAPI)                           │
│                      【纯桥梁角色】                               │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │   Auth API   │  │  Stego API   │  │    Message Gateway     │ │
│  │  (JWT认证)   │  │  (隐写服务)   │  │   (WebSocket Hub)      │ │
│  └──────────────┘  └──────────────┘  └────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────────────┤
│  │  Queue Service (临时消息队列，按 user_id 暂存)               │ │
│  └──────────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│              SQLite (仅用户账号 + 邀请码映射)                     │
└─────────────────────────────────────────────────────────────────┘
```

### 服务器职责（极简）

- 用户注册/登录（账号标识）
- 邀请码管理（code → user_id 映射）
- 隐写服务（embed/extract）
- 消息转发（WebSocket）
- 临时队列（离线消息暂存，带超时）

### 服务器不存储

- ❌ 好友关系
- ❌ 聊天记录
- ❌ 联系人信息

---

## 好友添加流程

```
┌─────────┐                                              ┌─────────┐
│  用户 A  │                                              │  用户 B  │
│ (被添加) │                                              │ (添加者) │
└────┬────┘                                              └────┬────┘
     │                                                        │
     │  1. 获取邀请码，构造分享链接                            │
     │     stegoapp://add/aX7kP9mZ                           │
     │  ─────────────────────────────────────────────────────>│
     │                            (通过任意渠道分享：微信/邮件等) │
     │                                                        │
     │                           2. B 通过链接查询 A 信息       │
     │                              本地保存 A 为联系人         │
     │                              (A 此时不知道 B 的存在)     │
     │                                                        │
     │                           3. B 发送第一条消息给 A        │
     │<─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─(消息自动携带 B 的公开身份)─ ─│
     │                                                        │
     │  4. A 收到消息，检测到是陌生人                           │
     │     弹出"好友请求"提示                                  │
     │                                                        │
     │  5. A 选择:                                            │
     │     ├─ 接受 → 保存 B 为联系人，显示消息                  │
     │     ├─ 拒绝 → 忽略此消息                                │
     │     └─ 屏蔽 → B 加入黑名单，后续消息自动丢弃             │
```

### 邀请码机制

- 每个用户同时只有**一个有效邀请码**
- 邀请码**永久有效**，直到用户主动重置
- 重置 = 删除旧 code + 生成新 code
- 旧链接立即失效

---

## 数据模型

### 服务器端（SQLite）

```sql
-- 用户账号表（仅标识作用）
CREATE TABLE users (
    id          TEXT PRIMARY KEY,  -- UUID
    username    TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 邀请码映射表
CREATE TABLE invite_codes (
    code        TEXT PRIMARY KEY,  -- 随机字符串
    user_id     TEXT UNIQUE NOT NULL REFERENCES users(id),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 离线消息临时队列
CREATE TABLE message_queue (
    id          TEXT PRIMARY KEY,  -- UUID
    to_user_id  TEXT NOT NULL REFERENCES users(id),
    payload     TEXT NOT NULL,     -- JSON 序列化的消息内容
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME NOT NULL  -- 过期时间（可配置）
);

CREATE INDEX idx_queue_expires ON message_queue(expires_at);
CREATE INDEX idx_queue_user ON message_queue(to_user_id);
```

### 客户端本地（Android Room / Web IndexedDB）

```
contacts (联系人表)
├── user_id      TEXT PK    -- 对方的用户ID
├── username     TEXT       -- 对方的用户名
├── nickname     TEXT       -- 本地备注名(可选)
├── status       TEXT       -- 'pending'|'accepted'
└── added_at     DATETIME

messages (消息表)
├── id           TEXT PK    -- 消息UUID
├── contact_id   TEXT FK    -- 关联联系人
├── direction    TEXT       -- 'sent'|'received'
├── content      TEXT       -- 消息内容
├── content_type TEXT       -- 'text'|'stego'
├── stego_image  BLOB       -- 隐写图像(如有)
├── status       TEXT       -- 'sending'|'sent'|'delivered'|'read'|'failed'
├── burn_after   INTEGER    -- 阅后即焚秒数(0=不焚)
├── burned       BOOLEAN    -- 是否已焚毁
├── revoked      BOOLEAN    -- 是否已撤回
└── created_at   DATETIME

blacklist (黑名单)
├── user_id      TEXT PK    -- 被屏蔽的用户ID
├── username     TEXT       -- 用户名(记录用)
└── blocked_at   DATETIME

settings (本地设置)
├── key          TEXT PK
└── value        TEXT
```

---

## 消息协议（WebSocket）

### 连接认证

```
ws://server/ws?token=<JWT_TOKEN>
```

### 消息格式

```json
{
  "type": "消息类型",
  "id": "消息UUID",
  "timestamp": 1234567890,
  "payload": { ... }
}
```

### 消息类型

| type | 方向 | 说明 |
|------|------|------|
| `chat` | C→S→C | 聊天消息 |
| `ack` | S→C | 服务器确认收到 |
| `delivered` | C→S→C | 送达回执 |
| `read` | C→S→C | 已读回执 |
| `revoke` | C→S→C | 撤回消息 |
| `typing` | C→S→C | 正在输入(可选) |
| `online` | S→C | 联系人上线通知 |
| `offline` | S→C | 联系人离线通知 |
| `error` | S→C | 错误通知 |

### 聊天消息格式

```json
{
  "type": "chat",
  "id": "msg-uuid-123",
  "timestamp": 1234567890,
  "payload": {
    "from_user_id": "uuid-of-sender",
    "from_username": "alice",
    "to_user_id": "uuid-of-receiver",
    "content": "Hello!",
    "content_type": "text",        // "text" | "stego"
    "stego_image": "base64...",    // 仅 content_type="stego" 时
    "burn_after": 0,               // 阅后即焚秒数，0=不焚
    "is_first_contact": false      // 首次联系标记
  }
}
```

### 消息状态流转

```
sending → sent → delivered → read
              ↘ failed
```

---

## REST API 设计

### API 端点

```
/api/v1/
├── auth/
│   ├── POST /register        # 注册
│   ├── POST /login           # 登录
│   ├── POST /logout          # 登出
│   └── GET  /me              # 获取当前用户信息
│
├── invite/
│   ├── GET  /my-code         # 获取我的邀请码
│   ├── POST /reset           # 重置邀请码
│   └── GET  /{code}          # 通过邀请码查询用户
│
├── user/
│   ├── PUT  /password        # 修改密码
│   └── GET  /{user_id}/public # 获取用户公开信息
│
├── stego/                    # (已有)
│   ├── POST /embed
│   ├── POST /extract
│   ├── POST /capacity
│   └── GET  /models
│
└── ws                        # WebSocket 端点
```

### 认证 API

**POST /api/v1/auth/register**
```json
// Request
{ "username": "alice", "password": "securePassword123" }

// Response 201
{ "user_id": "uuid-xxx", "username": "alice", "token": "jwt-token-xxx" }
```

**POST /api/v1/auth/login**
```json
// Request
{ "username": "alice", "password": "securePassword123" }

// Response 200
{ "user_id": "uuid-xxx", "username": "alice", "token": "jwt-token-xxx" }
```

**GET /api/v1/auth/me**
```json
// Header: Authorization: Bearer <token>
// Response 200
{ "user_id": "uuid-xxx", "username": "alice", "created_at": "2026-03-31T10:00:00Z" }
```

### 邀请码 API

**GET /api/v1/invite/my-code**
```json
// Response 200
{ "code": "aX7kP9mZ", "link": "stegoapp://add/aX7kP9mZ", "created_at": "..." }
```

**POST /api/v1/invite/reset**
```json
// Response 200
{ "code": "bY8lQ0nA", "link": "stegoapp://add/bY8lQ0nA", "created_at": "..." }
```

**GET /api/v1/invite/{code}**
```json
// Response 200
{ "user_id": "uuid-xxx", "username": "alice" }
```

### JWT Token

```json
{
  "sub": "uuid-xxx",        // user_id
  "username": "alice",
  "exp": 1234567890,        // 过期时间
  "iat": 1234567800         // 签发时间
}
```

Token 有效期：7 天（可配置）

---

## 客户端架构

### Android 架构

```
com.stegoapp.app/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── dao/ (ContactDao, MessageDao, BlacklistDao)
│   │   └── entity/ (ContactEntity, MessageEntity, BlacklistEntity)
│   ├── remote/
│   │   ├── api/ (AuthApi, InviteApi, StegoApi)
│   │   └── websocket/ (WebSocketClient, MessageHandler)
│   └── repository/ (AuthRepository, ContactRepository, MessageRepository)
├── domain/model/ (User, Contact, Message, ChatSession)
├── ui/
│   ├── screens/
│   │   ├── auth/ (LoginScreen, RegisterScreen)
│   │   ├── chat/ (ChatListScreen, ChatScreen)
│   │   ├── contact/ (ContactListScreen, AddContactScreen, ContactRequestScreen)
│   │   ├── profile/ (ProfileScreen, SettingsScreen)
│   │   └── stego/ (EmbedScreen, ExtractScreen) -- 已有
│   └── viewmodel/ (AuthViewModel, ChatViewModel, ContactViewModel)
└── service/MessageService.kt
```

### Web 架构

```
src/
├── api/ (auth.ts, invite.ts, stego.ts, websocket.ts)
├── stores/ (auth.ts, chat.ts, contact.ts) -- Pinia
├── db/ (IndexedDB: contacts.ts, messages.ts, blacklist.ts)
├── composables/ (useAuth.ts, useWebSocket.ts, useChat.ts)
├── views/
│   ├── auth/ (LoginView, RegisterView)
│   ├── chat/ (ChatListView, ChatView)
│   ├── contact/ (ContactListView, AddContactView, ContactRequestView)
│   ├── profile/ (ProfileView, SettingsView)
│   └── stego/ (EmbedView, ExtractView) -- 已有
└── components/ (MessageBubble, MessageInput, ContactItem, etc.)
```

### 页面导航

```
未登录: /login, /register
已登录:
  /chats          会话列表 (首页)
  /chat/:id       聊天界面
  /contacts       联系人列表
  /contacts/add   添加联系人
  /contacts/requests  好友请求
  /profile        个人资料
  /settings       设置
  /stego/embed    隐写嵌入 (已有)
  /stego/extract  隐写提取 (已有)

底部导航: [ 消息 ] [ 联系人 ] [ 隐写 ] [ 我的 ]
```

---

## 高级功能

### 已读回执

- **触发时机**：用户打开聊天界面且消息可见时发送
- **批量处理**：同一会话的多条未读消息合并为一个 `read` 回执
- **UI 显示**：发送方看到 ✓✓ 表示已读

### 消息撤回

- **约束**：仅可撤回自己发送的消息，时间限制 2 分钟（可配置）
- **离线处理**：撤回指令入队，对方上线后执行
- **UI 显示**：显示"消息已撤回" / "对方撤回了一条消息"

### 阅后即焚

- **机制**：发送时指定 `burn_after` 秒数
- **触发**：接收方打开消息后开始倒计时
- **销毁**：倒计时结束后本地删除消息内容和隐写图像
- **UI 显示**：特殊标记（火焰图标），显示倒计时，焚毁后显示占位

### 首次联系检测

```
收到消息时:
    if from_user_id in blacklist: 丢弃
    if from_user_id not in contacts:
        显示好友请求通知
        用户选择: 接受/拒绝/屏蔽
    else:
        正常处理
```

---

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| JWT_SECRET | (必填) | JWT 签名密钥 |
| JWT_EXPIRE_DAYS | 7 | Token 有效期 |
| QUEUE_DEFAULT_TTL | 86400 | 消息队列默认超时(秒) |
| REVOKE_TIME_LIMIT | 120 | 撤回时间限制(秒) |
| INVITE_CODE_LENGTH | 8 | 邀请码长度 |

---

## 约束与限制

1. 服务器重启会丢失内存消息队列（后续可迁移 Redis）
2. 本地数据删除后无法恢复（服务器不存储）
3. 单设备登录（多设备需同步本地数据，暂不实现）
4. 隐写消息依赖 Pulsar 算法环境

---

## 实现优先级

1. **P0 - 核心功能**
   - 用户注册/登录
   - WebSocket 连接与消息收发
   - 邀请码机制
   - 联系人本地管理
   - 基础聊天功能

2. **P1 - 重要功能**
   - 消息状态（已读回执）
   - 首次联系检测（好友请求）
   - 普通/隐写消息切换

3. **P2 - 增强功能**
   - 消息撤回
   - 阅后即焚
   - 黑名单管理
