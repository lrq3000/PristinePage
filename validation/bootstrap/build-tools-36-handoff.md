# build(android): pin installed SDK Build Tools 36.0.0

**Disposition:** U-BUILD-TOOLS-36 local candidate for `main`, not integrated. Exact clean base `6c0b9b5268e532734329e272f30fe4e585e1e7d5`; branch `work/pristinepage-build-tools-36` in `X:/local-workspace/.worktrees/pristinepage-build-tools-36`. The coordinator authorized this exact shared build-only slice and the directly coupled README baseline update. No remote is configured. Independent review and a fresh destination check precede adoption.

## Motivation and change

The base pins `buildTools = "35.0.0"`, but `C:/Users/local-user/AppData/Local/Android/Sdk/build-tools/35.0.0` was absent. Before editing the catalog, a read-only preflight verified the exact old line and the installed `36.0.0` package (`Pkg.Revision=36.0.0`). The old-pin preflight refused to invoke Gradle: this is an infrastructure refusal, not a failing product test. [P-02](acquisition-plan.md) and the [bootstrap receipt](toolchain-receipt.md) describe the original 35.0.0 toolchain; they and the earlier 35.0.0 raw evidence and runners remain unchanged.

Change only `gradle/libs.versions.toml`'s `buildTools` value to `36.0.0`. The app, data, and image-android scripts already use that shared pin. Keep the version explicit: [AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes) identifies 35.0.0 as the default/minimum, while Android documents the [explicit `buildToolsVersion` override](https://developer.android.com/tools/releases/build-tools). Removing the pin would not select the installed package deterministically, and installing the absent default or changing AGP is outside this slice. Compile/target SDK 36, min SDK 26, Gradle 8.13, AGP 8.13.2, JBR 21.0.8, Kotlin/Compose compiler 2.3.21, KSP 2.3.14, bytecode target 17, and arm64-v8a remain unchanged. README now points to the current toolchain evidence while still linking the historical bootstrap receipt. No product code, app identity/version, dependency, lock, verification metadata, schema, signing, or module script changed.

## Installed package identity

Actual file SHA-256 under the installed `build-tools/36.0.0` package, matched to the coordinator's independent readings. These are local identities, not fresh publisher authenticity or evidence that the build used each binary. AGP may use its own verified Maven AAPT2 for resource processing.

| Path | SHA-256 |
|---|---|
| `source.properties` | `7dee6632e9ad6cb111da2bb99d747211e27927061b1276d040bb1d71fded5ebb` |
| `aapt2.exe` | `babf3122e515ddb954c5ac4669e085ce990536c035e3072de30127bddd6e3608` |
| `lib/apksigner.jar` | `3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec` |
| `zipalign.exe` | `c503c7da88bd4f6cddbcc8d3febd41e1e5022a525147f05f8caf4602353409c0` |
| `aidl.exe` | `266dcf772ad1791b43f1ec5702af2e152d1222b051d3676af4e47006dd47d359` |

## Reproduce the host check

Use only an authorized clean destination with the exclusive Gradle/SQLite slot, more than 8 GiB free on C:, no live build/storage tools and no signing file in the test tree. Verify the local SDK files above. Verify the retained exact Gradle 8.13 installation before operation; do not use an unverified wrapper to recover a missing distribution. The run used the already-unpacked 8.13 `bin/gradle.bat` directly rather than launching the wrapper, so wrapper bootstrapping could not download a distribution. Its prior checksum-gated ZIP pin is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`; no ZIP was present for fresh hashing. The prior admitted-render receipt's authenticated `data-preflight/toolchain.json` (`1ee1d5f78ba6be8eec4e6d2ab3f86bb7ab5face62510c3be44822631318d3b71`) anchored the wrapper, cached launcher and `.zip.ok`, JBR, platform36 revision2, verification, locks, schema, accepted plan, and 19 cached AGP/KGP/KSP/Room/JUnit files. The historical 35.0.0 pin was deliberately not reused. Raw preflight records the additional installed launcher hashes.

Process-local settings: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `ANDROID_HOME=ANDROID_SDK_ROOT=C:\Users\local-user\AppData\Local\Android\Sdk` (set both variables to the same path), and `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`. Set `ANDROID_USER_HOME` to the authorized worktree's ignored `.android-user-home`; unset `ANDROID_SDK_HOME`; use an existing or newly allocated ignored worktree directory for `TEMP` and `TMP`. No global setting changes. Keep existing SDK and JDK auto-provisioning disabled. In the command below `<verified-gradle-8.13>` is the extracted `gradle-8.13` folder inside `GRADLE_USER_HOME\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d`, and `<guard>` is the retained `no-apk.init.gradle` described below.

```text
<verified-gradle-8.13>\bin\gradle.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict -Pandroid.builder.sdkDownload=false --init-script <guard> :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

