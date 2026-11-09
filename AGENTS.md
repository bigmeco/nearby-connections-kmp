# Working on NearbyLab

- Keep platform adapters behind `NearbyTransport`; shared logic uses StateFlow/MVI and Koin.
- Put user-facing strings in Compose resources, with English and Russian translations.
- Never automatically accept verification codes, including after host handoff.
- Do not treat SDK send callbacks, JVM tests or simulator builds as physical interoperability evidence.
- Keep benchmark failure counts and drain time visible; distinguish application goodput from radio throughput.
- Keep personal signing settings in ignored `iosApp/Signing.local.xcconfig`. Never commit private logs or device identifiers.
- Update `docs/BENCHMARKS.md` when adding physical results, including device/OS versions, roles, network conditions and confounders.
- Required checks: `:shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug :shared:compileKotlinIosArm64` and an unsigned iOS simulator build on macOS.
- The Apple SDK revision is pinned. Review verification formatting and Bonjour hashing against its source when upgrading.
