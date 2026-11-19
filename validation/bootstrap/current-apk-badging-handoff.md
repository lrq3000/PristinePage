# feat(validation): parse current APK badging metadata

**Disposition:** `U-CURRENT-APK-BADGING` is a host-only T-01/T-11 validation prerequisite for review, not integrated or accepted as artifact evidence. The single-parent candidate is on `work/pristinepage-current-apk-badging` in `X:/local-workspace/.worktrees/pristinepage-current-apk-badging`, from exact clean canonical `main` `2d69563fbdd4ab99288a6808dc18f19db65460ee`, tree `64d80b7be9d7b581c223deedd90f05429c7b0d2f`.

## Motivation and achieved scope

[P-02A](dependency-scope-amendment.md) requires badging from the current catalog-pinned SDK as one part of actual artifact verification. The older P-06 operational helper replays previously recorded commands and is not a parser for current metadata. Reuse the observed output format rather than the historical executable path or a literal version. This candidate adds just the inert parser, the replayed and extended unit tests, and this handoff under `validation/bootstrap/`; it neither invokes nor changes a runner.

`parse_badging(text, *, package, min_sdk, target_sdk)` consumes text in memory and returns `package`, `version_name`, `version_code`, `min_sdk`, and `target_sdk`. It requires actual string arguments, a dotted Android package ID, and exact non-boolean positive integer SDK expectations with `min_sdk <= target_sdk` within signed 32-bit range. It accepts LF or CRLF. It requires a unique package row with unique quoted attributes, a matching name, and separate exact `minSdkVersion` and `targetSdkVersion` rows. Neither the legacy `sdkVersion` spelling nor compile/target SDK output substitutes for minimum SDK. Other output rows are not interpreted. Malformed, duplicate, absent or mismatched metadata raises `ValueError`; wrong caller types raise `TypeError`.

Version fields are read, not compared to frozen release values. The version code is read as an ASCII decimal 32-bit field; the nonempty version name is returned as supplied within the observed single-quoted grammar (no guessed escaping). Both keys must be present for both artifacts. Only the named `app.pristinepage.debug.test` package can carry the observed pair of empty values, normalized to two `None`s; populated test versions are returned, not inferred from the app. No attempt is made to interpret an Android SDK codename or a quoted field outside the observed grammar.

## Input and exact historical observations

The original witness `X:/local-workspace/.worktrees/pristinepage-apk-sdk-witnesses/validation/bootstrap/test_current_apk_verification.py` was SHA-256 `d63b8b6f49369e1b27e53d1f4e6d6795ad98827c99b30a10f5fbeb3f583b10fa`. The original was preserved; the first run used a byte-identical copy, and the final test file keeps its original cases and appends a separate boundary test class.

The retained [T-09 comparison receipt](../editor/t09-editor-comparison-receipt.md) anchors the Q pair. In `X:/local-workspace/.worktrees/paperlight-v1/validation/raw/editor/`, `editor-comparison-current-sdk-commands.json` records two successful historical Build Tools `35.0.0` `aapt2 dump badging` invocations. The tested header forms are taken from lines 1–3 of the recorded stdout files:

| Retained stdout | SHA-256 of the full stdout | Observed values |
|---|---|---|
| `editor-comparison-current-sdk-app-badging.stdout` | `a9ecd030cf1ca70be645aa9d9c317eaf31f1bc6861ec90b27cd34ef15b3cc4d4` | `name='app.pristinepage.debug' versionCode='1' versionName='0.1.0-debug'` |
| `editor-comparison-current-sdk-test-badging.stdout` | `269761e44fd96c9f4777793c534e7a000658412a6312a4e4bbd68ddd00d71649` | `name='app.pristinepage.debug.test' versionCode='' versionName=''` |

Both files have `minSdkVersion:'26'` and `targetSdkVersion:'36'`; their package rows also carry platform build and compile SDK attributes. The fixtures are metadata witnesses only. This assignment did not execute either SDK version; `36.0.0` is the current repository pin, not the provenance of the Q transcripts.

## Verification and release

All three test batches used the same command from the assigned worktree with the installed `C:\Users\local-user\miniconda3\python.exe` (product version `3.13.12`):

```text
python -B -m unittest discover -s validation/bootstrap -p test_current_apk_verification.py -v
```

| Run stem | Python PID | Exit | Observed result | stderr SHA-256 |
|---|---:|---:|---|---|
| `01-original-red` | 37796 | 1 | 4 methods, 13 `Current APK badging verifier is absent` assertion failures, no errors | `63f7c35115de80668c2362f7f5406f413447094573d1eab02e2eb951fee95a9e` |
| `02-expanded-red` | 50636 | 1 | 14 methods, 56 of the same missing-parser assertion failures, no errors | `78d08f700035e303acb020d0e6a36935faec7c5f052e1c9297216b061292d7bc` |
| `03-focused-green` | 47008 | 0 | 14 fresh methods passed, including all subtests; no failures or errors | `a2c1e27bef1e8312ac0431c31b830a2b13d263dedb562937806b87759a0c3eaa` |

The raw `.stdout`, `.stderr`, and `.json` exit/argv/input-hash records are retained under the worker's ignored `validation/raw/bootstrap/current-apk-badging/`; stdout was empty in all three runs. The first shell launch attempt stopped before Python with `Start-Process: Cannot convert 'System.Object[]' to the type 'System.String' required by parameter 'FilePath'. Specified method is not supported.` Command resolution had returned both the installed Python and the WindowsApps alias. Selecting the first resolved application corrected the invocation; that setup failure is not counted as RED.

