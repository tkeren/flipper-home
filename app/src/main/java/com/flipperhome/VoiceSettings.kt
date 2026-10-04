package com.flipperhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class VoiceConfig(val enabled: Boolean = false, val url: String = "", val bridgeId: String, val token: String = "")

/** No token is kept in home.json or exported to the Home Assistant action registry. */
internal class VoiceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): VoiceConfig {
        val bridge = preferences.getString("bridge", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("bridge", it).commit() }
        val token = preferences.getString("token", null)?.let { encoded -> runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
                .doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrDefault("") }.orEmpty()
        return VoiceConfig(preferences.getBoolean("enabled", false), preferences.getString("url", "").orEmpty(), bridge, token)
    }
    fun save(url: String, token: String, enabled: Boolean) {
        homeAssistantSocketUrl(url)
        require(token.isNotBlank() && token.length <= 8192 && token.none(Char::isWhitespace)) { "Enter a Home Assistant long-lived access token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("url", url.trim().trimEnd('/')).putString("token", encrypted).putBoolean("enabled", enabled).commit()) { "Could not save Home Assistant settings" }
    }
    fun enabled(value: Boolean) { check(preferences.edit().putBoolean("enabled", value).commit()) }
    fun forget() { check(preferences.edit().remove("url").remove("token").putBoolean("enabled", false).commit()) }
    companion object { private const val KEY = "flipper-home-home-assistant-token" }
}
