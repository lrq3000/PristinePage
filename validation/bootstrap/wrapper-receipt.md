# Local wrapper-only preparation receipt — T-01

Date 2026-11-14; attempt T01-20261114-A; workspace `X:\local-workspace\.worktrees\paperlight-v1`. **This is not an Android application build, test, APK or T-01 acceptance.** No Android project or app implementation has been created at this checkpoint. The successful task below generated only wrapper files using an already installed tool, without acquiring the target Gradle distribution.

## Actual executed command

Process-local `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` and `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`. The existing build JVM was separately executed and reported OpenJDK21.0.8, build21.0.8+-14196175-b1038.72. No JDK install was performed.

```text
C:\Users\local-user\.gradle\wrapper\dists\gradle-8.12.1-bin\eumc4uhoysa37zql93vfjkxy0\gradle-8.12.1\bin\gradle.bat --offline --no-daemon --console=plain --max-workers=4 -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" -Dorg.gradle.java.installations.auto-download=false -Dorg.gradle.vfs.watch=false -p .superpowers/sdd/IMPLEMENTATION_PLAN/wrapper-project wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 --no-validate-url
```

The isolated ignored wrapper-only project contains no external plugins or repositories. URL validation was deliberately disabled so generation did not send a distribution HEAD request. Gradle reported `Task :wrapper`, `BUILD SUCCESSFUL in 42s`, `1 actionable task: 1 executed`, and the command exited0. Its single-use daemon was configured to stop when the command finished. This result applies only to the local wrapper generator; it must never be presented as a successful app compilation.

## Generated and copied files

Copied only the four new generated wrapper files into previously absent P targets, preserving their bytes. The hashes below identify those working-file bytes. Root `.gitattributes` fixes POSIX wrapper/properties to LF and Windows batch to CRLF on checkout; Git's canonical text blob for the batch is LF, so do not compare its index-blob hash with this CRLF working-file hash. The wrapper JAR is binary and byte-preserved in Git:

| File | SHA-256 |
|---|---|
| `gradlew` | `c07475b363508dbe7cf7d1ded1b31f6d44bb97d98d069a455ab862b1fb9b25f0` |
| `gradlew.bat` | `57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f` |
| `gradle/wrapper/gradle-wrapper.jar` | `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |
| `gradle/wrapper/gradle-wrapper.properties` | `32cc4ad5ed235516c0e58ea0a690b8d351ae98e4fdb74a936387db6437b8a063` |

The wrapper JAR is the8.12.1 bootstrap generator's artifact, not an8.13 JAR. Its hash matched the separately inspected [official8.12.1 wrapper JAR checksum](https://services.gradle.org/distributions/gradle-8.12.1-wrapper.jar.sha256), `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`. Once8.13 acquisition is approved, regenerate the wrapper with8.13 and issue a new receipt; do not relabel this identity.

Properties target `https://services.gradle.org/distributions/gradle-8.13-bin.zip` with expected SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`. A read-only filesystem assertion confirmed the isolated cache had no `wrapper/dists/gradle-8.13-bin` directory. **Do not invoke the copied wrapper before P-02 approval:** it is capable of downloading the target distribution; `--offline` does not prevent wrapper bootstrapping.

## Notices, storage and remaining gate

Retained the installed distribution's LICENSE and NOTICE byte-for-byte under `licenses/build-tools/`. Their hashes are `9536d88ea948603d18e232a13f5958d67807cd80828036b082bff171d2cf0703` and `c2de5fd9adc7ccc4e1382a0e1f38ec6e14f8ca6e28d3c20289fe80353b019aa1`, respectively. These are full build-tool distribution notices, not a claim that every listed component is in the wrapper or will be bundled in an APK. The application's own distribution license is undecided.

Free C: space was12.0GiB after preparation versus15.4GiB during intake. Counted file sizes in this task's isolated cache were745,144bytes and in plan scratch113,719bytes; the cause of the larger whole-volume change is not established. Do not attribute that change to these small directories without evidence. P-01's8GiB pre-heavy-command guard remains active. Nothing was deleted to recover space.

Next required permission is [P-02 development acquisition](acquisition-plan.md). App BootstrapPolicyTest, all Android/module checks, signed APK, physical-device evidence and visual/requirement acceptance remain not run. Temporary cache and ignored plan scratch are retained for manual cleanup.

## Subsequent8.13 refresh — verified2026-11-14

After the explicitly approved P-02 acquisition and observed policy RED→GREEN, the locally present checksum-gated Gradle8.13 distribution regenerated the root wrapper through `wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 --no-validate-url`. This ran alongside incremental five-module unit tests using `--offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict`. Raw log: `validation/raw/bootstrap/manifest-policy-green-wrapper.log`, exit0/68 tasks; eight actual app tests passed, other modules remain source-empty. No URL validation/download/signing/device task was performed in this refresh.

The regenerated JAR SHA-256 is `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, matching the inspected [official8.13 publisher checksum](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256). `gradlew` working-file SHA-256 is `734b3879d3501dce471cf0522d3bcbafe76873d9fc5129345b67fb43bd15e933`; Windows batch and properties retain the earlier working-file hashes. Historical8.12.1 identities above remain unchanged evidence, not current wrapper labels. Copied the8.13 distribution's full LICENSE/NOTICE to `licenses/build-tools/gradle-8.13-LICENSE.txt` and `gradle-8.13-NOTICE.txt`; hashes match the historical notice text above. These are build-tool notices, not a completed runtime dependency audit.
