# P-DEFAULT-JOURNEY-R — default library/editor journey and fixture isolation

## Scope, version and authority

Independent Android lifecycle and input-isolation review, 2026-11-17. Original verdict: **PASS WITH LIMITATIONS — 0 Critical / 0 Important / 3 Minor**. This is a source-review verdict for the bounded P increment, not a passed current native journey, task acceptance, permission grant or release decision.

Worktree `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`, confirmed HEAD/base `f789a38d919385b97fe1f345e398e9e32307fc05`, plus the uncommitted P files identified below. Read the complete tracked delta from that base and separately enumerated/read all five untracked files, including both debug files. No original review was rewritten. Original O report SHA-256 remains `118b9a1680570baa4ac8b4d06f7d591645cb038a20f9519b33893627894bcd01`, NEEDSFIXES 0C/1I/2M; its separate parent O-I1 correction and deferred O-M2 are context, not a recursive review. O-M1's navigation-context trigger is the subject of this new P increment.

Read complete root `AGENTS.md`, `docs/specs/{AGENTS,PRD,TODO,PIPELINE_CONTRACTS,common-mistakes-and-frequently-used-commands,OPERATIONAL_PLAN,UX_SPECIFICATION,ARCHITECTURE,SECURITY_PRIVACY}.md` and accepted `IMPLEMENTATION_PLAN.md`, including T-09/T-10. Recovered TODO's long T-03 row separately where the file reader truncated it. Accepted-plan SHA-256 is unchanged: `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. Read the complete requested `requesting-code-review/code-reviewer.md` template; severity follows actual effects and reasonable-user expectations, not merely specification omissions.

Read complete current `LibraryContracts`, `ImportLibrarySession`, `LibraryScreen`, `MainActivity`, `ApplicationLibraryActions`, `ImportLibraryViewModel`, `OriginalPagePreview`, the debug provider/manifest and all three new test files. Boundary context read includes `ApplicationServices`, `PristinePageApplication`, `LibraryPickerBinding`, `PhotoImportController`, `EditorState`, `EditorSession`, `EditorViewModel`, `PageEditorScreen`, `ApplicationEditorActions`, `Identifiers`, `DocumentCatalog` values and `RoomDocumentCatalog`. Read existing `ImportLibrarySessionTest`, `LibraryActivityIntegrationTest`, `ImportLibraryIntegrationTest`, `LibraryPickerBindingTest`, `OriginalPreviewIntegrationTest` and `EditorRevisionTest`, the app build/manifest files and test manifest, O's receipt/dispositions, P checkpoint/diffs and the bounded display/persistence runner definitions. Earlier algorithm arithmetic and persistence implementation were not reaudited.

The parent explicitly allowed one test-only bridge correction during this single review. Initially read `LibraryPageSelectionTest.kt` SHA `779dc040feb0d0a3be97b502bf1190bd5112c2046d0e316148bdf90e2f453ee0`; reread all final 68 lines at SHA `9956a1cd66a367d2474cc3bd43378a50966638541d537bdd6e4771e84515743f`. Only Java reflection was replaced with ordinary typed `LibraryIdentity`, `LibraryEvent.SelectPage` and selectedPage access; the five behavioral assertions and controlled metadata IO remain. All eight other P product/test hashes remained identical on the final hash read. This disclosed fixture-only change is part of the original review, not a second review or reviewer-authored fix.

## Commands and evidence boundary

Reviewer shell commands were read-only: `git --no-optional-locks status --short --branch`; `git --no-optional-locks rev-parse HEAD`; `git --no-optional-locks diff f789a38d919385b97fe1f345e398e9e32307fc05 --stat`; `git --no-optional-locks diff f789a38d919385b97fe1f345e398e9e32307fc05 -- app/src/main docs/specs/PIPELINE_CONTRACTS.md docs/specs/TODO.md validation/progress.md`; `git --no-optional-locks ls-files --others --exclude-standard`; `git --no-optional-locks diff --check f789a38d919385b97fe1f345e398e9e32307fc05` (exit 0, no output); `Get-Content -LiteralPath docs/specs/TODO.md | Select-Object -Index 12`; and `Get-FileHash -Algorithm SHA256 -Path <explicitly named source/evidence files> | Format-List Path,Hash`. Dedicated read/glob/grep tools supplied the remaining inspection. The only write is this report, created with patch.

No code/test/build/SDK/signature command, ADB/UI action, live process/device operation, network/acquisition, Git mutation, memory/conversation operation, deletion or nested delegation was performed. **Build/native slot never held.** The parent owns all execution and acceptance.

