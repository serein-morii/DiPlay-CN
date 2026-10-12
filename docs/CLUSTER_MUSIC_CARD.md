# Cluster music card (experimental)

Based on v0.2.18; branch `feature/cluster-music-card`.

Enable the cluster map, then open Settings → Advanced → the dashboard map card →
Music on cluster (experimental). The feature defaults to off.

- Temporary: show the card for 8 seconds after the current track metadata changes.
  Position/play-state updates and delayed artwork do not restart the timeout.
- Permanent: retain only the square cover, including while paused.
- In both modes, the title and artist overlay the top of the cover for 8 seconds
  after a track change. Caption expiry runs independently of cover visibility.
- The square cover matches the default speedometer height and corner proportions.
- Horizontal/vertical position and size apply immediately without reconnecting.
  Default placement is lower center; calibrate while parked to keep speed, telltales
  and navigation clear. The card stays within the projection surface, but OEM
  overlays/cropping may hide it on a particular firmware.

The card reuses iPhone iAP2 Now Playing metadata/artwork already received by DiPlay.
No new vehicle writes or Ultra negotiation are added. Pending artwork uses a music
symbol rather than knowingly showing the previous track's cover. No title/artist
or session disconnect clears the card. Reopening a view does not replay an expired
track. Enabling/changing settings gives a temporary preview of the current track.
Apps that use changing title text for lyrics may retrigger the temporary card.

Supported drawing routes: the existing cluster Presentation (including the
DiLink 3 1280×480 surface) and AdbClusterActivity. This is not a center-screen
launcher card and does not enable projection while the map feature is off.

## Parked vehicle validation

1. Verify the original gauges/test indicators remain visible; retain known-good APK
   and settings. Install only an APK signed compatibly with the existing install.
2. Enable the existing map projection, then enable the music card. Play a song from
   the iPhone; verify correct title, artist and cover (when supplied by that app).
3. Temporary: change tracks; confirm ~8 seconds of visibility, late artwork does not
   extend it, and elapsed playback updates do not keep it on-screen.
4. Permanent: confirm the cover stays through pause, the caption disappears after
   8 seconds, and both update when tracks change.
5. Adjust position/size while parked. Disable the card: it should disappear at once.
6. Disconnect/reconnect iPhone and reopen DiPlay: ensure no previous-session song or
   artwork persists. Check navigation and speed overlays still operate normally.

The contributor reported the first rectangular card was visible on their Song Pro,
but wireless CarPlay remained at the connecting/waiting stage while only the test
app was in use. The cause is unresolved without that session diagnostic report.
This is partial vehicle evidence, not a successful end-to-end connection test.
The revised square card still needs an on-car retest. The feature is not Ultra.
CI source-only APKs omit runtime accessory identity assets; use the project's
established complete-release packaging process for actual CarPlay connection.

## Validation of the first revision

Full repository Android CI command passed on the first revision: 1,348 shared,
1,151 common and 4 Home tests; zero failures/errors, one existing shared skip.
Debug lint and debug assembly passed for mobile, Home and map-host.
Tests cover expiry despite position/late-art updates, permanent mode while paused,
disable/disconnect cleanup, retained deadlines on view recreation and settings
bounds. Real UI captures include compact/full Spanish at font scale 1.5, Arabic
RTL and the 1280×480 music overlay. Source/public-tree checks pass.

Local sandbox validation used workspace-owned Gradle/Android/Robolectric caches
and a local init script pointing Robolectric downloads to Maven Central. These
machine-specific settings are not part of the change.

## Validation of the square revision

The complete repository Android CI command passed: 1,348 shared, 1,152 common
and 4 Home tests (2,504 total), zero failures/errors and one existing shared skip.
All three debug lint and assembly tasks passed. Added checks cover independent
caption expiry in permanent mode and square sizing. UI captures also include the
cover after its caption expires. The complete standalone test APK was assembled
and its signature and required runtime assets were verified. Wireless connection
on the vehicle remains unresolved and is not claimed as fixed.
