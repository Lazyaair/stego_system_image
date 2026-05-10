package com.stegoapp.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密工具。常量必须与
 *   docs/superpowers/specs/2026-05-10-e2e-encrypted-chat-design.md §4
 * 对齐,且必须与 Web e2ee.ts、Python scripts/emit_e2ee_fixture.py 产出
 * 字节级相同。修改这里的常量意味着破坏与其它客户端的兼容性。
 */
object CryptoUtils {
    private val SEED_SALT = "stegochat-seed-v1".toByteArray(Charsets.UTF_8)
    private val MKEY_SALT = "stegochat-mkey-v1".toByteArray(Charsets.UTF_8)
    private val MKEY_INFO = "e2ee".toByteArray(Charsets.UTF_8)
    private const val PBKDF2_ITER = 100_000
    private const val USER_KEY_BITS = 256
    private const val MKEY_BYTES = 32
    private const val FINGERPRINT_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun deriveUserKey(phrase: String): ByteArray {
        val trimmed = phrase.trim()
        val spec = PBEKeySpec(trimmed.toCharArray(), SEED_SALT, PBKDF2_ITER, USER_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    fun deriveMkey(selfKey: ByteArray, peerKey: ByteArray): ByteArray {
        val canon = if (compareBytes(selfKey, peerKey) <= 0) {
            selfKey + peerKey
        } else {
            peerKey + selfKey
        }
        return hkdfSha256(canon, MKEY_SALT, MKEY_INFO, MKEY_BYTES)
    }

    fun fingerprint(userKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(userKey).copyOf(FINGERPRINT_BYTES)

    fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xor: length mismatch" }
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    fun aesGcmEncrypt(mkey: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(mkey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(mkey: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(mkey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }

    /** RFC 5869 HKDF-Extract-then-Expand,HMAC-SHA-256。 */
    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(counter.toByte()))
            t = mac.doFinal()
            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }
}

/**
 * 单条消息的封装/解封。seal 输出可直接塞进 WS content;tryOpen 返回 null 表示
 * 输入不是 sealed 格式(历史明文),调用方兜底展示即可。
 */
object SealedMessage {
    private val b64uEnc = java.util.Base64.getUrlEncoder().withoutPadding()
    private val b64uDec = java.util.Base64.getUrlDecoder()

    fun seal(mkey: ByteArray, plaintext: String): String {
        val nonce = CryptoUtils.randomNonce()
        val ct = CryptoUtils.aesGcmEncrypt(mkey, nonce, plaintext.toByteArray(Charsets.UTF_8))
        val json = """{"v":1,"n":"${b64uEnc.encodeToString(nonce)}","c":"${b64uEnc.encodeToString(ct)}"}"""
        return b64uEnc.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun tryOpen(mkey: ByteArray, sealed: String): String? = try {
        val json = String(b64uDec.decode(sealed), Charsets.UTF_8)
        val obj = org.json.JSONObject(json)
        if (obj.optInt("v", 0) != 1) {
            null
        } else {
            val nonce = b64uDec.decode(obj.getString("n"))
            val ct = b64uDec.decode(obj.getString("c"))
            String(CryptoUtils.aesGcmDecrypt(mkey, nonce, ct), Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }
}
