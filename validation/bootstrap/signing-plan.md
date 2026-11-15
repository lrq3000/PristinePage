# S-01 — approved local development signing

Status: **explicitly approved through the S-01 form on 2026-11-14**. The operator selected “Approve S-01 local development signing”; no additional note or changed scope was supplied. P-01/P-02 themselves did not grant signing. Execute only after the prerequisite checks below pass. This permission is separate from later neural-asset/runtime acquisition and device-testing protocols. Current implementation remains T-01; no installable APK exists at this approval checkpoint.

Current package amendment I-01, explicitly approved2026-11-15: recurring named development/test targets are now **app.pristinepage.debug / app.pristinepage.debug.test**, using the same already-created local key/certificate. The original S-01 request allowed legacy package names; historical receipts retain that fact, while this document's active targets reflect I-01. Do not regenerate the key, silently migrate existing data or change release signing. New-identity artifacts require their own build/hash/package/privacy checks and LDPlayer-only review.

## Exact approved scope

After bootstrap tests and relevant build/manifest checks pass, generate one new development-only key at `X:\local-workspace\.worktrees\paperlight-v1\.local-signing\debug.keystore`, alias `androiddebugkey`, RSA2048, PKCS12, validity3650days, self-signed subject `CN=PristinePage Development,O=Local Development`. Refuse to overwrite or adopt an unexpected existing key. The standard public development password `android` is not a user secret or a production credential. Keep the private key ignored and never print, commit or upload it.

Proposed command from the primary worktree:

```text
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -noprompt -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 3650 -alias androiddebugkey -storepass android -keypass android -dname "CN=PristinePage Development,O=Local Development" -keystore .local-signing/debug.keystore
```

Configure only the debug build to use that exact key. Under the explicitly approved I-01 amendment, permit repeated local development/test APK signing for `app.pristinepage.debug` and instrumentation package `app.pristinepage.debug.test` as coherent implementation milestones are verified. Release signing stays null; no global default debug key or user production key is used.

APK tasks would be `:app:assembleDebug` and, when instrumented tests exist, `:app:assembleDebugAndroidTest`, under the same bounded Gradle/JDK/cache/resource envelope. Resolve only P-02-approved dependencies; normal cached runs use offline strict verification. Do not silently widen dependencies merely to build an instrumentation APK.

## Verification and exclusions

Verify signatures using installed Build Tools35.0.0 `apksigner.bat verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk`; retain public certificate fingerprint, APK SHA-256, byte length, package/version/minSDK/ABI and actual packaged manifest/resource inspection. Inspect test-APK signatures separately when built. Signing does not establish functionality, fidelity, offline/device acceptance or production readiness.

No phone/emulator installation, app launch, permissions/settings change, app-data clearing, external sharing, model acquisition, production/store signing or publication is included. Those require their own named-target approval. Build outputs are regenerable; any retained milestone delivery uses a unique version/hash filename under ignored `dist/`, never an unrelated user's file. The key/cache/outputs remain for manual cleanup.
