# 端到端加密聊天实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md` 为 Web 与 Android 聊天实现对称端到端加密,服务器不可见消息正文。

**Architecture:** 客户端助记词 → PBKDF2 → user_key;双方 user_key → HKDF(排序拼接) → mkey;AES-256-GCM 加密消息载荷;隐写场景把加密 ct 喂给 `/embed`,seed = 邀请码 XOR。服务器零改动。

**Tech Stack:** Web Crypto API (TS), javax.crypto (Android Kotlin), Vue 3 + Pinia + IndexedDB, Jetpack Compose + Room。

---

## 文件结构(整体映射)

### 新增

- `scripts/emit_e2ee_fixture.py` — Python 参考实现,生成黄金测试向量 JSON
- `docs/superpowers/specs/e2ee-test-vectors.json` — 黄金测试向量(Phase 1 产出,后续只读引用)
- `web/src/crypto/e2ee.ts` — Web 侧加解密工具
- `web/src/crypto/__tests__/e2ee.spec.ts` — Web 单元测试
- `web/src/stores/settings.ts` — 自己的 phrase/key 状态管理
- `web/src/db/settings.ts` — IndexedDB user_settings object store 封装
- `android/app/src/main/java/com/stegoapp/app/crypto/CryptoUtils.kt`
- `android/app/src/test/java/com/stegoapp/app/crypto/CryptoUtilsTest.kt`
- `android/app/src/main/java/com/stegoapp/app/data/local/entity/UserSettingsEntity.kt`
- `android/app/src/main/java/com/stegoapp/app/data/local/dao/UserSettingsDao.kt`

### 修改

- `web/src/db/index.ts` — 扩 DB schema(user_settings store + contacts 扩字段),版本 +1
- `web/src/stores/contacts.ts` — 增 `peerPhrase` / `peerUserKeyHex` 字段与设值方法
- `web/src/stores/chat.ts` — `getStegoKey` 改 XOR;接收路径解密;发送路径加密
- `web/src/views/profile/ProfileView.vue`(或同目录 Settings 页)— 助记词入口
- `web/src/views/contact/ContactDetailView.vue` — 对方助记词入口
- `web/src/views/chat/ChatView.vue` — banner + 加密发送 + 解密展示
- `android/app/src/main/java/com/stegoapp/app/data/local/AppDatabase.kt` — 版本 +1 + Migration
- `android/app/src/main/java/com/stegoapp/app/data/local/entity/ContactEntity.kt` — 扩三字段
- `android/app/src/main/java/com/stegoapp/app/ui/screens/settings/SettingsScreen.kt` — 助记词区
- `android/app/src/main/java/com/stegoapp/app/ui/screens/contact/ContactDetailScreen.kt` — 对方助记词区
- `android/app/src/main/java/com/stegoapp/app/ui/viewmodel/ChatViewModel.kt` — XOR + 加/解密
- `android/app/src/main/java/com/stegoapp/app/ui/screens/chat/ChatScreen.kt` — banner

### 不动

- `server/` 任何文件(per 设计 §6)
- `web/src/views/EmbedView.vue` / `ExtractView.vue`(独立工具页)
- `pulsar/` / `sparsample/` / `models/`

---

## Chunk 1: 加密工具基础(Python 参考 + Web + Android)

建立三份一致实现,通过同一份黄金测试向量双向验证。

### Task 1.1: Python 参考实现 + 生成 fixture

**Files:**
- Create: `scripts/emit_e2ee_fixture.py`
- Create: `docs/superpowers/specs/e2ee-test-vectors.json`

- [ ] **Step 1: 写 Python 参考脚本**

