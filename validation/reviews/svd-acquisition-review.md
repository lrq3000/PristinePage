# SVD-ACQ-R-20261117-A — independent preparation review

2026-11-17. **NEEDS FIXES before acquisition/execution: 0 Critical, 1 Important, 0 Minor.** Permission scope is verified; source-fit is plausible, not artifact/runtime acceptance.

## Scope and judgments

Read complete worktree-root/docs AGENTS, PRD, TODO, OPERATIONAL_PLAN, incident/commands, PIPELINE_CONTRACTS and GEOMETRY_ALGORITHMS (including201–228); accepted-plan introduction/T-06; both acquisition bills/toolchain receipt; actual root/settings/properties/catalog/wrapper, all five module builds, entire verification XML and six locks. No implementation review of the parent's concurrent RenderResult/GeometryEngine changes. Read-only Python hashing/XML/lock inspection only; its initial stdout-encoding failure was corrected. No Git, Gradle, tests, device, installation, executable/source-JAR acquisition, cache manipulation, delegation, memory or global-state operations. Only this report was written.

**Verified permission/spec compliance:** supplied explicit consent agrees with OPERATIONAL_PLAN:39 and bill:13–24: only `org.ejml:ejml-ddense:0.46.1` plus `org.ejml:ejml-core:0.46.1`, optional same-coordinate sources/metadata, Maven Central,16MiB downloads/256MiB growth/8GiB disk guard. No OpenCV/ejml-all/SDK/model widening. Coordinator ownership and build-slot release remain prerequisites; consent does not cure execution infeasibility.

**Verified publication evidence:** independently fetched [ddense POM][dp], [core POM][cp], [ddense module][dm], [core module][cm]. Both API/runtime variants advertise JVM11; ddense depends only on core0.46.1, core declares no dependencies. All four bill sizes/SHA256s match publisher module text. Runtime729639bytes; optional sources655348bytes. Published digests share the origin's trust boundary, not independent signature authentication. [Tag][tag] resolves to `fe9b94b4c47aeebb74ef90a6759d92916a9d4146`; [license][license]/source headers agree with Apache-2.0. Artifact notice contents remain uninspected.

**Actual configuration:** Gradle8.13, AGP8.13.2, Kotlin2.3.21, toolchain21/target17, Android minimum26/compile36/build-tools35.0.0; provisioning disabled. XML has584 components, metadata checking enabled, signatures disabled, no trust bypass configuration; no EJML entries. Six locks likewise contain no EJML. Receipt identifies existing JBR21.0.8; no live JVM/cache inventory was attempted. JVM11 metadata is compatible in principle with target17, but neither actual classfile versions nor Android/API26 support are proven.

**Source-fit, qualified:** pinned [factory][factory] `svd(8,9,false,true,false)` selects float64 implicit-QR, noncompact. [SVD][svd] `setup/computeUWV/undoTranspose/getV` transposes8×9, requests original V through transposed U, then swaps back; [bidiagonal][bidiag] `handleU` creates full9×9. This supports the ninth null vector required by geometry:54, without explicit normal equations. [Interface][interface] guarantees only eight singular values and no ordering: select V column8, not the minimum of eight represented vectors; compute extrema across exactly eight finite positive values. Require actual9×9 shape, normalized residual/orthogonality, Hartley/projective oracle, rank/condition and near-degenerate rejection tests. This audit ran none.

## Important finding I-1 — unchecked execution and misleading acquisition scope

**Verified:** bill:24 requires publisher-hash matching before execution, but bill:26 combines `--write-verification-metadata sha256` with tests. Gradle8.13 [writer][writer] `onArtifact/addChecksum` queues hashes; `maybeReadExistingFile` merges seeds only in `buildFinished`, not before artifact use. Thus seeded hashes do not enforce verification in writer mode, and tests can execute unchecked new JARs. Later strict verification cannot undo that exposure.

The pending `:core:dependencies` writer proposal avoids consuming EJML in tests, but is **not metadata-only**: `buildFinished → resolveAllConfigurationsConcurrently → resolveAllConfigurationsAndForceDownload` visits every project's safely resolvable configurations and forces external files. It can fetch JARs/unexpected graph members and consume the resource envelope before diff review.

**Required correction:** coordinator records a non-consuming, bounded acquisition phase and independently checks exact roots/bytes before execution; preferably preseed reviewed POM/module hashes plus exact published JAR hashes and use strict mode **without** the writer flag. Keep old hashes/versions unchanged; inspect generated locks and forbid additional accepted hashes for either JAR. Strict graph reporting alone does not acquire/validate every runtime artifact. Subsequent strict test/native evidence and resource measurements remain required. Parent's proposal is not accepted as currently characterized.

Declined: actual artifacts/classfiles/DEX/API26/LDPlayer compatibility, numerical correctness, new dependency-graph resolution, download/cache/disk measurements, packaged notices, security/signature/release/physical/visual acceptance. Two404 source-path lookups were not retried; other checks used successful official text. No completion/release approval.

## Input identities

SHA256 of UTF-8 records `relativePath + NUL + lowercaseSHA256 + LF`, sorted by path, for the following29 inputs: `b427662c403574875d25324e2fdd5bddffc0a0f4281b570e69a4c5b6f97c213a`.