- Inspected parent fixture-isolation RED XML: one failure, `Private debug editor-journey input is absent`. Subsequent isolation XML has 1/0 failures/0 errors. The affected parent build command records exit 0, 38.973006 seconds; the checkpoint records 88 app cases. This validates that earlier fixture build, not later selection bytes. Command receipt SHA: `36a933b6c4267a1e8b3be4ad413e2a2d591b6218bec0a9f6a0bf138c5aa991f1`.
- Inspected actual default-journey RED outcomes and instrumentation: one start/one failure, no pass. The underlying failure is specifically Page 2's `Selected = false`, not a missing node or scrolling failure. Execution reached visible page-2 Edit, rotation/Apply, an unsaved Look, real Activity recreation with the same retained editor and Discard/Back before failing at selection. Reopen/source assertions after that line were not reached by this RED.
- That RED uses main `b94c7a3b5602fabceea2d894ef5aa203c8d712574c72c8d1a4c95895054c5682` and test `5b9644ede5d99dd98c5e2aa5e6f877902c9b208d6ef6a14376aaea0d58a388b6`. Anchor `validation/raw/decode/picker-adapter-default-journey-context-red-final-manifest.json` hashes to `3b9e9b3f05b875a34eb0612825cc16a845261bcbf64cba566aab570f7d9dce77`. Inspected its current-generation header/record and separate shutdown: generation `31021bce-acad-4cc9-94c6-b19db9e6816c`, original player 35864 and VM 27896 both wait 0/exit 0, usage released. These are retained records, not reviewer live process queries or a comprehensive audit of the anchor's historical ownership list.
- Inspected the initial five missing-feature host REDs and their command receipt (18.347278 seconds, exit 1; SHA `ceab58c3be557b71502ccf821ca4c15397a38596ced930ab17613907bd8ab1c4`). They remain historical evidence, not successful reducer executions.
- During review, the first current integration compiled P production but failed the five new tests at the Java reflection bridge: four `NoSuchMethodException` failures plus the test's translated missing-constructor assertion. `Identifiers.kt:9-13` confirms the IDs are `@JvmInline`; boxed-class JVM signatures cannot be assumed. The log records 93 app tests/5 failures, BUILD FAILED in 45 seconds, 178 tasks/11 executed. Command receipt SHA `03f5b3706f254785d4840c939a00d878b93a7b8683a2cf1df0139c20c0b18447`; failing XML SHA `a0c6181ec533cce319806cc445b63fda99577d0823f51663f5510baa157feb39`. This is not evidence of a failing selection transition and not a green integration.
- At this review's evidence cutoff, the corrected typed test source has been read, but no successful corrected P host run or current P native journey has been inspected. Neither O's 862 recorded JVM/combined 40+1 native passes nor fixture-only 88-app evidence certifies the changed selection bytes. Later parent execution belongs in a separate receipt, without editing this original review.

## Strengths

- Selection now has the appropriate retained owner rather than composition-local `remember`. `LibraryScreen.kt:68-81` derives page selection from retained PageId, validates it against the displayed overview and consistently uses the resulting exact page for semantics, preview and Edit. Preview keys still contain document/page/source; no borrowed pixels enter navigation identity.
- `ImportLibrarySession.kt:41-55,154-164` retains same-document context, clears it on a different document or successful creation, ignores nonmember SelectPage events and revalidates the newest choice after metadata IO. Reading current selection after suspension avoids rolling a later tap back to a captured older value. Existing navigation and metadata-generation guards remain in place. Membership scans are bounded by the existing 100-page catalog contract; no additional index/cache is warranted here.
- Selection only updates metadata StateFlow. It neither calls provider/ingest/save/pin APIs nor changes page/source membership, accepted recipes or import destination. Existing request/phase/consumer/cancel transitions remain untouched and copies preserve their fields. Editor loading and Apply still pass exact retained IDs through the existing Application guarded read/save-CAS ports, not selection-based authority.
- `MainActivity.kt:122-155` adds only optional opaque PageId to the library Bundle, separately from the existing editor identity. Missing page fields remain compatible with older saved state; malformed identity uses the existing explicit recovery path. Initial restored page context cannot supply a displayed/editable page before a successful actual overview lookup. The Kotlin two-argument LibraryIdentity call sites remain source-compatible; no boxed Java constructor guarantee is inferred from `@JvmOverloads`.
- The fixture class/declaration live only under `app/src/debug`; no production source reference, source-set remapping, dependency, schema, storage permission or release manifest change was found. Declaration is explicitly nonexported/no-grants, and both read paths enforce same UID. URI validation requires exact content authority, one canonical lowercase UUID, no query/fragment; the derived leaf has no caller-controlled path segment. Leaf canonical-parent checking rejects ordinary out-of-root leaf symlinks. Only mode `r` reaches a read-only descriptor; query/enumeration/mutation methods fail with constant messages. Static length admission is 1..1,048,576 bytes.
- The new native journey genuinely keeps default MainActivity content and the default Application graph. It seeds through ContentResolver/controller/store rather than replacing content or injecting another graph. From the explicitly seeded overview onward it uses visible selection/Edit/rotation/Apply/Look/Back controls. It checks exact second-page identity, retained draft, reopened turns/revisions, first-page isolation and source-byte preservation. Its comments accurately exclude system-picker/grant and process-death claims.

