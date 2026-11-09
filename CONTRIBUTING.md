# Contributing

Small reproducible changes and honest device reports are welcome. Keep shared protocol / state behavior behind `NearbyTransport`; keep UI strings in both English and Russian resources.

## Checks

```sh
./gradlew :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
python3 tools/check-project.py
```

For Apple changes also run `:shared:compileKotlinIosArm64` and the unsigned simulator command in [BUILDING.md](docs/BUILDING.md). Regenerate the Xcode project after editing its generator. Neither CI nor fake transports prove radio interoperability.

Kotlin sources / Gradle scripts use ktfmt's Kotlin style (0.65 for the initial export); Swift sources use Xcode's `swift-format`; Python tools use Black. Formatting tools are development conveniences, not runtime dependencies.

CI runs Android/JVM checks on pushes and pull requests. An additional unsigned iOS build can be requested using **Run workflow → Build unsigned iOS simulator**. It needs no repository secrets or signing account.

## A useful physical report

Include app commit/build, device models, OS / Play Services versions, host and client roles, number of clients, exact Wi-Fi / Bluetooth / cellular conditions, whether discovery was a cold start or an existing connection, foreground/lock state, plan parameters, sent/completed/failure counts, latency sample size and replica hashes. Distinguish a successful manual recovery from recovery within the handoff deadline.

Do not post raw logs until reviewed. Exports can include ordinary messages, room names, endpoint IDs and local paths. Remove credentials, provisioning profiles, persistent device identifiers and personal content. Sharing a curated summary is usually sufficient to start an issue.

Never bypass verification to make a test pass. Stop and reject a code mismatch. Do not silently change network settings during a run; record any change explicitly.
