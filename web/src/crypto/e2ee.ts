/**
 * 端到端加密工具模块。
 *
 * 所有常量必须与 docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4
 * 对齐,且必须与 Android CryptoUtils、Python scripts/emit_e2ee_fixture.py 产出
 * 字节级相同。修改这里的常量意味着破坏与其它客户端的兼容性。
 */

const SEED_SALT = new TextEncoder().encode('stegochat-seed-v1')
const MKEY_SALT = new TextEncoder().encode('stegochat-mkey-v1')
const MKEY_INFO = new TextEncoder().encode('e2ee')
const PBKDF2_ITER = 100_000

export function bytesToHex(b: Uint8Array): string {
  let s = ''
  for (const x of b) s += x.toString(16).padStart(2, '0')
  return s
}

export function hexToBytes(hex: string): Uint8Array {
  if (hex.length % 2 !== 0) throw new Error('hex: odd length')
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  }
  return out
}

export async function deriveUserKey(phrase: string): Promise<Uint8Array> {
  const passBytes = new TextEncoder().encode(phrase.trim())
  const base = await crypto.subtle.importKey(
    'raw',
    passBytes as BufferSource,
    'PBKDF2',
    false,
    ['deriveBits'],
  )
  const bits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      hash: 'SHA-256',
      salt: SEED_SALT,
      iterations: PBKDF2_ITER,
    },
    base,
    256,
  )
  return new Uint8Array(bits)
}

function cmpBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length)
  for (let i = 0; i < n; i++) {
    if (a[i] !== b[i]) return a[i] - b[i]
  }
  return a.length - b.length
}

export async function deriveMkey(
  selfKey: Uint8Array,
  peerKey: Uint8Array,
): Promise<Uint8Array> {
  const [lo, hi] = cmpBytes(selfKey, peerKey) <= 0 ? [selfKey, peerKey] : [peerKey, selfKey]
  const canon = new Uint8Array(lo.length + hi.length)
  canon.set(lo, 0)
  canon.set(hi, lo.length)
  const base = await crypto.subtle.importKey(
    'raw',
    canon as BufferSource,
    'HKDF',
    false,
    ['deriveBits'],
  )
  const bits = await crypto.subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: MKEY_SALT,
      info: MKEY_INFO,
    },
    base,
    256,
  )
  return new Uint8Array(bits)
}

export async function fingerprint(userKey: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', userKey as BufferSource)
  return new Uint8Array(digest).slice(0, 8)
}

export function xorBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length !== b.length) throw new Error('xor: length mismatch')
  const out = new Uint8Array(a.length)
  for (let i = 0; i < a.length; i++) out[i] = a[i] ^ b[i]
  return out
}

async function importAesGcmKey(raw: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt'])
}

export async function aesGcmEncrypt(
  mkey: Uint8Array,
  nonce: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  const key = await importAesGcmKey(mkey)
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce as BufferSource },
    key,
    plaintext as BufferSource,
  )
  return new Uint8Array(ct)
}

export async function aesGcmDecrypt(
  mkey: Uint8Array,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  const key = await importAesGcmKey(mkey)
  const pt = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: nonce as BufferSource },
    key,
    ciphertext as BufferSource,
  )
  return new Uint8Array(pt)
}

export function randomNonce(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(12))
}

// base64url 工具:与 Android java.util.Base64.getUrlEncoder().withoutPadding() 对齐。

export function b64uEncode(b: Uint8Array): string {
  let s = ''
  for (const x of b) s += String.fromCharCode(x)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function b64uDecode(s: string): Uint8Array {
  const pad = (4 - (s.length % 4)) % 4
  const normalized = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat(pad)
  const raw = atob(normalized)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

/**
 * 把 plaintext 加密为 {v:1, n:<base64url-nonce>, c:<base64url-ct+tag>},
 * 整体 JSON 再 base64url 编码得到单个 ASCII 字符串,适合塞进 WS 的 content 字段。
 */
export async function sealMessage(mkey: Uint8Array, plaintext: string): Promise<string> {
  const nonce = randomNonce()
  const ct = await aesGcmEncrypt(mkey, nonce, new TextEncoder().encode(plaintext))
  const payload = { v: 1, n: b64uEncode(nonce), c: b64uEncode(ct) }
  return b64uEncode(new TextEncoder().encode(JSON.stringify(payload)))
}

/**
 * 识别并解封 sealed 载荷。失败时(格式不符或解密失败)返回 null,调用方可兜底为
 * "明文直接展示"以兼容老消息历史。
 */
export async function tryOpenMessage(
  mkey: Uint8Array,
  sealed: string,
): Promise<string | null> {
  try {
    const jsonBytes = b64uDecode(sealed)
    const obj = JSON.parse(new TextDecoder().decode(jsonBytes))
    if (!obj || obj.v !== 1 || typeof obj.n !== 'string' || typeof obj.c !== 'string') {
      return null
    }
    const pt = await aesGcmDecrypt(mkey, b64uDecode(obj.n), b64uDecode(obj.c))
    return new TextDecoder().decode(pt)
  } catch {
    return null
  }
}
