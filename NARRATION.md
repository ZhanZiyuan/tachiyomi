# Tachiyomi Narration

This personal Android build calls Gemini and ElevenLabs directly from the phone. No backend or local AI model is required. Generation needs an internet connection; saved transcripts and generated clips remain in the app's private storage for reuse.

## Using the app

1. Open **Settings → Reader → AI narration**, or tap the voice icon in the reader toolbar and select **Settings**.
2. Check the API credentials, Gemini model, ElevenLabs model, and default voice. This personal APK can include the local `.env` defaults. Replacement keys are stored using Android Keystore encryption and are excluded from manga backups.
3. Enable narration and save. A manga with no per-manga reader choice automatically uses **Vertical paged** reading so one page stays in view. Each page is analyzed separately: the visible page is first, and the rest continues in the background. Both services use the account's API quota.
4. A small floating control shows preparation from **0% to 100%**. Drag it anywhere within the reader; its position is remembered. Tap it to pause or resume narration. Muting does not stop chapter preparation. An error changes the control to **Retry**.
5. Once the chapter is ready, paged reading plays dialogue in reading order for the selected page. In vertical scrolling, dialogue is unlocked as it reaches the central reading zone. Play, Pause, and Replay are also available from the toolbar voice icon.
6. Use **Correct speakers** to change the character assigned to a dialogue bubble. New characters are matched to distinct ElevenLabs voices using their available adult/child and female/male labels. Use **Auto-match character voices** to update an older manga bank, or **Character voices** to choose any voice yourself; manual choices are preserved. If Gemini or ElevenLabs cannot prepare one page, the reader skips it, keeps preparing the others, and shows **Retry skipped pages** when the chapter queue finishes.
7. **Reset chapter narration** deletes that chapter's transcript, speaker corrections, and audio while keeping the character bank. **Reset this manga's characters** deletes the manga's character bank, reference crops, and all generated narration. Regeneration uses API quota.

## Processing and playback

- The visible page is analyzed first and each Gemini request contains only that one page. As soon as its first top-to-bottom dialogue clip is generated, narration can start while the remaining dialogue and pages continue preparing in the background. Moving to another page makes that page the next priority. Required images are loaded through the reader's existing page loader. Ordinary reading remains available during preparation.
- **Narration reading order** is separate from reader paging direction. Choose **Auto** to let Gemini detect each page's visual panel flow, or force **right-to-left**, **left-to-right**, or **top-to-bottom**. A forced choice applies to both panels and bubbles and cannot be overridden by Gemini. Pages analyzed with a different choice are reanalyzed before reuse.
- Narration only changes a manga whose saved reader mode is **Default**. A reader mode you explicitly set for a manga remains untouched.
- Progress counts completed page analysis (0–40%) and completed speech clips (40–99%). Only a fully committed chapter reaches 100%, including a chapter with no spoken dialogue. Cached work is reused rather than billed again.
- Long images are split into overlapping slices before upload. Overlapping dialogue is deduplicated and coordinates are mapped back to the source page.
- The series character bank carries stable IDs, descriptions, conservative fictional-character voice profiles, reference crops, and user-selected voice IDs into later requests. When ElevenLabs supplies labelled voices, adult/child and female/male profiles are assigned different matching voices; uncertain speakers use the narrator voice. A manual voice choice always overrides automatic matching.
- Gemini marks internal monologue separately from dialogue. When the thinker can be identified from the panel or story context, the thought uses that character's voice and a light device-local echo/reverb cue; uncertain thoughts use the narrator voice.
- Narration Settings includes a playback-speed control (0.50x–2.00x) and a pause-after-dialogue control (0–3 seconds). They affect locally cached and newly generated audio immediately; regenerating a chapter is not required.
- While a chapter is open, its single background queue remains active as you turn pages. It analyzes and generates audio for the page being read first, then following pages in reading order, and finally backfills any earlier pages until the whole chapter is ready.
- Gemini returns panel order separately from bubble order. For right-to-left pages it must finish the right panel before the left one, then read bubbles within each panel. Reset an already analyzed chapter to apply an improved panel transcript.
- The narration dialog's Usage & cost button shows Gemini response-token totals and a paid-tier list-price estimate tracked by this app from the time this feature is installed. It also refreshes ElevenLabs' live credits used, remaining, and period limit. A Gemini API key cannot read the full Google project invoice; that remains available in AI Studio or Google Cloud Billing.
- Speech is cached by manga/chapter, text, model, voice, emotion, and delivery. A correction prepares the updated chapter while reusing unaffected clips. An empty ElevenLabs response is retried once automatically; a page that still fails is added to the retry list instead of blocking the chapter.
- Playback stops on leaving the reader, app backgrounding, audio-focus loss, or headphone disconnection. Fast scrolling skips stale speech; small scroll movements do not replay completed bubbles. Preparation continues while scrolling or muted, stops on leaving the reader, and resumes from cached work when reopened.
- Split spreads, rotated pages, and cropped borders use page-level playback because their layout no longer matches source coordinates. AI transcription, speaker association, emotion, and positions can be wrong; the correction and reset controls remain available.

