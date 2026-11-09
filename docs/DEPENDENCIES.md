# Dependencies and upstream notes

NearbyLab source is Apache-2.0. Dependency licenses and redistribution requirements remain separate; this file is a source map, not a complete legal or store-compliance audit.

| Dependency | Source / version | Notes |
| --- | --- | --- |
| Google Nearby, Apple | [google/nearby](https://github.com/google/nearby/tree/a95eba75022cc1d00f0898bbde5e6ad03ee71ba2), exact commit | Apache-2.0 repository; brought in by Swift Package Manager |
| Google Play Services Nearby | [Android SDK](https://developers.google.com/nearby/connections/android/get-started), 19.5.1 | Distributed by Google Maven; Google SDK terms apply |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle), 9.5.0 | Apache-2.0 wrapper files; distribution checksum pinned |
| Kotlin / kotlinx | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [serialization](https://github.com/Kotlin/kotlinx.serialization) | Versions in Gradle build files |
| Compose Multiplatform | [JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform), 1.12.2 | Shared UI |
| AndroidX / JetBrains AndroidX | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Activity, lifecycle, Fragment and related dependencies |
| Koin | [InsertKoinIO/koin](https://github.com/InsertKoinIO/koin), 4.2.2 | Shared dependency injection |
| Abseil SwiftPM | [bourdakos1/abseil-cpp-SwiftPM](https://github.com/bourdakos1/abseil-cpp-SwiftPM) | Transitive Apple dependency; revision in `Package.resolved` |
| BoringSSL SwiftPM | [firebase/boringssl-SwiftPM](https://github.com/firebase/boringssl-SwiftPM), 0.7.2 | Transitive Apple dependency with its own notices |

No Nearby SDK source, Appium agent, signing material or device-control tooling is vendored. The offline report reader uses only Python's standard library. Consult resolved dependency license files before redistributing binaries.

## Before upgrading

Check Android/Swift verification-string compatibility, the Bonjour hash length for the exact Apple revision, BLEV2 settings, SDK medium availability, permission changes, and the device matrix. Run shared tests and native builds, then physically verify both host roles.

Upstream build warnings observed with the pinned Apple dependencies include an umbrella-header declaration warning, a BoringSSL module-alias warning, and ICU objects built for a newer deployment target than the project's declared iOS minimum. Successful compilation on the checked toolchain does not establish support for every declared iOS version.

Store readiness has not been validated. Privacy manifests, SDK disclosures / signatures, application permissions, release signing and current store requirements must be checked against the exact shipping dependency set. The physical Personal Team tests did not validate Hotspot entitlements or App Store acceptance.
