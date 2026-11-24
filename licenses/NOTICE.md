# Third-party components

DNSBunker for Android is free software under the GNU General Public License, version 3 or later. It includes the following components under their own licenses. Full license texts are in the licenses/native directory of the source code and inside the app package.

Native library (Go, pinned in native/quic/go.mod and go.sum):
- quic-go v0.65.0 – MIT – https://github.com/quic-go/quic-go
- qpack v0.6.0 – MIT – https://github.com/quic-go/qpack
- gVisor (go branch, 2026-11-23, 0d93122549be) – Apache-2.0 – https://github.com/google/gvisor
- google/btree v1.1.2 – Apache-2.0 – https://github.com/google/btree
- Go standard library 1.27.4 – BSD-3-Clause – https://go.dev/LICENSE
- golang.org/x/crypto v0.57.0, net v0.60.0, sys v0.48.0, text v0.42.0, time v0.15.0, exp (2025-07-11) – BSD-3-Clause with the Go patent grant – https://go.googlesource.com

Android libraries (from Maven Central and Google Maven):
- AndroidX Core, Activity, Lifecycle and Jetpack Compose – Apache-2.0 – https://developer.android.com/jetpack/androidx
- Kotlin standard library and kotlinx.coroutines – Apache-2.0 – https://github.com/JetBrains/kotlin
- OkHttp 4.12 and Okio – Apache-2.0 – https://square.github.io/okhttp/

The Android-to-Go bridge and the DNS, VPN and user interface code were written for this project. Intra, RethinkDNS, AdGuard dnsproxy and WireGuard were studied for lifecycle, socket binding and network stack integration patterns; no code was copied from them.