First append `--dry-run` to inspect the task graph, then repeat without it. If reproducing without the local init script, inspect the dry-run graph before running any test tasks: reject any APK packaging, assembly, signing, or device task. Ordinary AGP resource intermediates and lint-only library AARs are not APK packaging. The evidence guard confirmed Gradle 8.13, offline mode, the SDK-download flag, Build Tools 36.0.0 and unchanged SDK settings in the Android DSL. It was configured to fail before task execution if disallowed tasks appeared. The dry run exited 0 in 20.104 s with 188 tasks in the guarded graph; no task actions ran.

## Evidence and review boundary

The full offline command exited 0 in 351.958 s, with 143 actionable tasks executed and all five unit-test tasks fresh. The retained reports were independently recounted against the original task XMLs, including test-case elements and freshness checks.

| Task | Fresh tests | XML files |
|---|---:|---:|
| `:core:test` | 222 | 19 |
| `:test-fixtures:test` | 15 | 2 |
| `:data:testDebugUnitTest` | 623 | 59 |
| `:image-android:testDebugUnitTest` | 171 | 18 |
| `:app:testDebugUnitTest` | 102 | 15 |
| **Total (0 failures, 0 errors, 0 skips)** | **1,133** | **113** |

`:app:lintDebug` executed: 0 errors, 9 warnings and 1 hint. Manifest/compiler warnings from unchanged sources are in the build log; no 35.0.0 run was attempted to compare diagnostics. No APK output or local signing file was found.

Raw evidence is in the candidate worktree's ignored `validation/raw/u-build-tools-36/`; all 142 entries of `evidence.sha256` were verified (manifest SHA-256 `40df9f352d7d93ddb7eb41b53d76a0e3a5d5e4020b96325f38d37f22e8d00130`). It includes the old-pin preflight, exact argv and process-local inputs, logs/exit records, Win32 process-handle records, per-module XMLs, lint reports and byte-level tracked input manifests. The local runner uses the authenticated process primitives from the prior terminal-export compose evidence (`31d508b83b24a79ef6e0dfdab9436002dc0bb55420d6bc537fd32fe6d1fc6baa`), not its historical task list or toolchain assertions. The three approved tracked paths are this handoff, the single catalog pin, and the README baseline. README was edited before both Gradle invocations; its tested SHA-256 is `045178862498e58a85fbe6bf5569328e22e68667e1a9ee9395798f2607090c06`, catalog SHA-256 `506c905d475d389906853b87c546e74dece80ead246a4c7e3bfc3534913b3577`. Matched dry-run/full-build input manifest SHA-256 `75881737fbd5f5e41fbc4b7a946569f7a9c6e60f3e03ae697407821ab7c0dd96`; no later executable input change. The handoff itself is documentation only.

Gradle launcher PIDs 22552 (dry run) and 41532 (full run) exited 0; all 8 and 30 observed process handles respectively were signaled with exit 0 and recorded creation/exit times. No unresolved handles or open races. Prelaunch C: free bytes were `21707497472` and `21715042304`; release check had `21527089152` free. Fresh native inventory and separate CIM inspection found no remaining Gradle/Java or SQLite tools and no owned build shell; the exclusive host slot is released. See `release.json`, SHA-256 `547a678ed04779aab6c571c35f923a99736891023dc4fe3a831ae4ab02ab9408`.

No dependency or SDK acquisition, wrapper execution, APK assembly/signing, key copy, device/ADB/emulator action, or separate host SQLite command was performed. The existing unrelated ADB server was neither invoked nor changed. The evidence does not establish APK verification, a native runtime result, product acceptance, or a clean-cache build. If the destination has a different SDK or its current catalog no longer pins 35.0.0, reconcile the intent and repeat the affected destination checks rather than carrying over these results. README was the only directly coupled active literal reference outside the catalog; the three module scripts read the catalog dynamically. Refresh the single canonical documentation index for README after adoption, not from this worktree.

**Included commit:** The enclosing single-parent local commit by lrq3000, `build(android): pin installed SDK Build Tools 36.0.0`, replaces the missing host package pin, updates the current README baseline, and retains the bounded reproduction/evidence in this handoff. **Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).
