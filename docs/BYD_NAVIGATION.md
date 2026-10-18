# BYD navigation displays and vehicle data

Phone navigation arrows, next-turn distance and street names can appear on supported BYD displays. Ordinary operation requires no ADB, root, laptop or helper process. The map app must provide structured navigation metadata; compatibility is not guaranteed for every map app or version.

## Validated windshield path

Live guidance and street names were physically confirmed in both DiAuto and DiPlay on DiLink5.1 / Android13, firmware `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys`. The standalone output is restricted to that firmware and the verified stock receiver version10601004/signing certificate. Other firmware is not implicitly enabled by this result. Existing cluster/SOME-IP outputs remain available on supported factory services; the DiPlay contributor independently reported DiLink5.0 cluster/HUD operation.

The app sends navigation-only broadcasts to the stock ClusterDebug receiver as its normal Android UID. Vendor output runs outside phone control callbacks. Street text uses the installed HAL's UTF-16LE chunk protocol, capped at48 UTF-16 units without splitting a surrogate pair. Output logs exclude street text.

Normal route end, disconnect, disabling navigation output and stale guidance trigger cleanup. Force-stop/process kill may leave the last instruction visible until the app opens again; a recovery journal handles that next launch. There is no guaranteed process-independent expiry. Run only one projection app at a time.

Enable BYD navigation in settings. In DiAuto it is opt-in under Navigation; in DiPlay it is enabled by default when available. Debug-only receivers/demos require Android's DUMP permission and are absent from release manifests. Development starter and vendor-access experiments are not part of the production navigation path.

## Cluster guidance on FCE head units (needs no ADB)

Some head units built on the FCE/Coagent T7 platform have an instrument cluster with a CAN navigation view. The stock head-unit service sends that view only navigation start and stop and drops each guidance update, so it stays empty. When the head unit has the stock `fce_misc_service_hal_property` service, DiPlay writes the guidance there as its normal Android UID through `ICarProperty.setProperty` (transaction 5): navigation status 2 (`0x214000a5`), the next-turn glyph (`0x21400099`), and the distance in metres (`0x21400098`). The cluster formats kilometres itself. The second icon set (`0x2140009a`) is held at 0, because a leftover value blanks the U-turn and roundabout glyphs.

The output is off by default. It needs **Navigation on HUD and dashboard** and its own switch, **Guidance in the FCE cluster (experimental)**, in the same card: the property IDs were confirmed on one car, and another head unit with the same service could give them other meanings. On these head units, that card appears even without a BYD receiver. Apple maneuvers map to the cluster's AutoNavi glyphs: turns, slight and sharp turns, U-turns by driving side, roundabout exits 1–10, arrival and straight. The view is unlocked as soon as the iPhone reports an active route or names a navigation app (RouteGuidanceUpdate state or source name), even when that app sends no maneuvers, as Google Maps did on an older iPhone. Arrows and distance appear only with a maneuver. An unknown maneuver shows no arrow, never a wrong one. Distance is rounded to 10 m below 1 km and to 100 m above. Arrival, no route set, an empty source name, turning the switch off and the end of the session clear the view with the stock end sequence (icon 0, distance 0, status 1 then 0). A force-stopped DiPlay can leave the last arrow until navigation runs again.

Basis: on a car with this head unit and cluster, the property sequence, the glyph codes and the no-root Binder path were confirmed by another app that fed it Android Auto guidance; 2,500 m showed as "2.5 km". The CarPlay integration has not yet run in a car. Street names, lanes, the speed-limit sign and switching the cluster to its navigation page are not available on this path. The driver selects that page once, and the cluster remembers it. Left-hand-traffic roundabout glyphs are unknown.

## DiLink 3.0 cluster guidance and map (experimental, needs ADB)

DiLink 3.0 head units (Android 10, Qualcomm 6125, "1for2" cluster) have no SOME/IP service and ship the stock AMap adapter as `com.example.amapservice` instead of `com.byd.amapservice`. DiPlay sends it the same navigation broadcasts. That adapter shows preformatted text rather than the numeric extras, so DiPlay also sends `SEG_REMAIN_DIS_AUTO` ("250 m"), `ROUTE_REMAIN_DIS_AUTO` ("5.4 km"), `ROUTE_REMAIN_TIME_AUTO` ("10 min") and `ETA_TEXT` ("15:55"); without them the cluster shows -1. The cluster keeps its stock view until it is switched, so DiPlay also runs, through its adb shell, the calls the stock ClusterDebug app uses (`service call AutoContainer 2 i32 1000 i32 <command> s16 ""`):

- While CarPlay guidance is active: 39, "simple navigation", for the native turn card.
- While "CarPlay map on dashboard" shows its map window on the cluster: 17, "half-screen projection", sent 1 s after 16, "full-screen projection". After 18 the cluster ignores 17 on its own and its projection area stays empty; sending 16 first brings it back. The map window uses DiLink 3's projection display, `fission_bg_xdjaVirtualSurface` (1920x720, owned by `com.xdja.containerservice`). The map takes priority over the turn card.
- When both end: 18, "projection off", only if DiPlay changed the mode.

Approve DiPlay's ADB access once with "Check ADB access"; without it the broadcasts are still sent but the cluster keeps its stock view. The DiLink 5 "Dashboard map only in Small and Full navi" option does not apply: DiLink 3 does not report the wheel-menu mode.

Projection entry validates the replies from 16 and 17 separately. A refused or missing reply,
interrupted delay, or withdrawn map request attempts 18 immediately so full-screen projection
does not remain active while waiting for the normal retry. The recovery marker is durable before
16; failed restoration retains it and blocks new output until stock mode is restored.

