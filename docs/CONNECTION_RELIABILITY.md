# Connection reliability validation

Android 7.0 / API 24 is the installation minimum. A head unit's firmware, USB driver,
Bluetooth service, Wi-Fi implementation and video compositor also determine whether
a CarPlay session can run. Record the actual firmware and failed connection stage;
the Android version or vehicle model alone is not a compatibility verdict.

## Evidence and change scope

| Failure | Evidence | Change | What remains unverified |
| --- | --- | --- | --- |
| USBMUX trailer before protocol 1 | [#100 latest capture](https://github.com/shihabal3amri/DiPlay/issues/100#issuecomment-6008931361) reaches carkit/TLS, then rejects a declared length of 1 | Recover one four-byte boundary before/after a complete bounded diagnostic subtype 4 with receive magic; preserve normal and incomplete frames | The reporter also has later protocol/sequence observations. Full USB startup is not established by replaying this framing case. |
| Received video with no displayed frames | [#278 retest](https://github.com/shihabal3amri/DiPlay/issues/278#issuecomment-6016903203), WUTONG S311_ICA / Android 9 / Allwinner T7: the attached window is not accelerated; a contributor's SurfaceView build renders | Select SurfaceView from actual attached-window capability, preserve the content rectangle and surface ownership | New integrated build, overlays, touch, resizing, background/resume and sustained playback on that firmware |
| RFCOMM opens but cannot exchange bytes | [#330](https://github.com/shihabal3amri/DiPlay/issues/330), [#354](https://github.com/shihabal3amri/DiPlay/issues/354): an immediate reader failure and later null output stream. The diagnostic files are identical and constitute one capture. An Allwinner T8 with TopWay firmware, Android 8.1 ([#590](https://github.com/shihabal3amri/DiPlay/pull/590)): the connect returns in 4 to 17 ms, no byte arrives in 12 s, and a connect to a service UUID that no phone offers also succeeds, in 11 and 14 ms in two runs. | Acquire streams before iAP2, preserve typed causes, report first-byte/read status and cached service/bond state, close the socket once. Since 0.2.18, test a link that connected in under 50 ms and delivered nothing with a connect to a service no phone offers, and stop with the cause when that succeeds at once too ([#590](https://github.com/shihabal3amri/DiPlay/pull/590)) | Why the vendor Bluetooth socket is unusable on the #330/#354 unit, and whether the 0.2.18 check fires there. On the Allwinner T8, Android's adapter is not the radio the unit uses: it is on but has no name, `rfkill` lists no Bluetooth radio, and pairing, calls and music are done in the maker's `com.tw.bt` app ([unit record](https://github.com/Boring206/DiPlay-T8/blob/v0.2.12-t8.9/docs/reports/allwinner-t8-topway-2026-10-10.md)). The check ran there with #590's commit on `main` before 0.2.18; the 0.2.18 APK itself has not. Bond state is evidence, not a pairing gate; UUID/security mode are unchanged. |
| RFCOMM cannot be opened at all | [#709](https://github.com/shihabal3amri/DiPlay/issues/709), Changan CS35 Plus / Allwinner T7 / Android 9: on every attempt `BluetoothSocket.connect()` throws `NullPointerException … IBluetoothSocketManager.connectSocket(...)` after 3 ms, because the Bluetooth service returns no socket manager. The car's own A2DP, hands-free and PBAP keep working. 0.2.19 showed "Your iPhone isn't available" and retried without end | Report it as Bluetooth that apps cannot use on this head unit and stop the attempts. Android 13 reports the same condition as `bt get socket manager failed`, which is treated the same | Whether any firmware provides the socket manager later in the same boot. The change has not run on the #709 unit |
| Full RFCOMM receive queue stalls | Source inspection: the reader waits at 65,536 bytes, while the consumer previously never notified it after draining | Wake the reader after both full and partial consumption | No issue capture has established this as its root cause. A transport regression test proves resumed reading and preserved bytes. |
| USB read request cannot be queued | [#135](https://github.com/shihabal3amri/DiPlay/issues/135), [#244](https://github.com/shihabal3amri/DiPlay/issues/244): large USBMUX/NCM request rejection | Once a large queue attempt returns false, try 16 KiB once and retain that size only on success; log attempted sizes and endpoint/API metadata | Whether these particular drivers reject request size. A detached or otherwise unusable connection still fails. |

The queue retry relies on Android's explicit rejection semantics: a failed queue
leaves the buffer unchanged and clears its queued state. Android 9 permits buffers
larger than 16 KiB, so the retry is a vendor compatibility hypothesis rather than an
Android-version requirement. See the [Android 9 UsbRequest implementation](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java).

## Automated validation — 2026-10-06

Based on main `b26cd5443cf19707e7fdbbcc507b038bc37ad989`, the combined changes pass
1,534 unit cases: **1,533 passed**, one existing macOS wildcard-bind assumption skip,
zero failures/errors. Counts are 854 shared, 676 common and four Home. The 47 added
cases cover captured USB framing, queue rejection and large NTB reassembly, RFCOMM
failure/cancellation/backpressure, and video selection, ownership, layout and controls.

Mobile, Home, map-host and Automotive source-only debug APK builds and debug lint pass.
Lint has zero errors/fatal findings; existing warning counts are 18, five, two and four
respectively. All four APKs contain no runtime credential assets. Public-tree and
whitespace checks pass. These are automated source checks, not vehicle acceptance.

## Vehicle acceptance before release

Use a [standalone test build](BUILD.md#standalone-car-test-apk) with an explicit runtime
identity. An identity-free source/CI APK cannot establish iPhone connection success.
Keep each test attributable to its exact commit, APK digest, signing/update path,
firmware fingerprint, head-unit hardware, Android API, iPhone/iOS and connection mode.
Update an existing installation to preserve settings; record a fresh-install test
separately if one is needed.

| Representative setup | Required result | Candidate status |
| --- | --- | --- |
| Issue #100 USB device / iOS version | Reach authenticated CarPlay beyond the repaired protocol-1 boundary, render video and play audio; identify any subsequent failure separately | Not tested |
| WUTONG S311_ICA Android 9 software window | `Video output mode=SURFACE`; displayed-frame count increases; overlays and touch align in full screen, letterbox and cropped view areas | Not tested |
| Known working accelerated head unit | `Video output mode=TEXTURE`; picture adjustments, touch, cluster output and existing connection behavior continue working | Not tested |
| Affected API 28 USB queue-rejection unit | Record both queue sizes and whether fallback succeeds; if it does, verify complete large-frame reassembly, audio/video and reconnect | Not tested |
| Affected early-failure RFCOMM unit | Report getter/read failure stage and whether any bytes arrived; demonstrate authentication before declaring it fixed | Not tested |
| Known working wireless unit | Pair, authenticate, hand off to Wi-Fi, reconnect and maintain audio/video with a sustained stream | Not tested |
| Android 10+ and ARMv7 representative units | Verify startup, decoder output and reconnect independently of the Android 9 fallback case | Not tested |

On each relevant setup, with the vehicle parked:

1. Perform three cold starts and ten disconnect/reconnect cycles in each affected
   connection mode. Include USB unplug during a pending read and Bluetooth teardown
   during bootstrap; the next attempt must not inherit an old request or socket.
2. Run picture, touch and audio for at least 30 minutes. Exercise app background/resume,
   camera-window shrink/restore and available split/view-area modes. Check both received
   and displayed video counters; received frames alone are insufficient.
3. Compare the current released APK with the exact candidate using the same phone,
   port/cable, connection mode and settings. Record any setting changes rather than
   attributing improvement to the candidate alone.
4. Export a diagnostic report immediately after a failure and after a successful retest.
   Include the visible symptom and last completed connection stage. Review exports for
   personal information before posting; do not include runtime identity or Wi-Fi secrets.

Record each row as **pass**, **fail** or **not tested** with its evidence. Automated
tests and a contributor's earlier custom build do not mark the new candidate as passed.

## Remaining investigations

- USB interface-claim failures ([#335](https://github.com/shihabal3amri/DiPlay/issues/335),
  [#340](https://github.com/shihabal3amri/DiPlay/issues/340)) need descriptor, ownership
  and teardown evidence. Read-queue fallback does not repair a failed interface claim.
- Bluetooth's pre-authentication failures need an affected-firmware retest of the new
  diagnostics. Ordinary pairing or `connect()` success does not establish an iAP2 link.
- Wi-Fi Direct performance ([#131](https://github.com/shihabal3amri/DiPlay/issues/131))
  needs a controlled P2P/hotspot comparison. The available report does not prove scan
  suppression or a single channel change resolves it.
- Update-state loops ([#26](https://github.com/shihabal3amri/DiPlay/issues/26)) need a
  preserved-settings comparison and migration evidence. Resetting app data hides the
  original state and is not a general repair.

Do not close an issue or expand a supported-model claim solely because its synthetic
regression test passes. Preserve the distinction between a repaired captured failure,
an accepted device retest, and a remaining firmware-dependent failure.
