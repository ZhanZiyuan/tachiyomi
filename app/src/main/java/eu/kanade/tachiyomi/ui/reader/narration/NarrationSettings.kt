package eu.kanade.tachiyomi.ui.reader.narration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import eu.kanade.tachiyomi.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Narration preferences are separate from the preferences included in manga backups. */
class NarrationSettings(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences("narration_settings", Context.MODE_PRIVATE)
    private val credentials = context.applicationContext.getSharedPreferences("narration_credentials", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    var autoPlay: Boolean
        get() = preferences.getBoolean("auto_play", true)
        set(value) { preferences.edit().putBoolean("auto_play", value).apply() }

    /** Percentage so the controls round predictably and values persist without float drift. */
    var playbackSpeedPercent: Int
        get() = preferences.getInt("playback_speed_percent", 100).coerceIn(50, 200)
        set(value) { preferences.edit().putInt("playback_speed_percent", value.coerceIn(50, 200)).apply() }

    var pauseBetweenLinesMs: Int
        get() = preferences.getInt("pause_between_lines_ms", 250).coerceIn(0, 3_000)
        set(value) { preferences.edit().putInt("pause_between_lines_ms", value.coerceIn(0, 3_000)).apply() }

    var geminiKey: String
        get() = readSecret("gemini", BuildConfig.NARRATION_GEMINI_KEY)
        set(value) { writeSecret("gemini", value.trim()) }

    var elevenLabsKey: String
        get() = readSecret("elevenlabs", BuildConfig.NARRATION_ELEVENLABS_KEY)
        set(value) { writeSecret("elevenlabs", value.trim()) }

    var geminiModel: String
        get() = preferences.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) { preferences.edit().putString("gemini_model", value.trim()).apply() }

    var speechModel: String
        get() = preferences.getString("speech_model", "eleven_v3") ?: "eleven_v3"
        set(value) { preferences.edit().putString("speech_model", value.trim()).apply() }

    var defaultVoiceId: String
        get() = preferences.getString("default_voice", "JBFqnCBsd6RMkjVDRZzb") ?: "JBFqnCBsd6RMkjVDRZzb"
        set(value) { preferences.edit().putString("default_voice", value.trim()).apply() }

    fun geminiUsage(): GeminiUsageTotals = GeminiUsageTotals(
        requests = preferences.getLong("gemini_usage_requests", 0).coerceAtLeast(0),
        inputTokens = preferences.getLong("gemini_usage_input_tokens", 0).coerceAtLeast(0),
        outputTokens = preferences.getLong("gemini_usage_output_tokens", 0).coerceAtLeast(0),
        estimatedCostNanosUsd = preferences.getLong("gemini_usage_cost_nanos_usd", 0).coerceAtLeast(0),
        hasKnownPrice = preferences.getBoolean("gemini_usage_has_known_price", false),
    )

    /** Records response-reported token usage only after Gemini has returned a valid transcript. */
    fun recordGeminiUsage(model: String, usage: GeminiUsage) {
        val current = geminiUsage()
        val estimate = geminiPaidTierEstimateNanos(model, usage.promptTokens, usage.outputTokens)
        preferences.edit()
            .putLong("gemini_usage_requests", safeAdd(current.requests, 1))
            .putLong("gemini_usage_input_tokens", safeAdd(current.inputTokens, usage.promptTokens.coerceAtLeast(0)))
            .putLong("gemini_usage_output_tokens", safeAdd(current.outputTokens, usage.outputTokens.coerceAtLeast(0)))
            .putLong(
                "gemini_usage_cost_nanos_usd",
                safeAdd(current.estimatedCostNanosUsd, estimate ?: 0),
            )
            .putBoolean("gemini_usage_has_known_price", current.hasKnownPrice || estimate != null)
            .apply()
    }

    fun isConfigured(): Boolean = geminiKey.isNotBlank() && elevenLabsKey.isNotBlank() &&
        geminiModel.isNotBlank() && speechModel.isNotBlank() && defaultVoiceId.isNotBlank()

    private fun readSecret(name: String, default: String): String {
        if (!credentials.contains(name)) return default
        return runCatching {
            val parts = credentials.getString(name, "").orEmpty().split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun writeSecret(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(credentials.edit().putString(name, stored).commit()) { "Unable to save API credentials." }
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "tachiyomi_narration_api_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val keyLock = Any()
    }
}