## Issues

### Critical

None established in the bounded P delta.

### Important

None established in the bounded P delta. The observed reflection failures have a disclosed test-only source correction; they are not promoted to a production defect or silently counted as green.

### Minor

#### P-M1 — canonicalizing the fixture root also trusts a redirected root

**Evidence status:** true from path resolution; not executed here. **Locations:** `app/src/debug/kotlin/app/pristinepage/testing/PrivateEditorFixtureProvider.kt:30-32`; fixture creation at `app/src/androidTest/kotlin/app/pristinepage/LibraryEditorJourneyTest.kt:29-31`.

The leaf is checked against `root`, but `root` is itself the canonical result of the dedicated directory, with no comparison to the intended direct child of canonical cacheDir. If that directory is a symlink to a different readable directory, a matching UUID `.png` there passes the parent/size checks. `isDirectory` in the seeder likewise follows a preexisting directory symlink. Thus the code does not fully establish the stated dedicated-private-cache confinement and could consume the wrong preexisting bytes under a redirected same-UID fixture directory.

This is Minor, not an external arbitrary-file disclosure: both nonexported registration and same-UID checks remain, and creating that redirection already requires private-filesystem authority. No current redirection or real-document access was observed. Nevertheless the fixed-directory claim should fail closed rather than rely on that directory never becoming a link.

**Bounded test/fix idea:** anchor the expected direct child of canonical app.cacheDir independently, reject redirected roots and out-of-root leaves, and test ordinary file, sibling leaf link and root-directory link using only tiny synthetic files in a fresh operation-private test context/root. Do not replace/delete the retained journey directory or use real documents. Same-UID adversarial filesystem replacement races are a separate guarantee; ordinary canonical checks alone must not be described as race-proof descriptor confinement.

#### P-M2 — filesystem exceptions can bypass the provider's constant safe errors

**Evidence status:** true that IO exceptions are not translated; actual path-bearing failure not triggered. **Locations:** `PrivateEditorFixtureProvider.kt:19-21,30-32`.

Explicit rejection branches use constant messages, but canonicalization and `ParcelFileDescriptor.open` exceptions escape unchanged. A filesystem failure containing an absolute filename can therefore reach the resolver caller or retained failure diagnostics despite the intended no-sensitive-path error contract. This does not establish a production UI leak: the provider is debug-only/same-UID and downstream import errors have their own mapping. The gap is in this new adapter's claimed error boundary.

**Bounded test/fix idea:** translate relevant filesystem IO failures to the existing constant unavailable exception without preserving a path-bearing message/cause; retain the intentional constant SecurityException for foreign callers and do not swallow unrelated VM failures. Use a tiny operation-private fixture context or injected IO failure carrying a synthetic private-path sentinel to verify redaction and descriptor cleanup; no real-document, permission-reconfiguration or deletion experiment is needed.

#### P-M3 — retained recreation does not exercise the newly added Bundle round trip

**Evidence status:** true from test/control flow; no restored-selection defect established. **Locations:** `app/src/androidTest/kotlin/app/pristinepage/LibraryEditorJourneyTest.kt:51-54`; `app/src/test/kotlin/app/pristinepage/LibraryPageSelectionTest.kt:32-37`; `app/src/main/kotlin/app/pristinepage/MainActivity.kt:122-155,80`.

The native recreation intentionally asserts the same editor/session, while the library ViewModel is likewise retained; its factory does not create a session from restored IDs in that path. The host restoration case constructs LibraryIdentity directly. Neither test detects an omitted/wrong page key in MainActivity's new save/restore bridge. This is a limited regression-coverage gap, not a process-death claim or reason to discard the correct retained-owner mechanism. A future bridge regression would lose the chosen page after owner loss and could send the next Edit to the visible first-page fallback.

