# Physical observations — 9 November 2026

These are exploratory observations from a short, manually operated three-device lab, not a statistically controlled capacity study. Success and failure are both included. No simulator / fake transport result is presented as physical evidence.

The public summary removes raw messages, device identifiers, signing settings and machine paths. [Curated machine-readable data](benchmarks/2026-11-09.json) retains plans, counts and latency distributions. Raw private logs are not published, so this summary is not a fully auditable raw dataset. Reproduce on your own hardware before making product commitments.

## Setup

| Alias | Device | OS |
| --- | --- | --- |
| Android A | realme RMX3363 | Android 13 / API 33 |
| Android B | OPPO PKM110 | Android 16 / API 36 |
| iPhone | iPhone 16 Pro Max | iOS 26.6.2 |

Android used Google Play Services Nearby 19.5.1 (Play Services 26.43.30). iPhone used the pinned Apple SDK and **NearbyLabPersonal**, requesting BLE and Wi-Fi LAN only. Both sides used STAR. Every new connection was manually verified.

The initial load round used an early 0.2 build; later 0.2 changes added foreground keep-awake handling, clearer UI / reporting and migration fixes. This public export additionally changes localization, local signing configuration and documentation. The tables describe the bench builds that were actually exercised, not a claim that every public commit has been rerun on all three phones. Build-only checks are separate.

## Connectivity smoke tests

| Scenario | Observation |
| --- | --- |
| Shared Wi-Fi, Android host and iPhone client | Text / JSON / receipts / ping passed |
| Shared Wi-Fi, iPhone host and Android client | Text / JSON / receipts / ping passed |
| Three devices, Android host | Host had two simultaneous clients; text / JSON / receipts / ping passed |
| Three devices, iPhone host | Host had two simultaneous clients; text / JSON / receipts / ping passed |
| iPhone Wi-Fi off, Android Wi-Fi on | Both host directions exchanged data in separate rounds |
| Existing connection after both Wi-Fi radios were turned off | Data and ping still worked in the observed round |
| Cold start with both radios continuously off | **Not established**; Android Wi-Fi was later observed enabled during advertising |
| iPhone discovering OPPO host in the final load round | **Failed to discover**; iPhone had no Wi-Fi IP. Cause not isolated |

There is evidence of operation without a common Wi-Fi network, but the lab does not identify the active radio path. Bluetooth-only capacity, universal discovery reliability, Wi-Fi Direct and cross-platform Hotspot support are not established.

## Initial Android bulk run

Android B generated echo load to Android A. Both Android devices had Wi-Fi addresses on the same subnet; the actual SDK-selected transport was not instrumented. The baseline was 1 KiB at 10/s for 10 s: **100/100**, p50 **171 ms**, p95 **270 ms**, max **311 ms**, no timeout/failure/corruption.

The sweep generated for 5 s per stage, then drained receipts. Payload size is useful synthetic ASCII; B/s includes drain time. For the size stages, window = 8; for the 256 B rate stages, window = 64. Timeout = 10 s.

| Payload | Offered rate / s | Completed / sent | Goodput B/s | p95 ms |
| --- | ---: | ---: | ---: | ---: |
| 64 B | 5 | 25 / 25 | 311 | 231 |
| 1 KiB | 5 | 25 / 25 | 5,115 | 228 |
| 8 KiB | 5 | 25 / 25 | 40,861 | 236 |
| 16 KiB | 5 | 25 / 25 | 81,674 | 259 |
| 64 KiB | 5 | 25 / 25 | 326,374 | 256 |
| 256 KiB | 5 | 25 / 25 | 867,452 | 2,363 |
| 1 MiB | 5 | 9 / 9 | 855,049 | 8,115 |
| 256 B | 10 | 50 / 50 | 2,557 | 271 |
| 256 B | 50 | 237 / 237 | 11,678 | 256 |
| 256 B | 200 | 839 / 839 | 40,019 | 458 |
| 256 B | 1,000 | 910 / 910 | 42,949 | 490 |
| 256 B | Maximum | 882 / 882 | 41,528 | 555 |

All sent messages completed; timeout/failure/corruption counts were zero in this Android sweep. **Offered rate was not always achieved:** the 1 MiB stage admitted only nine deliveries and elapsed 11.037 s including drain. Around 0.85 MB/s here is application goodput, not a claim about the SDK maximum or radio speed.

## Initial iPhone load failures

iPhone had no Wi-Fi IP and showed cellular status; its exact Wi-Fi radio state was not independently established in that round. Android A hosted.

| Scenario | Completed / sent | Timeouts | p95 of successful exchanges |
| --- | ---: | ---: | ---: |
| iPhone echo, 64 B at 5/s | 25 / 25 | 0 | 1,810 ms |
| iPhone echo, 1 KiB at 5/s | 21 / 21 | 0 | 3,172 ms |
| iPhone echo, 8 KiB at 5/s | 6 / 11 | 5 | 8,949 ms |
| Concurrent relay, Android B, 1 KiB at 10/s | 19 / 25 | 6 | 9,655 ms |
| Concurrent relay, iPhone, 1 KiB at 10/s | 3 / 18 | 15 | 9,558 ms |

The sweep stopped on its first timeout. All-recipient relay waits for the slower recipient, so one stalled client affects another sender's result.

**Confounders:** iPhone screen lock / background transitions and Xcode test-agent connection invalidation occurred during this session; a later launch was explicitly refused because the device was locked. The final build keeps an active foreground session awake. These failures remain valid observed application outcomes, but cannot establish a clean BLE bandwidth ceiling. A controlled repeat is needed.

## Final state replication and handoff

| Scenario | Outcome |
| --- | --- |
| Android B host, Android A client; relay 64 B / 1/s / 5 s / window 1 | **5/5**, p50 403 ms, p95 490 ms; replicas matched |
| Planned handoff Android B → Android A, two devices | **Recovered** in 50,442 / 50,566 ms including manual verification; epoch 1, revision 5, counter 5 |
| Android A host, iPhone origin + Android B recipient; relay 64 B / 1/s / 5 s / window 1 | **5/5**, p50 433 ms, p95/max 486 ms; all replicas epoch 1, revision 10, counter 10 |
| Planned handoff Android A → iPhone, three devices | **Failed 120 s recovery deadline**; roles / committed snapshot transferred, but fresh verification was interrupted while test-agent connectivity failed |
| After manual reconnection, iPhone host + both Android clients generating concurrently | **5/5 each**, p95 827 / 865 ms; no timeout/failure/corruption |

The final restored state matched on all three devices: **epoch 2, revision 20, counter 20, 64 payload bytes, digest `de7b60ec`**. Manual restoration after the deadline is not a successful timed handoff. Network conditions for every final role transition were not independently controlled, so these results are not labeled a strict router-free throughput benchmark.

Separate physical host-crash takeover, long sessions, background operation and many-client scaling were **not completed**. Fake-transport tests cover some of these logical paths but do not establish their physical reliability.

## What to test next

Repeat on foreground devices with the screen awake: shared LAN, disconnected from router with Wi-Fi enabled, and fully disabled radios as separate conditions. Record exact radio / cellular settings before and after discovery; the SDK may affect system state. Test each host model in both roles, then add clients, longer runs, cancel under load, disconnects and host loss. Keep offered rate, achieved counts, failure counts and all replica hashes together.

For board/card games, these observations support continuing development of small, infrequent state updates. They do not establish a latency guarantee, large-player capacity or suitability for fast action gameplay.
