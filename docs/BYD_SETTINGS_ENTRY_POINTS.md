# BYD DiLink 1.0 settings entry points (Android 7.1)

Firmware audit of DiLink 1.0 (BYD Song Pro 2019, platform MSM8953 / Android 7.1.2, package
`VP128_1.38.2103120.14.2103090.zip`) covering every screen DiPlay opens through a settings intent.

The car ships no AOSP `Settings` package. Its settings UI is `VehicleSettings`
(`com.byd.vehiclesettings`, `priv-app/VehicleSettings`), and that app registers a decoy activity for
sixty standard `android.settings.*` actions. Every one of those actions lands on the decoy, which
answers with `string/unsupport_tips`: "多媒体系统不支持此操作" — *the multimedia system does not
support this operation*. A `startActivity` that reaches the decoy **succeeds**, so
`runCatching { startActivity(intent) }.isSuccess` cannot tell a real screen from the decoy. That is
the single fact that makes the ordering of intent candidates matter.

This document records which actions the decoy claims, which real activity answers the rest, and what
each of DiPlay's settings buttons should therefore send. Everything below is read out of the
firmware image, not inferred from behaviour on the car.

## Reproducing this audit

The methods described here need the firmware package and `aapt2`; `scripts/byd_firmware_actions.py`
runs the whole sweep.

```sh
unzip VP128_1.38.2103120.14.2103090.zip update.zip      # the outer zip is a signed wrapper
unzip update.zip                                        # system.transfer.list + system.new.dat
python3 scripts/byd_firmware_actions.py --image system.img --out build/byd-actions
```

The script unpacks every APK out of `system.img` with `7z`, dumps each manifest with
`aapt2 dump xmltree --file AndroidManifest.xml`, and inverts the result into
*action → responding components*. On this image that is **146 APKs, 989 components, 580 distinct
actions**. The two figures this document relies on are the decoy's action list and the per-action
responder set.

`VehicleSettings`' code is not in its APK: there is no `classes.dex` there, and the 2 MB APK carries
resources only. The dex lives in `priv-app/VehicleSettings/oat/arm64/VehicleSettings.odex` at offset
6740, carrying a stale checksum. Recomputing the SHA-1 and Adler-32 fields makes `dexdump` accept it.

## The decoy

`com.byd.vehiclesettings.main.unsupport.UnsupportActivity` declares a single `intent-filter` with
**61 action entries, 60 distinct** (`android.settings.APN_SETTINGS` is listed twice), plus the
categories `DEFAULT`, `VOICE_LAUNCH`, `com.android.settings.SHORTCUT`. The app runs under
`sharedUserId="android.uid.system"` with `targetSdkVersion=25`.

The decisive property: **not one of the 60 has a second responding component anywhere in the
image.** Once an action is on this list, an implicit intent cannot reach anything else — there is no
fallback to fall back to. The only route to a real screen is an explicit component, because an
explicit `ComponentName` skips intent-filter matching entirely and the decoy cannot intercept it.

```
android.settings.USER_DICTIONARY_SETTINGS        android.settings.ACTION_PRINT_SETTINGS
android.settings.LOCATION_SOURCE_SETTINGS        android.settings.STORAGE_USB_SETTINGS
android.settings.BLUETOOTH_SETTINGS              com.android.settings.STORAGE_USB_SETTINGS
android.settings.WIRELESS_SETTINGS               android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE
android.settings.AIRPLANE_MODE_SETTINGS          android.settings.NFCSHARING_SETTINGS
android.settings.NFC_SETTINGS                    android.settings.WIFI_DISPLAY_SETTINGS
android.settings.WIFI_IP_SETTINGS                android.settings.CAST_SETTINGS
android.settings.WIFI_IF_SETTINGS                android.intent.action.POWER_USAGE_SUMMARY
android.net.vpn.SETTINGS                         android.settings.BATTERY_SAVER_SETTINGS
android.settings.LOCALE_SETTINGS                 android.settings.SYNC_SETTINGS
android.settings.VOICE_INPUT_SETTINGS            android.settings.ACCOUNT_SYNC_SETTINGS
android.settings.ZEN_MODE_SETTINGS               android.settings.ADD_ACCOUNT_SETTINGS
android.settings.HOME_SETTINGS                   android.app.action.START_ENCRYPTION
android.settings.LICENSE                         android.settings.DREAM_SETTINGS
android.settings.APP_OPS_SETTINGS                android.settings.USER_SETTINGS
android.settings.USAGE_ACCESS_SETTINGS           android.settings.NFC_PAYMENT_SETTINGS
com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS
android.settings.CAPTIONING_SETTINGS             android.nfc.cardemulation.action.ACTION_CHANGE_DEFAULT
com.android.settings.TTS_SETTINGS                android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
android.settings.ACTION_APP_NOTIFICATION_REDACTION
com.android.settings.SETUP_LOCK_SCREEN            android.settings.ACTION_CONDITION_PROVIDER_SETTINGS
android.app.action.SET_NEW_PASSWORD              android.settings.NOTIFICATION_SETTINGS
android.settings.QUICK_LAUNCH_SETTINGS           android.settings.SOUND_SETTINGS
com.android.settings.QUICK_LAUNCH_SETTINGS       com.android.settings.SOUND_SETTINGS
                                                 android.settings.ACTION_OTHER_SOUND_SETTINGS
com.android.settings.sim.SIM_SUB_INFO_SETTINGS   android.app.action.ADD_DEVICE_ADMIN
android.provider.Telephony.ACTION_CHANGE_DEFAULT com.android.settings.USER_DICTIONARY_INSERT
android.settings.APP_NOTIFICATION_SETTINGS       android.settings.WEBVIEW_SETTINGS
android.settings.APPLICATION_DEVELOPMENT_SETTINGS
android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS
android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS
android.settings.SHOW_ADMIN_SUPPORT_DETAILS      android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS
```

