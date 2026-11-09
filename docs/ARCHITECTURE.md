# Architecture

## Shared core and native adapters

```text
Compose UI → LabViewModel (StateFlow / intents)
           → LabUseCase → LabRepository
                              ├─ WireCodec / ping / receipts / event log
                              ├─ LoadEngine → LoadWire
                              └─ NearbyTransport
                                   ├─ AndroidNearbyTransport (Play Services)
                                   └─ AppleNearbyTransport (Swift SDK)
```

`NearbyTransport` supplies discovery, verification, lifecycle and byte-message events. Native callbacks are delivered on the application thread. The repository owns peer state; the load engine owns generation, metrics, synthetic replicas and migration. Koin supplies shared dependencies. Android keeps its transport in `Application` so activity recreation does not recreate the SDK connection manager; iOS initializes its transport with the app host.

The shared framework is static `NearbyShared`. Swift implements the Kotlin-exported transport interface, and SwiftUI hosts the shared Compose view controller. iOS uses the SDK implementation directly; it does not call the Android adapter or a server.

## Discovery and verification

All platforms use service ID `org.bigmeco.nearbylab.v1` and STAR strategy. This ID is a sample namespace, not a secret or authorization boundary. Names use the shared prefix; load-session discovery appends a process node ID so a handoff can target the intended host. Endpoint IDs are transient and can change on reconnect.

The Apple Bonjour type is derived by the generator from the service ID. The pinned SDK's [`nsd_service_info.h`](https://github.com/google/nearby/blob/a95eba75022cc1d00f0898bbde5e6ad03ee71ba2/internal/platform/nsd_service_info.h) uses the first **6 SHA-256 bytes**, giving 12 uppercase hex characters. The resulting type here is `_D2E294C834F4._tcp`. The [web setup guide](https://developers.google.com/nearby/connections/swift/get-started) describes 12 bytes; check the actual source for the revision you use before changing this.

Android's four-digit `authenticationDigits` is a different representation from the pinned Swift SDK's verification string. `PairingCode.fromRawToken` matches the pinned C++ `EncryptionRunner::ToHumanReadableString`: uppercase Base64URL of the 32-byte UKEY2 verification token, truncated to five characters. Fixed test vectors cover format and token length.

The raw token stays local and is never sent or logged. Users compare independently generated codes on the two screens and manually accept both sides. Handoff never automatically accepts a new connection. This sample's prefix checks / node IDs do not establish trusted identity; production identity and application authorization are separate work.

## Protocol and load framing

`WireMessage` is a versioned JSON envelope `{v,id,type,body}`. Ordinary text/JSON/ping/pong/ack and lab frames share the 16 KiB frame limit. `LoadWire` adds bounded fragmentation and reassembly for larger synthetic packets. See [load testing](LOAD_TESTING.md) for exact bounds, receipt semantics and metric caveats.

The state experiment applies commands at one authoritative host, increments a revision/counter, sends snapshots to every connected client and returns `committed` once the required clients acknowledge. Sequence deduplication is bounded; snapshots and deduplication state are not durable.

This is a transport experiment rather than a complete game synchronization framework: no hidden-hand projection, persistent save, rollback simulation, automatic election, reconnect identity proof or anti-cheat layer is included.

## Lifecycle and logs

Android keeps the foreground activity awake; iOS disables idle sleep while advertising or discovering and restores it on stop. This does not grant background execution. A locked/backgrounded phone can change the outcome of a load run and must be recorded as a test condition.

Both adapters write bounded NDJSON into private app storage, exported only through the system share UI. In-memory events and visible history are capped. Benchmark bulk data / routine transfer-success events are suppressed; ordinary message bodies can appear in exports. No analytics service is configured by the sample; dependencies may have their own behavior and policies. Do not publish raw exports without review.
