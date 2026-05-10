# 端到端加密聊天设计

- 日期: 2026-05-10
- 作者: zya
- 状态: Draft(待评审)

## 1. 背景

当前系统聊天内容(含普通文本与隐写请求参数)均以明文经过后端转发。服务器能够读取一切消息内容,也能读取调用 `/embed` / `/extract` 时的 `msg` 和 `key` 字段。目标是在**不引入公私钥体系**的前提下,让服务器在任何时刻都无法读取聊天正文。

## 2. 目标与非目标

### 目标

- **G1** 服务器无法读取任何聊天正文(含普通文本与隐写提取出的 payload)
- **G2** 服务器仍可执行隐写算法(Pulsar / SparSample),不破坏当前双算法架构
- **G3** 双方**线下约定**各自的"助记词",应用据此派生出对称密钥 `mkey`,A 和 B 独立计算结果一致
- **G4** Web 与 Android 互通,同一对 (A 的 mnemonic, B 的 mnemonic) 在两端派生出逐字节相同的 `mkey`
- **G5** 现有明文历史消息保留可读,不作强制迁移
- **G6** 助记词未配置完整时给用户清晰提醒,并暂停消息发送,避免出现"明文降级"破坏 G1

### 非目标

- 不实现公私钥/非对称密钥体系
- 不实现真正的前向保密(PFS)或 double ratchet
- 不对助记词做 BIP39 词表校验
- 不为独立隐写工具页(EmbedView/ExtractView)做加密改造
- 不做密码包裹/设备 Keystore 的"at rest 加密",助记词与派生 key 直接明文存本地 DB(论文级 demo)
- 不改变邀请码长度、格式、生成机制

## 3. 总体架构

```
  A 端                    Server                  B 端
  ┌────────────────────┐                         ┌────────────────────┐
  │ phrase_A (自填)    │                         │ phrase_B (自填)    │
  │ phrase_B (线下来)  │                         │ phrase_A (线下来)  │
  └──────────┬─────────┘                         └──────────┬─────────┘
             │ PBKDF2                                       │ PBKDF2
             ▼                                              ▼
     self_key / peer_key                            self_key / peer_key
             │ HKDF(sorted concat)                          │ HKDF(sorted concat)
             ▼                                              ▼
           mkey ══════════════ 两端独立计算,严格相等 ═══════ mkey

  普通聊天:
     plaintext ─AES-GCM(mkey)→ ct ─ WS ─► Server (不透明) ─ WS ─► B ─AES-GCM⁻¹→ plaintext

  隐写聊天:
     plaintext ─AES-GCM(mkey)→ ct
     ct + seed(=invite_A ⊕ invite_B) ─HTTP POST /embed─►  Server 嵌入 ct 到图像
     image ─ WS ─► Server (image base64) ─ WS ─► B
     image + seed ─HTTP POST /extract─► Server 返回 ct
     ct ─AES-GCM⁻¹(mkey)→ plaintext
```

服务器只看到: 加密的不透明 WS 内容 / 不可识别的 ct 字节 / 公开的模型 seed(invite 异或值)。**永远看不到 plaintext**。

## 4. 密码学规格

所有两端都必须严格按照下面的参数实现,以保证互通。

### 4.1 phrase → user_key

```
user_key = PBKDF2-HMAC-SHA256(
  password   = UTF-8(phrase.trim()),      # 前后空格去除,避免粘贴带空格
  salt       = UTF-8("stegochat-seed-v1"),
  iterations = 100000,
  dkLen      = 32  bytes
)
```

`phrase` 为用户自由输入字符串,允许任意 Unicode。固定全局 salt 便于两端独立派生到同一结果(不为每个用户加独立 salt,因为双方线下约定后需要在对方设备得到同样 user_key)。

### 4.2 (self_key, peer_key) → mkey

```
canon = bytes_less_or_equal(self_key, peer_key)   # 按字节序取较小的排前面
canon += bytes_greater(self_key, peer_key)        # 较大的排后面
# 结果长度 64 字节

mkey = HKDF-SHA256(
  ikm  = canon,
  salt = UTF-8("stegochat-mkey-v1"),
  info = UTF-8("e2ee"),
  L    = 32  bytes
)
```