```python
# scripts/emit_e2ee_fixture.py
"""生成 E2EE 黄金测试向量。Web / Android 两端实现必须能复现每一个字段。
运行: python scripts/emit_e2ee_fixture.py
输出: docs/superpowers/specs/e2ee-test-vectors.json
"""
import hashlib, hmac, json, os
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SEED_SALT = b"stegochat-seed-v1"
MKEY_SALT = b"stegochat-mkey-v1"
MKEY_INFO = b"e2ee"

def derive_user_key(phrase: str) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(), length=32,
        salt=SEED_SALT, iterations=100_000,
    )
    return kdf.derive(phrase.strip().encode("utf-8"))

def derive_mkey(self_key: bytes, peer_key: bytes) -> bytes:
    canon = self_key + peer_key if self_key <= peer_key else peer_key + self_key
    kdf = HKDF(
        algorithm=hashes.SHA256(), length=32,
        salt=MKEY_SALT, info=MKEY_INFO,
    )
    return kdf.derive(canon)

def xor_bytes(a: bytes, b: bytes) -> bytes:
    assert len(a) == len(b)
    return bytes(x ^ y for x, y in zip(a, b))

def fingerprint(user_key: bytes) -> bytes:
    return hashlib.sha256(user_key).digest()[:8]

phrase_A = "alice-phrase-2026"
phrase_B = "bob secret phrase"
invite_A = "AB12CD34"
invite_B = "EF56GH78"
plaintext = "Hello, world!".encode("utf-8")
fixed_nonce = bytes.fromhex("000102030405060708090a0b")

uk_A = derive_user_key(phrase_A)
uk_B = derive_user_key(phrase_B)
mkey = derive_mkey(uk_A, uk_B)
assert derive_mkey(uk_B, uk_A) == mkey, "mkey must be symmetric"
seed = xor_bytes(invite_A.encode("utf-8"), invite_B.encode("utf-8"))

aesgcm = AESGCM(mkey)
ct = aesgcm.encrypt(fixed_nonce, plaintext, associated_data=None)

fixture = {
    "phrase_A": phrase_A,
    "phrase_B": phrase_B,
    "invite_A": invite_A,
    "invite_B": invite_B,
    "plaintext_utf8": plaintext.decode("utf-8"),
    "fixed_nonce_hex": fixed_nonce.hex(),
    "expected": {
        "user_key_A_hex": uk_A.hex(),
        "user_key_B_hex": uk_B.hex(),
        "fingerprint_A_hex": fingerprint(uk_A).hex(),
        "fingerprint_B_hex": fingerprint(uk_B).hex(),
        "mkey_hex": mkey.hex(),
        "seed_hex": seed.hex(),
        "ciphertext_with_tag_hex": ct.hex(),
    },
}

out = os.path.join(os.path.dirname(__file__), "..", "docs", "superpowers", "specs", "e2ee-test-vectors.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(fixture, f, ensure_ascii=False, indent=2)
print("wrote", out)
```

- [ ] **Step 2: 运行生成 fixture**

```bash
cd /home/zya/bishe
mamba activate sage
pip install cryptography  # 若未装
python scripts/emit_e2ee_fixture.py
cat docs/superpowers/specs/e2ee-test-vectors.json
```

Expected: 打印 JSON,包含 `expected.mkey_hex` 等字段,每项 hex 长度匹配(mkey_hex 长 64 = 32 字节)。

- [ ] **Step 3: 提交**

```bash
git add scripts/emit_e2ee_fixture.py docs/superpowers/specs/e2ee-test-vectors.json
git commit -m "feat(e2ee): golden test vectors + Python reference impl"
```

### Task 1.2: Web 加密模块

**Files:**
- Create: `web/src/crypto/e2ee.ts`
- Create: `web/src/crypto/__tests__/e2ee.spec.ts`

- [ ] **Step 1: 写失败的测试**

