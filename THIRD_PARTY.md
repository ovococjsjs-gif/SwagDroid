# Third-Party Licenses & Attributions

SwagDroid is licensed under the **GNU General Public License v3.0 (GPL-3.0-only)**.
All third-party libraries and architectural references used in this project are compatible with GPL-3.0.

| Component / Inspiration | Source Repository | License | Usage in SwagDroid |
| :--- | :--- | :--- | :--- |
| **AndroidHiddenApiBypass** | `https://github.com/LSPosed/AndroidHiddenApiBypass` | Apache-2.0 | Direct dependency in `:server` to invoke hidden system Binder interfaces (`IPackageManager`, `IAppOpsService`, `IDeviceIdleController`) on Android 10–16. |
| **libsu** | `https://github.com/topjohnwu/libsu` | Apache-2.0 | Root shell management in `:backend-root` for launching `swagdroid_server` under Magisk, KernelSU, and APatch. |
| **Shizuku API (Optional Bridge)** | `https://github.com/RikkaApps/Shizuku-API` | Apache-2.0 | Optional bridge in `:backend-root` allowing instant bootstrap of `swagdroid_server` if the user already runs Shizuku/Sui. |
| **BouncyCastle / Conscrypt** | `https://github.com/google/conscrypt` | Apache-2.0 | TLS 1.3 keying material export (`exportKeyingMaterial`) and SPAKE2 cryptographic primitives in `:adb-pairing`. |
| **Kotlinx Serialization & Coroutines** | `https://github.com/Kotlin/kotlinx.coroutines` | Apache-2.0 | Asynchronous IPC, reactive state flows, and JSON serialization in `:core-rollback`. |
| **Jetpack Compose & Material 3** | `https://developer.android.com/jetpack/compose` | Apache-2.0 | Modern declarative UI in `:app`. |
