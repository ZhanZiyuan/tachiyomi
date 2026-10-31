package eu.kanade.tachiyomi.ui.reader.narration

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/** Reader-session controls; API credentials are only displayed in the separate secure settings screen. */
fun showNarrationDialog(activity: ReaderActivity, controller: NarrationController) {
    if (activity.isFinishing || activity.isDestroyed) return

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(12))
    }
    val enable = SwitchCompat(activity).apply {
        text = "Enable AI narration"
        minHeight = dp(52)
        isChecked = controller.state.value.enabled
    }
    val status = TextView(activity).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(0, dp(8), 0, dp(12))
    }
    val speaking = TextView(activity).apply {
        textSize = 16f
        setPadding(0, 0, 0, dp(12))
    }
    panel.addView(enable, LinearLayout.LayoutParams(-1, -2))
    panel.addView(TextView(activity).apply {
        text = "Gemini analyzes selected pages and ElevenLabs voices the dialogue. Generation uses your API quota."
        textSize = 13f
        setPadding(0, 0, 0, dp(8))
    }, LinearLayout.LayoutParams(-1, -2))
    panel.addView(status, LinearLayout.LayoutParams(-1, -2))
    panel.addView(speaking, LinearLayout.LayoutParams(-1, -2))

    fun addButton(label: String, action: () -> Unit): Button = Button(activity).apply {
        text = label
        minHeight = dp(48)
        setOnClickListener { action() }
        panel.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    val play = addButton("Play") { controller.playOrPause() }
    val replay = addButton("Replay page") { controller.replay() }
    val retrySkipped = addButton("Retry skipped pages") { controller.retrySkippedPages() }
    val childDialogs = mutableSetOf<AlertDialog>()
    val requests = mutableSetOf<Job>()
    var observing: Job? = null
    var updatingSwitch = false
    var active = true
    lateinit var dialog: AlertDialog
    lateinit var destroyObserver: LifecycleEventObserver

    fun canShow(): Boolean = active && dialog.isShowing && !activity.isFinishing && !activity.isDestroyed
    fun showChild(child: AlertDialog) {
        if (!canShow()) return
        childDialogs.add(child)
        child.setOnDismissListener { childDialogs.remove(child) }
        child.show()
    }

    val correct = addButton("Correct speakers") {
        val lines = controller.state.value.lines
        if (lines.isNotEmpty()) {
            val names = controller.state.value.characters.associate { it.id to it.name }
            val labels = lines.map { line ->
                val name = names[line.characterId] ?: "Narrator / unknown"
                "$name: ${line.text.take(100)}"
            }
            showChild(
                AlertDialog.Builder(activity)
                    .setTitle("Choose dialogue to correct")
                    .setItems(labels.toTypedArray()) { _, lineIndex ->
                        val characters = controller.state.value.characters.filterNot { it.id == "narrator" }
                        val speakerIds = listOf("narrator") + characters.map { it.id }
                        val speakerNames = listOf("Narrator / unknown") + characters.map { it.name }
                        showChild(
                            AlertDialog.Builder(activity)
                                .setTitle("Speaker for: ${lines[lineIndex].text.take(70)}")
                                .setItems(speakerNames.toTypedArray()) { _, speakerIndex ->
                                    controller.assignSpeaker(lines[lineIndex], speakerIds[speakerIndex])
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .create(),
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create(),
            )
        }
    }

    val voices = addButton("Character voices") {
        val characters = controller.state.value.characters.filterNot { it.id == "narrator" }
        if (characters.isNotEmpty()) {
            showChild(
                AlertDialog.Builder(activity)
                    .setTitle("Choose a character")
                    .setItems(characters.map { it.name }.toTypedArray()) { _, characterIndex ->
                        val character = characters[characterIndex]
                        status.text = "Loading voices for ${character.name}…"
                        val request = activity.lifecycleScope.launch {
                            try {
                                val available = controller.voices()
                                if (!canShow()) return@launch
                                if (available.isEmpty()) {
                                    status.text = "No voices were returned. Check ElevenLabs settings."
                                } else {
                                    showChild(
                                        AlertDialog.Builder(activity)
                                            .setTitle("Voice for ${character.name}")
                                            .setSingleChoiceItems(
                                                available.map { it.name }.toTypedArray(),
                                                available.indexOfFirst { it.id == character.voiceId },
                                            ) { picker, voiceIndex ->
                                                controller.assignVoice(character, available[voiceIndex].id)
                                                picker.dismiss()
                                            }
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .create(),
                                    )
                                    status.text = controller.state.value.message
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                if (canShow()) {
                                    status.text = "Could not load voices. Check the key, voice-read permission, and connection in settings."
                                }
                            }
                        }
                        requests.add(request)
                        request.invokeOnCompletion { requests.remove(request) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create(),
            )
        }
    }
    val autoMatchVoices = addButton("Auto-match character voices") { controller.autoMatchCharacterVoices() }
    val usage = addButton("Usage & cost") {
        status.text = "Loading current API usage…"
        val request = activity.lifecycleScope.launch {
            try {
                val summary = controller.usageSummary()
                if (!canShow()) return@launch
                val gemini = summary.gemini
                val eleven = summary.elevenLabs
                val geminiCost = if (gemini.hasKnownPrice) {
                    String.format(Locale.US, "\$%.6f", gemini.estimatedCostNanosUsd / 1_000_000_000.0)
                } else {
                    "Unavailable for this Gemini model"
                }
                val message = buildString {
                    appendLine("Gemini — tracked by this app since this feature was added")
                    appendLine("Requests: ${String.format(Locale.US, "%,d", gemini.requests)}")
                    appendLine("Input: ${String.format(Locale.US, "%,d", gemini.inputTokens)} tokens")
                    appendLine("Output: ${String.format(Locale.US, "%,d", gemini.outputTokens)} tokens")
                    appendLine("Estimated paid-tier list-price cost: $geminiCost")
                    appendLine()
                    appendLine("Exact Google project spending is not exposed to an API key. Check AI Studio or Google Cloud Billing for the full project total, which can differ because of caching, your tier, tax, or other apps.")
                    appendLine()
                    appendLine("ElevenLabs — live account balance")
                    appendLine("Plan: ${eleven.tier}")
                    appendLine("Used: ${String.format(Locale.US, "%,d", eleven.creditsUsed)} credits")
                    appendLine("Remaining: ${String.format(Locale.US, "%,d", eleven.creditsRemaining)} credits")
                    appendLine("Period limit: ${String.format(Locale.US, "%,d", eleven.creditLimit)} credits")
                    if (eleven.overageAmount.isNotBlank() && eleven.overageAmount != "0") {
                        appendLine("Current overage: ${eleven.currency.uppercase()} ${eleven.overageAmount}")
                    }
                }
                showChild(
                    AlertDialog.Builder(activity)
                        .setTitle("Narration usage & cost")
                        .setMessage(message)
                        .setPositiveButton("Close", null)
                        .create(),
                )
                status.text = controller.state.value.message
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (canShow()) {
                    status.text = "Could not load usage. Check your API keys and connection."
                }
            }
        }
        requests.add(request)
        request.invokeOnCompletion { requests.remove(request) }
    }

    val resetChapter = addButton("Reset chapter narration") {
        showChild(
            AlertDialog.Builder(activity)
                .setTitle("Reset this chapter?")
                .setMessage(
                    "Delete this chapter's generated audio, transcript, and speaker corrections? " +
                        "Character voices are kept. If narration is enabled, the chapter will be analyzed again as you read. " +
                        "Regeneration uses API quota and may incur charges.",
                )
                .setPositiveButton("Reset chapter") { _, _ -> controller.resetChapter() }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }
    val resetCharacters = addButton("Reset this manga's characters") {
        showChild(
            AlertDialog.Builder(activity)
                .setTitle("Reset all characters for this manga?")
                .setMessage(
                    "Delete the character references, assigned voices, and generated narration for this manga? " +
                        "The characters will be identified again. Regeneration uses API quota and may incur charges.",
                )
                .setPositiveButton("Reset characters") { _, _ -> controller.resetCharacters() }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    val scroll = ScrollView(activity).apply { addView(panel) }
    dialog = AlertDialog.Builder(activity)
        .setTitle("AI narration")
        .setView(scroll)
        .setNeutralButton("Settings") { _, _ ->
            activity.startActivity(NarrationSettingsActivity.newIntent(activity))
        }
        .setNegativeButton("Close", null)
        .create()

    enable.setOnCheckedChangeListener { _, value ->
        if (!updatingSwitch) controller.setEnabled(value)
    }
    fun cleanup() {
        if (!active) return
        active = false
        activity.lifecycle.removeObserver(destroyObserver)
        observing?.cancel()
        requests.toList().forEach { it.cancel() }
        requests.clear()
        childDialogs.toList().forEach { it.dismiss() }
        childDialogs.clear()
    }
    dialog.setOnDismissListener { cleanup() }
    destroyObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            cleanup()
            dialog.dismiss()
        }
    }
    activity.lifecycle.addObserver(destroyObserver)
    dialog.show()
    observing = activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.state.collect { state ->
                if (!canShow()) return@collect
                updatingSwitch = true
                enable.isChecked = state.enabled
                updatingSwitch = false
                dialog.setTitle(if (state.pageIndex >= 0) "AI narration · Page ${state.pageIndex + 1}" else "AI narration")
                status.text = state.message
                val current = state.lines.firstOrNull { it.id == state.currentLineId }
                speaking.text = current?.let { line ->
                    val name = state.characters.firstOrNull { it.id == line.characterId }?.name ?: "Narrator"
                    "${if (line.innerThought) "Thought — " else ""}$name: ${line.text}"
                }.orEmpty()
                speaking.visibility = if (current == null) View.GONE else View.VISIBLE
                play.text = if (state.playing) {
                    "Pause"
                } else if (state.preparingChapter && !state.pageReady) {
                    if (state.paused) "Unmute when ready" else "Mute when ready"
                } else {
                    "Play"
                }
                play.isEnabled = true
                replay.isEnabled = state.enabled && state.lines.isNotEmpty() && !state.busy
                retrySkipped.visibility = if (state.failedPages.isEmpty()) View.GONE else View.VISIBLE
                retrySkipped.isEnabled = state.enabled && state.failedPages.isNotEmpty() && !state.busy
                retrySkipped.text = if (state.failedPages.size == 1) {
                    "Retry skipped page"
                } else {
                    "Retry ${state.failedPages.size} skipped pages"
                }
                correct.isEnabled = state.lines.isNotEmpty() && !state.busy
                voices.isEnabled = state.characters.any { it.id != "narrator" } && !state.busy
                autoMatchVoices.isEnabled = state.characters.any { it.id != "narrator" } && !state.busy
                usage.isEnabled = controller.settings.isConfigured()
                resetChapter.isEnabled = state.pageIndex >= 0
                resetCharacters.isEnabled = state.characters.isNotEmpty()
            }
        }
    }
}
