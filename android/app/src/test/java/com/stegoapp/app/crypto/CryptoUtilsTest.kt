package com.stegoapp.app.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoUtilsTest {
    private val fixture: JSONObject by lazy {
        val url = javaClass.classLoader!!.getResource("e2ee-test-vectors.json")
            ?: error("e2ee-test-vectors.json missing under src/test/resources")
        JSONObject(url.readText(Charsets.UTF_8))
    }
    private val expected get() = fixture.getJSONObject("expected")

    @Test fun userKeyA_matchesFixture() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        assertEquals(expected.getString("user_key_A_hex"), uk.toHex())
    }

    @Test fun userKeyB_matchesFixture() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        assertEquals(expected.getString("user_key_B_hex"), uk.toHex())
    }

    @Test fun fingerprint_A_matches() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        assertEquals(expected.getString("fingerprint_A_hex"), CryptoUtils.fingerprint(uk).toHex())
    }

    @Test fun fingerprint_B_matches() {
        val uk = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        assertEquals(expected.getString("fingerprint_B_hex"), CryptoUtils.fingerprint(uk).toHex())
    }

    @Test fun mkey_symmetric_matchesFixture() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val m1 = CryptoUtils.deriveMkey(ukA, ukB)
        val m2 = CryptoUtils.deriveMkey(ukB, ukA)
        assertEquals(expected.getString("mkey_hex"), m1.toHex())
        assertArrayEquals(m1, m2)
    }

    @Test fun xorSeed_matchesFixture() {
        val a = fixture.getString("invite_A").toByteArray(Charsets.UTF_8)
        val b = fixture.getString("invite_B").toByteArray(Charsets.UTF_8)
        assertEquals(expected.getString("seed_hex"), CryptoUtils.xorBytes(a, b).toHex())
    }

    @Test fun aesGcm_reproducesCiphertextWithFixedNonce() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val mkey = CryptoUtils.deriveMkey(ukA, ukB)
        val nonce = fixture.getString("fixed_nonce_hex").hexToBytes()
        val pt = fixture.getString("plaintext_utf8").toByteArray(Charsets.UTF_8)
        val ct = CryptoUtils.aesGcmEncrypt(mkey, nonce, pt)
        assertEquals(expected.getString("ciphertext_with_tag_hex"), ct.toHex())
        val dec = CryptoUtils.aesGcmDecrypt(mkey, nonce, ct)
        assertArrayEquals(pt, dec)
    }

    @Test fun sealedMessage_roundTrips() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val mkey = CryptoUtils.deriveMkey(ukA, ukB)
        val sealed = SealedMessage.seal(mkey, "测试消息 🚀 Hello")
        val opened = SealedMessage.tryOpen(mkey, sealed)
        assertNotNull(opened)
        assertEquals("测试消息 🚀 Hello", opened)
    }

    @Test fun sealedMessage_nullForLegacyPlaintext() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val mkey = CryptoUtils.deriveMkey(ukA, ukB)
        assertNull(SealedMessage.tryOpen(mkey, "this is a plain message"))
    }

    @Test fun sealedMessage_nullOnKeyMismatch() {
        val ukA = CryptoUtils.deriveUserKey(fixture.getString("phrase_A"))
        val ukB = CryptoUtils.deriveUserKey(fixture.getString("phrase_B"))
        val mkey = CryptoUtils.deriveMkey(ukA, ukB)
        val sealed = SealedMessage.seal(mkey, "secret")
        val wrongKey = CryptoUtils.deriveUserKey("wrong phrase")
        assertNull(SealedMessage.tryOpen(wrongKey, sealed))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex: odd length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
