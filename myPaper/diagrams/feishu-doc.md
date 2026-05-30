# 毕业论文插图 — 可证安全图像隐写系统

## 第2章 相关技术与理论基础

### 图1 扩散模型前向/逆向过程示意图

> 横排展示加噪过程 x₀→x₁→…→xₜ 和去噪过程 xₜ→xₜ₋₁→…→x₀，标准 DDPM 图示风格

<whiteboard type="blank"></whiteboard>

### 图2 Pulsar 嵌入流程图

> key → 种子初始化 → 区域估计 → 消息编码（Sage 纠错码）→ 扩散采样生成载图

<whiteboard type="blank"></whiteboard>

### 图3 Pulsar 提取流程图

> 载图 + key → 种子初始化 → 同步扩散 → 区域定位 → 解码提取消息

<whiteboard type="blank"></whiteboard>

### 图4 SparSample 嵌入流程图

> key → 密钥派生 → 确定性扩散采样 → 分布计算 → 高斯量化 → 算术编码 → 生成载图

<whiteboard type="blank"></whiteboard>

### 图5 E2EE 密钥派生层次图

> 助记词 → PBKDF2 → user_key → HKDF → msg_key → AES-256-GCM；stego_key 由双方 invite_code XOR 派生

<whiteboard type="blank"></whiteboard>

### 图6 加密-隐写双重保护时序图

> 发送方明文 → AES-GCM 加密 → 密文嵌入载图 → 传输 → 提取密文 → AES-GCM 解密 → 明文

<whiteboard type="blank"></whiteboard>

## 第3章 系统设计

### 图7 系统总体架构图

> 三层架构：客户端层（Android / Web）→ 通信层（REST API / WebSocket）→ 服务层（FastAPI + 双算法引擎 + SQLite）

<whiteboard type="blank"></whiteboard>

### 图8 消息状态机图

> sending → sent → delivered → read；sending → failed；任意状态 → revoked

<whiteboard type="blank"></whiteboard>

### 图9 WebSocket 通信时序图

> 客户端 ↔ 服务器：chat / ack / delivered / read / typing / friend_request 交互流程

<whiteboard type="blank"></whiteboard>

### 图10 Web 客户端架构图

> Vue 3 + Pinia + IndexedDB + Web Crypto API 分层架构

<whiteboard type="blank"></whiteboard>

## 第4章 系统实现

### 图11 单端登录时序图

> 新连接 → 服务器发 kicked 给旧连接 → 5s 延迟 → 强制关闭旧 WS → 新连接接管

<whiteboard type="blank"></whiteboard>

### 图12 好友请求流程图

> 用户 A 发请求 → 服务器去重/存储 → 推送/离线缓存 → 用户 B accept → 自动恢复暂存消息

<whiteboard type="blank"></whiteboard>

### 图13 隐写消息完整时序图

> 发送方加密 → 调用 embed API → 服务器生成载图 → 推送 → 接收方调用 extract API → 解密显示

<whiteboard type="blank"></whiteboard>

## 第5章 系统测试与分析

### 图14 Pulsar vs SparSample 性能对比图

> 嵌入容量、首次计算耗时、缓存后耗时、FID/SSIM 指标对比

<whiteboard type="blank"></whiteboard>

### 图15 隐写样本对比图

> 原图 vs 载图并排对比，附 PSNR/SSIM 数值

<whiteboard type="blank"></whiteboard>
