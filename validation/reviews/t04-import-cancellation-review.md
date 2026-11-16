# T04-CANCEL-R — independent persistence/input review

## Scope and method

Reviewed actual tracked and untracked cancellation changes against HEAD `713cd058291b20304e5c6e63797a3385863b7264`, branch `feat/paperlight-v1`, T04-20261116-A/P-01/U-05. Read complete mandatory root/spec authorities, accepted IMPLEMENTATION_PLAN, requested domain specifications, incident register, P-04/lifecycle/backend references and requesting-code-review/code-reviewer.md. Inspected preparation, lookup, phases, completion, SourceStore, recovery, controller and relevant existing/new tests, not merely the diff.

Only this report was written. Reviewer commands were read-only Git status/diff/rev-parse and bounded `python -B -X utf8 -` file/hash/XML/ZIP checks. No Kotlin test, runner, build, SDK, ADB, console, UI, network, acquisition, indexing, recall, Git mutation, cleanup or nested delegation occurred. Retained execution results below are coordinator evidence, not reviewer reruns.

## Strengths

- `data/src/main/kotlin/app/pristinepage/data/repository/RoomImportSessions.kt:149–178` closes insertion in one short metadata transaction, validates the ordered bounded child identities/destinations before writes, preserves terminal children/digest and checks owning cancellation before writes and after the last write. Work is O(children); no provider/file work holds Room's writer.
- Existing Room writer serialization gives cancellation versus commit a coherent ordering: an earlier committed child survives; cancellation winning first prevents subsequent publication through phase/completion live-state checks. Lookup/replay still verifies committed receipts/private bytes independently of the parent's canceled summary.
- Tests include actual filesystem/journal composition and native Room/codec/reopen evidence, plus a real SQLite ABORT rolling back earlier child updates. The post-inspection hook defaults to no-op and changes no production decoder.
- The small library runner overrides only installation/test scope, retaining the corrected optimization guard, shared allocator, exact target/process identities and verified shutdown primitives.

## Evidence checked

Matched 33 inventory hashes, all 35 artifact-input hashes, 51 XML hashes/465 recorded cases with zero failures/errors: data372/core23/fixtures15 reused by integration; image37/app18 executed. Eight retained RED failures name the missing cancellation command. Integration records149seconds/174tasks. Accepted-plan SHA remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Built/retained test APKs match `10d2b006ef8c60f4d57ed8a604f205d3250dec869ffc5994285a495ba97a80c3`, 3,634,062bytes; ZIP CRC checks pass, no native libraries. Saved SDK records identify S-01, the self-target runner/private Room service and no INTERNET. Independently parsed29named starts/passes, the transcript prefix and80individual command-output files. All13manifest evidence hashes plus base runner match final anchor `bd75f7b8e35f97428bfe24671276ff1d5dc04271db4f8662281d10068ec75564`.

Saved shutdown and current file-only registry agree on generation `4c095794-fe04-4372-9ea3-5275522ca907`: original player24380/VM35552 retain matching starts, wait0/exit0; listed instances stopped, owner-null/released, cleanup completed, no reservation/allocation lock. No fresh process/device query occurred.

## Findings

Critical: **0**. Important: **1**. Minor: **1**.

### CANCEL-I1 — unattempted cancellation is reported as expiry

**Verified source/composition defect:** `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestLookup.kt:17–20`, `RoomIngestPreparation.kt:18`, and `app/src/main/kotlin/app/pristinepage/capture/PhotoImportController.kt:28–39`. Prepare two selected children, commit the first, cancelBatch, then deliver/replay the same callback. The first retains its committed receipt, but the never-attempted canceled child has no journal: lookup returns null and preparation returns SESSION_EXPIRED before interpreting its persisted CANCELED state. An attempted canceled sibling instead reports CANCELED. The new native `image-android/src/androidTest/kotlin/app/pristinepage/image/RoomImportCancellationTest.kt:44–47` explicitly confirms the former behavior; its passing assertion characterizes the inconsistency rather than correcting it.

**Consequence:** the ordered per-item result contract cannot distinguish deliberate abandonment from an expired selection without an additional durable-state lookup; consumers can present incorrect expiry/reselection guidance for an item the user canceled. This violates the specified distinct cancellation outcomes, although it neither publishes a page nor loses bytes. It should be corrected before visible per-item integration, not excused by absent UI.

**Minimal remedy:** recognize a valid ITEM's persisted CANCELED outcome before the generic inactive/expired-session rejection on the no-intent path. Preserve no-IO/no-intent behavior and real expiry classification. Update host/native expectations and add a controller-composition mixed committed/canceled replay assertion.

### CANCEL-M1 — cancellation cardinality boundaries lack regression coverage

**Verified coverage gap:** `data/src/test/kotlin/app/pristinepage/data/ImportCancellationTest.kt:22–25,87–92`; guard `RoomImportSessions.kt:156`. Selected cancellation fixtures use three children. A future `>100`→`>=100` regression would refuse cancellation of a legitimate100-item batch without a direct boundary oracle. Current production comparison is correct; no mutant/test rerun is claimed. Add100-item acceptance and malformed101-item refusal tests proving whole-row preservation on refusal.

## Individually considered and declined behaviors

- Expiry/membership/anchor revalidation during closure: intentionally unnecessary; cancellation inserts nothing and must unblock abandonment.
- CANCELED parent after earlier success: approved abandonment summary; committed child receipts remain authoritative.
- Terminal-parent replay: deliberately read-only, not retroactive cancellation of successful pages.
- Unrelated corrupt journal/counter repair: not this metadata command's authority; existing integrity/recovery gates remain.
- Cancellation racing an already committed transaction/result delivery: reconciliation is required; no impossible rollback guarantee is claimed.
- Immediate provider interruption: command does not cancel the owning coroutine or guarantee blocked IO termination.
- New batches waiting behind old copy admission: slot release is not IO/resource release; future UI must distinguish them.
- Private sealing/moving after abandonment: permitted private completion; publication remains fenced and bytes preserved.
- Historical journals/jobs/leases/staging: deliberately retained, not finalized, reclaimed or clean-recovery acceptance.
- Recovery's retained blocked/canceled records: no resurrection found; reporting/finalization remains separate work.
- Persistable-grant release: unwired platform lifecycle, not achieved by clearing private URI text.
- Picker interaction: Intent/adapter evidence is not actual opened-provider evidence.
- Activity Result persistence/recreation/wrong-navigation routing: still unwritten application integration.
- Per-item UI/application-scoped budget/startup recovery: not supplied by this command or disabled bootstrap.
- Exhaustive native cancellation/commit interleavings, WAL/power loss: not established by these selected cases.
- General runner partial-launch/host-wide management: inherited fail-closed obligations, not a new general-purpose allocator guarantee.
- API26/27, profiles/ICC/WebP, physical performance: separate unfinished gates.
- Prior PICKER corrections and PICKER-M1/IMPORT-M1/SOURCE-M1/ROOM-M1/D-M1/storage minors: unchanged and unclosed here.
- Neural methods, album batch editing, classical editor/export, rights/release: mandatory independent unfinished work.

## Verdict

**NEEDS FIXES — 0 Critical / 1 Important / 1 Minor.** Metadata cancellation/publication safety is coherent within the inspected boundary; correct the composed no-intent error classification before presenting per-item outcomes. No overall T03/T04, scanner, release or merge acceptance is granted.
