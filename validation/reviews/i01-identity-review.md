# I01-R — independent identity and frozen-artifact review

2026-11-15; attempt `T01-20261115-I01`; workspace `X:\local-workspace\.worktrees\paperlight-v1`; branch `feat/paperlight-v1`; HEAD/base `c1c807a74350d43050337ef16a96a37680d57c5b`. Reviewer role: independent senior Android build/artifact reviewer.

## Separate verdicts

**Spec compliance: PASS for the bounded I-01 identity/static-bootstrap contract.** Actual changes implement the explicitly approved PristinePage identity without widening dependencies, paths or operational permissions. This does not accept T-01 runtime launch or any complete scanner requirement.

**Task quality: PASS WITH MINOR FOLLOW-UP.** No Critical or Important migration/artifact defect identified. One new stale task-state record and two acknowledged preexisting presentation/compatibility minors remain below. Coordinator disposition, record reconciliation and the verified milestone commit remain pending; this report grants no new authority.

## Coverage and evidence integrity

Read completely root AGENTS; `docs/specs/{AGENTS,PRD,TODO,PIPELINE_CONTRACTS,common-mistakes-and-frequently-used-commands,OPERATIONAL_PLAN,IMPLEMENTATION_PLAN,ARCHITECTURE,SECURITY_PRIVACY,PERFORMANCE_AND_OFFLINE,VALIDATION_PLAN,LICENSES_AND_SOURCES,DECISIONS_AND_RISKS,REQUIREMENTS_TRACEABILITY}.md`; I-01 amendment/receipt, S-01/P-04 records, current progress/README, prior artifact review, all four moved Kotlin files, four affected build files, catalog, manifest, strings and backup XMLs. Direct source/Git/artifact inspection sufficed; no graph/index was created.

The supplied review package SHA-256 is exactly `9df489b550a3f4b05bd46f883fe4402577480ed5b5462e725d87925f892d0a14`. Its complete 22-path diff equals the actual staged `git diff --cached --unified=8 c1c807a`. Each staged destination blob matches the package; working files match the index except permitted coordinator-only `validation/progress.md` updates. Read-only staged whitespace checking is clean.

**True — preservation:** verification XML, all six locks, catalog, settings/properties, source manifest/backup policies and accepted plan are byte-identical to baseline. Accepted-plan SHA remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. Independently reproduced the 24-file immutable source technical digest `54f5d3f72134075b40ca178f8e68f39a626352de2632b2e7db4fe598aba263c3`; every root seed copy matches its source. Root remains clean at `a74c341e780f0ea7466ce6814d998e63afb27d27`. Historical APK bytes/hash remain intact.

## Verified implementation and artifact

- **True — wiring:** root group/app namespace are `app.pristinepage`; debug ID is `app.pristinepage.debug`; data/image namespaces align. Four Kotlin moves change packages/reflection/manifest expectations, not feature behavior. `app/build.gradle.kts:65–80` consistently supplies the renamed manifest/resource keys consumed by `ManifestPolicyTest.kt:50–106`; merged-artifact providers retain producer dependencies. Generated BuildConfig agrees. Generated Android-test lint model `app/build/intermediates/android_test_lint_model/debug/generateDebugAndroidTestLintModel/debug.xml:26` confirms `app.pristinepage.debug.test`; no test APK is claimed built. Named debug signing remains isolated; release signing remains null (`app/build.gradle.kts:31–47`).
- **True — exact artifact:** normal APK and `dist/pristinepage-0.1.0-debug-i01-19595f091f69.apk` independently hash to `19595f091f69eda19fb7d05450c55160ba32c598ab6ee68b4d1d703be16557a7`, each 16,779,849 bytes. Current and historical APK signatures independently verify with the same RSA2048 certificate SHA-256 `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee` and APK v2.
- **True — packaged identity/policy:** label PristinePage, package above, exported `app.pristinepage.MainActivity` with MAIN/LAUNCHER together, matching DEX definition, min26/target36/compile36. VersionCode/versionName agree with current output metadata, not a fixed release assertion. Debug/release merged manifests agree with their respective IDs and backup/network boundaries; release is not debuggable. Compiled manifest semantics match the historical artifact after identity normalization and irrelevant component ordering/line metadata. No capability widening found.
- **True — resources:** compiled label and backup references resolve to the actual resource table/XML. `allowBackup=false`, no BackupAgent, no INTERNET/ACCESS_NETWORK_STATE. Legacy, cloud-backup and device-transfer sections each exclude all nine specified domains at `.` with no includes. This is static configuration, not OEM transfer proof.
- **True — ZIP/DEX/native:** 527 unique entries, CRC clean; 24,287 unique defined DEX classes include new app/library identities and zero legacy `app/paperlight` definitions. Inspected host-tool/inference-runtime prefixes and model/key filename extensions are absent, not a complete semantic security audit. Only four ZIP entries change from the historical APK: two DEX files, manifest and resource table. All three native libraries remain byte-identical, ELF64/AArch64, with PT_LOAD alignment/congruence at 16 KiB and uncompressed ZIP data offsets aligned at 16 KiB; SDK zipalign passes.

