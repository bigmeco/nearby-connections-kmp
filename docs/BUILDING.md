# Build and run

## Toolchain

These are pinned project versions, not a claim that every dependency is the latest.

| Component | Version / requirement |
| --- | --- |
| JDK | 21 (the local checked build used 21.0.8) |
| Python | 3.9+ for the project generator and offline tools |
| Gradle wrapper | 9.5.0, distribution SHA-256 checked |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Android Gradle Plugin | 9.3.1 |
| Android | minSdk 26, targetSdk 36, compileSdk 37 |
| Android SDK packages | `platforms;android-37.0`, `build-tools;37.0.0` |
| Google Play Services Nearby | 19.5.1 |
| Xcode checked locally | 26.5, Apple Silicon Mac |
| iOS project deployment target | 16.0; older OS versions have not been physically validated |
| Apple Nearby | commit `a95eba75022cc1d00f0898bbde5e6ad03ee71ba2` |

Only arm64 iOS device and Apple Silicon simulator targets are configured. Intel iOS simulators are not configured. The pinned SDK's transitive binary objects produce deployment-target / module warnings; a declared iOS 16 minimum is not evidence that this dependency set works on iOS 16.

## Android and shared tests

Install JDK 21 and Android SDK command-line tools. Point `JAVA_HOME` to the JDK and `ANDROID_HOME` to the SDK, or let Android Studio create an ignored `local.properties` with `sdk.dir`.

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
```

Install `androidApp/build/outputs/apk/debug/androidApp-debug.apk` with Android Studio or `adb -s YOUR_DEVICE_SERIAL install -r ...`. Use an Android device with functioning Google Play Services. No Firebase project, Google API key, backend or account login is configured by this example.

Bluetooth / nearby-device permissions are requested at runtime. Android 12 and earlier also need location permission as configured in the adapter. The app currently targets SDK 36. If raising targetSdk to 37+, revisit the [Android Nearby permission requirements](https://developers.google.com/nearby/connections/android/get-started), including local-network access.

## iPhone signing

The Xcode project is already generated and committed. Personal settings live in an ignored file:

```sh
cp iosApp/Signing.example.xcconfig iosApp/Signing.local.xcconfig
```

Edit `NEARBY_DEVELOPMENT_TEAM` and `NEARBY_BUNDLE_ID_PREFIX` there. Use your own Team ID and unique reverse-DNS bundle prefix. Open `iosApp/NearbyLab.xcodeproj`, select your physical iPhone and run the intended scheme. Configure your Apple Account in Xcode locally; no account data belongs in this repository. Enable Developer Mode and trust your developer profile on the iPhone if iOS requests it.

| Scheme | Requested media | Signing |
| --- | --- | --- |
| **NearbyLabPersonal** | BLE + Wi-Fi LAN; BLEV2 enabled | Empty entitlements; intended starting point for Personal Team development |
| **NearbyLab** | Pinned SDK default set: Bluetooth, BLE, WebRTC, Wi-Fi LAN/Aware/Hotspot/Direct, AWDL | Wi-Fi information + Hotspot Configuration entitlements; requires a suitable profile |

These are requested media, not a capability matrix or observed radio transport. Only the Personal scheme was used in the documented physical tests. Enabling all media does not establish cross-platform Hotspot support.

Set `JAVA_HOME` / `ANDROID_HOME` in the environment that launches Xcode, or configure the tools so its build phase can locate them. A terminal launch with exported variables is convenient:

```sh
open iosApp/NearbyLab.xcodeproj
```

The build phase invokes `:shared:embedAndSignAppleFrameworkForXcode`. `Config.xcconfig` provides safe defaults and optionally includes `Signing.local.xcconfig`. Regenerating the project never embeds a personal Team ID:

```sh
python3 iosApp/generate_project.py
```

The bundle IDs may be customized independently of the shared Nearby service ID. To change the service ID itself, update `Transport.kt`, `AppleNearbyTransport.swift` and the generator together; regenerate the Bonjour service hash. For the pinned SDK the hash uses **6 bytes / 12 hex characters**; see [architecture](ARCHITECTURE.md#discovery-and-verification).

## Unsigned iOS integration build

On an Apple Silicon Mac with Xcode selected via `xcode-select`:

```sh
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/NearbyLab.xcodeproj \
  -scheme NearbyLabPersonal -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios \
  -clonedSourcePackagesDirPath build/SourcePackages \
  CODE_SIGNING_ALLOWED=NO build
```

Swift packages are pinned by revision / `Package.resolved`; the first build downloads them. This build requires no Apple signing identity and does not test discovery or physical transfers. Use actual Android and iPhone hardware for interoperability.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Gradle cannot locate Java / Android SDK | JDK 21, environment inherited by Xcode, ignored `local.properties` |
| Room is not found | Both apps foreground, required permissions, same service ID and strategy; stop and restart discovery |
| Codes differ | Reject; do not bypass verification. Confirm both builds use the pinned normalization |
| Personal Team cannot sign full scheme | Select **NearbyLabPersonal** and configure your own bundle prefix |
| iOS local network access denied | Enable it in system settings, then restart discovery |
| Load stalls | Inspect timeouts and pending/queue values; stop, reconnect and start at 64 B / 1/s |
| Handoff changes roles but never recovers | Compare fresh codes on every new connection within the 120 s deadline; inspect migration events |

Store packaging and privacy-manifest validation are outside this lab's build checks. See [dependency notes](DEPENDENCIES.md).
