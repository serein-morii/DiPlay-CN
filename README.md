# DiPlay CN

> 简体中文说明见 [README.zh-CN.md](README.zh-CN.md)。本仓库基于上游 DiPlay `v0.2.17`。

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay.cn`; installs alongside official DiPlay.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.
>
> **Tested vehicle: 2025 BYD Han DM-i, DiLink 5.0.** Other models and other head-unit systems are not guaranteed. Test on your own car, or fork the source and adapt it.

[Download the latest CN build](https://github.com/serein-morii/DiPlay-CN/releases/latest) · [Gitee](https://gitee.com/oneeyear/DiPlay-CN/releases/latest) · [CN optimization log](docs/CN_OPTIMIZATIONS.md) · [All CN releases](https://github.com/serein-morii/DiPlay-CN/releases) · [Upstream DiPlay](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.17)

![DiPlay home](site/assets/home.png)

## What CN adds on top of upstream

Every CN change is written down in the **[CN optimization log](docs/CN_OPTIMIZATIONS.md)** — each release, what changed and why. Current base: upstream `v0.2.17`.

- Custom dashboard turn card, CN edition: size 30–95 % and opacity 20–100 % sliders, day/night glass following the head unit, a trip info strip (arrival · duration · distance), and the card survives wireless session drops.
- In-app updates since `0.2.10-cn.8`: About → Check for updates downloads and installs the next CN build over the current one, settings kept. Four channels: Gitee (default), GitHub, gh-proxy.com, ghproxy.net.
- Boot auto-start ADB repair for firmwares that block third-party boot receivers.
- Optional delayed Bluetooth pause while CarPlay runs (5/10/15/30 s), so calls ring on CarPlay only; pairing is never touched.
- Smaller dashboard-map size option (125 % stream): smaller features, more map.
- Simplified Chinese by default when the car's language is unsupported; the wireless handoff watchdog steps aside once AirPlay is already active.
- DiLink 5 full/small cluster-map reconciliation, a proportional turn-card placement preview, connection-stage hints, and optional reconnect when the selected iPhone rejoins Bluetooth.
- Release APK built as a release variant with the official identity, sized like the official package, signed with one stable CN key since `0.2.10-cn.4` so updates overlay-install.

## 0.2.15 — CN 同步版

基于官方 **v0.2.17** 全量同步（CN 功能一条未少）。官方侧新增：首次设置向导（Setup guide）、应用外观与紧凑布局、音频缓冲恢复、Wi-Fi Direct 5 GHz 优选、车机蓝牙音频（实验）、Android 7.1 支持、解码器帧率控制等；**CN 的 #438 小屏导航、#439 蓝牙重连自动连接、#440 1% 滑条、#441 开机自启 ADB 修复四个 PR 已被官方收编**。CN 侧保留四通道应用内更新、1% 转向卡、小屏三模式（ADB 优先）、蓝牙延时暂停等全部增强。

## 0.2.14 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. Wireless supports Wi-Fi Direct or the car’s existing hotspot; Wi-Fi Direct requires Android 10+; the APK supports Android 7.1+ for wired use.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

**Trademarks and liability**: this project has no affiliation with or authorization from Apple Inc. or BYD; "CarPlay" and "BYD" are trademarks of their owners, used here only descriptively for compatibility; the optional in-app display names are the user's own local personalization. The software is provided free of charge, as-is, contains no Apple proprietary code and circumvents no technical protection measures; use at your own risk. See each release's disclaimer.

## What’s new in 0.2.14

- Searchable Settings, clear categories, quick controls and reconnect notices, with layouts for short screens and Arabic RTL.
- **Interface size** from Automatic to 200% for DiPlay's own controls, separate from CarPlay picture sizing.
- Default automatic connection choice: Last used, Wireless or USB; scheduled day/night appearance; more reliable car-button image selection.
- Targeted USB/Bluetooth recovery, hotspot address discovery, video output on windows without hardware acceleration, and safer wireless handoff.
- **Smooth video (experimental)**, off by default, with a latency tradeoff and no picture adjustments on its SurfaceView path.
- Configurable wheel-key Siri, microphone-source fallback, optional audio-focus handling and BYD call-watcher repairs.
- **Call echo cancellation** and **Clearer call voices**, experimental and off by default; opt-in changes apply at the next connection.
- Album artwork proportions, system-bar-aware rotation/split-screen areas and recognition of an observed DiLink 3 cluster surface.

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) and [validation](docs/VALIDATION.md) for contribution links and remaining physical tests. General stutter, calls/Siri, decoder and model-specific reports still need current-device evidence. [0.2.13 notes](docs/RELEASE-NOTES-0.2.13.md) remain available as historical guidance.

If a problem remains, reproduce it on **0.2.15-cn.3**, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/DiPlay**; older Android versions use the document picker. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing issue](https://github.com/serein-morii/DiPlay-CN/issues), or [create one](https://github.com/serein-morii/DiPlay-CN/issues/new). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

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

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
