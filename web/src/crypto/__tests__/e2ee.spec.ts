import { describe, it, expect } from 'vitest'
import fixture from '../../../../docs/superpowers/specs/e2ee-test-vectors.json' with { type: 'json' }
import {
  aesGcmDecrypt,
  aesGcmEncrypt,
  bytesToHex,
  deriveMkey,
  deriveUserKey,
  fingerprint,
  hexToBytes,
  sealMessage,
  tryOpenMessage,
  xorBytes,
} from '../e2ee'

const expected = fixture.expected

describe('e2ee golden vectors', () => {
  it('derives user_key_A from phrase_A', async () => {
    const uk = await deriveUserKey(fixture.phrase_A)
    expect(bytesToHex(uk)).toBe(expected.user_key_A_hex)
  })

  it('derives user_key_B from phrase_B', async () => {
    const uk = await deriveUserKey(fixture.phrase_B)
    expect(bytesToHex(uk)).toBe(expected.user_key_B_hex)
  })

  it('fingerprint matches for A', async () => {
    const uk = await deriveUserKey(fixture.phrase_A)
    const fp = await fingerprint(uk)
    expect(bytesToHex(fp)).toBe(expected.fingerprint_A_hex)
  })

  it('fingerprint matches for B', async () => {
    const uk = await deriveUserKey(fixture.phrase_B)
    const fp = await fingerprint(uk)
    expect(bytesToHex(fp)).toBe(expected.fingerprint_B_hex)
  })

  it('mkey is symmetric and matches expected', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const m1 = await deriveMkey(ukA, ukB)
    const m2 = await deriveMkey(ukB, ukA)
    expect(bytesToHex(m1)).toBe(expected.mkey_hex)
    expect(bytesToHex(m2)).toBe(expected.mkey_hex)
  })

  it('XOR seed from invite codes', () => {
    const a = new TextEncoder().encode(fixture.invite_A)
    const b = new TextEncoder().encode(fixture.invite_B)
    expect(bytesToHex(xorBytes(a, b))).toBe(expected.seed_hex)
  })

  it('AES-GCM reproduces ciphertext with fixed nonce', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const mkey = await deriveMkey(ukA, ukB)
    const nonce = hexToBytes(fixture.fixed_nonce_hex)
    const pt = new TextEncoder().encode(fixture.plaintext_utf8)
    const ct = await aesGcmEncrypt(mkey, nonce, pt)
    expect(bytesToHex(ct)).toBe(expected.ciphertext_with_tag_hex)
    const dec = await aesGcmDecrypt(mkey, nonce, ct)
    expect(new TextDecoder().decode(dec)).toBe(fixture.plaintext_utf8)
  })
})

describe('sealMessage / tryOpenMessage', () => {
  it('round-trips plaintext through seal/open', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const mkey = await deriveMkey(ukA, ukB)
    const sealed = await sealMessage(mkey, '测试消息 Hello 🚀')
    const opened = await tryOpenMessage(mkey, sealed)
    expect(opened).toBe('测试消息 Hello 🚀')
  })

  it('tryOpenMessage returns null on non-sealed input', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const mkey = await deriveMkey(ukA, ukB)
    const opened = await tryOpenMessage(mkey, 'this is a legacy plaintext message')
    expect(opened).toBeNull()
  })

  it('tryOpenMessage returns null when mkey mismatches', async () => {
    const ukA = await deriveUserKey(fixture.phrase_A)
    const ukB = await deriveUserKey(fixture.phrase_B)
    const mkey = await deriveMkey(ukA, ukB)
    const sealed = await sealMessage(mkey, 'secret')
    const wrongKey = await deriveUserKey('wrong phrase')
    expect(await tryOpenMessage(wrongKey, sealed)).toBeNull()
  })
})
