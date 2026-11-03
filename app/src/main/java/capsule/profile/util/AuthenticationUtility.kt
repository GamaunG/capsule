package capsule.profile.util

import android.content.Intent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
            // No key yet: bootstrap by sending a freshly generated one. The
            // force flag lets the other side replace a stale key from a
            // previous installation of this app.
            val newKey =
                try {
                    val keyGen = KeyGenerator.getInstance("HmacSHA256")
                    keyGen.init(256)
                    keyGen.generateKey().encoded.toHexString()
                } catch (e: java.security.NoSuchAlgorithmException) {
                    // HmacSHA256 is mandated for every Java implementation.
                    throw IllegalStateException("HmacSHA256 is unavailable", e)
                }
            LocalStorageManager.setString(LocalStorageManager.PREF_AUTH_KEY, newKey)
            intent.putExtra("auth_key", newKey)
            intent.putExtra("auth_force", true)
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
            val receivedKey = intent.getStringExtra("auth_key")
            if (isValidKey(receivedKey) && intent.getBooleanExtra("auth_force", false)) {
                // The other side was reinstalled and lost its data: accept the
                // fresh key it bootstrapped with. Only our own signature can
                // reach this activity, so the flag cannot be abused.
                CapsuleLog.i("Accepting a re-bootstrapped auth key from the other profile")
                LocalStorageManager.setString(LocalStorageManager.PREF_AUTH_KEY, receivedKey!!)
                return true
            }

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

    private fun sign(hexKey: String, action: String?, timestamp: Long, nonce: String?): String {
        // HmacSHA256 exists on every Android version; a missing algorithm or
        // an invalid key made from a validated 32-byte hex string cannot
        // happen, so the former crash-wrapper is gone.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexKey.hexToByteArray(), "HmacSHA256"))
        val actionBytes = action?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val nonceBytes = nonce?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val buffer =
            ByteBuffer.allocate(
                4 + actionBytes.size + Long.SIZE_BYTES + 4 + nonceBytes.size,
            )
        buffer.putInt(actionBytes.size)
        buffer.put(actionBytes)
        buffer.putLong(timestamp)
        buffer.putInt(nonceBytes.size)
        buffer.put(nonceBytes)
        return mac.doFinal(buffer.array()).toHexString()
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
}
