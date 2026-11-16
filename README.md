# myTVS — Ntorq 150 TFT companion (work in progress)

An independent Android app for the **TVS Ntorq 150 TFT** scooter cluster, built
by reverse-engineering the owner's **own** cluster (for interoperability with
hardware they own). The long-term goal is to put Google Maps on the TFT; this
first milestone proves the connection and decodes live data, which the earlier
community app never managed on a 150 TFT.

## Status — Milestone 1 (read-only)

- Scans for the cluster over Bluetooth LE and connects.
- Lets Android **pair/bond** with it (the 150 TFT requires bonding — the likely
  reason older apps failed here).
- Subscribes to the cluster's notify channel and **decodes every frame** with a
  verified codec.
- Shows live frames and keeps a timestamped, exportable log.
- **Writes nothing to the cluster.** The cluster drops a silent link after ~15 s,
  so the app reconnects automatically. Holding the link with the keepalive is the
  next milestone (its exact bytes are being confirmed first — no guessed frames
  are ever sent to a cluster that drives the speedometer).

See [`PROTOCOL.md`](PROTOCOL.md) for the decoded protocol.

## Build

Every push builds a debug APK in GitHub Actions (**Actions → Android CI →
app-debug**). Install `app-debug.apk` on the phone (`adb install -r app-debug.apk`,
or just open it).

To build locally you need Android Studio (which brings the SDK):
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Using it

1. Turn the scooter on; make sure the official TVS Connect app is **closed**.
2. Open this app, tap **Connect**, grant Bluetooth permission.
3. When Android shows a pairing request, accept it.
4. Watch the live frames. Tap **Export log** to share a capture.

## Roadmap

- **M2** — send the keepalive to hold the link; label exact fields (speed,
  odometer, fuel) from the app's frame classes.
- **M3** — turn-by-turn to the cluster (Mappls-based nav frames).
- **M4** — the map: explore the cluster's Wi-Fi AP (confirmed present) and the
  map-mirroring stream.

This project is not affiliated with or endorsed by TVS Motor.
