# Umay

Umay is an Android indoor-navigation assistant for blind and visually impaired
people. It guides someone through a building with spoken turn-by-turn
instructions and distinct vibration patterns, warns about people directly
ahead using the camera, and works across floors, including stairs and
elevators. It keeps working with the screen off and the phone in a pocket.

The app is Turkish-first (UI and speech), and every user-facing string is a
resource ready for translation.

> **Safety:** Umay is an aid, not a mobility tool. It does **not** replace a
> white cane or a guide dog. The camera only detects people. Stairs, doors and
> ramps are known only where whoever mapped the building tagged them, and the
> estimated position can drift by a few meters.

## How it works

There is no GPS indoors, so position comes from two complementary sources:

- **Pedestrian dead reckoning (PDR):** steps from the accelerometer and a
  gyroscope-only heading. The magnetometer is deliberately not used, because
  steel and wiring distort it differently in every corridor. Stride length is
  calibrated per person.
- **BLE fingerprinting:** named places record the signal strengths of nearby
  Bluetooth beacons during mapping. While navigating, a confident match
  snaps the position back to that place and corrects PDR drift.

A building is mapped once by walking it with the **Beacon Tarayıcı** screen:
record, name places as you pass them, and tag doors, stairs, elevators and
ramps. Maps can be exported and imported between devices as `.umay.json`
files.

Floor changes are confirmed from barometric pressure when the phone has a
barometer (stairs and elevators alike), otherwise from footsteps on stairs or
a button press.

### Modules

| Module | Contents |
|---|---|
| `core-model` | Plain-Kotlin data model: building maps, positions, alerts, field-log events, JSON |
| `positioning` | Step detection, PDR, BLE scanning and fingerprint matching, position fusion, barometer |
| `perception` | CameraX + TensorFlow Lite (EfficientDet-Lite0) person detection with distance/bearing |
| `orchestrator` | A* routing across floors, turn-by-turn guidance, obstacle alerts, rerouting |
| `feedback` | Text-to-speech (voice selection, audio focus) and haptic patterns |
| `app` | Compose UI, the foreground navigation session, storage, field logging |

## Building

Requirements: JDK 17 or newer (JDK 11 is also used as a toolchain for
`core-model`), and the Android SDK with platform 36.

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # install on a connected device
```

Tests and lint:

```sh
./gradlew :core-model:test :positioning:test :perception:test \
          :feedback:test :orchestrator:test :app:testDebugUnitTest :app:lintDebug
```

The app needs real hardware to be useful (sensors, Bluetooth, camera). An
emulator can only show the UI.

### Release builds and signing

Release builds are shrunk and optimized with R8. They are not obfuscated,
because the source is public anyway and readable crash stack traces matter
more.

Signing credentials are never stored in this repository. If none are found,
`./gradlew :app:assembleRelease` produces an unsigned build, so anyone can
build from source. Maintainers provide credentials in one of two ways:

- **Environment variables:** `UMAY_KEYSTORE_FILE`, `UMAY_KEYSTORE_PASSWORD`,
  `UMAY_KEY_ALIAS`, `UMAY_KEY_PASSWORD`.
- **A properties file outside the repository:** by default
  `~/.umay-signing/keystore.properties`, or any path passed as the Gradle
  property `umay.signing.properties`. The file contains `storeFile`,
  `storePassword`, `keyAlias` and `keyPassword`.

## Field testing

With **Dosyalar → Navigasyon sırasında kayıt tut** enabled, every navigation
session is written to a JSON Lines file containing:

- the map and all tuning constants,
- positions from PDR, fingerprinting and fusion,
- beacon signal strengths and barometric pressure,
- spoken instructions and floor changes,
- "Buradayım" ground-truth marks, which measure the real position error.

These logs are how the guessed constants get calibrated against real walks.
Crash reports are also written locally. Nothing leaves the device unless the
user shares it.

## Contributing

Issues and pull requests are welcome. Please run the tests and lint before
opening a pull request. For anything that changes how guidance behaves,
describe how you tested it on a real device.

## License

Umay is free software, licensed under the **GNU General Public License v3.0**
([LICENSE](LICENSE)): you may use, study, modify and redistribute it, and any
distributed derivative must be released under the same license with its
source code.

Third-party components keep their own licenses, all compatible with GPLv3:

- **Apache License 2.0:** AndroidX and Jetpack Compose, CameraX,
  TensorFlow Lite and its Task Library, the EfficientDet-Lite0 model
  (`perception/src/main/assets/`), kotlinx.coroutines and
  kotlinx.serialization.

---

## Türkçe

Umay, görme engelli kişilerin bina içinde sesli yönergeler ve titreşimlerle
gitmek istedikleri yere ulaşmasına yardım eden açık kaynaklı bir Android
uygulamasıdır.

**Umay bir yardımcıdır; beyaz baston ya da rehber köpeğin yerini tutmaz.**

Kaynak kodu GNU GPL v3 lisansıyla yayınlanmaktadır.
