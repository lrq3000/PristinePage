# T01-NOTICES — cached dependency declaration and notice register

Attempt `T01-20261114-A`; snapshot 2026-11-14. **Verified outcome: F3's 583-component register/retention portion is addressed. Legal clearance and final APK-specific notices remain open.** P-02A's explicitly approved twelve-coordinate acquisition exception was read alongside the current OPERATIONAL_PLAN; it was not interpreted as rights clearance (`validation/bootstrap/dependency-scope-amendment.md:3,13-20`; `docs/specs/OPERATIONAL_PLAN.md:37-43`). The earlier T01-DEP-R report remains historical and unchanged.

## Deliverables and identities

- `licenses/dependencies/inventory.json`: 583 component rows, cached POM declarations/parent evidence, source/SCM declarations, lock-derived roles, embedded notice mappings and unresolved reasons. SHA-256: `3840fcc940673cb3a8a09518c72871ebdd92503c7b763328cbc32f04e06f490e`.
- `licenses/dependencies/snapshot.json`: coverage, ten missing-license-declaration coordinates, and hashes/sizes for the inventory and every retained text. SHA-256: `0f77ee9aeb8325356d4f5976352ee7c13c57b85e044537c54b58959639d56129`.
- `licenses/dependencies/notices/sha256-<digest>.txt`: 69 deduplicated original-byte legal texts, totaling 676,659 bytes, mapped to 208 artifact/archive-entry occurrences across 164 components. No copyright text was trimmed or normalized.
- `licenses/README.md`: explains consumption, limitations and remaining gates. Generated dependency files total 71 files / 2,130,702 bytes, excluding this short report and README; combined text remains below 5 MiB.

The XML snapshot is SHA-256 `adb85e2ef63ab5acdd356a13b61abb12209553c6f649a90cf79eca76b9cb98ad`; all 994 recorded artifact hashes were independently matched against cached bytes. Each inventory coordinate plus `verification_component_line` identifies existing hash records instead of duplicating them (`gradle/verification-metadata.xml:1-4157`). Exact six-lockfile, catalog, root-build/settings, approval/operational and historical-report hashes are retained in `inventory.json` under `source_snapshot_sha256`. Historical audit SHA-256: `0093ea68cb50db243da392004c9883d8914d660dfc948597563f2023abc3837b`.

## Declaration, role and notice coverage

All 583 components have cached POMs; the cache contains 585 POM coordinate sources, with no uncached/cyclic parent chain. License coverage is **494 own / 79 inherited / ten missing**. POM source hashes and whether each POM itself appears in artifact verification are recorded separately: a fingerprint generated here is not independent authenticity evidence. Project/SCM URLs and tags retain their declaring POM, inheritance and unresolved expressions; 102 components have a declared SCM tag, 481 do not. Declared tags are not verified immutable revisions. Inherited ancestor URLs are not silently converted into child repository URLs.

The ten missing POM/parent license declarations are `javax.inject:javax.inject:1`; `net.java:jvnet-parent:1` and `:3`; `net.sf.kxml:kxml2:2.3.0`; `org.bouncycastle:bcpkix-jdk18on:1.79`, `bcprov-jdk18on:1.79`, `bcutil-jdk18on:1.79`; `org.jdom:jdom2:2.0.6`; and `org.sonatype.oss:oss-parent:7` and `:9`. Embedded text, where found, remains separate evidence rather than an invented POM declaration.

Lock-derived roles are **161 release-runtime / six debug-only / 40 test / 162 build-tool / 214 unknown**, with exact source-file/line references per row. Unknown means no role established from module locks, not unauthorized acquisition or app-runtime inclusion. Runtime-lock membership also includes metadata/BOMs and never proves APK bundling. Role precedence and exceptions are documented in `inventory.json`.

There are 409 binary components and 174 metadata-only components. Of the binary components, 245 have no embedded legal-text entry under the documented scan and are explicitly flagged. All 583 components retain **unverified legal-permission** status. Repository candidates come from `settings.gradle.kts:1-14` and P-02's origin section; observed historical download origins remain unavailable, never inferred from group names or project URLs.

## Method, checks and limits

Used existing Python 3 standard-library one-off inspection/generation, then independent saved-output verification; no new script framework, dependency manager, plugin, installation or source download was introduced. Authored README/report changes used patch. The generation checked source snapshot identities before writing only within `licenses/dependencies/`.

Inspected 514 ZIP containers, including 105 AAR nested JARs, selecting complete named LICENSE/LICENCE/NOTICE/COPYING text and documented conventional alternatives. Bounds: 2 MiB per text, 64 MiB aggregate inspected text, 128 MiB per nested JAR, one AAR-JAR nesting level. No bound was hit and no text was truncated. Hash-derived output names prevent archive path traversal; original archive paths remain descriptive data only. Arbitrarily named legal text, deeper nested archives, native-binary strings and unpublished sources are outside coverage.

No Gradle, network, device, signing, Git, nested-agent or private-data operations occurred; `.local-signing` was not accessed. No cache/temp file was deleted. Only the permitted register/notice paths, README and this report were written. Final packaged contents, distribution/JDK/SDK notices, legal obligations, license compatibility and release acceptance require their separate reviews; retain those gates rather than interpreting register completeness as permission to distribute.
