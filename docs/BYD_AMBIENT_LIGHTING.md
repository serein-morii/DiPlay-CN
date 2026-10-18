# Optional BYD ambient lighting

Contributed by 寒叙 (@Hanxu4131), adapted from original ambient-light work in the contributor's local DiPlay-based BYD CarPlay project. This patch is based on upstream DiPlay main b26cd544 (v0.2.13). It keeps DiPlay branding, application identifiers and existing network behavior.

Configure the feature while parked. Control is off by default. Saving an enabled configuration first performs a read-only check using existing ADB authorization and the vehicle lamp interface. The feature does not enable ADB, offer an authorization key for approval or change debugging settings. A failed check leaves the saved configuration unchanged. Access setup remains a separate user-controlled action.

Only head units exposing the expected BYD interior-lamp API and valid color, brightness and area values are supported. This is not a claim of support for every BYD model, model year or firmware. Unknown or unreadable states are rejected before taking control. Missing older preferences use defaults; absent or incorrectly typed enable values remain disabled.

## Behavior

The media channel supplies energy from successful PCM writes. Only level and frame metadata are retained, not recorded audio. AudioTrack playback position ties the analysis to played audio. Navigation and call channels do not supply the light envelope. Phone playback ownership prevents stale callbacks from a replaced CarPlay session from resuming the lights.

The opt-in card is in Settings → Advanced and is marked experimental. Changes apply live after saving; Cancel leaves the saved configuration unchanged.

Available modes are sound energy, beat, estimated BPM, bass priority and smart follow. BPM is an estimate from recent onsets, not track metadata. Selected OEM color numbers form the palette. Brightness transition speed affects brightness smoothing; color beat detection keeps its standard cadence. The configured brightness is a ceiling. Setting 0 means raw OEM level 1, the lowest supported level. On a 2023 Han (GCC, DiLink 3.0) that level switches the lamps fully off, observed by stepping all zones through levels 1, 2, 3 and 6; other models may differ.

With control enabled, **When no music is playing** decides what paused, stopped or disconnected playback does. **Steady glow** (the default) holds the chosen color at the chosen level. **Car's own lighting** keeps the steady glow through short pauses and track changes, then hands the lamps back to the car's own settings after 15 seconds without music; the next track starts a new lamp session. **Lowest level** holds every zone at raw level 1, which is dark on a Han. During playback, disabling music following uses a fixed color and brightness. Disabling ambient control attempts to restore the pre-control front/rear colors, brightness and area. Changing the configuration takes effect only after Save; Cancel does not apply the draft.

## Settings dialog

The dialog opens with a **What happens** summary that rewrites itself as the draft changes, so each setting's effect is visible before saving. It follows the controller rules above:

- Control off: the car keeps its own lamp colors, brightness and area.
- Music playing: the chosen area (Front, Rear or All) follows the music, or holds one fixed color, up to the chosen level. With Front or Rear selected, the other zone is not changed.
- Paused, stopped or disconnected: steady glow, the car's own lighting after 15 seconds, or the lowest level, as chosen. Steady glow and the lowest level apply to the chosen area (the lowest level to every zone).
- Level 0: every zone stays at the lowest level while music plays; with the car's own lighting chosen the lamps are still handed back after 15 seconds without music.

Colors are picked from a grid of the car's 31 colors, drawn with the 31-color OEM preview reference described below; the lamp interface accepts color numbers, not arbitrary RGB, so there is no free color wheel. With **Follow music** on, any number of colors can be selected (at least one). With it off, the grid selects a single color, the first of any previous selection, as the lamps use it. With **Follow album cover** on, the grid is disabled because the cover decides the colors. Each color-change mode has a one-line description, and brightness is a 0 to 6 slider. The color-change mode and brightness-speed controls are hidden while Follow music is off, because fixed mode does not use them.

The controller samples every 50 ms and limits lamp submissions to one per 200 ms. Lamp calls run outside audio writes through a token-bound, bounded stdin worker. The worker accepts only interior-lamp operations. It holds a process lock, reads back writes, keeps one original-state snapshot and restores on normal stop or EOF. A five-second heartbeat and fifteen-second lease attempt restoration after a lost connection. Recovery uses bounded retry delays and retains the original snapshot when a stop is unconfirmed.

Restoration is best effort. Power loss, forced process death, a permanently blocked vehicle service or repeated failed readback can prevent restoration. The worker's final failed restore is reported before it exits. This patch does not guarantee recovery under those conditions.