## The four actions DiPlay sends that are on the list

| Action | Where DiPlay sends it | Status |
|---|---|---|
| `android.settings.BLUETOOTH_SETTINGS` | `openBluetoothSettings` fallback | explicit component first, so the decoy is never reached |
| `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` | `openDeveloperOptions` fallback | idem |
| `android.settings.WIRELESS_SETTINGS` | `openCarWifiSettings` fallback | **reached on every hotspot button press** |
| `android.settings.USAGE_ACCESS_SETTINGS` | the small-window "grant access" button | **reached, and has no replacement** |

## The actions DiPlay sends that are not on the list

These work as written. The real screen answers the standard action, so no explicit component is
required and an added one would only be a second code path to keep correct.

| Action | Real activity | Notes |
|---|---|---|
| `android.settings.APPLICATION_DETAILS_SETTINGS` | `...system.application.InstalledAppDetailsActivity` | `priority=1`, expects a `package:` data URI |
| `android.settings.action.MANAGE_WRITE_SETTINGS` | `...main.permission.SpecialPermissionActivity` | `priority` unset, expects a `package:` data URI |
| `android.settings.action.MANAGE_OVERLAY_PERMISSION` | `...main.permission.DrawOverlayDetailsActivity` | `priority` unset, expects a `package:` data URI |
| `android.settings.WIFI_SETTINGS` | `...system.mvp.view.link.wifi.WifiActivity` | also answers `android.net.wifi.PICK_WIFI_NETWORK` |
| `android.settings.ACCESSIBILITY_SETTINGS` | `...system.mvp.view.app.AccessibilityActivity` | **the activity is signature-protected — see below** |

Note the action name for the first three: `SpecialPermissionActivity` and `DrawOverlayDetailsActivity`
declare `android.settings.action.MANAGE_*` (with the `.action.` infix). That is the name
`Settings.ACTION_MANAGE_WRITE_SETTINGS` resolves to, so the constant is correct as used.

## Screens the car does not have

**`com.android.settings.WIFI_TETHER_SETTINGS` has no responder at all.** The string does not occur
anywhere in the image. `openCarWifiSettings` sends it, gets a `null` from `resolveActivity`, and
falls through to the `android.settings.WIRELESS_SETTINGS` fallback — which is on the decoy list. So
every hotspot button on this head unit ends at the decoy. Two knock-on effects:

- The `target == "com.byd.carsettings"` branch, which sends the driver to the car home screen before
  opening the car's hotspot dialog, has never run. There is no `com.byd.carsettings` package in this
  image; `CarHotspotSetup.isBydHeadUnit` has always returned false here.
- The car's own hotspot screen is `...system.mvp.view.link.hotspot.PerHospotActivity`, reachable
  through BYD's own action `android.intent.action.HOTSPOT_SETTINGS` — not the AOSP tether action.

**`com.byd.carsettings` and `com.byd.systemsettings` are both absent** from all 146 packages.
`com.byd.systemsettings` matters because `UsbAutoConfirmService.ACCESSIBILITY_SCREENS` lists
`com.byd.systemsettings/com.byd.systemsettings.accessibility.AccessibilityMainActivity` as its
preferred accessibility screen; that component does not exist on this firmware.

## Bluetooth and developer options: the components are real

Both fixes on `feat/developer-options-entry` target components that the image confirms:

| Component | Source APK | Package | Launchable |
|---|---|---|---|
| `com.byd.btsetting.BluetoothManagerDialog` | `priv-app/BluetoothSettings/BluetoothSettings.apk` | `com.byd.btsetting` | yes — has an `intent-filter`, `targetSdk 26`, no explicit `exported`, so it defaults to exported |
| `com.byd.vehiclesettings.development.DevelopmentSettingsActivity` | `priv-app/VehicleSettings` | `com.byd.vehiclesettings` | yes — `exported="true"` |
| `com.byd.vehiclesettings.system.developmentsettings.DevelopmentSettingsActivity` | `priv-app/VehicleSettings` | `com.byd.vehiclesettings` | yes |

`BluetoothManagerDialog` carries `android.intent.action.MAIN` with no `LAUNCHER` category, so it is
not an app-drawer entry — it is reachable only by explicit component, which is exactly how
`openBluetoothSettings` calls it. The directory name `BluetoothSettings` does not match the package
`com.byd.btsetting`; the mapping has to come from the manifest, not the path.

## Accessibility (辅助功能): the screen exists, nothing opens it

DiPlay's USB auto-confirm needs the driver to enable `UsbAutoConfirmService` under accessibility.
The car does have that screen, and the user-visible label is **辅助功能** (`system_accessibility_title`;
the string is not translated as 无障碍 anywhere in the resources, which is why searching for the
latter finds nothing):

| Class | Role |
|---|---|
| `...system.mvp.view.app.AccessibilityActivity` | the activity, title 辅助功能 |
| `...system.accessibility.AccessibilitySettings` | the list, with `ListAdapter` / `ViewHolder` / `OnItemClickListener` |
| `...system.accessibility.ToggleAccessibilityServiceFragment` | the per-service enable dialog |
| `...system.accessibility.AccessibilityUtils`, `SettingsContentObserver` | helpers |
| layouts `accessibility_main`, `accessibility_settings`, `accessibility_item`, `enable_accessibility_service_dialog_content` | the UI |

It is nevertheless unreachable on this firmware, for four independent reasons:

1. **No launcher entry.** `AccessibilityActivity` has two `intent-filter`s: one for
   `android.settings.ACCESSIBILITY_SETTINGS` with `category.DEFAULT` and `priority=1`, and one for
   `android.intent.action.MAIN` with **no category** — so it never appears in the app drawer.
2. **No in-app link.** Every reference to the class in the dex (29 lines) is inside
   `AccessibilityActivity` and its own inner classes. Nothing else in `VehicleSettings` starts it.
   The settings home (`MainActivity`) hosts 音频 / 驾驶 / 灯光 / 系统 / 车辆, and the 系统 page
   (`SystemFragment`) lists 应用 / 时间 / 语言 / 连接 / 重置 / 单位 / 版本 — accessibility is not
   among them. The string `android.settings.ACCESSIBILITY_SETTINGS` does not occur anywhere in the
   dex: BYD's own app never sends the intent that its own activity answers.
3. **Signature protection.** The activity declares
   `android:permission="android.permission.GRANT_RUNTIME_PERMISSIONS"`, whose `protectionLevel` in
   this `framework-res.apk` is `0x302` — base level `signature` (`|installer|verifier`). A caller
   without the platform certificate is refused with a `SecurityException` before the screen opens,
   regardless of which of the two filters matched.
4. **Nothing else provides one.** The only accessibility service declared in the whole image is
   `com.baidu.input/com.baidu.acs.service.AcsService` (百度输入法). There is no AOSP `Settings`
   package and no other accessibility screen.

So on DiLink 1.0 the UI route to enabling DiPlay's accessibility service does not exist, and
`UsbAutoConfirmService.openSettings` cannot succeed — it will try the standard action
(`SecurityException`), then `com.byd.systemsettings` (absent), then `com.android.settings` (absent),
and return `false`. The ADB route the project already uses —
`settings put secure enabled_accessibility_services` plus `accessibility_enabled 1`, in
[WheelKeyService.kt](../common/src/main/java/com/shilapi/xcertplay/WheelKeyService.kt) and
[UsbPermissionSetup.kt](../common/src/main/java/com/shilapi/xcertplay/UsbPermissionSetup.kt) — is the
only one that works here and should be treated as the primary path on this firmware, not a fallback.

## What DiPlay should take from this

The one rule worth carrying forward is that an explicit component must come before any standard action
in a candidate list, and that `startActivity` returning without a throw is not evidence the driver saw
the screen. Everything else below is what that rule turned into here.

## What was implemented

Two screens were changed, both in `DiPlayActivity`, and both behind the same two-part test:

```kotlin
private val targetedByBydSettingsWork get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1

private fun answeredByBydDecoy(action: String): Boolean {
    if (!targetedByBydSettingsWork) return false
    val info = packageManager.resolveActivity(Intent(action), 0)?.activityInfo ?: return false
    return info.packageName == BYD_SETTINGS_PACKAGE && info.name.contains("unsupport", ignoreCase = true)
}
```

- **Bluetooth** — `openBluetoothSettings()` opens
  `com.byd.btsetting/com.byd.btsetting.BluetoothManagerDialog` where the decoy answers
  `BLUETOOTH_SETTINGS`. All four call sites use it.
- **Hotspot** — `openCarWifiSettings()` tries
  `com.byd.vehiclesettings/...link.hotspot.PerHospotActivity` whenever the AOSP hotspot screen is
  unusable, which is either when nothing answers `com.android.settings.WIFI_TETHER_SETTINGS` (what the
  factory image does) or when the decoy does. Both cases are settled by `answeredByBydDecoy`, not by
  comparing the resolving package: a later build that adds a real tether screen to `VehicleSettings`
  would resolve to the same package and must not be mistaken for the decoy.

When `PerHospotActivity` cannot be opened, the code does **not** hand the driver to the
`android.settings.WIRELESS_SETTINGS` fallback on a decoy build — that action is on the decoy list too,
so it would answer with the same "not supported" dialog that brought the driver here. Where
`answeredByBydDecoy(WIRELESS_SETTINGS)` holds, it shows the same toast as the Bluetooth path instead.
Where it does not, the fallback is taken exactly as before.

`BydSettingsDecoyTest` covers all four shapes: the decoy on Android 7.1 (BYD's screen is opened), a
stock `com.android.settings` resolver, an unrelated app claiming the action, and the same decoy on
Android 7.1's successor (left alone, the standard action is used).

The test needs both halves. The decoy is identified by package **and** class, because
`com.byd.vehiclesettings` also holds the real `WifiActivity` and `InstalledAppDetailsActivity` — the
package name alone would match them too. The version check holds the whole change to DiLink 1.0: on
any other build neither branch is taken, so the code is the code it always was.

**The `<queries>` block was deliberately left alone.** Declaring `com.byd.vehiclesettings` there would
make the decoy visible to `resolveActivity` on Android 11+, which is what would activate this path on
newer head units. Leaving it out means the fix cannot reach them at all — and by the same token, on
Android 11+ the resolution is filtered, so `answeredByBydDecoy` returns false and the standard action
is used, exactly as before. Android 8, 9 and 10 have no package-visibility filtering, so there the
version check is what keeps the change inside Android 7.1.

| Head unit | Behaviour |
| --- | --- |
| Android 7.1, stock AOSP | unchanged — no such package resolves, so the standard action is used |
| **Android 7.1, DiLink 1.0** | decoy detected, BYD's own screen opened |
| Android 8–10, stock or BYD | unchanged — version check |
| Android 11+, stock or BYD | unchanged — version check, and without `<queries>` the decoy is not even visible |

Not changed, because the factory image shows the standard action already reaches the real screen:
`APPLICATION_DETAILS_SETTINGS` (`InstalledAppDetailsActivity`), `MANAGE_WRITE_SETTINGS`
(`SpecialPermissionActivity`), `MANAGE_OVERLAY_PERMISSION` (`DrawOverlayDetailsActivity`) and
`WIFI_SETTINGS` (`WifiActivity`).

## Open questions

- Whether the two changed screens behave correctly on the car has not been measured; the evidence is
  the firmware's intent-filter resolution plus the component declarations.
- `USAGE_ACCESS_SETTINGS` is still unhandled. It is on the decoy list and nothing on this firmware
  replaces it, so the small-window "grant access" button cannot work. It was left alone rather than
  gated, because hiding a control that works elsewhere is a regression on those head units.
- Accessibility is still unreachable through the UI. The ADB route in `WheelKeyService` and
  `UsbPermissionSetup` is the one that works on this firmware.
- `com.byd.systemsettings` is still listed in `UsbAutoConfirmService.ACCESSIBILITY_SCREENS` but does
  not exist in this image. It may be real on another DiLink build, so it was left in place.
- `SpecialPermissionActivity` is the 特殊权限 screen and answers `MANAGE_WRITE_SETTINGS`. Whether it
  shows the same switch the AOSP screen does on this build is unverified.
- Whether a newer `VehicleSettings` links `AccessibilityActivity` into its settings menu is unknown.
  The activity is inherited from AOSP, so a newer build may well expose it.