```text
AGENTS.md f5a35f0f41f3ad4cb0740702114b6b968fd6eeba1b5eaf1ab54416a951f47499
app/build.gradle.kts 106aad2dc3e6ac147f7381abc10fcfff5b9941c62144417619c87552021c483d
app/gradle.lockfile 91949d6ed3ebac45698b8bad7bb642b89eb4ed370c469f362ca0a41e68d89bf3
build.gradle.kts d875ad5a68a7b30484c08a6fc815a7519cf861165934f6fbaae6e31e26aa5c22
core/build.gradle.kts 8bcdf80fa615d9f397fcdbbfbc51ba4c291b7a19d726c0fdc0d43d07e67d3010
core/gradle.lockfile 269baf55a31603738adbb3ef072fc6b00039a39a9c67217f1df9741d99b0aeb7
data/build.gradle.kts e757617ddb02ed32244450a09fd8ab633f7fd5da58de321f908a3330aeaf22e1
data/gradle.lockfile 5917fe4c88f0ca0923a974bf7280c26a6c0ed3ba69425f0e53968c2d59bf97dd
docs/specs/AGENTS.md 7eb61463bbc3d2eb6423896a8270ff737b56923ed478f9587af49691c4e6da02
docs/specs/GEOMETRY_ALGORITHMS.md df73269a75985a14cbf961ba66933f9f38fff36fa0d7c9aab81b7bf577eb3dc8
docs/specs/IMPLEMENTATION_PLAN.md 791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa
docs/specs/OPERATIONAL_PLAN.md 668433554381fcf4f2ff0dbd50884cfebc747d4b96a630c1a58b961fe421b48f
docs/specs/PIPELINE_CONTRACTS.md 2b4337205e65afe0d0f30acc513c11d17bae7807cadc2f4d9829fed4d0a6598f
docs/specs/PRD.md 833964c36786265a9001704f5970678cc49e13df455a9cc3f6644e5fb4f5eb41
docs/specs/TODO.md 82afa5a5fa695856feb1b08fe30e2f20050b5e660e9952f2ee0a69527f8d1d36
docs/specs/common-mistakes-and-frequently-used-commands.md 53ba82d942fa53df3ef8b48bb7ce0400a6a64b115c633235811d191e6df4e83e
gradle.properties d90529da2c1586ee8a8b7488ac4573bc6198189aedc7a8acb4953448e8567192
gradle/libs.versions.toml 64bac3883cc8412a6dcf9fd95eec4f1ed392d90c01dff7049e131db03120381d
gradle/verification-metadata.xml 78a246849b5350a75e7137d6b9469e2b7d6997b213ac02ea10b8bff3fd3b5d1b
gradle/wrapper/gradle-wrapper.properties 32cc4ad5ed235516c0e58ea0a690b8d351ae98e4fdb74a936387db6437b8a063
image-android/build.gradle.kts 8da1a83213aaef304d96839ee3981dbf6795202211ae27c8708edf9840d8812d
image-android/gradle.lockfile 623db6b5b5fc4b77e807106f573577ebcec001d36190488e294d7295a10cfc79
settings-gradle.lockfile 6656e3aed66762d2f39666de089080c66a3224078aabdf27764b041da508df84
settings.gradle.kts 00a2f2c339010cde5ad89f6933701925fe4b54aa1caf228818abae7768beb5e6
test-fixtures/build.gradle.kts 3c7ed2419207ec018038eff2828302d790980337379876e69fa3ee44656ca39e
test-fixtures/gradle.lockfile 81a4d9b858b2cdfd61ef5cbd35ba30a0fda2b9ea643edadad1e643f1e4fc6d83
validation/bootstrap/acquisition-plan.md 92fe6ddb0febb2b3b229c281a2ef1a7ee997ca4cd4870e568e678f7abc6842da
validation/bootstrap/svd-acquisition-amendment.md 697f8d14ced18d1cd8acb6e7736ea77e62ff82c0cb28d691c346f490e8ba28d8
validation/bootstrap/toolchain-receipt.md b8a83ad31a2f3fd2848f52cebb5efbb1461835cc8c2ae2a7004315ac552cd9d4
```

[dp]: https://repo.maven.apache.org/maven2/org/ejml/ejml-ddense/0.46.1/ejml-ddense-0.46.1.pom
[cp]: https://repo.maven.apache.org/maven2/org/ejml/ejml-core/0.46.1/ejml-core-0.46.1.pom
[dm]: https://repo.maven.apache.org/maven2/org/ejml/ejml-ddense/0.46.1/ejml-ddense-0.46.1.module
[cm]: https://repo.maven.apache.org/maven2/org/ejml/ejml-core/0.46.1/ejml-core-0.46.1.module
[tag]: https://api.github.com/repos/lessthanoptimal/ejml/git/ref/tags/v0.46.1
[license]: https://raw.githubusercontent.com/lessthanoptimal/ejml/fe9b94b4c47aeebb74ef90a6759d92916a9d4146/LICENSE-2.0.txt
[factory]: https://raw.githubusercontent.com/lessthanoptimal/ejml/fe9b94b4c47aeebb74ef90a6759d92916a9d4146/main/ejml-ddense/src/org/ejml/dense/row/factory/DecompositionFactory_DDRM.java
[svd]: https://raw.githubusercontent.com/lessthanoptimal/ejml/fe9b94b4c47aeebb74ef90a6759d92916a9d4146/main/ejml-ddense/src/org/ejml/dense/row/decomposition/svd/SvdImplicitQrDecompose_DDRM.java
[bidiag]: https://raw.githubusercontent.com/lessthanoptimal/ejml/fe9b94b4c47aeebb74ef90a6759d92916a9d4146/main/ejml-ddense/src/org/ejml/dense/row/decomposition/bidiagonal/BidiagonalDecompositionRow_DDRM.java
[interface]: https://raw.githubusercontent.com/lessthanoptimal/ejml/fe9b94b4c47aeebb74ef90a6759d92916a9d4146/main/ejml-core/src/org/ejml/interfaces/decomposition/SingularValueDecomposition.java
[writer]: https://raw.githubusercontent.com/gradle/gradle/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ivyresolve/verification/writer/WriteDependencyVerificationFile.java
