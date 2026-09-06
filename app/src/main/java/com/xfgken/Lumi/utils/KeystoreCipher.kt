package com.xfgken.Lumi.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM 加密器。
 *
 * 用于加密保存节点密码 / 混淆密码等敏感字段（文档第十九条：禁止明文保存）。
 * 密钥不可导出，仅本应用可解密。
 */
object KeystoreCipher {

    private const val KEY_ALIAS = "lumi_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密明文，输出 base64(iv + ciphertext) */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 加密失败不抛给上层流程（降级为空串表示不可用，可读性优先于崩溃）
            ""
        }
    }

    /** 解密 [encrypted]（encrypt 的输出） */
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encrypted, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}