```typescript
// web/src/crypto/__tests__/e2ee.spec.ts
import { describe, it, expect } from 'vitest'
import fixture from '../../../../docs/superpowers/specs/e2ee-test-vectors.json'
import {
  deriveUserKey, deriveMkey, fingerprint,
  xorBytes, aesGcmEncrypt, aesGcmDecrypt,
  hexToBytes, bytesToHex,
} from '../e2ee'

const exp = fixture.expected

describe('e2ee golden vectors', () => {
  it('derives user_key_A', async () => {
    const uk = await deriveUserKey(fixture.phrase_A)
    expect(bytesToHex(uk)).toBe(exp.user_key_A_hex)
  })
  it('derives user_key_B', async () => {
    const uk = await deriveUserKey(fixture.phrase_B)
    expect(bytesToHex(uk)).toBe(exp.user_key_B_hex)
  })
  it('fingerprint matches', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    expect(bytesToHex(await fingerprint(ukA))).toBe(exp.fingerprint_A_hex)
  })
  it('mkey symmetric', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const m1 = await deriveMkey(ukA, ukB)
    const m2 = await deriveMkey(ukB, ukA)
    expect(bytesToHex(m1)).toBe(exp.mkey_hex)
    expect(bytesToHex(m2)).toBe(exp.mkey_hex)
  })
  it('xor seed', () => {
    const a = new TextEncoder().encode(fixture.invite_A)
    const b = new TextEncoder().encode(fixture.invite_B)
    expect(bytesToHex(xorBytes(a, b))).toBe(exp.seed_hex)
  })
  it('AES-GCM reproduces ciphertext with fixed nonce', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const mkey = await deriveMkey(ukA, ukB)
    const nonce = hexToBytes(fixture.fixed_nonce_hex)
    const pt = new TextEncoder().encode(fixture.plaintext_utf8)
    const ct = await aesGcmEncrypt(mkey, nonce, pt)
    expect(bytesToHex(ct)).toBe(exp.ciphertext_with_tag_hex)
    const dec = await aesGcmDecrypt(mkey, nonce, ct)
    expect(new TextDecoder().decode(dec)).toBe(fixture.plaintext_utf8)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd web
npm test -- crypto/__tests__/e2ee.spec.ts
```

Expected: FAIL — `Cannot find module '../e2ee'`.

- [ ] **Step 3: 最小实现**

```typescript
// web/src/crypto/e2ee.ts
/** 端到端加密工具:PBKDF2/HKDF/AES-GCM/XOR/fingerprint/hex。
 * 所有常量必须与 docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4 对齐。
 */
const SEED_SALT = new TextEncoder().encode('stegochat-seed-v1')
const MKEY_SALT = new TextEncoder().encode('stegochat-mkey-v1')
const MKEY_INFO = new TextEncoder().encode('e2ee')
const PBKDF2_ITER = 100_000

export function bytesToHex(b: Uint8Array): string {
  return Array.from(b).map(x => x.toString(16).padStart(2, '0')).join('')
}
export function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i*2, i*2+2), 16)
  return out
}

export async function deriveUserKey(phrase: string): Promise<Uint8Array> {
  const pt = new TextEncoder().encode(phrase.trim())
  const base = await crypto.subtle.importKey('raw', pt, 'PBKDF2', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: SEED_SALT, iterations: PBKDF2_ITER },
    base, 256,
  )
  return new Uint8Array(bits)
}

function cmpBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length)
  for (let i = 0; i < n; i++) { if (a[i] !== b[i]) return a[i] - b[i] }
  return a.length - b.length
}

export async function deriveMkey(selfKey: Uint8Array, peerKey: Uint8Array): Promise<Uint8Array> {
  const [lo, hi] = cmpBytes(selfKey, peerKey) <= 0 ? [selfKey, peerKey] : [peerKey, selfKey]
  const canon = new Uint8Array(lo.length + hi.length)
  canon.set(lo); canon.set(hi, lo.length)
  const base = await crypto.subtle.importKey('raw', canon, 'HKDF', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: MKEY_SALT, info: MKEY_INFO },
    base, 256,
  )
  return new Uint8Array(bits)
}

export async function fingerprint(userKey: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', userKey)
  return new Uint8Array(digest).slice(0, 8)
}

export function xorBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length !== b.length) throw new Error('xor: length mismatch')
  const out = new Uint8Array(a.length)
  for (let i = 0; i < a.length; i++) out[i] = a[i] ^ b[i]
  return out
}

async function importAesGcmKey(raw: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt','decrypt'])
}

export async function aesGcmEncrypt(mkey: Uint8Array, nonce: Uint8Array, pt: Uint8Array): Promise<Uint8Array> {
  const k = await importAesGcmKey(mkey)
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, k, pt)
  return new Uint8Array(ct)
}
export async function aesGcmDecrypt(mkey: Uint8Array, nonce: Uint8Array, ct: Uint8Array): Promise<Uint8Array> {
  const k = await importAesGcmKey(mkey)
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, k, ct)
  return new Uint8Array(pt)
}

/** 把 plaintext 加密为 {v:1, n:base64url-nonce, c:base64url-ct} 再 base64url 整体编码,供 WS 透传 */
export async function sealMessage(mkey: Uint8Array, plaintext: string): Promise<string> {
  const nonce = crypto.getRandomValues(new Uint8Array(12))
  const ct = await aesGcmEncrypt(mkey, nonce, new TextEncoder().encode(plaintext))
  const payload = { v: 1, n: b64uEncode(nonce), c: b64uEncode(ct) }
  return b64uEncode(new TextEncoder().encode(JSON.stringify(payload)))
}
/** 识别 sealed 格式并解密;非 sealed (如老明文) 返回 null 由调用方兜底 */
export async function tryOpenMessage(mkey: Uint8Array, sealed: string): Promise<string | null> {
  try {
    const json = JSON.parse(new TextDecoder().decode(b64uDecode(sealed)))
    if (!json || json.v !== 1 || !json.n || !json.c) return null
    const pt = await aesGcmDecrypt(mkey, b64uDecode(json.n), b64uDecode(json.c))
    return new TextDecoder().decode(pt)
  } catch { return null }
}

function b64uEncode(b: Uint8Array): string {
  let s = ''
  for (const x of b) s += String.fromCharCode(x)
  return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')
}
function b64uDecode(s: string): Uint8Array {
  const padded = s.replace(/-/g,'+').replace(/_/g,'/') + '=='.slice((s.length+2)%4 ? 0 : 2)
  const raw = atob(padded)
  const out = new Uint8Array(raw.length)
  for (let i=0; i<raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd web
npm test -- crypto/__tests__/e2ee.spec.ts
```

