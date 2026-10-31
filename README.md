| Build | Stable | Weekly Preview | Contribute |
|-------|--------|----------------|------------|
| [![CI](https://github.com/tachiyomiorg/tachiyomi/actions/workflows/build_push.yml/badge.svg)](https://github.com/tachiyomiorg/tachiyomi/actions/workflows/build_push.yml) | [![stable release](https://img.shields.io/github/release/tachiyomiorg/tachiyomi.svg?maxAge=3600&label=download)](https://github.com/tachiyomiorg/tachiyomi/releases) | [![latest preview build](https://img.shields.io/github/v/release/tachiyomiorg/tachiyomi-preview.svg?maxAge=3600&label=download)](https://github.com/tachiyomiorg/tachiyomi-preview/releases) | [![Translation status](https://hosted.weblate.org/widgets/tachiyomi/-/svg-badge.svg)](https://hosted.weblate.org/engage/tachiyomi/?utm_source=widget) |

# ![app icon](./.github/readme-images/app-icon.png)Tachiyomi
Tachiyomi is a free and open source manga reader for Android 6.0 and above.

## Features

Features include:
* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.io/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), and [Bangumi](https://bgm.tv/) support
* Categories to organize your library
* Light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service
* Optional AI narration using Gemini page analysis and ElevenLabs text-to-speech

## Optional AI narration

This fork adds an optional narration feature. Gemini reads the visible manga page, identifies dialogue, panel order, speakers, emotions, and inner thoughts. ElevenLabs then generates the character voices. The app contacts those providers directly from your phone; there is no narration backend operated by this project.

### What you need

1. A Gemini API key with access to the selected model and enough quota or billing credit for image analysis.
2. An ElevenLabs API key with text-to-speech access and available credits.
3. An internet connection while a page is being analyzed or a new voice clip is generated.

Both providers can charge for usage. Gemini receives the page images and context needed to determine the reading order and speakers. ElevenLabs receives the dialogue text to synthesize it. Review each provider's pricing, terms, and privacy information before enabling narration.

### Set it up in the app

1. Open a chapter, tap the narration/voice icon, then choose **Settings**. You can also open **Settings → Reader → AI narration**.
2. Enter your Gemini API key, ElevenLabs API key, Gemini model, ElevenLabs model, and a default ElevenLabs voice ID. Use **Load available voices** to choose a narrator voice from your ElevenLabs account.
3. Save the settings and enable **AI narration**.
4. Start reading. The current page is prepared first; the app then prepares the following pages until the chapter is complete. The floating control shows progress and can pause or resume narration.

The narration dialog includes controls for playback speed, the gap between dialogue clips, character-voice correction, retrying failed pages, and **Usage & cost**. The usage view tracks Gemini tokens used by this app after the feature is enabled and fetches the current ElevenLabs credit balance. Gemini's full project invoice remains in Google AI Studio or Google Cloud Billing.

For right-to-left manga, narration follows panels before individual bubbles: it finishes the right panel before moving to the left. If a chapter was analyzed before an update or its order is wrong, use **Reset chapter narration** to reanalyze it. See [NARRATION.md](./NARRATION.md) for detailed behavior and troubleshooting.

### Keep keys private

Never commit, post, or share your API keys. For an open-source build, enter keys only through the app's settings screen; they are stored on the device with Android Keystore encryption and are excluded from manga backups.

Developers can copy `.env.example` to a local root `.env` for a private build, but `.env` is ignored by Git and must never be included in a public APK or commit. A key embedded in an APK can be extracted by anyone who obtains that APK.

## Download
Get the app from our [releases page](https://github.com/tachiyomiorg/tachiyomi/releases).

If you want to try new features before they get to the stable release, you can download the preview version [here](https://github.com/tachiyomiorg/tachiyomi-preview/releases).

## Issues, Feature Requests and Contributing

Please make sure to read the full guidelines. Your issue may be closed without warning if you do not.

<details><summary>Issues</summary>

**Before reporting a new issue, take a look at the [FAQ](https://tachiyomi.org/docs/faq/general), the [changelog](https://tachiyomi.org/changelogs/) and the already opened [issues](https://github.com/tachiyomiorg/tachiyomi/issues).**

</details>

<details><summary>Bugs</summary>

* Include version (More → About → Version)
 * If not latest, try updating, it may have already been solved
 * Preview version is equal to the number of commits as seen on the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* Don't group unrelated requests into one issue

DO: https://github.com/tachiyomiorg/tachiyomi/issues/24 https://github.com/tachiyomiorg/tachiyomi/issues/71

DON'T: https://github.com/tachiyomiorg/tachiyomi/issues/75

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how. Avoid writing just "like X app does"
* Include screenshot (if needed)

Source requests are not accepted.
</details>

<details><summary>Contributing</summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md).
</details>

<details><summary>Code of Conduct</summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

## FAQ

[See our website.](https://tachiyomi.org/)

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Disclaimer

The developer of this application does not have any affiliation with the content providers available.
