package com.nasframe.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_NAME = "nas_frame_secure"
        private const val KEY_ALIAS = "nas_frame_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_HOST = "nas_host"
        private const val KEY_SHARE = "nas_share"
        private const val KEY_USERNAME = "nas_username"
        private const val KEY_PASSWORD = "nas_password"
        private const val GCM_IV_LENGTH = 12
        private const val ENCRYPTED_PREFIX = "aes+"
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            createKey()
        }
    }

    private fun createKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Exception) {
            Log.e(TAG, "AndroidKeyStore unavailable — cannot encrypt credentials", e)
            null
        }
    }

    fun saveCredentials(host: String, share: String, username: String, password: String) {
        val encHost = encrypt(host) ?: return
        val encShare = encrypt(share) ?: return
        val encUsername = encrypt(username) ?: return
        val encPassword = encrypt(password) ?: return

        prefs.edit().apply {
            putString(KEY_HOST, encHost)
            putString(KEY_SHARE, encShare)
            putString(KEY_USERNAME, encUsername)
            putString(KEY_PASSWORD, encPassword)
            apply()
        }
    }

    fun getHost(): String? = prefs.getString(KEY_HOST, null)?.let { decrypt(it) }
    fun getShare(): String? = prefs.getString(KEY_SHARE, null)?.let { decrypt(it) }
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)?.let { decrypt(it) }
    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)?.let { decrypt(it) }

    fun hasCredentials(): Boolean =
        prefs.getString(KEY_HOST, null) != null &&
        prefs.getString(KEY_SHARE, null) != null &&
        prefs.getString(KEY_USERNAME, null) != null

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_SHARE)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    /**
     * Encrypt plaintext with AES-256-GCM.
     * @return Base64-encoded ciphertext prefixed with "aes+", or null if encryption fails.
     */
    private fun encrypt(plainText: String): String? {
        return try {
            val secretKey = getSecretKey()
                ?: throw IllegalStateException("AndroidKeyStore key not available")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed — credential NOT saved", e)
            null
        }
    }

    /**
     * Decrypt a value previously produced by [encrypt].
     * Supports legacy plaintext values (no "aes+" prefix) for migration.
     * @return Decrypted plaintext, or null if decryption fails.
     */
    private fun decrypt(storedValue: String): String? {
        // Handle legacy plaintext values (no encrypted prefix)
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) {
            Log.w(TAG, "Found legacy plaintext credential — will be migrated on next save")
            return storedValue
        }

        val encoded = storedValue.removePrefix(ENCRYPTED_PREFIX)
        return try {
            val secretKey = getSecretKey()
                ?: throw IllegalStateException("AndroidKeyStore key not available")
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) {
                Log.e(TAG, "Ciphertext too short for GCM")
                return null
            }
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }
}
