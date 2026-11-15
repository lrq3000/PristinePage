# Application implementation task state

This checklist is the live PristinePage application task-state authority, not the historical documentation receipt. The operator approved local implementation under [P-01](OPERATIONAL_PLAN.md) on 2026-11-14. All acceptance checkboxes remain unimplemented/unverified at setup. Use [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for exact files, interfaces, test steps and commands. Claim metadata belongs here; cross-file requirements/test links belong in [REQUIREMENTS_TRACEABILITY.md](REQUIREMENTS_TRACEABILITY.md).

User amendment U-01 in live PRD requires multiple actual neural methods, including learned geometry and appearance, before completion. Original optional-model defaults do not authorize finishing with every model deferred. Provider selection/assets/runtime and amended T-08/T-12 acceptance need a separately approved concrete plan; no neural download is included in P-02. This blocks only dependent provider/release acceptance, not the approved foundation/classical slice.

U-04/FR-17 now mandates [cumulative album batch editing](BATCH_EDITING.md), explicitly approved for design/requirement recording2026-11-15. Select all/subsets, preserve independent accumulated turns/settings across selection changes and save all effectively edited pages on one Done, with previews, Undo/Redo, Cancel and one atomic recipe save. This supplements T-02/T-03/T-09/T-10, not a replacement implementation plan or completed feature. [Independent design review](../../validation/reviews/batch-editing-design-review.md) passed its documentation/builder scope; implementation precautions were made explicit. T-02 remains unclaimed/not started.

| ID | Task | Depends on | Initial state | Claim / evidence |
|---|---|---|---|---|
| T-01 | Reproducible Android bootstrap and capability-free launch | None | Verified — bounded bootstrap only | Coordinator `LOCAL_SESSION`; T01-20261115-I01 source b3759b2.9 app tests GREEN/offline build148tasks, static review PASS. Reviewed SHA19595f091f69 installed/byte-matched; launcher/UI/PID logs checked on owned index2/API28 ARM64 translation; original player/VM exits verified and usage released. [Runtime receipt](../../validation/bootstrap/ldplayer-receipt.md) and [independent review](../../validation/reviews/t01-runtime-review.md) PASS with retained RT-1/RT-2 Minor limitations. Phone excluded; no full scanner/offline/physical/rights acceptance. |
| T-02 | Pure contracts, geometry math and deterministic fixtures | T-01 | Not started | Unclaimed. |
| T-03 | Durable source/document/recipe store and recovery | T-02 | Not started | Unclaimed; U-04 adds atomic applyBatchRecipes, full revision validation and one saved-batch Undo; BatchRecipeTest remains future work. |
| T-04 | Picker import and orientation/color-normalized decode | T-03 | Not started | Unclaimed. |
| T-05 | Camera capture with manual fallbacks | T-04 | Not started | Unclaimed. |
| T-06 | Perspective/manual-curve geometry and protected masks | T-02,T-04 | Not started | Unclaimed. |
| T-07 | Classical appearance, noise/detail and binary rendering | T-02,T-06 | Not started | Unclaimed. |
| T-08 | Provider registry, exact public adapters and gating | T-06,T-07 | Not started | Unclaimed; U-01 mandates multiple working neural geometry/appearance methods at completion; exact provider/asset/runtime plan remains separately gated. |
| T-09 | Editor carousel, comparisons, recipe history and cancellation | T-03,T-06,T-07 | Not started | Unclaimed; U-04 adds independent per-page batch drafts, changing-selection cumulative turns/settings, Mixed values and Done/Cancel/Undo/Redo; BatchEditSessionTest remains future work. |
| T-10 | Local library and multi-page editing | T-03,T-09 | Not started | Unclaimed; U-04 adds album overview selection/Select all/clear, previews and integrated batch-save/reopen/Undo/accessibility journey. |
| T-11 | Snapshot PDF/image export and safe sharing | T-07,T-10 | Not started | Unclaimed. |
| T-12 | Device/offline/fidelity/accessibility/privacy/release gates | T-05,T-08,T-09,T-10,T-11 | Not started | Unclaimed; no application validation run. |

## Claim and completion record template

### U-04 documentation-only amendment / U04-20261115-BATCH

Owner coordinator `LOCAL_SESSION`; baseline `34cee75`; explicit current-album choice and “Approve and record requirement” form2026-11-15. Approved paths: `docs/specs/{BATCH_EDITING,PRD,UX_SPECIFICATION,DATA_MODEL_AND_STORAGE,TODO,REQUIREMENTS_TRACEABILITY,OPERATIONAL_PLAN}.md`, `validation/progress.md` and independent report `validation/reviews/batch-editing-design-review.md`. Scope: design/required tests, cross-file integration, independent review and verified local documentation commit only. No product code/schema/dependency/model/emulator/device operation, push or merge. Accepted plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; no new production task is claimed. State: approved design recorded; independent compliance/design PASS, no material builder question; pending-Done/conflict/Redo precautions added as future tests. FR-17 feature remains not started/unaccepted.

### Completed bounded claim T-01 / T01-20261114-A, identity/runtime subattempt T01-20261115-I01

Claimed 2026-11-14T13:51:41Z by the integration coordinator, session `LOCAL_SESSION`, in branch `feat/paperlight-v1` and worktree `X:\local-workspace\.worktrees\paperlight-v1`. Input source package technical SHA-256 `54f5d3f72134075b40ca178f8e68f39a626352de2632b2e7db4fe598aba263c3`; original implementation-plan SHA-256 `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; seed commit `a74c341e780f0ea7466ce6814d998e63afb27d27`. Current prerequisites: P-01, exact P-02/P-02A, S-01 with I-01 package amendment, and exclusive LDPlayer P-04 are granted. Personal phone is excluded; neural acquisition, broader devices, production signing and publication remain unapproved. Current subattemptT01-20261115-I01 changes executable identity only; old plan/source prefix mapping follows U-03.

Permitted coordinator paths: root build/settings/ignore/instruction files, `gradle/`, the five module build files, T-01 app policy/launcher/manifest/resources and tests, live `docs/specs/` authority records, `validation/` and required notices under `licenses/`. These are not permission to bypass failing-test or acquisition gates. A delegated toolchain reviewer may write only its named report under `validation/reviews/`, not shared configuration. No other production task is claimed.

Last verified milestone: I-01 sourceb3759b2 after9 green app tests/offline strict148-task build and independent static review; P-04/I-01 freshly installed/byte-matched the reviewed replacement, verified launch/foreground/UI/scoped logs and gracefully stopped the owned instance with both original player/VM exits confirmed. Independent T01-RUNTIME-R passed bounded scope/evidence quality with RT-1 transcript and RT-2 post-shutdown-anchor Minor limitations; coordinator reverified actual evidence/hashes/current released records and accepted only bounded T-01. No application source changed or device replay occurred during reconciliation. Publisher-verified8.13 wrapper, S-01 signing, P-02A and583-component register retained; lint0errors/3warnings, source-empty nonapp tests and rights gaps remain. Next unclaimed task is T-02 under the accepted plan; full scanner/physical/offline/visual/rights gates stay open. Restart map: [validation/progress.md](../../validation/progress.md).

For a task append: owner; claim UTC time; attempt ID; input package/contract hashes; branch/worktree if applicable; exact permitted file set; last verified milestone; next action; focused test names/commands; result/evidence paths; reviewer findings/dispositions; and acceptance state. A resumed worker verifies current source/build state before trusting a prior checklist.

Use `in-progress`, `blocked`, `ready-for-review`, `verified`, or `superseded`; explain a block and its smallest remedy. Test output and implementation acceptance are different. An optional model can be deliberately deferred with evidence, but T-08's registry/unsupported-state tests still must pass. Do not use a deferred model to justify checking an unrelated mandatory feature complete.

- [x] T-01 accepted — bounded reproducible/capability-free bootstrap only; runtime-evidence limitations and unperformed dependent physical/offline/rights gates remain explicit in the receipt/review.
- [ ] T-02 accepted.
- [ ] T-03 accepted.
- [ ] T-04 accepted.
- [ ] T-05 accepted.
- [ ] T-06 accepted.
- [ ] T-07 accepted.
- [ ] T-08 capability boundary accepted; each actually shipped asset independently accepted.
- [ ] T-09 accepted.
- [ ] T-10 accepted.
- [ ] T-11 accepted.
- [ ] T-12 release gate accepted; unresolved failures disclosed.
- [ ] FR-17 batch-editing feature accepted through current-build T-03/T-09/T-10 evidence; documentation approval alone does not pass it.
