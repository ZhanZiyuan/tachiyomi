package eu.kanade.tachiyomi.ui.reader.narration

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NarrationSettingsActivity : BaseActivity() {

    private lateinit var settings: NarrationSettings
    private lateinit var enabled: SwitchCompat
    private lateinit var autoPlay: SwitchCompat
    private lateinit var readingOrder: Button
    private lateinit var playbackSpeed: SeekBar
    private lateinit var pauseBetweenLines: SeekBar
    private lateinit var geminiKey: EditText
    private lateinit var elevenLabsKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var speechModel: EditText
    private lateinit var voiceId: EditText
    private lateinit var status: TextView
    private var narrationReadingOrder = "auto"
    private var voiceRequest: Job? = null
    private var voicePicker: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)
        // API keys never appear in screenshots, the recent-apps preview, or saved view state.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = NarrationSettings(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            title = getString(R.string.narration_settings_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = getString(R.string.narration_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        form.addView(TextView(this).apply {
            setText(R.string.narration_settings_explanation)
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })
        enabled = form.addSwitch(R.string.narration_enable, settings.enabled)
        autoPlay = form.addSwitch(R.string.narration_auto_play, settings.autoPlay)
        narrationReadingOrder = settings.narrationReadingOrder
        readingOrder = form.addChoice(
            R.string.narration_reading_order,
            readingOrderLabel(narrationReadingOrder),
        )
        readingOrder.setOnClickListener {
            val values = listOf("auto", "right-to-left", "left-to-right", "top-to-bottom")
            val labels = values.map(::readingOrderLabel)
            AlertDialog.Builder(this)
                .setTitle(R.string.narration_reading_order)
                .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(narrationReadingOrder)) { dialog, which ->
                    narrationReadingOrder = values[which]
                    readingOrder.text = labels[which]
                    readingOrder.contentDescription = getString(
                        R.string.narration_control_value,
                        getString(R.string.narration_reading_order),
                        labels[which],
                    )
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        playbackSpeed = form.addRange(
            R.string.narration_playback_speed,
            settings.playbackSpeedPercent,
            min = 50,
            max = 200,
            step = 5,
        ) { percent -> getString(R.string.narration_speed_value, percent / 100f) }
        pauseBetweenLines = form.addRange(
            R.string.narration_pause_between_lines,
            settings.pauseBetweenLinesMs,
            min = 0,
            max = 3_000,
            step = 100,
        ) { millis -> getString(R.string.narration_pause_value, millis) }
        geminiKey = form.addField(R.string.narration_gemini_key, settings.geminiKey, secret = true)
        elevenLabsKey = form.addField(R.string.narration_elevenlabs_key, settings.elevenLabsKey, secret = true)
        geminiModel = form.addField(R.string.narration_gemini_model, settings.geminiModel)
        speechModel = form.addField(R.string.narration_speech_model, settings.speechModel)
        voiceId = form.addField(R.string.narration_default_voice, settings.defaultVoiceId)
        form.addView(TextView(this).apply {
            setText(R.string.narration_page_queue)
            setPadding(0, dp(16), 0, dp(8))
        })

        val loadVoicesButton = Button(this).apply { setText(R.string.narration_load_voices) }
        loadVoicesButton.setOnClickListener { loadVoices(loadVoicesButton) }
        form.addView(loadVoicesButton, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(8), 0, dp(8))
        }
        form.addView(status)
        form.addView(Button(this).apply {
            setText(R.string.narration_save)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(-1, -2))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun LinearLayout.addSwitch(label: Int, value: Boolean): SwitchCompat {
        return SwitchCompat(this@NarrationSettingsActivity).apply {
            setText(label)
            isChecked = value
            minHeight = dp(56)
            setPadding(0, dp(4), 0, dp(4))
            addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun LinearLayout.addField(label: Int, value: String, secret: Boolean = false): EditText {
        val labelView = TextView(this@NarrationSettingsActivity).apply {
            id = View.generateViewId()
            setText(label)
            setPadding(0, dp(16), 0, dp(4))
        }
        addView(labelView)
        val field = EditText(this@NarrationSettingsActivity).apply {
            id = View.generateViewId()
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setText(value)
            isSaveEnabled = false
            minHeight = dp(48)
            contentDescription = getString(label)
        }
        labelView.labelFor = field.id
        addView(field, LinearLayout.LayoutParams(-1, -2))
        return field
    }

    private fun LinearLayout.addChoice(label: Int, value: String): Button {
        addView(TextView(this@NarrationSettingsActivity).apply {
            setText(label)
            setPadding(0, dp(16), 0, dp(4))
        })
        val button = Button(this@NarrationSettingsActivity).apply {
            text = value
            minHeight = dp(48)
            contentDescription = getString(R.string.narration_control_value, getString(label), value)
        }
        addView(button, LinearLayout.LayoutParams(-1, -2))
        return button
    }

    private fun readingOrderLabel(value: String): String = getString(
        when (normalizeNarrationReadingOrder(value)) {
            "right-to-left" -> R.string.narration_reading_order_rtl
            "left-to-right" -> R.string.narration_reading_order_ltr
            "top-to-bottom" -> R.string.narration_reading_order_ttb
            else -> R.string.narration_reading_order_auto
        },
    )

    private fun LinearLayout.addRange(
        label: Int,
        value: Int,
        min: Int,
        max: Int,
        step: Int,
        formatValue: (Int) -> String,
    ): SeekBar {
        val title = TextView(this@NarrationSettingsActivity).apply {
            setPadding(0, dp(16), 0, dp(4))
        }
        val slider = SeekBar(this@NarrationSettingsActivity).apply {
            this.max = (max - min) / step
            progress = ((value.coerceIn(min, max) - min) / step)
            minHeight = dp(48)
        }
        fun refreshLabel() {
            val current = min + slider.progress * step
            val description = getString(
                R.string.narration_control_value,
                getString(label),
                formatValue(current),
            )
            title.text = description
            slider.contentDescription = description
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = refreshLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        refreshLabel()
        addView(title)
        addView(slider, LinearLayout.LayoutParams(-1, -2))
        return slider
    }

    private fun save() {
        val requiredFields = listOf(geminiKey, elevenLabsKey, geminiModel, speechModel, voiceId)
        if (enabled.isChecked) {
            val emptyField = requiredFields.firstOrNull { it.text.isNullOrBlank() }
            if (emptyField != null) {
                emptyField.error = getString(R.string.narration_required)
                emptyField.requestFocus()
                return
            }
        }
        try {
            settings.geminiKey = geminiKey.text.toString()
            settings.elevenLabsKey = elevenLabsKey.text.toString()
            settings.geminiModel = geminiModel.text.toString()
            settings.speechModel = speechModel.text.toString()
            settings.defaultVoiceId = voiceId.text.toString()
            settings.autoPlay = autoPlay.isChecked
            settings.narrationReadingOrder = narrationReadingOrder
            settings.playbackSpeedPercent = 50 + playbackSpeed.progress * 5
            settings.pauseBetweenLinesMs = pauseBetweenLines.progress * 100
            settings.enabled = enabled.isChecked
            Toast.makeText(this, R.string.narration_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: Exception) {
            status.setText(R.string.narration_credentials_save_error)
        }
    }

    private fun loadVoices(button: Button) {
        val key = elevenLabsKey.text.toString().trim()
        if (key.isBlank()) {
            elevenLabsKey.error = getString(R.string.narration_required)
            return
        }
        button.isEnabled = false
        status.setText(R.string.narration_loading_voices)
        voiceRequest = lifecycleScope.launch {
            try {
                val voices = NarrationApi().voices(key)
                if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@launch
                }
                if (voices.isEmpty()) {
                    status.setText(R.string.narration_no_voices)
                } else {
                    status.setText(R.string.narration_voices_loaded)
                    val picker = AlertDialog.Builder(this@NarrationSettingsActivity)
                        .setTitle(R.string.narration_choose_voice)
                        .setItems(voices.map { it.name }.toTypedArray()) { _, which ->
                            voiceId.setText(voices[which].id)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                    voicePicker?.dismiss()
                    voicePicker = picker
                    picker.setOnDismissListener { if (voicePicker === picker) voicePicker = null }
                    picker.show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.setText(R.string.narration_voice_load_error)
            } finally {
                if (!isDestroyed) button.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        voiceRequest?.cancel()
        voiceRequest = null
        voicePicker?.dismiss()
        voicePicker = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, NarrationSettingsActivity::class.java)
    }
}