排序消除方向差异(A 发时 self=A, peer=B;B 收时 self=B, peer=A,两侧 canon 一致)。

### 4.3 消息加密 (AES-GCM)

```
nonce      = CSPRNG(12 bytes)                # 每条消息新随机
ct_and_tag = AES-256-GCM-Encrypt(
  key        = mkey,
  plaintext  = UTF-8(raw_text)  或  raw_msg_bytes (隐写场景),
  nonce      = nonce,
  aad        = ""                            # 本版本不带关联数据
)
```

### 4.4 线路载荷格式 (普通聊天)

发往 `content` 字段的是一个 base64 字符串,解码后为 JSON:

```json
{
  "v": 1,
  "n": "<base64url: 12 bytes nonce>",
  "c": "<base64url: ciphertext+tag>"
}
```

发送前外层再 base64 包一次,让 WS 看到的仅是普通 ASCII 字符串。旧明文消息不带这个结构,客户端按 `v` 字段识别新旧。

### 4.5 隐写 seed 派生 (model key)

```
seed_bytes = invite_A_utf8 ^ invite_B_utf8   # 按字节 XOR
# 当前 invite_code 长度为 8 字符 (INVITE_CODE_LENGTH),
# 两端通过 /invite/user-code 拿到对方码后在本地 XOR
seed_str = hex(seed_bytes)                   # 作为字符串传给 /embed /extract 的 key 参数
```

若未来邀请码长度变化,调整上式对齐方式;本阶段两端定长 8 字符,不涉及 padding。

`seed_str` 对服务器可见 — 它本来就是双方邀请码的函数,服务器本就知道双方邀请码,不构成泄漏。

## 5. 前端改动(Web + Android 对称)

### 5.1 设置页(SettingsView / SettingsScreen)

新增一节 **"加密助记词"**:

| 控件 | 行为 |
|------|------|
| 文本输入框(placeholder: "任意字符串,线下与对方约定") | 输入后点"保存"落盘,自动 trim |
| 指纹显示 | 保存后展示 `SHA-256(user_key)` 的前 8 字节 hex,例如 `a1 b2 c3 d4 e5 f6 07 08`,给用户双向核对 |
| "重新设置"按钮 | 清除当前 phrase/user_key,回到未设置状态 |

### 5.2 联系人详情页(ContactDetailView / ContactDetailScreen)

新增一节 **"对方的加密助记词"**:

- 文本输入框 + 保存按钮
- 保存后显示对方的 user_key 指纹(前 8 字节 hex),用户可以跟对方电话/当面比对
- 未设置时在联系人列表上小角标 🔒❌ 提示

### 5.3 聊天页(ChatView / ChatScreen)

聊天页加载时检查:

1. 自己的 phrase 是否已设置?
2. 这个联系人的 phrase 是否已设置?

任一未满足 → 输入框 disabled + 顶部横幅:

> ⚠️ 请先在**设置**中设置自己的助记词,并在**联系人详情**中为该联系人配置助记词,才能加密通信。

都已设置 → 正常聊天,消息按 4.4 格式加密发送。

### 5.4 存储

**Web (IndexedDB)**:

- `user_settings` object store:
  ```
  { id: "self",
    phrase: "<string>",           # 明文保存便于用户修改
    user_key_hex: "<64 hex>",     # 缓存,避免每次重算 PBKDF2
    fingerprint_hex: "<16 hex>"
  }
  ```
- `contacts` object store 扩字段:
  ```
  { user_id, username, invite_code, ...
    peer_phrase: "<string>",      # 新增
    peer_user_key_hex: "<64 hex>",# 新增,缓存
    peer_fingerprint_hex: "<16 hex>" # 新增
  }
  ```

**Android (Room)**:

- `UserSettings` 新 entity,单行: `phrase`, `userKeyHex`, `fingerprintHex`
- `ContactEntity` 扩字段: `peerPhrase`, `peerUserKeyHex`, `peerFingerprintHex`

