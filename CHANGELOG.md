# DiPlay 0.2.19 — 2026-10-12

- Start wired CarPlay several seconds sooner by not waiting 2 s on each refused USB network write, ask for CarPlay mode first on iOS 26, grant USB access through approved ADB on Android 7.1–9 head units without a USB permission page, and let Auto-confirm USB permission find the system dialog (#644, #689).
- Report the playback position of 16 kHz calls, Siri and navigation prompts at their own rate instead of three times too fast, restart a microphone whose first read fails, and stop background music underruns from a 0.5 s audio wait (#644).
- Keep CarPlay from restarting at the first touch with the DiLink 4 dashboard map on and from staying scaled down after the reversing camera, show the last picture when a SurfaceView output comes back, and redesign the in-CarPlay settings menu (#663, #674, #669, #642).
- Japanese, complete Traditional Chinese with guides, and 67 more Arabic strings (#646, #691, #682, #681, #631).
- Download the diagnostic report on a phone by QR code or a typed address and code, keep the phone page answering behind idle connections, show connection recovery tips and a Bluetooth-off message, and fix wireless CarPlay on MIUI 13 (#686, #687, #685, #677, #679, #673).
- Set BYD navigation audio defaults on a first setup, add an optional album-artwork card on the dashboard map, record why the car hotspot could not be started, and fall back to the default Bluetooth adapter (#656, #660, #667, #641).

See [0.2.19 release notes](docs/RELEASE-NOTES-0.2.19.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.18 — 2026-10-11

- Keep wired sessions running when a USB read finishes as DiPlay stops waiting for it, start wired CarPlay on Android 7.1 head units, when a head-unit USB audio driver holds the iPhone, and without a VPN dialog over approved network ADB (#625, #614, #618).
- Stop discarding music packets after a 10 ms wait, and log the /feedback position against the received audio (#618, #625).
- Show the real connection stage in every app language, stop with a clear cause when a head unit's Bluetooth cannot reach the iPhone, and find the connected iPhone through sink-role Bluetooth profiles (#624, #590, #585).
- Wheel next/previous on the CarPlay screen without the wheel-key service, Geely/Lynk `geely_fx11` wheel keys and Siri, wheel-key service restore retries, and next/previous to answer or decline with CarPlay call keys (#600, #626, #622, #625).
- Settings backup and restore from a phone, an installer choice for updates, and About inside Settings (#598, #593, #597).
- Experimental, off-by-default options: Harman full screen with 32-bit x86 libraries, square corners, clearer navigation voice over USB, MediaTek low-latency decoding, a media-key audio focus opt-out, car phone audio for calls, a cluster speedometer and FCE cluster guidance (#613, #596, #622, #608, #615, #595, #607, #599).

See [0.2.18 release notes](docs/RELEASE-NOTES-0.2.18.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.17 — 2026-10-10

- Keep wired sessions running after a damaged NCM block, retry identification without unsupported CarPlay messages for older iOS, recognize the Android 10+ and Chinese USB permission prompts, explain refused wired mode switches and retry NCM interface claims (#575).
- Add an iPhone charging choice for wired USB: Normal 2.4 A, Reduced 1.5 A or Low 0.5 A (#583).
- Recommend the built-in car hotspot, and pause the car's Wi-Fi network search during Wi-Fi Direct on Android 7.1 and later when network ADB is approved (#570).
- Record the head-unit microphone for app voice notes and clock Opus 16 kHz microphone packets at 16 kHz (#548, #547).
- Check daily for updates with an Update available notice, recognize the DiLink generation from the controller version, and save Android 9 reports to Download/DiPlay (#488, #531, #541).
- Add album-cover ambient lighting and fix saving ambient settings on hotfix firmware; add experimental BYD call popup hiding and external controller keys (#537, #550, #539, #549).

See [0.2.17 release notes](docs/RELEASE-NOTES-0.2.17.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.16 — 2026-10-09

- Send the Siri and call microphone on Android 7.1–9 head units without an Opus encoder through a bundled software Opus encoder, and offer Opus only when it can be encoded; accepted on a BOS Mini A1 (Android 9) with an iPhone 12 on iOS 27 (#468, #483).
- Fix wired NCM receive framing and Android 8 USB reads, and retry rejected large USB reads at smaller sizes (#478, #495).
- Refresh hotspot addresses after a first wireless timeout, request Android 17 local-network permission, handle missing VPN authorization screens and add WPA3 car-hotspot security (#474, #465, #517, #501).
- Add experimental Low-latency decoding and Direct video output, an FPS counter, a hardware low-latency Smooth video decoder and bounded video backlog recovery (#496, #456, #347).
- Add inline Settings search, confirm before the quick menu discards staged changes, allow turning off the swipe-down gesture, and refine Settings layout and reconnect prompts (#450, #500, #487, #486, #482, #490, #491).
- Place the dashboard car marker with 1% sliders, give the small-window turn card its own placement, theme the cluster waiting screen, show the full DiLink 3 arrival time, declare initial appearance and add side-panel resizing (#494, #493, #481, #458, #346, #480).
- Add experimental, off-by-default car Bluetooth pause, music-following ambient lighting, navigation wheel volume and a Platform 21 instrument route; refine call echo alignment, buffered-audio pacing and audio diagnostics (#307, #345, #344, #348, #421, #484, #499).

See [0.2.16 release notes](docs/RELEASE-NOTES-0.2.16.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.15 — 2026-10-08

- Lower the minimum to Android 7.1/API 25 with compatibility fallbacks; Android 7.1–8.1 vehicle validation remains pending (#407).
- Add the first-launch DiLink setup guide and manual GitHub update checks in About (#416, #413).
- Add Light/Dark/Auto app appearance and refine compact layouts, fullscreen handling, Language and About navigation (#422, #420).
- Recover the music prebuffer after underruns, including corrected retained-audio accounting, and supply a main-decoder operating-rate hint with fallback (#401, #417).
- Add Wi-Fi Direct automatic band choices and optional selected-iPhone Bluetooth launch; experimental Bluetooth audio remains off by default (#432, #439).
- Correct square-canvas physical dimensions, refine turn-card placement to 1% steps and add optional small-window marker layouts with experimental Auto following (#404, #440, #438).
- Add explicit experimental ADB boot-start repair and correct Ukrainian wording (#441, #399).

See [0.2.15 release notes](docs/RELEASE-NOTES-0.2.15.md) for contribution links and limits, and [validation](docs/VALIDATION.md) for checks. Full-release vehicle acceptance is not claimed.

# DiPlay 0.2.14 — 2026-10-07

- Group Settings by driver goal with search, adaptive layouts, quick controls and reconnect notices (#369).
- Add an independent Interface size control, preserving CarPlay geometry and following real density/window changes (#378).
- Add a default automatic-connection choice, scheduled day/night mode and launcher return to active CarPlay (#373, #365, #376).
- Improve custom car-button image selection and clarify the main app's source-build instructions (#364, #377, #374).
- Repair the captured USBMUX diagnostic trailer, wake drained Bluetooth receive queues and retry explicitly rejected large USB reads once at 16 KiB; improve stream diagnostics (#362).
- Select compatible video output without window hardware acceleration; use Android video decryption when available and retain a fallback (#362, #332).
- Add default-off experimental Smooth video pacing with explicit latency/picture-adjustment limits (#367).
- Preserve usable hotspot interface addresses and authenticated Bluetooth control on the observed rendered-session handoff fallback (#394, #396).
- Add configurable steering-wheel Siri, bounded microphone-source fallback and optional audio-focus handling (#360, #371, #339).
- Repair BYD call-watcher/audio-status behavior and add opt-in, default-off experimental software call echo cancellation and voice filtering (#370).
- Preserve artwork proportions, correct system-bar-aware split/rotation geometry and recognize the specifically observed 1280×480 DiLink 3 cluster surface (#375, #389, #388).

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) for all 20 contribution links, credits and limitations. Android 9/API 28 remains required. See [validation](docs/VALIDATION.md) for measured checks; complete-release vehicle acceptance is not claimed.

# DiPlay 0.2.13 — 2026-10-06

- Enable the legacy Android 9 Wi-Fi Direct group path with generated credentials and serialized ownership/cleanup; requested frequency remains unverified on Android 9 (#282).
- Prefer current IPv4 hotspot endpoints, preserve scoped IPv6 fallback, and refine Auto channel priorities beside a 5 GHz station without guaranteeing a band (#283, #309).
- Avoid unused Android NSD and USB-service dependencies for wireless startup; retain only rendered sessions on the guarded handoff fallback (#313, #300, #258).
- Add explicit experimental hotspot join Check/Apply/Restore on eligible Android 13+, user-configured 5 GHz hotspots, with complete private recovery state and no automatic mutation (#251).
- Switch wireless to USB within the host activity, verify requested permissions independently, preserve other accessibility services and cancel stale permission work (#268).
- Preserve validated USBMUX payload replies with narrow four-byte trailer recovery (#298).
- Add optional live dock/split-screen areas, square-canvas screen rotation and the selected-decoder square check; add a default-off experimental side panel (#246, #277, #284, #245).
- Preserve DiLink 4 native casting mode, offer pre-connection calibration and apply live cluster picture adjustments (#260, #265).
- Retain a recent dashboard turn card only across wireless replacement within its stale window; add a Smaller map choice (125%) and the checked DiLink 3 full-then-half projection sequence with compensation (#304, #306, #296).
- Restore battery reads when only sys.car.protocol is populated and recover eligible unbound wheel services using already-authorized ADB (#285, #297).
- Add independent default-off experimental DiLink 3 call keys and dashboard calls, with initialized watcher readiness, unique ownership, pristine-safe cancellation and retryable dirty cleanup; target-car acceptance remains requested (#243).
- Correct the observed 24 kHz Siri microphone RTP clock while retaining 48 kHz for telephony/unobserved formats; add bounded, default-off experimental AAC-LC buffered music and single-session renderer ownership (#295, #308).
- Set TCP_NODELAY on the touch event channel; contributor latency observations remain device-specific (#311).
- Improve full-size multi-window home/settings appearance, ambient-setting visibility and shared menu persistence; add main-settings car-button customization (#252, #239, #281, #302).
- Add light waiting/cluster placeholders and a 300 ms cluster fade; the main waiting screen follows CarPlay day/night mode (#305, #317).
- Add Traditional Chinese (Taiwan) as the seventh app language, preserve explicit script selection and correct Simplified Chinese hotspot wording (#314, #286). The release website also gains a Traditional Chinese edition.
- Retain the multilingual website groundwork, add the smooth-wireless guide and make buffered-ownership tests deterministic without runtime/API changes (#240, #310, #312).

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) for all 35 contribution links, credits, experimental settings, compatibility limits and diagnostic export steps. Final exact-release validation is recorded in [VALIDATION](docs/VALIDATION.md). This remains a public preview; no fresh complete-release vehicle test is claimed.

# DiPlay 0.2.12 — 2026-10-04

- Add Existing Wi-Fi / Same LAN wireless CarPlay with scoped IPv4/IPv6 discovery and network-change cleanup (#223).
- Wait for a stable car-hotspot interface and recover bounded wireless attempts when no AirPlay TCP follows StartSession (#229); add observed-state, authorized-ADB hotspot fallback on firmware exposing supported commands (#235).
- Improve Apple USB attach matching and narrowly scoped optional USB-prompt assistance (#170, #224).
- Pause Android 10 station scans during eligible hotspot/P2P sessions, preserving Same LAN, with controller leases and durable retryable restoration (#225).
- Improve split-screen, launcher cards, short-screen preparation and virtual cluster/floating-map geometry (#171, #172, #181).
- Add independent system-bar controls and correct in-session save/cancel and Local/USB-CH341 authentication selection (#191, #194).
- Add system, light-sensor, day and night CarPlay appearance modes, richer custom turn cards, and live main-video picture controls (#178, #193, #211).
- Offer custom integer resolution from 30% to 160%, with shared limits, correct 30%/160% labels and decoder/canvas capability fallback; refresh connection settings on resume (#179, #230, #196).
- Reconcile opt-in DiLink 4 cluster routing/calibration into one decoder owner, retain verified HUD gates, and journal exact stock-map holds and recovery (#213, #187).
- Add DiLink 3 guidance text and projection-display support with committed recovery before mutation, partial-setup compensation and retryable stock restoration (#182).
- Add opt-in wheel map zoom and main-screen joystick while preserving press/release and call behavior; reject stale queued work across phone/screen changes (#214, #231).
- Switch supported dashboard contents live using actual delivery and safely retained paused choices; preserve selection across stream/phone replacement (#232).
- Add a five-second dashboard-song-on-change window with timer invalidation, and retain album art while the next transfer is pending (#215, #228).
- Export reports through Downloads, document picker, app-external or private fallback storage, with explicit View/Share actions (#185, #219).

See [0.2.12 release notes](docs/RELEASE-NOTES-0.2.12.md) for the complete corrections, hardware evidence and issue-reporting steps. This remains a public preview; no fresh end-to-end vehicle test of the complete repaired release is claimed.

# DiPlay 0.2.11 — 2026-10-03

- Add preferred Wi-Fi Direct channel selection for the next connection; Auto remains the default, and manual channel rejection/mismatch reports an error (#175).
- Add a movable custom dashboard turn card with 2% position steps; leave unknown arrows blank and clear expired guidance (#155).
- Offer two-, three- or four-finger settings swipes, keeping three as the default (#156).
- Add opt-in read-only legacy vehicle-data detection while preserving default DiLink 5.0 mode; reject unaccepted/stale probe publication and fix accepted-battery lock ordering (#158, integrated through #173).
- Keep wireless location/vehicle data on its runtime Wi-Fi link and defer parked-video decisions until SETUP/event readiness (#157).
- Add optional automatic car-hotspot startup, off by default, with verified own-package authorization and unified vehicle settings (#164/#173).
- Support Android TV/remote controls while preserving head-unit touchscreen Back and absolute knob X/Y; keep its workflow source-only (#146).
- Retain artists across partial title updates and publish song metadata/artwork only when changed (#161, #162).
- Correct Android 9 audio API use, release failed codecs, reconnect with settled geometry/readiness, and require own-app VPN scope (#168 and local corrections).
- Add one guarded Auto-mode API 29 P2P recovery for the exact reported pre-create Builder error, plus bounded wireless/media/theme/own-app-exit diagnostics without payload recording or automatic uploads.

See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) for requirements, device-test limits and fresh-report guidance. Preferred channel selection does not establish stutter as fixed; Qin Plus, Siri, iOS 15 and day/night reports remain under investigation. Android 9 is still the minimum; Wi-Fi Direct needs Android 10+.

# DiPlay 0.2.10 — 2026-10-03

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