## Build

The Android application ID is separate from the original Tachiyomi app. Original-app automatic updates are disabled for this fork.

Put optional personal defaults in the ignored root `.env` using `.env.example` as the template. No credential values belong in tracked source. An APK containing embedded defaults is a private artifact: these defaults can be extracted from the APK. A redistributable build should omit them and use the on-device settings form instead.

Build the universal debug APK with Java 17 or a compatible JDK (this build uses Java 21) and Android SDK 36:

```powershell
.\gradlew.bat :app:assembleStandardDebug
```

The APK is generated under `app/build/outputs/apk/standard/debug/`. The universal APK supports ARM and x86 devices. The minimum Android version is 8.0 (API 26), matching the current extension runtime.

Focused JVM tests cover transcript sanitization, image-coordinate mapping, cache-path boundaries, and playback visibility decisions. Provider availability, credits, and quotas are separate from APK compilation.

## Troubleshooting

If Gemini reports HTTP 402 or depleted prepayment credits, add credit to the API project in Google AI Studio or enter a key with available quota. A successful model-list request does not establish that image inference is funded. After correcting the account, tap Play to retry; the app does not repeatedly retry failed generation on each scroll frame.

For a rejected ElevenLabs model or voice, select a voice available to your account in settings. A key without voice-list permission can still use a manually entered voice ID if speech-generation permission is present.


## Current extension catalogs

This personal build supports the current gzip/protobuf `index.pb` extension store and the older `index.min.json` repositories. Add this URL in Browse ? Extensions ? Extension repos:

`https://github.com/keiyoushi/extensions/raw/repo/index.pb`

The URL is normalized to GitHub's direct raw host. Adding or removing a repository refreshes the catalog automatically. The reader supports the tachiyomix 1.6 combined manga/chapter API and retains the older 1.4/1.5 suspend and Rx bridges. Source-provided manga and chapter `memo` metadata is persisted and included in backups; database migration 28 adds empty JSON defaults without replacing library rows.

Modern catalog APKs are verified against their advertised package, version code, and store signing certificate before installation. They still require the existing explicit extension trust action before loading. A catalog is a list of third-party code, and individual source websites can still require login, Cloudflare completion, or become unavailable.

Language-specific catalog rows preserve the real APK package name and share download/cancel state. Their separate list keys use source IDs; changing the package name for display breaks APK identity verification. Verification failures show a retry message and include the mismatched identity in verbose diagnostics.

Keep the minimum SDK at 26. Compiling the host below API 24 desugars interface defaults into host-only helpers, which dynamically loaded modern APKs cannot inherit. This caused `GeneratedSerializer.typeParametersSerializers()` to throw `AbstractMethodError` in MangaDex; the current Android baseline preserves those native defaults. See [Android's desugaring implementation](https://android.googlesource.com/platform/external/desugar/+/master/java/com/google/devtools/build/android/desugar/Desugar.java).

The compatibility update is adapted from the Apache-2.0 Mihon source, revision `e588e04359fe1058206a2cbac1b01018ad787359`, and checked against Keiyoushi's published store schema and extension API. It upgrades Kotlin/Compose compiler, networking, coroutine, serialization, and JavaScript runtime dependencies required by the new extension binaries. It is an extension compatibility backport into Tachiyomi Narration, not an official Mihon release.

The tiny `app/src/test/resources/extension/keiyoushi-sample.pb` fixture is the publisher's real MangaDex metadata entry extracted from the store index for parser regression testing; it contains no APK or credentials.

Build compatibility pins: Gradle 8.14.3, Android Gradle plugin 8.10.1, Kotlin/Compose compiler 2.4.21, and D8/R8 9.1.57. The R8 override in `settings.gradle.kts` is required to read Kotlin 2.4 metadata.

Portions of the extension compatibility code: Copyright 2024 Mihon Open Source Project, licensed under Apache-2.0; see `LICENSE`.
