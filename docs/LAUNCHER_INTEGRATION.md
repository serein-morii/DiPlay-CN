# CarPlay on your launcher's home screen

DiPlay can put CarPlay information on a launcher's home screen in four ways:

| | What it shows | Works with | Needs |
|---|---|---|---|
| [Navigation widget](#1-navigation-widget) | Next turn arrow, distance, road, arrival, song | Any launcher that hosts standard Android widgets | Nothing extra |
| [Map card](#2-map-card) | The live CarPlay map, as a card on the home screen | Any launcher, including BYD home and map home | Two permissions (see below) |
| [Embedded map](#3-embedded-live-map-for-launcher-developers) | The live CarPlay map inside the launcher's own layout | Launchers that add support for it | Launcher code, Android 11+ |
| [Home-screen bubble](#4-home-screen-bubble) | The DiPlay icon as a shortcut back to CarPlay | Any launcher, including ones that list only their own cards | Display over other apps |

The live map is CarPlay's instrument-cluster map: the map the iPhone draws for the dashboard. The map card and the embedded map need **CarPlay map on instrument cluster** turned on in DiPlay. Without it, the iPhone does not send that map.

Why the live map is not a widget: Android widgets are drawn by the launcher from a fixed set of views (text, images, buttons, lists). An app cannot run a video decoder inside another app's widget. So the widget shows text and arrows, and the live map needs the card or the embedding protocol.

## 1. Navigation widget

Add **CarPlay navigation** from your launcher's widget list (it is a 4×2 widget and can be resized). It shows:

- the next maneuver's arrow and distance, and the road it leads to;
- arrival time, remaining distance and remaining time;
- the song playing in CarPlay;
- "CarPlay is not connected" or "No route" when there is nothing to show.

Tap the widget to open CarPlay, or DiPlay when CarPlay is not connected. The widget updates at most once a second.

BYD's own home (Launcher3) accepts only the widgets on its built-in list, so the widget cannot be added there. Use the map card on BYD home.

## 2. Map card

While DiPlay is in the background, a card with the live map shows on the home screen:

- on BYD home, BYD map home and MyCar;
- on whichever launcher is set as the default home, for example a third-party car launcher.

Use the card:

- **Tap** opens CarPlay.
- **Drag** moves the card.
- **Pinch** resizes it, from a quarter of the screen width up to the full width.

Size and place are remembered. The card goes when you open another app and comes back on the home screen. It also goes when CarPlay disconnects.

In DiPlay settings, under the dashboard map, turn on **Dashboard map on the centre screen** (off by default) and give two permissions:

- **Draw over other apps**: the settings screen offers it, or over ADB:

  ```bash
  adb shell appops set com.shihab.diplay SYSTEM_ALERT_WINDOW allow
  ```

- **Usage Access**, so the card shows only on home screens. Without it, the card shows over every app:

  ```bash
  adb shell appops set com.shihab.diplay GET_USAGE_STATS allow
  ```

(Test builds use the package `com.shihab.diplay.hudtest`.)

The card is a separate decoder for the same stream, so the dashboard keeps its map. While the card shows the map, "Dashboard map only in Small and Full navi" does not pause the stream. If a launcher embeds the map (section 3), the card stays hidden.

## 3. Embedded live map (for launcher developers)

DiPlay can hand the live map to your launcher as a [`SurfaceControlViewHost.SurfacePackage`](https://developer.android.com/reference/android/view/SurfaceControlViewHost.SurfacePackage). You put it into a `SurfaceView` in your own layout. The map then scrolls, animates and resizes with your screen like any other view, while DiPlay draws it.

Requirements:

- Android 11 (API 30) or newer on the head unit;
- the driver turns on **Share the live map with other launchers** in DiPlay (off by default);
- CarPlay connected with **CarPlay map on instrument cluster** on, for the map itself.

A complete working example is the sample app in [`samples/maphost`](../samples/maphost/src/main/java/com/diplay/maphost/MainActivity.kt).

### Protocol

DiPlay exports a bound service. You talk to it with [`Messenger`](https://developer.android.com/reference/android/os/Messenger), so you need no AIDL or library. Every message you send must set `replyTo` to your own `Messenger`.

Service action: `com.shihab.diplay.action.EMBED_MAP`. The package differs between release and test builds, so find the service by its action.

| Direction | `what` | Data (`Bundle`) | Meaning |
|---|---|---|---|
| launcher → DiPlay | `1` ATTACH | `hostToken` (IBinder, `surfaceView.getHostToken()`), `displayId` (int), `width`, `height` (int, px) | Show the map in this view |
| launcher → DiPlay | `2` RESIZE | `width`, `height` | The view changed size |
| launcher → DiPlay | `3` DETACH | — | Stop showing the map |
| DiPlay → launcher | `101` ATTACHED | `surfacePackage` (SurfacePackage), `streamActive` (boolean) | Put this into your SurfaceView |
| DiPlay → launcher | `102` STREAM_STATE | `streamActive` (boolean) | The map started or stopped |
| DiPlay → launcher | `199` ERROR | `error`: `disabled`, `unsupported` or `bad_request` | The map cannot be shown |

Turning sharing off releases every attached map and sends `ERROR` with `disabled` to its launcher. Treat this as a detach. Enabling sharing again requires a fresh `ATTACH`; old views do not reconnect automatically.

The map is 8:3. If your view has another shape, DiPlay fills it and crops the edges, keeping the car position near the centre. While there is no map, the view shows a "waiting" text. A tap on the map opens CarPlay.

### Steps

1. Declare that you look for DiPlay (package visibility on Android 11+):

   ```xml
   <queries>
       <intent>
           <action android:name="com.shihab.diplay.action.EMBED_MAP" />
       </intent>
   </queries>
   ```

2. Find and bind the service:

   ```kotlin
   val intent = Intent("com.shihab.diplay.action.EMBED_MAP")
   val info = packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo
       ?: return // DiPlay is not installed
   intent.setClassName(info.packageName, info.name)
   bindService(intent, connection, Context.BIND_AUTO_CREATE)
   ```

3. Once the service is connected and your `SurfaceView` has its surface, ask for the map:

   ```kotlin
   service.send(Message.obtain(null, 1 /* ATTACH */).apply {
       data = Bundle().apply {
           putBinder("hostToken", mapView.hostToken)
           putInt("displayId", mapView.display.displayId)
           putInt("width", mapView.width)
           putInt("height", mapView.height)
       }
       replyTo = replies
   })
   ```

4. Handle the replies:

   ```kotlin
   val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
       when (message.what) {
           101 -> mapView.setChildSurfacePackage(
               message.data.getParcelable<SurfaceControlViewHost.SurfacePackage>("surfacePackage")!!)
           102 -> { /* message.data.getBoolean("streamActive") */ }
           199 -> { /* message.data.getString("error") */ }
       }
       true
   })
   ```

5. Send RESIZE (`2`) from `surfaceChanged`. Send DETACH (`3`) and unbind when your screen stops. DiPlay also cleans up when your process dies.

### Notes

- Each attached view gets its own decoder in DiPlay. Attach one view, not one per page.
- Once the driver turns sharing on, any app on the head unit could ask for the map. DiPlay logs which package asked (log tag `DiPlay-MapEmbed`).
- If DiPlay restarts, your `ServiceConnection` is disconnected and reconnected; attach again then.
- The protocol is new. Handle `ERROR` and a missing or older DiPlay gracefully.

## 4. Home-screen bubble

A shortcut the app draws itself over the launcher's home screen: tap it to come back to CarPlay,
drag it anywhere. It is an overlay window, not a widget and not a launcher entry, so it also works
on a head unit whose launcher lists only its own cards and would never show a normally installed app
(#650).

Turn it on under **Advanced → Home-screen bubble (experimental)** and allow **Display over other
apps**. The settings card says whether that permission is granted, and it can be given over ADB:

```bash
adb shell appops set com.shihab.diplay SYSTEM_ALERT_WINDOW allow
```

Behaviour:

- it draws the app's own icon, the same tile the launcher's app list shows;
- its height defaults to 10% of the screen height and the width follows the icon; **Bubble size** in
  the same card changes it (8 / 10 / 12 / 16%);
- it hides as soon as any DiPlay screen is in front, so it never covers CarPlay or the settings, and
  it comes back about half a second after you leave DiPlay;
- a drag moves it and the place is remembered;
- hold it to lock the place (a light sweeps across the icon while you hold) and hold it again to
  unlock, so it cannot be moved by accident;
- tapping it opens CarPlay when a session is up, and DiPlay's settings when it is not.

## DiPlay Home (sample launcher)

[`samples/home`](../samples/home/src/main/java/com/diplay/home/HomeActivity.kt) is a small launcher built on the public APIs above. It shows:

- the embedded live map, with a clock above it and buttons for Apps, CarPlay and BYD home below;
- a column of any Android widgets, including **CarPlay navigation**;
- a full app list.

It runs without system privileges. BYD's home stays installed, and the **BYD home** button opens it.

- **Widgets.** A launcher needs the owner's consent to bind widgets. BYD head units have no consent screen, so allow it once over ADB:

  ```bash
  adb shell appwidget grantbind --package com.diplay.home --user 0
  ```

- **Making it the home screen.** Pick it as the default home app, or over ADB:

  ```bash
  adb shell cmd package set-home-activity com.diplay.home/.HomeActivity
  ```

- **Going back to BYD's home:**

  ```bash
  adb shell cmd package set-home-activity com.android.launcher3/.home.MainActivity
  ```

  BYD's map-mode button also returns to BYD's home, because BYD's own home list contains only BYD launchers.

## Troubleshooting

| Problem | Check |
|---|---|
| No card | Card switch on; "Draw over other apps" allowed; CarPlay connected; dashboard map on; you are on a home screen. |
| Card shows over every app | Usage Access is not allowed for DiPlay. |
| Card on BYD home but not on another launcher | Make that launcher the default home; DiPlay reads the default home when the card starts. |
| Embedded map says `disabled` | Turn on "Share the live map with other launchers" in DiPlay. |
| Embedded map stays on "waiting" | CarPlay is not connected, or "CarPlay map on instrument cluster" is off. |
| Widget says "CarPlay is not connected" while CarPlay works | Open DiPlay once after installing it; the widget follows the running session. |
| Bubble is missing | "Home-screen bubble" is on, "Draw over other apps" is allowed, and no DiPlay screen is in front. |