The projection display does not exist after the car starts until the cluster has projected once. When DiPlay opens with BYD navigation on and the display is missing, it runs 16 (projection on), 35 (Di4.0 mode, which creates the display) and 18, as BYD DashCast does; the cluster shows an empty projection area for about six seconds. The display then stays until the car restarts. The map window selects this display by its exact name and one of two observed sizes: 1920x720 uses the measured DiLink 4.0 stream, while the 1280x480 surface reported by one DiLink 3 car gets a generic stream sized to that display. Recognition of the smaller surface still needs an on-car map-output test; other 8:3 sizes are not automatically selected. If a CarPlay session started before the display existed, DiPlay shows the map window when the display appears and reconnects once so the iPhone sends the cluster stream.

Basis: on a BYD Han EV (GCC, DiLink 3.0 / Android 10), over shell: 16 then 35 created `fission_bg_xdjaVirtualSurface` (display 1, 1920x720, `FLAG_PRESENTATION`, not private, owner `com.xdja.containerservice`), and an ordinary app launched there appeared in the cluster's projection area with speed and readouts still visible (17 and 16 looked the same); 18 restored the gauges and the display remained. A broadcast with these extras followed by 39 showed the turn arrow, road, distances and ETA on the cluster; the windshield HUD showed nothing. The windshield HUD stayed blank in every test while driving, in both 39 and 18 cluster layouts. This was true even though the adapter wrote the instrument CAN guidance registers, including remaining time and ETA (those parse only from Chinese-format text such as "10分钟" and "预计今天15:55到达", which the cluster then shows in Chinese, so DiPlay keeps English text). The car reports a W-HUD (`SET_HUD_CONFIG` 1) with HUD navigation enabled (`SET_DYNAMIC_NAVI_FUNCTION_STATUS_FEEDBACK` 1), but its settings page shows only the ADAS option. DiPlay therefore claims no HUD guidance on DiLink 3. DiPlay's own map window there is not yet confirmed. A force-stopped DiPlay can leave the cluster switched until DiPlay next restores it.

## CarPlay map on the instrument cluster (experimental)

DiPlay can ask the iPhone for CarPlay's second, instrument-cluster screen and show it in the BYD cluster's map area. The iPhone renders this map itself; DiPlay decodes the stream onto the cluster projection display. No root or persistent helper is needed. The optional DiLink 5.1 automatic mode described below needs a one-time permission setup.

Validated on DiLink5.0 / Android12, firmware `BYD-AUTO/DiLink5.0/DiLink5.0:12/SKQ1.230128.001/eng.build.20251111.182747:user/release-keys`, with Apple Maps on iOS 27:

- Turn on "CarPlay map on dashboard" under BYD navigation. The switch appears only when a cluster projection display is visible to the app.
- In the cluster's own steering-wheel menu, choose "Full screen navi" or "Small screen navi". "Turn on by navi" shows arrows only, even during CarPlay navigation.
- The cluster's speed readout stays visible. "Small screen navi" crops the same picture on the cluster side; Android does not report that crop.
- The car marker is placed through CarPlay's safe area: the centre of the panel (x 35–64 %, y 16–75 %), measured with a calibration grid to be clear of BYD's own readouts in both Full and Small screen navi. "Car marker · horizontal" (Left 40 % … Right 40 %) and "Car marker · vertical" (Up 30 % … Down 30 %) move it from there in 10 % steps of the panel. Near a panel edge the safe area shrinks so the marker stays at its centre.
- "Dashboard shows" picks which of the cluster contents the iPhone offers in `altScreenURLs` DiPlay asks for: Map (`maps:/car/instrumentcluster/map`, default), Turn card (`…/instructioncard`) or Map with turn card (`maps:/car/instrumentcluster`). On the tested car the turn card fit the Small screen navi window well and streamed only 0–5 kbit/s against 0.3–4 Mbit/s for the map. The iPhone lays out the map, the car marker and the iOS glass turn card inside the same safe area, so the position settings below move whichever is shown; they are labelled "Car marker" or "Turn card" to match. The glass card cannot be moved on its own: it is painted into the dashboard video, not a separate Android view.
- Switching "Dashboard shows" between the iPhone's own contents applies at once, without reconnecting: DiPlay sends `showUI` with the new URL and `forceKeyFrame` for the cluster screen (`{"uuid": <alt screen UUID>, "url": …}`), the same commands as the map pause below. On the contributor's Tang lab build the dashboard switched within a second; without a route the turn card shows Apple Maps' pinned places. A paused map stays paused and comes back with the new content. Failed delivery falls back to reconnecting for the current selection. A recreated cluster stream starts at its initial URL, then reapplies the live selection after SETUP/event readiness; wheel zoom follows delivered content. A replacement phone in the retained connection inherits the last delivered or safely retained paused selection. Switching between Map and DiPlay's own card uses the existing live overlay update; a different URL involving that overlay, or the DiLink 5.1 layout, reconnects.
- "Dashboard map size" (or "Turn card size") sets the stream size, which the cluster scales up to the panel: Standard (100 %, sharpest), Larger (83 %, 1600x600, default) or Largest (67 %). Apple Maps ignores the reported physical size on the cluster, so resolution is the only way to change the map's scale.

How it works: the iPhone lists the cluster content it offers in its `/info` request (`altScreenURLs`). DiPlay declares a second display of the cluster's size with no input devices and `initialURL=maps:/car/instrumentcluster/map`; without an initial URL the iPhone streams only a black frame. BYD exposes the cluster projection area as public presentation displays owned by `com.byd.containerservice`. The stock map's display (`fission_bg_XDJAScreenProjection`) is hidden from third-party apps, but its `shared_…_0` sibling is composited on top of it, so DiPlay shows a `Presentation` there.

