package capsule.profile.util

import android.content.Intent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs Intents crossing the profile boundary so only our own app can invoke
 * its high-privilege actions through the system's IntentForwarderActivity.
 * The exported activity is separately protected by a signature-level
 * permission; this HMAC is defense in depth and must never be the sole
 * authorization mechanism.
 */
object AuthenticationUtility {
    private const val TIMESTAMP_TOLERANCE_MS = 30 * 1000L

    @Synchronized
    fun signIntent(intent: Intent) {
        val key = LocalStorageManager.getString(LocalStorageManager.PREF_AUTH_KEY)
        if (!isValidKey(key)) {
            // No key yet: bootstrap by sending a freshly generated one.
            val newKey =
                try {
                    val keyGen = KeyGenerator.getInstance("HmacSHA256")
                    keyGen.init(256)
                    bytesToHex(keyGen.generateKey().encoded)
                } catch (e: NoSuchAlgorithmException) {
                    throw RuntimeException("WTF?")
                }
            LocalStorageManager.setString(LocalStorageManager.PREF_AUTH_KEY, newKey)
            intent.putExtra("auth_key", newKey)
        } else {
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            intent.putExtra("timestamp", timestamp)
            intent.putExtra("nonce", nonce)
            intent.putExtra("signature", sign(key!!, intent.action, timestamp, nonce))
        }
    }

    @Synchronized
    fun checkIntent(intent: Intent): Boolean {
        val key = LocalStorageManager.getString(LocalStorageManager.PREF_AUTH_KEY)
        if (!isValidKey(key)) {
            // The activity's signature-level permission authenticates the sender
            // during bootstrap. Never accept malformed or missing keys.
            val receivedKey = intent.getStringExtra("auth_key")
            if (isValidKey(receivedKey)) {
                LocalStorageManager.setString(LocalStorageManager.PREF_AUTH_KEY, receivedKey!!)
                return true
            }
            return false
        } else {
            val now = System.currentTimeMillis()
            val intentTimestamp = intent.getLongExtra("timestamp", 0)
            val age = now - intentTimestamp
            if (age < 0 || age > TIMESTAMP_TOLERANCE_MS) return false

            val nonce = intent.getStringExtra("nonce")
            if (nonce == null || nonce.length > 128) return false

            val expected = sign(key!!, intent.action, intentTimestamp, nonce)
            val received = intent.getStringExtra("signature")
            val valid =
                received != null &&
                    MessageDigest.isEqual(
                        expected.toByteArray(StandardCharsets.UTF_8),
                        received.toByteArray(StandardCharsets.UTF_8),
                    )
            return valid && consumeNonce(nonce, now)
        }
    }

    @Synchronized
    fun reset() {
        LocalStorageManager.remove(LocalStorageManager.PREF_AUTH_KEY)
        LocalStorageManager.remove(LocalStorageManager.PREF_AUTH_NONCES)
    }

    private fun sign(hexKey: String, action: String?, timestamp: Long, nonce: String?): String =
        try {
            val keySpec = SecretKeySpec(hexStringToByteArray(hexKey), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val actionBytes = action?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val nonceBytes = nonce?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val buffer =
                ByteBuffer.allocate(
                    4 + actionBytes.size + java.lang.Long.BYTES + 4 + nonceBytes.size,
                )
            buffer.putInt(actionBytes.size)
            buffer.put(actionBytes)
            buffer.putLong(timestamp)
            buffer.putInt(nonceBytes.size)
            buffer.put(nonceBytes)
            bytesToHex(mac.doFinal(buffer.array()))
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("WTF?")
        } catch (e: InvalidKeyException) {
            throw RuntimeException("WTF?")
        }

    private fun isValidKey(key: String?): Boolean =
        key != null && key.length == 64 && key.matches(Regex("[0-9A-Fa-f]{64}"))

    /** Rejects replayed nonces; entries expire with the timestamp tolerance. */
    private fun consumeNonce(nonce: String, now: Long): Boolean {
        val stored = LocalStorageManager.getString(LocalStorageManager.PREF_AUTH_NONCES)
        val retained = StringBuilder()
        var alreadySeen = false
        if (!stored.isNullOrEmpty()) {
            for (entry in stored.split(",")) {
                val separator = entry.lastIndexOf(':')
                if (separator <= 0) continue
                val savedNonce = entry.substring(0, separator)
                val savedAt = entry.substring(separator + 1).toLongOrNull() ?: continue
                if (now - savedAt <= TIMESTAMP_TOLERANCE_MS) {
                    if (savedNonce == nonce) alreadySeen = true
                    if (retained.isNotEmpty()) retained.append(',')
                    retained.append(entry)
                }
            }
        }
        if (alreadySeen) return false
        if (retained.isNotEmpty()) retained.append(',')
        retained.append(nonce).append(':').append(now)
        LocalStorageManager.setString(LocalStorageManager.PREF_AUTH_NONCES, retained.toString())
        return true
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in data.indices) {
            data[i] =
                (
                    (Character.digit(hex[i * 2], 16) shl 4) +
                        Character.digit(hex[i * 2 + 1], 16)
                ).toByte()
        }
        return data
    }
}