**Bounded test idea:** round-trip an actual saved library Bundle into a newly constructed identity/session with controlled metadata, checking document/page/pending-BATCH preservation, old Bundle without page, malformed page type/ID and a well-formed foreign/removed page being cleared on load without provider/save/launch actions. Keep this distinct from a later genuine fresh-process journey; Bundle/component checks alone do not certify process-death recovery or unsaved-draft persistence.

## Recommendations

Complete the already authorized corrected host/current-artifact native verification before accepting P, retaining the failed reflection run and earlier intended REDs. Do not modify ID visibility/representation or production logic merely to satisfy an invalid Java reflection assumption. Record dispositions for the three Minors rather than rewriting this review or requesting a recursive pass.

Keep the debug input adapter narrowly private. The current merged-manifest test is valuable, but it does not inspect release class/package contents; retain the separate release-artifact exclusion gate for both declaration and class. Prefer a bounded class/artifact inventory over adding a public provider, granting the test APK broad access or replacing default Activity content. Keep the actual default journey labelled overview-to-editor-to-overview, not picker-to-library creation, grant acceptance or fresh-process persistence.

## Declined to judge / unaccepted behaviors

Every behavior considered but not certified is listed below with its reason. These are limitations, not waivers. The concrete root/error findings above remain findings, not exclusions hidden in this list.

### Prior boundaries and unchanged requirements

- Earlier geometry/map/topology/sampler/appearance arithmetic and their numerical Minors: unchanged prerequisites, not a fresh numerical audit.
- N raw Room/SQLite/canonical/source-binding/save-CAS implementation and its deferred coverage: consumed through existing ports; no recursive persistence acceptance.
- O-I1 native allocation/resource containment and graphics-owner timing: previous correction is historical context; P does not change that implementation or certify every native failure timing.
- O-M2 conflict guidance/rebase workflow: explicitly deferred, unchanged by this page-context increment.
- Original O review verdict and evidence identities: retained without rewriting; P's source mechanism does not retroactively change that report.
- GC/source pins, reference reclamation, permanent deletion, Undo retention and broad store/job/WAL/power-loss recovery: no new reclaim or delete operation is enabled or certified by selection.

### Fixture and entry-boundary evidence

- Runtime foreign-UID access/grant denial: manifest and Binder checks inspected, but no separate-UID client or grant experiment was run.
- Encoded traversal, extra/empty path components, wrong scheme/authority, noncanonical UUID, query and fragment adversaries: source rejection traced; no exhaustive runtime URI corpus executed.
- Root/leaf symlink, concurrent replacement and descriptor-time size/type guarantees: P-M1 identifies a source gap; wider adversarial filesystem certification remains unperformed.
- Zero-byte/exact-1-byte/exact-1MiB/over-1MiB/nonregular fixture boundaries: static file/length predicate inspected, not native boundary execution or post-open growth proof.
- All non-read modes and query/insert/update/delete behavior: explicit source refusal traced, not an exhaustive resolver-method runtime matrix.
- Path-bearing filesystem failure/redaction: P-M2 identifies the untranslated boundary; no actual sensitive-path failure was induced or production UI leak asserted.
- Non-PNG bytes disguised by a `.png` suffix: this adapter checks naming, not PNG magic; the ordinary inspector remains responsible for untrusted content. The journey generates an actual tiny PNG and does not certify arbitrary fixture content.
- Release packaged class absence: debug source placement/build configuration inspected and release declaration checked by the parent isolation test; no release class/DEX inventory was executed by this reviewer.
- Actual system-picker selection, temporary/persistable URI grants, revoked grants and P-06 media flow: bypassed intentionally by same-UID private seeding, not passed by this journey.
- Library-list document discovery/open and visible document creation/import: `LibraryEvent.Open(doc)` is fixture addressing; default overview onward is exercised, not an uninterrupted external-input/list-creation journey.
- Fresh-process accepted-state reopen and real process-death recovery: Activity recreation retains owners; P-M3 addresses the narrower missing Bundle bridge oracle, not those larger gates.

### Current runtime and product functionality

