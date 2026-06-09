# 安卓抓普 (KissLink)

Tap two Android phones back-to-back and instantly share files, photos, and contact
cards — no accounts, no internet, no manual Wi-Fi pairing dialogs.

KissLink uses a three-layer handshake: an **NFC** tap starts pairing, **BLE** exchanges
the connection credentials out-of-band, and a **Wi-Fi Direct** group carries the actual
high-speed transfer over TCP.

## Features

- **Back-to-back tap to connect** — NFC handshake, fully silent (no system Wi-Fi prompts).
- **Bidirectional transfer** — once connected, either side can send files, photos, or a
  contact card, in multiple rounds.
- **Contact cards** — share a vCard (with avatar embedded) that the receiver can save to Contacts.
- **Live transfer UI** — central avatar with a ring progress, transfer speed, NFC ripple
  standby animation, and a card-fly animation when sending a card.
- **Transfer history** — every sent/received item is recorded locally (Room).
- **Manual disconnect** and automatic, power-aware cleanup of idle connections.

## How it works

```
NFC tap (HCE / reader)
   └─ BLE GATT side-channel  ── exchange pairing tokens ─┐
                                                         ▼
                                      Group Owner election
                          GO: create Wi-Fi Direct group ──► send credentials over BLE
                          Client: receive credentials ───► join group (silent connect)
                                                         ▼
                          Wi-Fi Direct CONNECTED → TCP socket (192.168.49.1:47890)
                                                         ▼
                          PeerConnection: full-duplex framed transfer (CRC32 per chunk)
```

- **Pairing / connectivity** (Java): `nfc/`, `pairing/`, `wifidirect/`.
- **Transfer** (Java): `transfer/` — `FileTransferService` (foreground service, single session
  owner), `PeerConnection` (framed TCP, heartbeat liveness).
- **UI**: single-screen `ui/home/HomeActivity` (Java) hosting `BeamStageView` — the central
  animation module written in **Jetpack Compose** (Kotlin). Profile/received-card sheets and
  history are Java + View/Material.

## Tech stack

- Language: **Java** (core + UI) + **Kotlin / Jetpack Compose** (central animation module)
- minSdk 29, targetSdk / compileSdk 34
- Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.2.10, Compose 1.7.5
- Room (transfer history), AndroidX, Material Components

## Building

The repository has **no Gradle wrapper**. Build with Gradle 8.13 and a JDK 21
(e.g. Android Studio's bundled JBR):

```bash
# Debug APK
gradle :app:assembleDebug

# Signed release APK (signed with the Android debug key, installable directly)
gradle :app:assembleRelease
```

Outputs:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

> Opening the project in Android Studio (which provides Gradle + JBR 21) also works.

## Continuous integration

`.github/workflows/android.yml` runs on every push to `main` (or manual dispatch):
it builds the **signed release APK** and publishes it to **GitHub Releases**
(tagged `v1.0.<run-number>`). The release APK is signed with the Android debug key so it
can be installed directly; switch to a real upload key before publishing to the Play Store.

## Permissions

NFC + HCE, Bluetooth (advertise/connect/scan), Wi-Fi / Wi-Fi Direct (+ nearby devices /
location on older APIs), foreground service (`connectedDevice` / `dataSync`), wake lock,
notifications, and scoped media access for picking files/photos.

> On aggressive OEM builds (MIUI, One UI), disable battery optimization / enable autostart
> for the app so the foreground service isn't killed while the screen interaction briefly
> backgrounds the app during the tap.

## Known issues

See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for the current list of pending work and limitations.