Expected: 6/6 PASS。若有失败 — 大概率 KDF 参数或 XOR 字节序与 Python 偏差,对照 `docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4` 修正。

- [ ] **Step 5: 提交**

```bash
git add web/src/crypto/
git commit -m "feat(web): E2EE crypto module + golden vector tests"
```

### Task 1.3: Android 加密模块

**Files:**
- Create: `android/app/src/main/java/com/stegoapp/app/crypto/CryptoUtils.kt`
- Create: `android/app/src/test/java/com/stegoapp/app/crypto/CryptoUtilsTest.kt`

- [ ] **Step 1: 拷贝 fixture 到 android 资源**

```bash
cp docs/superpowers/specs/e2ee-test-vectors.json android/app/src/test/resources/e2ee-test-vectors.json
```

(若 `android/app/src/test/resources/` 不存在先 `mkdir -p`)

- [ ] **Step 2: 写失败的测试**

```kotlin
// android/app/src/test/java/com/stegoapp/app/crypto/CryptoUtilsTest.kt
package com.stegoapp.app.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoUtilsTest {
    private val fixture: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("e2ee-test-vectors.json")!!.readText())
    }
    private val expected get() = fixture.getJSONObject("expected")

    @Test fun userKeyA() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        assertEquals(expected.getString("user_key_A_hex"), uk.toHex())
    }
    @Test fun userKeyB() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        assertEquals(expected.getString("user_key_B_hex"), uk.toHex())
    }
    @Test fun fingerprintA() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        assertEquals(expected.getString("fingerprint_A_hex"), CryptoUtils.fingerprint(uk).toHex())
    }
    @Test fun mkeySymmetric() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val m1 = CryptoUtils.deriveMkey(ukA, ukB)
        val m2 = CryptoUtils.deriveMkey(ukB, ukA)
        assertEquals(expected.getString("mkey_hex"), m1.toHex())
        assertArrayEquals(m1, m2)
    }
    @Test fun xorSeed() {
        val a = fixture.getString("invite_A").toByteArray(Charsets.UTF_8)
        val b = fixture.getString("invite_B").toByteArray(Charsets.UTF_8)
        assertEquals(expected.getString("seed_hex"), CryptoUtils.xorBytes(a, b).toHex())
    }
    @Test fun aesGcm() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val mkey = CryptoUtils.deriveMkey(ukA, ukB)
        val nonce = expected.getString("fixed_nonce_hex").let { hex ->
            fixture.getString("fixed_nonce_hex").hexToBytes()
        }
        val pt = fixture.getString("plaintext_utf8").toByteArray(Charsets.UTF_8)
        val ct = CryptoUtils.aesGcmEncrypt(mkey, nonce, pt)
        assertEquals(expected.getString("ciphertext_with_tag_hex"), ct.toHex())
        val dec = CryptoUtils.aesGcmDecrypt(mkey, nonce, ct)
        assertArrayEquals(pt, dec)
    }
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
fun String.hexToBytes() = ByteArray(length / 2) { substring(it*2, it*2+2).toInt(16).toByte() }
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd android
./gradlew test --tests CryptoUtilsTest
```

