package de.kf.blitztext

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Provider(val label: String) { OPENAI("OpenAI"), GROQ("Groq") }
enum class GroqModel(val id: String) {
    LARGE_V3("whisper-large-v3"),
    LARGE_V3_TURBO("whisper-large-v3-turbo")
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun observeMode(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeModeObserver(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", Mode.BLITZTEXT.name)!!) }.getOrDefault(Mode.BLITZTEXT)
        set(value) { prefs.edit().putString("mode", value.name).apply() }

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", Provider.OPENAI.name)!!) }.getOrDefault(Provider.OPENAI)
        set(value) { prefs.edit().putString("provider", value.name).apply() }

    var groqModel: GroqModel
        get() = runCatching { GroqModel.valueOf(prefs.getString("groq_model", GroqModel.LARGE_V3_TURBO.name)!!) }
            .getOrDefault(GroqModel.LARGE_V3_TURBO)
        set(value) { prefs.edit().putString("groq_model", value.name).apply() }

    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(value) { prefs.edit().putInt("bubble_y", value).apply() }

    var bubbleRight: Boolean
        get() = prefs.getBoolean("bubble_right", true)
        set(value) { prefs.edit().putBoolean("bubble_right", value).apply() }

    fun apiKey(provider: Provider = this.provider): String {
        // Der bisherige OpenAI-Schlüssel bleibt unter seinem alten Namen lesbar.
        val data = prefs.getString(keyName(provider), null) ?: return ""
        return try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun setApiKey(value: String, provider: Provider = this.provider) {
        if (value.isBlank()) {
            prefs.edit().remove(keyName(provider)).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(keyName(provider), Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun keyName(provider: Provider) = if (provider == Provider.OPENAI) "api_key" else "groq_api_key"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("blitztext_api_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("blitztext_api_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