两端都是明文落盘(非目标之一)。

### 5.5 mkey 非持久化

`mkey` 只在内存中派生,进入聊天页时由 `self.user_key` + `contact.peer_user_key` 即时 HKDF 得到,页面销毁即释放。不落盘,降低潜在泄漏面。

## 6. 后端改动

### 6.1 WebSocket 层

**无改动**。`handle_message` 照旧透传 `content` 字段,服务器既不解析也不解密。

### 6.2 隐写端点

**无改动**。`/api/v1/stego/embed` `/api/v1/stego/extract` API 签名不变,只是 client 现在传的 `msg` 是加密后的 ct bytes、`key` 是 XOR hex 字符串。服务器不 care 内容是什么,只当 bytes 处理。

### 6.3 独立隐写工具

**不动**。EmbedView / ExtractView 仍按用户手填 key + 明文 msg 运作,维持 demo 场景不变。

## 7. 现有数据兼容

- **历史明文消息** 按 `v` 字段缺失识别,客户端直接展示字节,不尝试解密
- **离线队列**(server 侧 `queue_service`) 存的就是加密后的 WS 载荷,出队时原样发送给新上线客户端,无需改造
- **邀请码** 格式不变,只是 `getStegoKey` 逻辑从 concat 改为 XOR

## 8. 跨端互通验证

为防止 Web Crypto API 与 Android javax.crypto 在 KDF/AES-GCM 边界实现上出现细小不一致,提供一份**黄金测试向量**:

```
phrase_A       = "alice-phrase-2026"
phrase_B       = "bob secret phrase"
invite_A       = "AB12CD34"
invite_B       = "EF56GH78"
plaintext      = "Hello, world!"

expected user_key_A (hex) = <到实现阶段实际跑出来填入>
expected user_key_B (hex) = ...
expected mkey      (hex) = ...
expected seed_hex        = ...
```

Web 和 Android 双端各实现后跑同一组 fixture,必须完全一致才能通过 Phase 1 验收。

## 9. 分阶段实施

| Phase | 范围 | 验收 |
|-------|------|------|
| 1 | 密码学工具库 (Web TS `crypto.ts` + Android `CryptoUtils.kt`) | 双端跑相同测试向量,字节级相等 |
| 2 | 密钥管理 UI(设置页 + 联系人详情 + 本地存储) | 手工验证:设好自己的 + 对方的,指纹正确展示 |
| 3 | 普通聊天加密(WS 路径) | A ↔ B 互发,明文只在两端出现;抓包看 WS 无 plaintext |
| 4 | 隐写聊天改造(XOR seed + ct 嵌入/提取) | A 嵌入、B 提取显示原文;错配助记词时提取出的 ct 解不开(GCM tag 校验失败) |
| 5 | 集成测试 + 兼容 | 老数据共存正常、助记词未设有 banner、重置助记词后重新派生 |

## 10. 风险与开放项

| 风险 | 缓解 |
|------|------|
| Web Crypto 与 Android 在 HKDF 边界不一致 | Phase 1 黄金测试向量双端严格比对 |
| phrase 被空格/Unicode normalization 差异打乱 | 两端都做 `trim()` + 不做 NFC/NFD 规范化(输入什么是什么) |
| 用户换新设备丢失 phrase → 无法解密历史 | 接受此风险(符合"E2E、服务端不托管"语义) |
| 邀请码被重置后 seed 变化 | mkey 不依赖邀请码;stego seed 会变,但重置邀请码本来就是"换身份"操作 |
| GCM nonce 重用 | 随机 12 字节 nonce + 单用户单 mkey 长期下可能碰撞;实用范围内(<2³² 条/mkey)不用 worry |
| mkey 内存驻留 | 仅活跃会话,页面销毁即 GC;不保存到 DB |

## 11. 待用户确认后再动工的点

无(方向性问题已在设计讨论阶段确认)。设计通过评审后进入 writing-plans 阶段细化任务。