## Retained tests and independent commands

Inspected preserved RED XML: four expected missing-`app.pristinepage.BuildPolicy` assertion failures, zero errors. Current GREEN XML equals preserved copies: Bootstrap4 + Manifest5, zero failures/errors/skips. Assembly log records BUILD SUCCESSFUL, 148 tasks/69 executed; other module tests are NO-SOURCE. Current lint XML has zero errors and three disclosed warnings. These are inspected completed results, not reviewer-rerun tests.

Ran read-only `python -B -` hashing/XML/ZIP/DEX/ELF/Git inspections and installed SDK35 commands: `java -jar apksigner.jar verify --verbose --print-certs`, `aapt2 dump badging`, `aapt2 dump xmltree` for manifest/both policies, `aapt2 dump resources`, and `zipalign -c -P 16 -v 4`. Final checks pass. Two preliminary reviewer probes failed from locale decoding and overly strict manifest-order comparison; explicit UTF-8 and structural comparison resolved those inspection issues, not application defects.

## Effect-based findings

**Critical: none. Important: none within scope.**

**I01-1 — Minor, true; `docs/specs/TODO.md:9`.** Live task authority still says “focused RED running” despite completed GREEN/current receipt. This can misdirect resumption. Coordinator should reconcile this row and staged progress with completed evidence before committing, retaining T-01 acceptance unchecked; no rebuild is needed for record-only correction.

**ART-1 — Minor, unchanged; `app/src/main/AndroidManifest.xml:2–5`.** CAMERA still implies required rear-camera support despite optional camera.any. Broader device/store filtering can exclude import-capable camera-less/front-only devices. Carry the existing deferred regression/manifest correction into a separately scoped change; not a newly introduced identity blocker.

**ART-2 — Minor, unchanged; `app/src/main/AndroidManifest.xml:6`.** No application icon; current badging/lint corroborate unfinished launcher presentation. Approved artwork remains future work.

## Declined judgments and precaution

Not judged: installation/launch, LDPlayer ownership/readiness/ARM translation, actual test-APK signing, API26 execution, airplane-mode journeys, accessibility, OEM backup/transfer, physical performance/16KiB execution, clean-cache source-to-binary reproducibility, semantic malware/SBOM completeness, legal/trademark/distribution clearance, production signing or release acceptance. Scanner, neural providers, ingestion/persistence, geometry/appearance, editor, export and fidelity remain unimplemented/unaccepted; the bootstrap visibly disables actions.

No builds, signing, acquisition/network, ADB/device/emulator operations, private-key access, indexing, Git mutation or nested delegation occurred. Only this report was written. Preserve the exact reviewed APK; changed artifacts require renewed checks and only separately authorized P-04 execution. Personal phone remains excluded from all operations.