The focused tests cover exact retained header forms, changed app/test versions, CRLF, duplicate/incorrect/malformed package and SDK fields, legacy minimum/target decoys, malformed and ambiguous version fields, and invalid caller types/ranges. The final test SHA-256 is `228b4f5b0d5eecc637cc4e00c2b7d714a9855443bcf2cb6b96e1c3a0f879fdc2`; parser SHA-256 is `200bf7d6073dc98493fe2beafa105d649aded749723c86d3505258c423903117`. No other tests were run: there is no existing bootstrap Python suite or affected existing parser consumer, and the historical operational runners are outside this assignment. Each Python batch finished synchronously with `HasExited=true` and the recorded exit code. No retry/background task remains; the serialized Python test slot returns to the coordinator at handoff.

## Coordinator reception and open gates

Independently review the three-path diff against the exact base, check the output grammar and refusal behavior, then rerun the named focused command on the destination after reconciling any drift. The parser's return value does not attest to the command's provenance or exit, APK bytes, agreement with current Gradle output metadata, signer, manifest/resources/backup policy, alignment, ABI or dependency contents. Separate authorized artifact verification must bind those proofs to the same exact APK pair; no APK was built, read, signed, verified or installed here. No SDK/Gradle, ADB, LDPlayer, phone, cleanup, acquisition, remote or conversation/memory action was performed. Current preview delivery and the uninterrupted import → edit → save → reopen → verified export journey remain open.

**Included commit:** the enclosing single-parent local commit by lrq3000, `feat(validation): parse current APK badging metadata`. It preserves the witness and proves the text-only boundary without reviving an old SDK command. **Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

## Independent review round 1/5 — Important, test-only preparation

The reviewer found that a valid row can mask a malformed duplicate when the colon is missing but the payload remains. At candidate `a585624cc8707592f3fae1e914f5342369f6a06f`, lines 45–56 use `line.partition(":")[0].strip(" \t")` as the key. Without a colon, that expression includes the payload (for example, `minSdkVersion '28'`), so the malformed row is ignored and the original valid row supplies `26`. The same trace applies to `targetSdkVersion` and `package`, independent of row order. Existing duplicate/malformed cases with colons did not exercise this branch.

The new refusal test pairs each exact required label with a valid report, using a missing colon with a space-separated payload and a wrong `=` delimiter with tab whitespace, in both orders. **Predicted RED on the unchanged parser:** 12 subtest failures with `ValueError not raised`; the malformed rows are ignored and the report is accepted. The separate control expects unchanged metadata with longer labels `packageInstaller`, `minSdkVersionPreview`, `targetSdkVersion_2` and real unrelated badging rows; it is predicted to pass on both the unchanged and corrected parser.

**UNRUN:** The snapshot worker holds the sole test slot. This round only edits the test source and appends this record; the parser and all earlier evidence are unchanged, and no staging or commit was performed. After the slot is released, run the focused command above on the unchanged parser to observe the expected REDs before changing production. The correction must recognize each exact required label before checking row syntax, without treating a longer identifier as that label. Then rerun the focused suite and retain actual results separately from this prediction.

### Round 1/5 correction and observed evidence

After the snapshot worker's verified slot release, the same focused Python command ran on exact parent `a585624cc8707592f3fae1e914f5342369f6a06f`, tree `1f2858135e6bd63cfd598514dcba4e9051397e35`. The review test SHA-256 `b441337285833f74ea055cc979e3fd90cffea78043ddca63bcae71d0b816ca94` was identical in RED and GREEN. RED used original parser SHA-256 `200bf7d6073dc98493fe2beafa105d649aded749723c86d3505258c423903117`; GREEN used corrected parser SHA-256 `48f466134dc180932ce8502c984eff2ebc98a5f48e00afcc4d369887de89047b`. Both runs record the pre-append handoff SHA-256 `0650da91486f7d3ac53ceb19efcb24de9985843e1685a5e38539920c52ee41a0`.

The scan now reads the complete leading label token after optional spaces/tabs, independently of the colon. The token includes word characters, dots and hyphens so longer labels do not match by prefix. Only an exact required label is counted; its original full row is retained for the unchanged package and SDK syntax checks. A malformed duplicate cannot be masked in either order. No caller, version, line-ending or artifact-verification behavior was changed.

| Run stem | Python PID | Exit | Observed result | stderr SHA-256 | JSON SHA-256 |
|---|---:|---:|---|---|---|
| `04-review-round-1-red` | 1732 | 1 | 16 methods; 12 `ValueError not raised` subtest failures, no errors; unrelated-label control passed | `36eef7597d8623b2f00d534b74759bd7ee5bfdf109eb304a53097526b6b8b8d4` | `00a821ca0e8d9d383bde1c478705b58ee0516942ad2d264dc29969a202e88774` |
| `05-review-round-1-green` | 29924 | 0 | All 16 methods passed, including the 12 refusal subtests and unrelated-label control | `b251148046688abf6a67a8f30574b10ecd8012aff488dbddf3ccc48b6534e7cc` | `84cd1405c211cccd4a24beef1e51382a3d8bc5cfa546f003ca60061b1d5e486b` |

Both stdout streams were empty (SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`). All six files are retained beside the original evidence in ignored `validation/raw/bootstrap/current-apk-badging/`. Both Python processes completed synchronously with `HasExited=true`; the serialized Python-only slot is released at handoff. The single correction commit, `fix(validation): reject malformed duplicate badging labels`, contains only the parser, the prepared test source and this handoff. Scoped independent re-review and exact destination verification remain with the coordinator; no APK, SDK, Gradle, signing or device operation was performed.