Expected: FAIL — `CryptoUtils` 符号未解析。

- [ ] **Step 4: 最小实现**

```kotlin
// android/app/src/main/java/com/stegoapp/app/crypto/CryptoUtils.kt
package com.stegoapp.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 必须与 docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4 对齐。 */
object CryptoUtils {
    private val SEED_SALT = "stegochat-seed-v1".toByteArray(Charsets.UTF_8)
    private val MKEY_SALT = "stegochat-mkey-v1".toByteArray(Charsets.UTF_8)
    private val MKEY_INFO = "e2ee".toByteArray(Charsets.UTF_8)
    private const val PBKDF2_ITER = 100_000

    fun deriveUserKey(phrase: String): ByteArray {
        val trimmed = phrase.trim()
        val spec = PBEKeySpec(trimmed.toCharArray(), SEED_SALT, PBKDF2_ITER, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deriveMkey(selfKey: ByteArray, peerKey: ByteArray): ByteArray {
        val canon = if (compareBytes(selfKey, peerKey) <= 0) selfKey + peerKey else peerKey + selfKey
        return hkdfSha256(canon, MKEY_SALT, MKEY_INFO, 32)
    }

    fun fingerprint(userKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(userKey).copyOf(8)

    fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xor: length mismatch" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    fun aesGcmEncrypt(mkey: ByteArray, nonce: ByteArray, pt: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(mkey, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(pt)
    }

    fun aesGcmDecrypt(mkey: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mkey, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    fun randomNonce(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }

    /** RFC 5869 HKDF-Extract-then-Expand with SHA-256 */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(byteArrayOf(counter.toByte()))
            t = mac.doFinal()
            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, take)
            offset += take; counter++
        }
        return out
    }
}

/** 可随消息一起传的 sealed 载荷工具:{v:1, n, c} base64url 外包一层 base64url */
object SealedMessage {
    private val b64u = java.util.Base64.getUrlEncoder().withoutPadding()
    private val b64uDec = java.util.Base64.getUrlDecoder()

    fun seal(mkey: ByteArray, plaintext: String): String {
        val nonce = CryptoUtils.randomNonce()
        val ct = CryptoUtils.aesGcmEncrypt(mkey, nonce, plaintext.toByteArray(Charsets.UTF_8))
        val json = """{"v":1,"n":"${b64u.encodeToString(nonce)}","c":"${b64u.encodeToString(ct)}"}"""
        return b64u.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** 返回 null 表示不是 sealed 格式(老明文);由调用方兜底原样展示 */
    fun tryOpen(mkey: ByteArray, sealed: String): String? = try {
        val json = String(b64uDec.decode(sealed), Charsets.UTF_8)
        val obj = org.json.JSONObject(json)
        if (obj.optInt("v", 0) != 1) null else {
            val nonce = b64uDec.decode(obj.getString("n"))
            val ct = b64uDec.decode(obj.getString("c"))
            String(CryptoUtils.aesGcmDecrypt(mkey, nonce, ct), Charsets.UTF_8)
        }
    } catch (_: Throwable) { null }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd android
./gradlew test --tests CryptoUtilsTest
```

Expected: 6/6 PASS。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/stegoapp/app/crypto/ \
        android/app/src/test/java/com/stegoapp/app/crypto/ \
        android/app/src/test/resources/e2ee-test-vectors.json