- Successful corrected P host/native/default-journey result: not inspected at this report's evidence cutoff; original REDs and failed reflection integration cannot certify final bytes.
- Regression-free pending import/cancel/queued-result routing with selectedPage simultaneously populated: unchanged control flow and inherited tests inspected, but no new combined native acceptance claimed.
- Exhaustive same-document reopen/new-document/empty/removed/reordered-page selection interleavings: selected cases exist in source; current execution and broader combinations remain finite coverage.
- Exact rendered pixels and complete discarded-Look recipe equality in the new default journey: it asserts turns/revisions/source identity, not an independent pixel oracle or every photometric field; inherited editor tests are separate evidence.
- Shape/crop/curve/magnifier/Detail/strength controls: mandatory unfinished UI, not accepted from existing backend algorithms.
- Carousel/neighbors/debounce/cache/Info/provenance, synchronized Original/input/result comparison and true full-context detail: mandatory unfinished behavior, not supplied by this selected-page journey.
- U-04/FR-17 cumulative album subset editing, Mixed state and atomic Done/Undo/Cancel: still required; individual-page rotation and retained selection do not implement batch editing.
- Durable masks/execution/accepted-DEGRADED retention and export consent: separate unfinished contracts, not supplied by metadata selection or this test provider.
- Camera, complete document management/reorder/duplicate/remove/confirmed deletion, export review/render/verification/publication and sharing: outside the bounded implemented journey and still required.
- U-01 multiple genuinely working neural geometry and appearance methods: not implemented or accepted by classical presets or fixture plumbing.

### Platform, quality and release gates

- API26/27, OEM behavior, wider codec/profile/WebP/HDR and hostile-input compatibility: not established by the retained API28 synthetic journey.
- Sustained graphics/PSS/GC/resource pressure, latency/thermal/performance measurements: no benchmark or physical-device claim follows from logical accounting or emulator functional timing.
- Representative real-page fidelity, color/content retention and subjective visual acceptance: tiny synthetic fixtures are diagnostic evidence only.
- TalkBack/large fonts/contrast/Switch Access/reduced motion/full accessibility: minimum-control source semantics and Compose assertions do not certify these acceptance gates.
- Whole-application airplane-mode/fresh-install functionality and complete privacy/backup diagnostics: no complete scanner or production audit was performed here.
- Physical-phone/device operations: explicitly excluded; no query, connection, installation or measurement was performed.
- Sample/model/dependency rights, production signing, store naming/listing/publication and final installable-artifact acceptance: separate open gates; no acquisition, external publication, push or merge is authorized by this review.

## Assessment

**Source review: PASS WITH LIMITATIONS, 0C/0I/3M. Ready for unconditional task/release acceptance: No.** Retaining and revalidating opaque page context is a coherent minimal correction to the observed default-navigation loss, and P introduces no established consequential production regression. The debug adapter has two bounded confinement/error-hardening Minors, the new saved-ID bridge needs its own oracle, and successful corrected current-build evidence remains the parent's acceptance obligation. No GREEN or complete T-09/T-10/scanner claim is made.

## Frozen reviewed P identities

SHA-256 of actual inspected bytes, relative to the worktree. The final test-only bridge hash is used; its earlier hash is recorded above. Authority snapshots at initial inspection were PIPELINE_CONTRACTS `562ba45a4673a834fb569aa21c3ebc2104777974a034335aa6dd14a371462cb8` and TODO `a45ca5ad3c7cf08fc680c791c0d07ebd1deb5d2a9a6efdfeec75a3eb49007e94`; parent evidence/checkpoint additions are not reviewer product edits.

```text
543ea0ec9460dca099fd1c2053d96cd1439f2cffd202bec89615c75b68f9cd21  app/src/main/kotlin/app/pristinepage/MainActivity.kt
82e78e3a143dde0a3e7d5a90ff21bf87e81850516f326fbed1d736478eedeeb8  app/src/main/kotlin/app/pristinepage/library/ImportLibrarySession.kt
83e6dab5ad63d92fdc052bbcc9d407b8c67774b1dfbe8be0c21994b518dbd1ae  app/src/main/kotlin/app/pristinepage/library/LibraryContracts.kt
b80df0e6914184a9e255c2d872beea84bc48d56e25421c028141df367d892c3b  app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt
0a1346041ff1b5fc7734e2de8b99b1d419b47885b3edf1c17612d2d527250770  app/src/debug/AndroidManifest.xml
43757ade630dce7ebb4ba586aaf89f95079e46f0e50254ad21209f4d423a0b90  app/src/debug/kotlin/app/pristinepage/testing/PrivateEditorFixtureProvider.kt
56df326b98f8d42ac04910d01b298e3f8bbf9bd88c1221b4f728f023d8bb5085  app/src/test/kotlin/app/pristinepage/EditorFixtureIsolationTest.kt
9956a1cd66a367d2474cc3bd43378a50966638541d537bdd6e4771e84515743f  app/src/test/kotlin/app/pristinepage/LibraryPageSelectionTest.kt
310991db9fe43f8bc1347b0c5ca2fe1a67c1c9fc0883baa1454dab7628c95c79  app/src/androidTest/kotlin/app/pristinepage/LibraryEditorJourneyTest.kt
```
