# DiPlay CN

> 简体中文说明见 [README.zh-CN.md](README.zh-CN.md)。本仓库基于上游 DiPlay `v0.2.18`。

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay.cn`; installs alongside official DiPlay.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.
>
> **Tested vehicle: 2025 BYD Han DM-i, DiLink 5.0.** Other models and other head-unit systems are not guaranteed. Test on your own car, or fork the source and adapt it.

[Download the latest CN build](https://github.com/serein-morii/DiPlay-CN/releases/latest) · [Gitee](https://gitee.com/oneeyear/DiPlay-CN/releases/latest) · [CN optimization log](docs/CN_OPTIMIZATIONS.md) · [All CN releases](https://github.com/serein-morii/DiPlay-CN/releases) · [Upstream DiPlay](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18)

**Signing warning:** The CN release signing key is public. A matching Android signature does not prove an APK came from this project. Use only the linked release pages; see [the signing limitation](SECURITY.md). The package name is retained for existing users' in-place updates.

![DiPlay home](site/assets/home.png)

## What CN adds on top of upstream

Every CN change is written down in the **[CN optimization log](docs/CN_OPTIMIZATIONS.md)** — each release, what changed and why. Current base: upstream `v0.2.18`.

- Custom dashboard turn card, CN edition: size 30–95 % and opacity 20–100 % sliders, day/night glass following the head unit, a trip info strip (arrival · duration · distance), and the card survives wireless session drops.
- In-app updates since `0.2.10-cn.8`: About → Check for updates downloads and installs the next CN build over the current one, settings kept. Four channels: Gitee (default), GitHub, gh-proxy.com, ghproxy.net.
- Boot auto-start ADB repair for firmwares that block third-party boot receivers.
- Optional delayed Bluetooth pause while CarPlay runs (5/10/15/30 s), so calls ring on CarPlay only; pairing is never touched.
- Smaller dashboard-map size option (125 % stream): smaller features, more map.
- Simplified Chinese by default when the car's language is unsupported; the wireless handoff watchdog steps aside once AirPlay is already active.
- DiLink 5 full/small cluster-map reconciliation, a proportional turn-card placement preview, connection-stage hints, and optional reconnect when the selected iPhone rejoins Bluetooth.
- Release APK built as a release variant with the official identity, sized like the official package, signed with one stable CN key since `0.2.10-cn.4` so updates overlay-install.

## 0.2.18 — CN synchronized preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. Wireless supports Wi-Fi Direct, the car’s existing hotspot or Same LAN. Wi-Fi Direct on Android 7.1–9 uses a firmware-dependent legacy path; Android 7.1–8.1 still needs in-car testing.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

**Trademarks and liability**: this project has no affiliation with or authorization from Apple Inc. or BYD; "CarPlay" and "BYD" are trademarks of their owners, used here only descriptively for compatibility; the optional in-app display names are the user's own local personalization. The software is provided free of charge, as-is, contains no Apple proprietary code and circumvents no technical protection measures; use at your own risk. See each release's disclaimer.

## What’s new in 0.2.18

- Wired CarPlay no longer drops every few minutes when a USB read finishes just as DiPlay stops waiting for it, and it now starts on Android 7.1 head units, on head units whose USB audio driver held the iPhone, and without a VPN dialog when network ADB is approved.
- Fewer short music dropouts, and the connection screen shows the real stage and failure in every app language.
- On head units whose Android Bluetooth cannot reach the iPhone, wireless CarPlay stops with a clear message and suggests USB.
- The wheel's next and previous keys change CarPlay tracks on the CarPlay screen without the wheel-key service, Geely/Lynk `geely_fx11` head units get wheel media keys and Siri, and wheel keys stuck on "Allowed, starting…" recover by themselves.
- Back up and restore settings from a phone through a QR code in **Settings → About**, and pick another installer, such as MT Manager, for updates.
- New experimental, off-by-default options: Harman full screen, square corners, clearer navigation voice over USB, MediaTek low-latency decoding, a media-key audio focus opt-out, car phone audio for calls, a cluster speedometer and FCE cluster guidance.

See [0.2.18 release notes](docs/RELEASE-NOTES-0.2.18.md) and [validation](docs/VALIDATION.md) for contribution links and remaining physical tests. General stutter, calls/Siri, decoder and model-specific reports still need current-device evidence. [0.2.17 notes](docs/RELEASE-NOTES-0.2.17.md) remain available as historical guidance.

If a problem remains, reproduce it on **0.2.18-cn.1**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; Android 7.1–9 asks for storage access and saves there too. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing CN issue](https://github.com/serein-morii/DiPlay-CN/issues), or [create one](https://github.com/serein-morii/DiPlay-CN/issues/new). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md) — select `mobile` for the main DiPlay app; `maphost` is a map sample.
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [CN optimization log](docs/CN_OPTIMIZATIONS.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The website and app support English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese and Traditional Chinese. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a public source snapshot. Local research and tester reports are excluded. The CN Android release-signing key is intentionally public for update compatibility and is **not a security boundary**; see [SECURITY.md](SECURITY.md). The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude accessory identity keys, **but include the CN Android signing key**. Tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
