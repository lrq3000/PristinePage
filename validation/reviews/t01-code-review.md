# T01-CODE-R — independent bootstrap code/build review

Attempt: T01-20261114-A; reviewed 2026-11-14 as senior Android code/build reviewer. Workspace: `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`, preparation base `434d58f`. Findings concern the actual frozen working files, including untracked files, not merely `git diff`.

## Verdict

**Bootstrap-code ready, with Minor findings below and outstanding dependency/permission, APK and device gates.** No Critical or Important code defect identified within this scope. This is not T-01 acceptance, proof of launch, scanner completion, production readiness or permission to merge. APK assembly remains held pending T01-DEP-R; this review does not waive that audit.

## Full-read coverage and method

Read completely: root `AGENTS.md`; `docs/specs/{AGENTS,PRD,TODO,PIPELINE_CONTRACTS,common-mistakes-and-frequently-used-commands,OPERATIONAL_PLAN,ARCHITECTURE,IMPLEMENTATION_PLAN,SECURITY_PRIVACY,PERFORMANCE_AND_OFFLINE}.md`, including accepted T-01. Independently rehashed the unchanged implementation plan: `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Enumerated and read all eight files under `app/src/main` and `app/src/test`: manifest, MainActivity, BuildPolicy, strings, both backup policies and both test classes. Read all five module build files, root build/settings/properties, catalog, wrapper properties/scripts, two empty library manifests, README, `.gitignore`, progress and bootstrap policy/wrapper/signing/toolchain receipts. Enumerated the other modules' source trees: core/fixtures have no sources; data/image contain only manifests.

Read complete current AGP debug/release merged manifests, earlier module/lint log, lint text, latest strict-test/wrapper log, both red JUnit reports, both preserved post-manifest-green JUnit reports and Gradle version log. Direct file reads avoided ignored-output glob omissions. Used read-only Git status, Python hashing/class-header inspection and source inspection; no graph indexing was needed for this small explicit file set. No build, network, acquisition, device, signing, Git mutation or nested agent was used. Never opened `.local-signing` or any private key. Only this report was written.

## Findings

### R1 — Minor: launcher assertion permits a missing launcher filter

**True:** `app/src/test/kotlin/app/paperlight/ManifestPolicyTest.kt:58–62` accepts any exported `app.paperlight.MainActivity`; it never checks MAIN and LAUNCHER in the same intent filter. Removing that filter would leave the assertion green while removing normal launcher discovery. Current production source is correct (`app/src/main/AndroidManifest.xml:7–11`), so this is a regression-detection gap, not an observed broken launcher. Minimal remedy: assert a MAIN action and LAUNCHER category within one filter of that activity; retain an in-memory missing-filter negative case. No device is needed for this assertion.

### R2 — Minor: current README contradicts approved signing configuration

**True:** `README.md:13,27` still says build evidence is pending and signing is disabled in both variants. Existing evidence predates this review, and `app/build.gradle.kts:31–47` now selects the named development identity only for debug. This can mislead the next operator about the active gate/configuration. Minimal remedy: replace those present-tense statements with the observed bootstrap evidence and S-01 debug-only permission, link the current receipts and preserve the APK/dependency/device restrictions. Historical receipts explicitly labeled as checkpoints need not be rewritten.

## Evidence-based assessment

- **True — build graph/configuration:** `settings.gradle.kts:17–18` includes exactly five modules. App consumes core/data/image (`app/build.gradle.kts:83–86`); data and image dependency directions introduce no cycle (`data/build.gradle.kts:32–39`, `image-android/build.gradle.kts:27–32`). Core remains Android-free. Automatic SDK/JDK provisioning is disabled (`gradle.properties:6–8`); worker/heap limits match the envelope (`:1–4`).
- **True — API/language consistency:** catalog minimum26/compile-target36 (`gradle/libs.versions.toml:5–8`), generated MIN_API (`app/build.gradle.kts:11–25`) and policy (`app/src/main/kotlin/app/paperlight/BuildPolicy.kt:4–11`) agree. All five modules target Java/Kotlin17 while selecting JDK21. Existing app Kotlin class headers independently report major61, consistent with Java17; this does not prove Android runtime compatibility. No unsupported framework API usage was identified in the small launcher.
- **True — executable privacy assertions:** `app/build.gradle.kts:65–80` wires AGP `SingleArtifact.MERGED_MANIFEST` providers as Test inputs with producer dependencies, plus tracked backup-source inputs. Tests parse those real artifacts (`ManifestPolicyTest.kt:39–57`), covering both packages, SDKs, network permissions and backup attributes. Backup tests inspect source XML, not compiled/package-selected resources (`:22–36,65–76`). Nine-domain exclusions match the normative policy. Empty endpoint/allowlist unit checks alone would not prove privacy.
- **True — honest retained coverage:** latest raw `validation/raw/bootstrap/manifest-policy-green-wrapper.log:121–125` records executed app tests/wrapper and 68 tasks. Each preserved `*post-manifest-green.junit.xml:2–7` records four tests, zero failures/errors. Red XML records four intended missing-policy or missing-input assertions per suite. Other modules are explicitly NO-SOURCE (`manifest-policy-green-wrapper.log:13,24,48,70`). Earlier lint has zero errors/three warnings (`app/build/reports/lint-results-debug.txt:49`), including the already-disclosed missing icon (`:38–45`). These are historical observed results, not newly rerun checks.
- **True — launcher/signing boundary:** activity package and merged MAIN/LAUNCHER match the compiled class; MainActivity performs no capture/import/model IO (`MainActivity.kt:29–63`). Disabled actions and notice are honest, not an implemented journey. Debug uses the S-01 path/package; release signing is null (`app/build.gradle.kts:31–47`). Independent wrapper hashing reproduced publisher-expected JAR SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`; pinned ZIP checksum remains configured. No APK/signature verification follows from this.

## Declined to judge / remaining gates

1. Dependency authorization, CameraX video/media3 wording, resolved locks/checksums and complete SDK/initializer rights/security audit: assigned to T01-DEP-R; no duplicated audit or inferred permission.
2. Actual APK assembly, dex/classpath packaging, compiled backup/string resources, native ABI/16KB compatibility, reproducibility and installed signature/package identity: no APK exists; inspect the authorized artifact after audit closure. Current signing edits postdate the retained test run.
3. Actual launch, fresh-install airplane-mode behavior, API26/current-device operation, permission refusal/revocation, OEM cloud/device-transfer behavior, accessibility, large fonts and visual quality: static review cannot establish them; named-device permission and actual build evidence are absent.
4. Import/capture/edit/render/save/reopen/export/share, algorithms/neural fidelity, persistence/recovery, cancellation and resource/performance behavior: unimplemented outside this bootstrap; no scanner coverage or acceptance claimed. Production signing, publication and distribution readiness remain separately unapproved.

Next: disposition the Minor findings, retain all open gates, finish T01-DEP-R, then perform only the separately authorized artifact checks; obtain named-target approval before device work.