git commit -m "feat(android): E2EE crypto module + golden vector tests"
```

---

## Chunk 2: Web 密钥管理 UI + 存储

### Task 2.1: IndexedDB schema 扩展

**Files:**
- Modify: `web/src/db/index.ts`(添加 user_settings object store + contacts 扩字段 + 版本号 +1)

- [ ] **Step 1: 读 `web/src/db/index.ts` 当前版本号和 upgrade 逻辑**
- [ ] **Step 2: 版本号 +1,在 upgrade 里 `createObjectStore('user_settings', {keyPath: 'id'})`**
- [ ] **Step 3: contacts store 新字段在写入时追加(JS 动态属性,schema 不约束)**
- [ ] **Step 4: 导出 `saveUserSettings` / `loadUserSettings` 函数封装读写**
- [ ] **Step 5: 提交 `feat(web): user_settings IndexedDB store for E2EE`**

### Task 2.2: Pinia settings store

**Files:**
- Create: `web/src/stores/settings.ts`

- [ ] **Step 1: `defineStore('settings', ...)`,state: `{ phrase, userKeyHex, fingerprintHex }`**
- [ ] **Step 2: `actions.setPhrase(newPhrase)` → 调 `deriveUserKey` → 存 DB + state**
- [ ] **Step 3: `actions.clear()` 重置**
- [ ] **Step 4: `actions.hydrate()` 从 DB 读并回填(App 启动时 `auth.verify()` 后调用)**
- [ ] **Step 5: 提交**

### Task 2.3: 在 contacts store 加对方助记词字段

**Files:**
- Modify: `web/src/stores/contacts.ts`

- [ ] **Step 1: 扩 Contact 类型 `peerPhrase? / peerUserKeyHex? / peerFingerprintHex?`**
- [ ] **Step 2: 新 action `setPeerPhrase(contactId, phrase)`:派生 → 保存 DB + state**
- [ ] **Step 3: 查询派生结果方法 `getPeerUserKey(contactId)`**
- [ ] **Step 4: 提交**

### Task 2.4: Settings/Profile 页 UI

**Files:**
- Modify: `web/src/views/profile/ProfileView.vue`(或 `SettingsView.vue`,以项目实际为准)

- [ ] **Step 1: 新增"加密助记词"卡片:输入框、保存按钮、指纹 span**
- [ ] **Step 2: 绑定到 settings store:`v-model` 输入框,保存按钮调 `setPhrase`**
- [ ] **Step 3: 指纹用 `ukHexToFpLabel(state.fingerprintHex)`,格式化为 `a1:b2:c3:d4:e5:f6:07:08`**
- [ ] **Step 4: "重置"按钮调 `clear`**
- [ ] **Step 5: 手动验证:浏览器填入 `"alice-phrase-2026"` → 指纹应等于 fixture `fingerprint_A_hex` 前 8 字节的冒号分隔**
- [ ] **Step 6: 提交**

### Task 2.5: ContactDetail 页 UI

**Files:**
- Modify: `web/src/views/contact/ContactDetailView.vue`

- [ ] **Step 1: 新增"对方的加密助记词"区:输入框、保存按钮、指纹 span**
- [ ] **Step 2: 绑定到 contacts store,保存按钮调 `setPeerPhrase(contactId, phrase)`**
- [ ] **Step 3: 联系人列表项上 🔒 小角标:`peerUserKeyHex` 为空则显示"未加密"图标**
- [ ] **Step 4: 手动验证:第二个浏览器以 Bob 身份设置 `phrase_B`,联系人加 Alice 后设 `peerPhrase = phrase_A`,双端指纹对得上**
- [ ] **Step 5: 提交**

---

## Chunk 3: Android 密钥管理 UI + 存储

### Task 3.1: Room schema 扩展 + Migration

**Files:**
- Create: `android/app/src/main/java/com/stegoapp/app/data/local/entity/UserSettingsEntity.kt`
- Create: `android/app/src/main/java/com/stegoapp/app/data/local/dao/UserSettingsDao.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/data/local/entity/ContactEntity.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/data/local/AppDatabase.kt`

- [ ] **Step 1: `UserSettingsEntity(id: Int = 0 PK, phrase: String, userKeyHex: String, fingerprintHex: String)`**
- [ ] **Step 2: `UserSettingsDao`:`upsert`、`flowSingle()` 返回单行 Flow**
- [ ] **Step 3: `ContactEntity` 加三字段 `peerPhrase, peerUserKeyHex, peerFingerprintHex`(可空)**
- [ ] **Step 4: `AppDatabase` 版本 +1,写 `Migration(oldV, newV)`:**
  ```kotlin
  database.execSQL("ALTER TABLE contacts ADD COLUMN peer_phrase TEXT")
  database.execSQL("ALTER TABLE contacts ADD COLUMN peer_user_key_hex TEXT")
  database.execSQL("ALTER TABLE contacts ADD COLUMN peer_fingerprint_hex TEXT")
  database.execSQL("""
    CREATE TABLE IF NOT EXISTS user_settings (
      id INTEGER NOT NULL PRIMARY KEY,
      phrase TEXT NOT NULL,
      user_key_hex TEXT NOT NULL,
      fingerprint_hex TEXT NOT NULL
    )
  """.trimIndent())
  ```
- [ ] **Step 5: `./gradlew assembleDebug` 确认编译**
- [ ] **Step 6: 提交 `feat(android): Room schema v+1 for E2EE user_settings + contact extensions`**

### Task 3.2: SettingsScreen 加助记词区

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: 复合 Card,含 `OutlinedTextField` + `Button("保存")` + 指纹 `Text`**
- [ ] **Step 2: VM: `saveSelfPhrase(phrase)` 派生 → 存 Room**
- [ ] **Step 3: 指纹从 `user_key_hex` 经 `fingerprint` 计算后冒号分隔展示**
- [ ] **Step 4: 真机/模拟器验证指纹 = fixture `fingerprint_A_hex`(Alice phrase)**
- [ ] **Step 5: 提交**

### Task 3.3: ContactDetailScreen 加对方助记词区

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/contact/ContactDetailScreen.kt`