Limits: the cluster window belongs to the CarPlay screen, so it stops while that screen is closed and the session runs in the background. Other map apps and other firmware are untested.

The optional centre-screen map card mirrors that same dashboard stream. **Keep the home-screen map in sync with the dashboard** (on by default) shows the card. Turn it off to hide the home-screen card so only the instrument cluster shows the dashboard map. This does not change what CarPlay draws on the main screen.

### DiLink 5.1 theme profile

The exact Android 13 firmware `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys` has a separate profile for its measured 1920×720 cluster. Other firmware retains the original PR display selection, renderer and settings.

- Map uses shared display `_0`, with a 1920×480 viewport at y=144 and contrast bands behind the instrument readouts.
- Scenario and Simple use shared display `_1`, with a 600×720 side viewport at x=1320. Everything outside the side map is transparent. No white mini-map shading is added.
- The iPhone sends one continuous 1920×720 map. DiPlay crops it at native scale into each viewport, centered horizontally and aligned to the bottom to retain the vehicle marker. Theme changes do not restart CarPlay. The side crop shows less surrounding map area than a separately negotiated portrait stream.
- The two rendering surfaces remain alive when cards close. Window opacity controls visibility, and direct decoder surface handoffs preserve its video reference frames.

Enable **Follow instrument theme and map card** to follow the stock cluster activities. Android Usage Access is required because BYD's theme API is signature-protected. This firmware has no working Usage Access settings page, so the owner must approve one-time ADB setup. For the HUD Test package:

```sh
adb shell appops set com.shihab.diplay.hudtest GET_USAGE_STATS allow
```

Open **Settings → BYD navigation → Automatic map setup · ADB** for the guided setup, available even when automatic mode is off. It shows the command for the installed package, offers a copy button, explains multiple-device ADB selection, and displays the current permission status. After running the command on your computer, tap **Check and enable** to verify permission and enable both the cluster map and automatic following. If permission is still missing, the guide stays open with instructions; an active CarPlay session reconnects once after successful setup. The copy button copies to the car clipboard, so the command is also displayed for typing on your computer. To revoke it, substitute `default` for `allow`. Without access or a recognized active theme, automatic mode hides the overlay. Manual mode remains available but cannot follow card visibility. If no current theme can be inferred after startup, select a different theme once to produce a fresh event.

Only the four stock full-map, mini-map, Scenario and Simple activity events are processed locally. Closing the mini-map card hides the side overlay; Map theme selects the full layer. Merely moving focus to a head-unit app does not hide a still-visible cluster activity. See [Android's UsageStatsManager documentation](https://developer.android.com/reference/android/app/usage/UsageStatsManager) for the permission model.

### Dashboard map only in Small and Full navi (optional, needs ADB)

The iPhone draws and streams the cluster map for the whole session, even while the cluster shows no projection: in Off and "Turn on by navi" the cluster draws arrows only (in "Turn on by navi" the stock map even removes its own cluster window). With "Dashboard map only in Small and Full navi" turned on, DiPlay reads the mode the driver picked on the wheel every second and, while it is Off or "Turn on by navi", sends `stopUI` for the alt screen (`{"type": "stopUI", "params": {"uuid": <alt screen UUID>}}`). When the driver picks Small or Full screen navi it sends `showUI` with the map URL (`{"uuid", "url": "maps:/car/instrumentcluster/map"}`) and `forceKeyFrame` for the same UUID. CarKit handles both as car-initiated commands (`_handleStopUIWithParameters:` / `_handleShowUIWithParameters:`); the stream stays up, so nothing reconnects.

Measured on the car: after `stopUI` the cluster stream carried no frames at all while the main screen went on as usual; after a switch on the wheel `showUI` went out about 0.6 s later and the map was back within a second. If the mode cannot be read (no ADB access), DiPlay keeps the map streaming as without the setting.

Ordinary apps cannot read the mode: BYD's `INSTRUMENT_NAVI_TYPE` needs a BYD signature. The adb shell reads it through the `autoservice` binder (instrument device 1007, feature `0x40C03032`): `service call autoservice 5 i32 1007 i32 1086337074` → `Parcel(00000000 0000000N)`, N = 1 Off, 2 Turn on by navi, 3 Small screen navi, 4 Full screen navi. (The shell can also set it through `INSTRUMENT_NAVI_TYPE_SET`, `0x4C10A018`, with `service call autoservice 6 …`; DiPlay does not change the mode.)

DiPlay runs the read through the head unit's own adbd on `127.0.0.1:5555` ("ADB over network" in developer options) with its own RSA key. The car asks once to allow that key; DiPlay offers it only after an explicit settings action, never during background validation or while driving. The TLS pairing flavour of wireless debugging is not supported.

### Dashboard map zoom from the steering wheel (optional)

CarPlay lets the car zoom the cluster map: the accessory sends the command `changeMapZoomLevel` with the cluster screen's UUID and a `zoomDirection` (`{"type": "changeMapZoomLevel", "params": {"uuid": <alt screen UUID>, "zoomDirection": N}}`), the same family as `stopUI`/`showUI`. With Apple Maps on a Tang, `zoomDirection` 0 zooms in and 1 zooms out (2, 3 and -1 also zoomed out). The iPhone does not answer these commands on the event channel, so the values were checked on the dashboard.

The Tang's wheel has no spare keys for this, and BYD's window manager takes the wheel keys before any app can see them: volume (`KEYCODE` 291 / 292, scan 115 / 114, device `simulate-keys`) changes the volume, the custom key (305, scan 300) runs the action chosen for it in BYD's settings (screen rotation on our car). An accessibility service that filters key events receives keys earlier, in Android's input filter, and may keep them. With **Settings → BYD navigation → Zoom the dashboard map with the wheel** turned on and DiPlay's wheel key service running:

- while the dashboard shows the CarPlay map, the custom key switches the volume keys to map zoom (volume up zooms in, volume down out) and back; the mode key can instead turn zoom on for five seconds after the last zoom press;
- the mode shows briefly as a toast and, where the song shows on the dashboard, as "🔍 Map zoom" (with BYD's Bluetooth-music icon) or "🔊 Volume" for three seconds (this note uses the dashboard song's ADB access);
- without the dashboard map the custom key keeps its BYD action and zoom mode ends; during a call (Android in a call or communication audio mode, which DiPlay sets for CarPlay calls) the volume keys always control the volume;
- every key can be reassigned in the settings by pressing it, for wheels with other codes. Assignment expires after ten seconds and is cancelled when leaving the settings or disabling the feature.

Map loss, a new CarPlay session, or disabling the feature ends zoom mode; reconnecting requires another mode-key press. Each physical key keeps the same consume/pass decision from its first press through repeats and release, including a call or timeout that begins during that press.

The contributor tested an earlier lab build on a 2024 Tang. The updated upstream implementation has local regression coverage, but call-volume behavior and these lifecycle changes still need a vehicle retest.

On the Tang the console's volume control sends exactly the same codes, scan codes and input device as the wheel's volume keys, so while zoom mode is on it zooms too. The phone (313) and short voice (304) keys are taken by BYD earlier or have their own action and are not used.

BYD's settings have no accessibility page. The "Turn on the wheel key service · ADB" button adds DiPlay's service to `enabled_accessibility_services` through the head unit's own adbd (the car asks once to allow DiPlay's key) and keeps services already listed. The service only receives key events; it declares no window-content access.