## Follow album artwork colors

The optional **Follow album artwork colors** switch builds on the music-lighting contribution by 寒叙 (@Hanxu4131). It is off for existing and new installations unless explicitly saved, so the selected-color mode keeps its current behavior. Save applies the choice without reconnecting CarPlay; Cancel keeps the previous configuration.

With artwork following enabled, an available colored cover supplies the base hue. Musical analysis still controls brightness and color movement within nearby supported OEM colors. The lamp interface accepts color numbers, not arbitrary RGB, so artwork colors are approximated using a 31-color OEM preview reference. That reference was checked on the contributor's 2023 Tang DM-i Champion Edition / platform 21; it is not a calibrated color measurement or a guarantee for other BYD firmware.

Gray, black, transparent and very dark pixels do not supply a usable colored hue. If no usable artwork color is found, the palette falls back to **all 31 supported color numbers**, following the chosen music mode rather than staying on the previous song's hue. Missing or unreadable artwork uses the same fallback. This means a gray or black cover does not make the lamps gray or black.

White is a secondary fallback only: the existing colored-hue extraction runs first. When it finds no valid colored hue, a cover with at least half its sampled area occupied by bright, low-chroma pixels can select white or cold white. A mostly white cover with a usable colored detail still follows that colored detail. Neutral/warm white maps to OEM color 29; a qualifying blue/cyan cast maps to cold white, color 30.

Artwork can arrive after the CarPlay connection or after playback starts. The integration retains the current session's artwork, requests missing initial artwork within bounded limits and handles a real track change. Changes to a title alone can be lyrics; they do not require repeatedly decoding the same cover. Artwork is sampled on the existing artwork worker, outside PCM writes. Stale callbacks from a replaced session are ignored.

If the phone reuses the same artwork reference, source app, artist, album and duration and reports no observable position restart, a title change cannot reliably distinguish a new track from a lyric update. A later artwork payload is still accepted; the feature does not invent a cover from the nearest cached item.

The source implementation was exercised in the contributor's BYD CarPlay fork. This new upstream port has separate local/CI checks and has not been installed in a vehicle. No vehicle identifiers, phone records, raw in-car logs, OEM artwork files or third-party APKs are included.

## The car's own music rhythm

**Car's own music rhythm**, a switch in the same card, leaves the lamps to the car: while CarPlay music plays, the car's own ambient-light music rhythm runs with the colours and effects set in the car's ambient-light menu, where its music rhythm must be on. DiPlay does not set the lamps itself while the switch is on.

How it works, as seen on a 2024 Tang (DiLink 5, Android 12):

- **Where the rhythm lives.** It is in system_server (`com.android.server.audio.visualizer`). A music app calls BYD's `IAudioService.startAudioOutput(package, token)`. system_server then starts a Visualizer on the output mix (its log shows `initVisualizer: id = 0`) and sends colours to the lamp ECU (`ColorVisualizerEffect: send color N to ECU`) until `stopAudioOutput` or until the token's process dies.
- **Which apps it starts for.** It starts only for BYD's own music apps (its media centre, Kuwo, Kugou, NetEase, QQ Music). A call naming DiPlay creates a session, but nothing starts. DiPlay therefore names the media centre, `com.byd.mediacenter`.
- **How DiPlay makes the call.** First from its own process, by reflection on the hidden `IAudioService` (`BydCarMusicRhythmDirect`). On the 2024 Tang (DiLink 5, Android 12L) ART logs that access as `blocked, reflection, denied`, yet the call goes through, in a debuggable and in a non-debuggable build. Other firmware may enforce the block. There DiPlay falls back to a helper: `BydCarMusicRhythmTool`, run by app_process as the shell user over DiPlay's existing network ADB access. The switch never asks for ADB.
  - Switching it on first checks that DiPlay can look up the two calls itself. Only if it cannot does it run the helper's `--probe`, which only checks that the audio service has the calls.
  - While on, DiPlay starts the rhythm when CarPlay's media track plays and stops it when it pauses, stops or the session ends. It makes the direct call first and opens the helper only when that fails. A rhythm is stopped the same way it was started: on the Tang the head unit keeps each session under its token. The direct call uses one token for DiPlay's whole process.
  - If DiPlay's process ends, the head unit drops its token, which ends a rhythm started directly. If DiPlay goes away while the helper runs, the helper's stdin closes and it stops. If the helper dies, the head unit drops its token.