- [ ] **Step 1: Card:输入框 + 保存 + 指纹展示**
- [ ] **Step 2: VM `savePeerPhrase(contactId, phrase)`**
- [ ] **Step 3: ContactList 列表项角标 🔒**
- [ ] **Step 4: 真机验证**
- [ ] **Step 5: 提交**

---

## Chunk 4: 聊天加密(Web + Android)

### Task 4.1: Web ChatView 加密发送 + 解密展示 + banner

**Files:**
- Modify: `web/src/stores/chat.ts`
- Modify: `web/src/views/chat/ChatView.vue`

- [ ] **Step 1: 在 chat store 注入 settings store 与 contacts store,暴露 `currentMkey: Ref<Uint8Array|null>`**
- [ ] **Step 2: 进入 ChatView 时:若 `settings.userKeyHex` 与 `contact.peerUserKeyHex` 都有 → 计算 mkey 存入 store**
- [ ] **Step 3: 发送普通文本前: `sealMessage(mkey, text)` 生成 sealed 字符串;WS payload `content` 字段放 sealed**
- [ ] **Step 4: 收到消息展示前:调 `tryOpenMessage(mkey, content)`;`null` 说明是老明文,原样展示**
- [ ] **Step 5: 聊天页顶部条件渲染 banner,按 "自己未设" / "对方未设" 分别出不同提示;输入框 disabled**
- [ ] **Step 6: 手动测试:Alice/Bob 双浏览器,双方配齐助记词 → 发 "hello" → 网络面板看到 WS content 为加密串,对端显示明文**
- [ ] **Step 7: 提交**