The diagnostic report shows what the service was given. After the `Wheel key service:` line, `Wheel keys since DiPlay started:` counts the presses, how many DiPlay kept and how many it left to the head unit, and up to 40 `Wheel keys:` lines follow with the time, `key <code>/<scan code>`, the input device number and the outcome. A volume key that navigation volume left alone carries the reason. `presses=0` with the service bound means the head unit handles its wheel keys before Android sees them, so DiPlay cannot use them. The lines are kept in memory and start again when DiPlay's process does. A key that writes text is counted (`textKeysNotListed`) and never listed.

### CarPlay joystick on the steering wheel (optional)

DiPlay already declares a rotary knob for CarPlay's main screen (HID `xcertplay Knob`). Checked with Apple Maps and lists on a Tang: only the knob's turn moves CarPlay's focus (the first turn opens the Maps side panel with search, pinned places and recents), select activates the focused item and back closes the screen; the knob's x/y nudges only pan a focused map, so the joystick does not use them.

With **Settings → BYD navigation → CarPlay joystick on the wheel** turned on and the wheel key service running (see above):

- BYD's media key (289, scan 89, normally opens BYD's media app) turns the joystick on and off; without a CarPlay session it keeps its BYD action;
- while the joystick is on, previous/next (88 / 87) and the volume roller (291 / 292) turn the knob one step back or forward, play/pause (353, scan 505) selects and the custom key (305) goes back. With the joystick off the custom key switches the map zoom as before (if that setting is on); turning the joystick on ends zoom mode;
- a toast shows the keys at a glance, and "Joystick on" / "Joystick off" shows briefly where the song shows on the dashboard;
- "Joystick turns off by itself" (on by default) ends it 15 seconds after the last press and when a route starts, so the keys go back to music and volume; a new CarPlay session, or turning the setting off, ends it too;
- during a call every key keeps its usual action;
- every key can be reassigned in the settings by pressing it; a key assignment takes the next press before the joystick does.

The wheel's dashboard-menu key would be the natural back key, but BYD takes it before the input filter (keycode 309, consumed while queueing), and even read from the input device the dashboard still opens its own menu on the same press. So the custom key goes back instead.

### CarPlay calls: wheel call keys and the dashboard (DiLink 3)

BYD's own CarPlay app (`com.byd.carplay.ui`) answers CarPlay calls from the wheel and shows them on the instrument cluster and the HUD. It relies on `sys.carplay.*` properties that only BYD's root CarPlay daemon can set, so with DiPlay BYD's window manager treats the call key as a Bluetooth phone key. Read from the firmware (`PhoneWindowManager.interceptKeyBeforeQueueing`, `BinderCarplayServer.CarplayNotifyInstrumentCallState`):

- the call key (313) is handled while queueing, before the input filter: BYD opens its Bluetooth phone screen on release, but the key still reaches the focused app and the wheel key service;
- the hang-up key (314) and the multifunction "menu" key (309) reach no app; on release BYD sends `com.byd.btcall.action.CLOSE_BLUETOOTHSETTING` with the key code to the current user;
- the CarPlay voice keys (327 short, 328 long) reach the wheel key service before BYD's window manager keeps them.

DiPlay follows the iPhone's calls from iAP2 CallStateUpdate (0x4155, already subscribed). The separate
**CarPlay call, Siri and media keys (experimental)** setting is disabled by default and requires explicit opt-in.

With that setting enabled and a CarPlay session active:

- the phone key (313) answers a ringing call with CarPlay's telephony HID Hook Switch, and is consumed without hanging up during a connected call. It reaches DiPlay through the focused CarPlay screen or the wheel key service; outside the CarPlay screen, the service is needed. Since BYD has already opened its phone screen by the key's release, DiPlay brings CarPlay back to the front;
- the hang-up key (314) and multifunction mode/menu key (309) end or decline the call (telephony HID Drop), through BYD's broadcast. This path requires the call-key setting to be enabled, but does not depend on the wheel key service;
- next track answers and previous track declines only while a call rings. Neither track key ends a connected call;
- with a CarPlay session, the CarPlay voice keys open Siri through the wheel key service. **Siri on long press only** (Settings, wheel keys) makes a short press of the voice button do nothing in DiPlay; only the long press (328, or 312 on the CarPlay screen) opens Siri. It is off by default. DiLink 3's play/pause key (331) toggles CarPlay playback. One press of the wheel's voice key also sends the generic voice key (304 short, 312 long) about half a second later, which the CarPlay screen would turn into a second Siri request that ends the first; only the first key within 1.5 s opens Siri. Without the wheel key service, BYD's window manager treats CarPlay as not connected (DiPlay does not set `sys.carplay.connected`) and opens its Bluetooth page on the long press (328).

The Siri and media support above applies specifically to DiLink 3 keys 327/328 and 331. This setting
does not control ordinary media-key mappings or the separate configurable wheel-key settings.
With this setting disabled, these experimental call-key and dedicated Siri/play-pause mappings are not applied.
Outside a CarPlay call every call key keeps BYD's action. The exported hang-up receiver requires
the sender's `android.permission.DUMP`; BYD's system-server window manager can send it, while an
ordinary third-party app cannot. Actual sender/key behavior still requires vehicle acceptance.

**Settings → BYD navigation → CarPlay calls on the dashboard** (optional, needs ADB over network) also writes what BYD's CarPlay app writes, through the adb shell (`BydCarPlayCallTool`, feature ids resolved on the car): the instrument's call state and caller (device 1007, `INSTRUMENT_CALL_STATE_SET`, `INSTRUMENT_CALL_INFO_SET` as UTF-16LE up to 60 bytes), the call time every second (`INSTRUMENT_CALL_TIME_HOUR/MINUTE/SECOND_SET`), the car's call state (device 1023, `SET_CALL_STATE_SET`, `SET_CMD_BTCALL_STATE_SET`: 2 ringing, 1 dialing, 3 active, 5 ended). It deliberately does not set the audio system's CarPlay call status (device 1002, `AUDIO_CARPLAY_CALL_STATUS`) during a call: on a GCC DiLink 3 Han that switches the amplifier to BYD CarPlay's call channel and the caller goes silent, because BYD's audio service plays a third-party app's voice stream as media. The call end still writes 1 (idle) so a stale status is cleared. The call time comes from a small watcher under the adb shell that also ends the call on the car if DiPlay's process goes away mid-call.

Before its first vehicle write, a call reserves a token and waits for confirmation that its shell
watcher initialized. A failed launch or missing readiness acknowledgment prevents new call-state
writes; one retry is attempted for an unchanged call. Cancelling a reservation that has made no
vehicle writes does not send idle values; failed or uncertain writes retain cleanup ownership.

Each displayed-call lifetime has a new package-qualified UUID token. The shell tool serializes its
ownership marker and vehicle writes with a file lock. A stale watcher, another variant's old
process, or a prior PID cannot update or clear a newer call. Cleanup retires only its own token;
an end write that throws retains ownership for retry. A queued update rechecks that dashboard
output remains enabled before writing. If the car refuses one of a call's writes (a non-zero result
or an error), the tool sets the writes it already made back to their idle values, newest first, and
reports a failure so DiPlay retries on the next call update instead of treating the call as shown. A
feature this firmware does not define is skipped, not undone. End writes are all attempted; a
refused one keeps ownership so the end is retried. The idle values are what BYD's CarPlay app sends
when a call ends, not a snapshot of the previous state, because these command features have no
confirmed read-back. This bounds lifecycle interference; it does not establish
that every vehicle feature id/value or partial-write outcome is correct on a particular firmware.

The microphone already follows the iPhone's stream type: a call records with `VOICE_COMMUNICATION` and the platform's echo canceller and noise suppressor in communication mode, Siri with `VOICE_RECOGNITION`.

BYD plays a third-party call as media, so the car's own echo canceller never sees it and the amplifier's bass applies to the voice. Two experimental call settings, off by default and applied at the next connection, can compensate inside DiPlay:

- **Call echo cancellation** runs SpeexDSP's echo canceller (250 ms tail, BSD licensed, `shared/src/main/jni/speexdsp`) on the call microphone, using what DiPlay itself just played as the reference. It needs mono capture at the downlink sample rate; otherwise the call continues with the platform effects only (`microphone echo canceller enabled=false` in the log).
- **Clearer call voices** removes the lows below 200 Hz from the caller's voice (4th-order high-pass) before playback.

With **CarPlay calls on the dashboard** on, DiPlay arms the call watcher when a CarPlay session starts, so the first call's caller name reaches the cluster and HUD without waiting for the watcher to launch. An armed watcher makes no vehicle writes; it is cancelled when the session ends or the setting is turned off.

Both experimental settings default off. The opt-in feature is included for release testing;
physical acceptance still needs confirmation on each target firmware: answering/ending a real call
from the wheel, caller card and timer on cluster/HUD, microphone routing, cancellation/disable and
process-death cleanup. The reported live key codes alone do not prove these behaviors.

### Car phone audio (experimental)

Two more settings under **Settings → Advanced → Video and audio**, off by default, let the car's own call processing handle the echo instead of DiPlay's canceller. On a GCC Han (DiLink 3, Android 10) the car's audio layer has a phone state that BYD's Telecom enters for any call (`AudioManager.setCallState(CALL_CLIENT_BT, MODE_IN_CALL)` in the Telecom log). Measured with a test app, not yet with a real CarPlay call:

- Outside that state the `VOICE_COMMUNICATION` microphone has an always-on automatic gain of about +14 to +27 dB and clips, so the echo comes back loud and no software canceller can model it (SpeexDSP removed 0.7 dB).
- Inside it the same microphone returned digital silence for the far end's echo (100% of 609 frames with the cabin quiet) and still passed a voice, but clipped it hard (13 to 23% of samples in 8 to 14 s counts at about -4.5 dBFS, against under 1% outside it). The plain `MIC` source is muted in the state.
- The other call-capable sources do not have that problem in the state. On the same Han, with the voice counted aloud, `VOICE_RECOGNITION`, `UNPROCESSED`, `CAMCORDER` and `VOICE_PERFORMANCE` gave about -16 to -20 dBFS with no clipping, passed the voice while the far end was playing (double talk), and left only a brief low-level burst of the far end's echo (about -44 dBFS over 8 s with the voice silent) where `VOICE_COMMUNICATION` leaked a loud, clipping burst. The second capture channel is muted in the state (it carries a separate microphone outside it).

Any app may register a self-managed Telecom call with the normal permission `MANAGE_OWN_CALLS`, so DiPlay holds one while a call needs the state:

- **Car phone audio for CarPlay calls** holds the call from the iPhone's first call frame until the call ends, plus two seconds so back-to-back calls do not leave the state. While it is on, DiPlay's own echo canceller and the platform effects stay out of the CarPlay call microphone. If Telecom refuses the call, DiPlay's canceller is used again for five minutes (`Telecom refused the call that selects the car's phone audio state` in the log). Applies at the next call.
- **Car phone audio for VoIP calls in other apps** does the same while another app both records from `VOICE_COMMUNICATION` and plays with the matching usage, which is how WhatsApp, Telegram, Teams, Zoom and similar apps hold a call. A recording alone is not enough: while the state lasts BYD's own audio layer keeps `VOICE_RECOGNITION` and `VOICE_COMMUNICATION` recorders open, so they never end on their own. Voice notes, video, assistants and any other recording use other sources and are never routed, because the car mutes the plain microphone in the state. It needs DiPlay running outside a CarPlay session as well, so turning it on also enables the wheel-key accessibility service, and it only watches while that service, the app or a CarPlay session keeps DiPlay alive.

Log tag `DiPlay-PhoneAudio` shows when the state is entered and left. If the car's hands-free link is connected, Telecom already holds a managed call for the iPhone and may refuse a second one; pausing the car's Bluetooth radio during CarPlay avoids that.

While the CarPlay call holds the state, DiPlay records the call from `VOICE_RECOGNITION` instead of `VOICE_COMMUNICATION`, which clipped. The stock CarPlay service opens its microphone stream without an input preset, which AAudio treats as `VOICE_RECOGNITION`. Geely/Lynk head units, where that source records silence, keep `VOICE_COMMUNICATION`. One Han owner described the call voice with `VOICE_COMMUNICATION` as very bassy with far too much gain; after listening to recordings of each source on a PC, they preferred `VOICE_RECOGNITION`, which they described as clear and natural.

## ADB vehicle-data settings and firmware scope

Settings → Location contains **Advanced vehicle data**, collapsed by default, with two saved modes.
**Default mode**, validated on DiLink 5.0 head units, uses the 0.2.10 CAN/CANFD battery selection and
the established DiLink 5.0 speed/gear addresses. **Legacy head-unit detection**, tested on controller
13 / DiLink 3.0, resolves and validates the current firmware's fields. Selecting legacy detection for
the first time may offer DiPlay's ADB key and continues directly into the read-only probe. The probe
opens its own connection, which needs the key saved with "Always allow"; a refusal right after
approval is retried once, because adbd saves the key just after confirming it. A failed
probe leaves the current mode unchanged. Changing the mode reconnects an active session only when a
battery, wheel-speed or parked-video switch is on.

In default mode, **Check ADB access** shows the battery, speed and gear it read, and marks a value an
enabled switch needs but cannot read. Turning a switch on runs the same check; an active session
reconnects only once every enabled switch's data is readable. Otherwise the current connection stays,
and a later successful check applies the switch.

The probe launches a one-shot `app_process` under the shell uid, reflects the running firmware's
`BYDAutoFeatureIds` and `BYDAutoConstants`, and exits. It resolves only speed, gearbox, SOC, electric
range, remaining battery energy and BMS state, then validates every candidate with read-only
`autoservice` transactions 5/7. The first failed read ends the probe as incomplete. It makes no vehicle writes, starts no persistent helper and never
enables ADB. A complete successful result is committed as the last-known-good field snapshot;
`Build.FINGERPRINT` and `Build.DISPLAY` are retained only as diagnostic metadata. In legacy mode the
vehicle-status, wheel-speed and parked-video capabilities stay inactive until their required fields
pass the first probe.

A successful probe, the selected mode and every exposed feature switch are loaded after returning from CarPlay,
shifting gear, activity recreation, process restart and an in-place app update. ADB transport failure
does not hide them. When ADB is READY but the saved fields are unreadable on two complete checks,
DiPlay automatically probes again. Only a complete successful candidate replaces the old snapshot;
failure keeps the old snapshot and exposes a manual retry. A complete candidate that no longer
confirms a saved field is held: the page names those fields and offers **Replace saved vehicle data
anyway**, which never overwrites a snapshot saved in the meantime.

The existing **Dashboard song** switch needs ADB, not the navigation receiver. It stays in the BYD
navigation card where that card is shown and otherwise appears once under Advanced vehicle data. It
is not part of the legacy probe.

The numeric feature IDs below are used in default mode. Legacy mode uses the saved probe addresses.
Known controller-13 values are candidates only and must still return a plausible live reading before
DiPlay accepts them.

## Car battery for the iPhone (optional, needs ADB)

CarPlay's vehicle status lets the car tell the iPhone its charge and range; Apple Maps then warns about a low charge and offers chargers on the way. With "Car battery for the iPhone" turned on, DiPlay declares an electric vehicle in its iAP2 identification (VehicleInformation with engine type electric and the chosen charging connectors, VehicleStatus with range, range warning, charge and maximum range) and answers the iPhone's StartVehicleStatusUpdates (`0xA100`) with VehicleStatusUpdate (`0xA101`) every 30 s.

DiPlay declares the electric vehicle only when it already has a battery reading as the iPhone identifies the accessory. With ADB off or not approved, or on a car without these properties, the identification stays as without the switch, and the log says `iap2 no battery reading: not declaring an electric vehicle`. The controller-13 probe publishes the reading it validates before the switch can appear.

"Charging connectors" picks what the iPhone is told the car can plug into: CCS2 and Type 2 (Europe, the default), GB/T DC and AC (China), or CCS1 and J1772 (North America). Pick the one that matches the car's charging inlet.

The values come from the adb shell (apps need a BYD signature for them), read every 30 s while the iPhone asks. In default mode every sample first runs `getprop ro.car.protocol` and selects the CAN or CANFD addresses below. Legacy mode instead uses the addresses confirmed by its saved probe. Empty, unreadable or unsupported values produce no battery reading.

For `CANFD`, the existing addresses are:

- charge: statistic device 1014, `0x4A505038` (`STATISTIC_ELEC_PERCENTAGE`), float percent — `service call autoservice 7 i32 1014 i32 1246777400`;
- range: 1014, `0x4A50203E` (`STATISTIC_ELEC_DRIVING_RANGE` on this platform), km — `service call autoservice 5 i32 1014 i32 1246765118`;
- energy left: power device 1005, `882901008` (`POWER_BATTERY_REMAIN_ELECTRICITY`), float kWh;
- charging: charging device 1009, `876609560` (BMS state, 1 = charging).

For `CAN`, the verified SDK addresses are:

- charge: float percent — `service call autoservice 7 i32 1014 i32 1033543720`;
- range: integer km — `service call autoservice 5 i32 1014 i32 1033203771`;
- charging: BMS state, 1 = charging — `service call autoservice 5 i32 1009 i32 876611608`.

An October 2, 2026 ADB capture from a head unit reporting `CAN` returned 51 %, 36 km and charging state 1. The separate integer SOC interface agreed at 51 %. Remaining energy is not verified on this protocol: the CANFD energy command returned zero. DiPlay therefore does not run it for CAN, keeps energy and capacity unknown, and omits the Wh parameters from vehicle updates while retaining SOC, range and charging state. The contributor has also confirmed testing on their vehicle.

Full charge is estimated only from known energy; full range is scaled up from the current range and SOC. A protocol change discards the previous capacity estimate, and a failed sample clears the cached reading and capacity. Settings and background samples are serialized so an older read cannot overwrite a newer protocol's data. At or below "Low charge warning" (20 % by default) DiPlay sets the range warning. On the CANFD car above DiPlay read 25 %, 150 km and 25.1 kWh, and with the warning threshold at 30 % Apple Maps offered to find a charging station. The suggestion comes from Apple Maps and iOS; Google Maps did not react in testing.

If CarPlay connected before the first battery reading was available, battery reporting stays off for that connection. Enabling the setting reconnects an active session once a default-mode ADB check reads the battery, or after a successful legacy probe; returning after idle requests an immediate background refresh rather than waiting for the next 30-second poll. The UI and iAP2 loop never wait for ADB.

Vehicle-data mode, battery, wheel speed and parked-video settings have one owner under **Settings → Location → Advanced vehicle data**. The separate BYD ADB card controls optional car-hotspot startup. It does not expose another set of vehicle switches or override which fields the selected vehicle mode supports. Hotspot authorization and user vehicle checks/probes wait for one another; saved legacy validation resumes after a hotspot authorization finishes.

## Wheel speed for tunnels (optional, needs ADB)

"Report location to iPhone" (Settings → Location) sends the head unit's position as `$GPGGA` + `$GPRMC` in iAP2 LocationInformation (`0xFFFB`). In a tunnel or car park there is no fix, and the iPhone has only its own motion sensors. "Wheel speed for tunnels" adds the car's speed and gear so the iPhone can keep the position moving:

- DiPlay also sets VehicleSpeedData (id 20) in the LocationInformation identification component. It sends `$PASCD` only if the iPhone selects it (id 4) in StartLocationInformation (`0xFFFA`); the log shows the ids the iPhone asked for (`components=[…]`).
- Every LocationInformation (about once a second) carries the samples since the previous one, even without a GPS fix: `$PASCD,<first sample, s since boot>,C,<P/R/N/D>,0,<n>,<offset s>,<speed m/s>,…*CS`. The layout copies a production head unit's log; what `C` and `0` stand for is not public.
- Speed: device 1013, `-1807745016`, float km/h (BYD SDK speed), read four times a second over adb — `service call autoservice 7 i32 1013 i32 -1807745016`. Gear: device 1011, `555745336`, 1 P, 2 R, 3 N, 4 D (5 and 6, M and S on older SDKs, also count as D), read once a second — `service call autoservice 5 i32 1011 i32 555745336`. Legacy mode reads both from the addresses its probe confirmed.

Checked in the car at walking speed: the gear followed D, R and P (4, 2, 1), and the speed arrives in whole km/h. During wireless CarPlay the short-lived Bluetooth link now advertises neither Location nor Vehicle; the Wi-Fi iAP2 tunnel advertises the complete runtime data plane and sends `$GPGGA`, `$GPRMC` and requested `$PASCD` only after its own StartLocationInformation. Wired CarPlay advertises and sends the same data on its single USB iAP2 link. Whether the iPhone uses the speed for dead reckoning in a tunnel is still to be tested. Gyro and accelerometer (`$PAGCD`, `$PAACD`) are not sent because their layout is not public.

## Video while parked (optional, needs ADB)

iOS 27 can play video on the CarPlay screen while the car is parked ("video in car"): the iPhone hands the head unit a media URL and drives playback, and the head unit plays it in its own player. With "Video while parked" turned on, DiPlay offers this and plays the video full screen over CarPlay. Video starts on the car screen as soon as it is sent to CarPlay on the iPhone, with no further step in Now Playing. A tap shows **Back to CarPlay**, play/pause, 10 s back and forward and a time bar that can be dragged to seek. The switch is off by default and reconnects CarPlay.

Video is allowed only while the gear reads P. DiPlay reads the gearbox once a second through the adb shell (gearbox device 1011, `service call autoservice 5 i32 1011 i32 555745336` → 1 P, 2 R, 3 N, 4 D) and tells the iPhone with `setVideoPlaybackAllowed`. The latest decision is retained until AirPlay SETUP has negotiated video playback and the encrypted event channel is ready, because those stages can complete after the first gear read on both Wi-Fi and USB. Leaving P closes the player and the iPhone goes on with audio only; so does a gear that cannot be read (no ADB access). The steering-wheel keys drive the car's player while it is open: play/pause toggles it and next/previous skip 10 s. They do not go to the iPhone, which ends the video session on a CarPlay play/pause.

What the iPhone expects, as observed with iOS 27 and checked against Apple's CarPlay Simulator (Additional Tools for Xcode 27) and its AirPlay web app:

- `/info` carries `videoPlaybackInfo`: `videoPlaybackAllowed`, `featuresEx` (the legacy feature bits plus bits 0 and 64, base64 of the little-endian bit set) and `playbackCapabilities`. SETUP enables `videoPlayback` when the iPhone proposes it; without `videoPlaybackInfo` the iPhone tears the session down.
- The iPhone opens a "CarPlayVideo Settings App" data stream (`BB493F61-…`), encrypted like the iAP tunnel; each `sync` package gets an empty `rply`. Playback runs over remote control sessions without a socket (`A6B27562-…` video setup, `E3DC3EA6-…` overlay UI, `controlType` 1), answered with a stream ID from 3 up. Tearing down one of them must not close the iAP tunnel (stream ID 1).
- Playback messages arrive as `POST /command` with `X-Apple-StreamID` and `{params: {data: bplist}}`: `insertPlayQueueItem`, `setRate`, `seek`, `playbackInfo`, `property`, `setProperty`, `stop`. DiPlay answers `playbackInfo`, `seek` and `property` the way Apple's web app does and sends `playbackState` when the car pauses or resumes. The iPhone sends `insertPlayQueueItem` and `setRate` 1 as soon as the video goes to CarPlay and then asks for `playbackInfo` every second, so DiPlay opens its player on that `setRate`; `requestUI` with `videoplayback:` (Now Playing's video button) opens it too.
- URLs with an app's own scheme are loaded through the iPhone, as Apple's receiver does: an `unhandledURL` request (`FCUP_Response_URL`, `FCUP_Response_IsContentKeyRequest`, `FCUP_Response_RequestID`) is answered with `FCUP_Response_Data`. A resource loader's answer has status 0 rather than an HTTP status. FairPlay keys (`skd`) are not requested.

The player is Media3 ExoPlayer, which parses media in the app. The head unit's own MP4 parser (`libmmparser_lite.so` in `media.extractor`) aborted on progressive Safari video on a DiLink 5.0 Tang.

What plays: video from Safari and from video player apps works in the car, including pause, seeking and the wheel keys. Apple TV sends HLS encrypted with `cbcs` (SAMPLE-AES) and keys for FairPlay, Widevine and PlayReady only; over AirPlay the iPhone brokers just the FairPlay key (`unhandledURL`, `streamingKey`), which needs a licensed FairPlay receiver, so Apple TV+ does not play here. When the car's player cannot play an item, DiPlay tells the iPhone as Apple's receiver does (`{type: error, error: {domain, code}, uuid}`), shows a short note and returns to CarPlay. Netflix does not support AirPlay. In testing YouTube played audio only.

DiLink 3 cluster-mode changes keep a separate recovery journal before any
`AutoContainer` command. If display creation fails after projection starts, DiPlay
attempts projection-off immediately. A failed restoration stays pending and retries
using already approved local ADB; reopening DiPlay also recovers an interrupted
output even when navigation output has since been disabled. A new mode waits for
that recovery. Android cannot guarantee restoration before force-stop; recovery
runs after the app opens again. This journal does not change the stock-map package
hold or the verified windshield HUD receiver checks.

Before releasing DiLink 3 support, retest on the car: first display creation after
boot, guidance-only mode, map priority over guidance, normal disconnect, temporary
ADB loss during creation and shutdown, and reopening after an interrupted output.
Confirm that gauges return after recovery and that existing DiLink 5 routing still
wins on its supported hardware. Unit tests exercise the failure/recovery paths;
the repaired branch still needs an end-to-end vehicle test.