- **What else drives the lamps.** While started, everything on the output mix drives the rhythm, including navigation prompts during music.

### Head units that make no spectrum of their own (2023 Han, DiLink 3.0, Android 10)

The Han's `AudioService` has no visualizer for this. Its `AtmosphereLampControl.startAudioOutput` only tells the lamp controller that media audio is working in music mode (`AUDIO_MEDIA_AUDIO_WORKING_MODE_SET` 2, `AUDIO_MEDIA_AUDIO_WORKING_STATE_SET` 1; stop sets the state to 2), and only for the media centre, Kuwo, Kugou and the karaoke app. The spectrum comes from elsewhere: the media centre builds it itself, and the framework's `AudioDataHandler` does it for Kuwo and Kugou, then calls `BYDAutoAudioDevice.setAmbientLightFreq`, 16 values from 1 to 15 at about 40, 80, 120, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1200, 1600, 1800 and 3500 Hz (feature ids 774897672 to 774897732, 4 apart). On the Han, `startAudioOutput` alone left every lamp dark.

**Send music data to the car's rhythm** (Settings, Ambient light, off by default) fills that gap. While the car's own music rhythm runs, DiPlay reduces the PCM it plays to the same 16 bands (Goertzel filters over the last 2048 frames, in dB below the loudest recent band) and the ADB helper writes them about eleven times a second. The car's own rhythm modes then drive all lamps, including the passenger dash, which the front and rear lamp zones cannot reach.

Observed on a 2023 Han (GCC): with the car's Rhythm Ambient Light on and DiPlay's own lamp control off, writing the "working" state alone switched every lamp off; writing the state and synthetic bands at about 12 Hz for 12 seconds made every lamp, including the passenger dash, follow a beat pattern; the matching stop returned the lamps to normal. This setting has not been installed on a car; the data path was verified with that synthetic input.

## Evidence and verification

Album-color extension validation on 2026-10-09: the full nine-task repository CI command passed locally, with 2258 tests reported, zero failures/errors and 1 existing platform skip. All three lint tasks had zero errors, and mobile/home/maphost source-only debug builds assembled. Compact 600dp / font 1.3 and full 1280dp / font 1.5 captures were rendered at a 960px physical viewport and visually checked; the dialog controls and Save/Cancel remained accessible. This does not replace vehicle acceptance of the upstream port.

The source ambient-light feature was field-tested by the contributor on a 2023 BYD Tang DM-i Champion Edition / platform-controller 21. These local observations motivated this module; they do not verify this upstream integration, other model years or every recovery path. Exact earlier-build behavior should be attributed to that local build only. This public patch has not been installed or tested in a vehicle.

Local validation on 2026-10-06 passed shared/common Kotlin compilation, 63 focused shared tests and three focused UI tests. Native build tasks were excluded. These checks cover source compilation and simulated UI behavior; no APK or vehicle validation was performed.

The focused JVM suite covers playback ownership, activity policy, written-versus-played envelopes, sampled beats, palette/low-frequency analysis, brightness smoothing, write cadence, fake-client recovery, protocol validation, restore order and worker ownership. Additional preference tests cover missing keys, legacy single-color settings and malformed values. UI tests cover Cancel, failed read-only access checks and a completed check after the dialog was canceled. Compilation and test results for this patch are recorded by the contribution review, separately from earlier vehicle observations.

The patch contains source and focused tests only. No local diagnostic logs, vehicle or phone records, ADB destination configuration, authentication assets, private logo, third-party APKs or decompiled code are included. Existing upstream LocalAdb loopback behavior and runtime authorization are reused unchanged.

## Local checks

```sh
./gradlew --offline :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.media.Ambient*' --tests 'com.shilapi.xcertplay.hud.BydAmbientLightPolicyTest' --tests 'com.shilapi.xcertplay.hud.AmbientWorkerOwnerLockTest' --tests 'com.shilapi.xcertplay.adb.LocalAdbInteractiveTest'
./gradlew --offline :shared:compileDebugKotlin :common:compileDebugKotlin
```

Before a release, check the default-off UI, all supported locales, failed authorization without new prompts, unsupported lamp states, Save/Cancel, music pause/resume, disconnect/reconnect, disabling control with unequal front/rear states, rapid setting changes and the complete normal vehicle workload while parked. Public build checks use no private authentication inputs.