### Task 4.2: Android ChatViewModel 加密

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/viewmodel/ChatViewModel.kt`
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: VM 增 `mkey: StateFlow<ByteArray?>`,由 self + contact 两个源合并派生**
- [ ] **Step 2: `sendText(text)`:有 mkey 则 seal 后作为 content 发送,否则抛/标错**
- [ ] **Step 3: 接收路径在映射 `MessageEntity` 前调 `SealedMessage.tryOpen(mkey, content)`,失败则原样存**
- [ ] **Step 4: ChatScreen 顶部 banner 同 Web**
- [ ] **Step 5: 真机验证 Web ↔ Android 互发加密消息**
- [ ] **Step 6: 提交**

---

## Chunk 5: 隐写聊天改造

### Task 5.1: Web 隐写路径用 XOR seed + 加密 ct

**Files:**
- Modify: `web/src/stores/chat.ts`(`getStegoKey`)
- Modify: `web/src/views/chat/ChatView.vue`(stego 发/收逻辑)

- [ ] **Step 1: `getStegoKey` 重写**
  ```ts
  function getStegoKey(): string {
    const a = new TextEncoder().encode(myInviteCode.value)
    const b = new TextEncoder().encode(peerInviteCode.value)
    return bytesToHex(xorBytes(a, b))
  }
  ```
  删掉旧的方向判断 `isOutgoing` 参数。
- [ ] **Step 2: 发送隐写前:`ct = aesGcmEncrypt(mkey, randomNonce, plaintextBytes)`,把 `nonce||ct` 或 `{v,n,c}` 再 base64 作为 msg 传给 `/embed`**
- [ ] **Step 3: 收到 stego 图片展示时调 `/extract` 拿到 ct 字符串,按 sealed 格式解出 plaintext 展示**
- [ ] **Step 4: 容量检查需考虑加密后膨胀(nonce 12 + tag 16 + base64 约 +37%),提前扣**
- [ ] **Step 5: 手动验证:Alice 发 "秘密",Bob 看到明文;错配助记词 → Bob 看到 "无法解密" 提示**
- [ ] **Step 6: 提交**

### Task 5.2: Android 隐写路径用 XOR seed + 加密 ct

**Files:**
- Modify: `android/app/src/main/java/com/stegoapp/app/ui/viewmodel/ChatViewModel.kt`

- [ ] **Step 1: `getStegoKey` 改为 XOR hex**
- [ ] **Step 2: embed 请求前用 `SealedMessage.seal(mkey, text)` 得到 sealed 字符串,传给 `/embed` 的 `message` 参数**
- [ ] **Step 3: extract 响应后 `SealedMessage.tryOpen(mkey, response)` 解密**
- [ ] **Step 4: 真机验证互通**
- [ ] **Step 5: 提交**

---

## Chunk 6: 集成验证与文档

### Task 6.1: 跨端互通手动测试

- [ ] Web Alice ↔ Android Bob:普通消息 + 隐写消息,加密链路
- [ ] 改一方助记词 → 解密失败并 UI 提示
- [ ] 重启后助记词持久化,指纹一致
- [ ] 老明文消息仍正常展示
- [ ] 离线消息:A 发时 B 离线 → B 上线后收到并能解密

### Task 6.2: README 补说明

**Files:**
- Modify: `README.md`

- [ ] **Step 1: "功能特性" 新增 "端到端加密(助记词约定)" 一条,链接到 spec**
- [ ] **Step 2: "快速开始" 末尾加一节 "启用 E2E 加密(可选但强烈推荐)" — 写基本步骤:自己填助记词 → 为每个联系人填对方助记词 → 开始加密聊天**
- [ ] **Step 3: 提交 `docs(readme): document E2E encryption feature`**

---

## 风险 / 验证清单

- [ ] Phase 1 验收:Python/Web/Android 三端对同一 fixture 产出完全相同的 hex(mkey_hex, ciphertext_with_tag_hex 最关键)
- [ ] 跨端互操作:实际 Web ↔ Android 配对发送加密消息并能解密
- [ ] 性能:PBKDF2 100k iter 在低端 Android 约 100-300ms,在设置页保存时可接受;chat 页不应重复计算(缓存在 store/VM 里)
- [ ] 兼容:带旧明文历史的账号打开聊天不崩,旧消息原样显示
- [ ] 指纹展示 8 字节(16 hex,加冒号后 23 字符)够用;若发现冲突太易构造可以后升到 16 字节

## 验收标准

1. Chunk 1 所有 golden vector 测试双端通过
2. Chunk 4 实现后,WS 抓包看 content 字段无明文
3. Chunk 5 实现后,`/embed` `/extract` 请求的 msg 字段无明文
4. Chunk 6 的互通场景全部通过
