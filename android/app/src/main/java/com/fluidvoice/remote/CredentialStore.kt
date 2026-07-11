package com.fluidvoice.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(private val context: Context) {
    data class Connection(
        val instanceId: String,
        val baseUrl: String,
        val fingerprint: String,
        val credential: String,
    )

    private val preferences = context.getSharedPreferences("remote_connection", Context.MODE_PRIVATE)
    private val alias = "fluidvoice-remote-credential"

    fun save(connection: Connection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject()
            .put("instanceId", connection.instanceId)
            .put("baseUrl", connection.baseUrl)
            .put("fingerprint", connection.fingerprint)
            .put("credential", connection.credential)
            .toString()
            .toByteArray()
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Connection? = runCatching {
        val ciphertext = Base64.decode(preferences.getString("ciphertext", null), Base64.NO_WRAP)
        val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext)))
        Connection(
            json.getString("instanceId"),
            json.getString("baseUrl"),
            json.getString("fingerprint"),
            json.getString("credential"),
        )
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
