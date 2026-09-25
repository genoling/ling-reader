package com.lreader.sync

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 生词本同步的**端到端加密**。
 *
 * 密钥不从服务器取，而是由**同步码里的口令**现场派生：云端（GitHub 私有仓库）只存密文，
 * 连仓库主人都读不出用户生词。换设备只要输入同一个同步码，密钥自然一致。
 *
 * 算法：`PBKDF2-HMAC-SHA256(口令, salt = "lingreader:" + 同步id, 12 万轮)` → `AES-256-GCM`。
 * 输出：`[12B IV][密文 || 16B tag]` 整体 Base64（无换行，可直接放进 Git 文件）。
 */
object SyncCrypto {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 口令 + 同步 id 一起派生密钥：不同用户用同一口令也不会撞出同一密钥 */
    private fun deriveKey(passphrase: String, syncId: String): SecretKeySpec {
        val salt = "lingreader:$syncId".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** 明文 → Base64 密文 */
    fun encrypt(plain: String, passphrase: String, syncId: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, syncId),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(enc, 0, out, iv.size, enc.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Base64 密文 → 明文；口令不对或数据损坏返回 null（调用方据此提示"同步码不正确"） */
    fun decrypt(token: String, passphrase: String, syncId: String): String? = try {
        val all = Base64.decode(token.trim(), Base64.DEFAULT)
        if (all.size <= IV_BYTES) {
            null
        } else {
            val iv = all.copyOfRange(0, IV_BYTES)
            val body = all.copyOfRange(IV_BYTES, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, syncId),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(body), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    /** 生成口令：排除 0/O/1/I 等易混字符，方便手输或抄写 */
    fun newPassphrase(): String = randomFrom("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", 32)

    /** 生成同步 id（在仓库里就是用户目录名，短一点便于辨认） */
    fun newSyncId(): String = randomFrom("abcdefghijkmnpqrstuvwxyz23456789", 12)

    private fun randomFrom(alphabet: String, length: Int): String {
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
    }
}
