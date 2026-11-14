# Application implementation task state

This checklist is the live PristinePage application task-state authority, not the historical documentation receipt. The operator approved local implementation under [P-01](OPERATIONAL_PLAN.md) on 2026-11-14. All acceptance checkboxes remain unimplemented/unverified at setup. Use [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for exact files, interfaces, test steps and commands. Claim metadata belongs here; cross-file requirements/test links belong in [REQUIREMENTS_TRACEABILITY.md](REQUIREMENTS_TRACEABILITY.md).

User amendment U-01 in live PRD requires multiple actual neural methods, including learned geometry and appearance, before completion. Original optional-model defaults do not authorize finishing with every model deferred. Provider selection/assets/runtime and amended T-08/T-12 acceptance need a separately approved concrete plan; no neural download is included in P-02. This blocks only dependent provider/release acceptance, not the approved foundation/classical slice.

| ID | Task | Depends on | Initial state | Claim / evidence |
|---|---|---|---|---|
| T-01 | Reproducible Android bootstrap and capability-free launch | None | In progress — approved acquisition/bootstrap | Owner coordinator `LOCAL_SESSION`; attempt `T01-20261114-A`; P-02 approved and SETUP-R findings closed; next actual acquisition and failing policy test; no app build/test run yet. |
| T-02 | Pure contracts, geometry math and deterministic fixtures | T-01 | Not started | Unclaimed. |
| T-03 | Durable source/document/recipe store and recovery | T-02 | Not started | Unclaimed. |
| T-04 | Picker import and orientation/color-normalized decode | T-03 | Not started | Unclaimed. |
| T-05 | Camera capture with manual fallbacks | T-04 | Not started | Unclaimed. |
| T-06 | Perspective/manual-curve geometry and protected masks | T-02,T-04 | Not started | Unclaimed. |
| T-07 | Classical appearance, noise/detail and binary rendering | T-02,T-06 | Not started | Unclaimed. |
| T-08 | Provider registry, exact public adapters and gating | T-06,T-07 | Not started | Unclaimed; neural integrations optional. |
| T-09 | Editor carousel, comparisons, recipe history and cancellation | T-03,T-06,T-07 | Not started | Unclaimed. |
| T-10 | Local library and multi-page editing | T-03,T-09 | Not started | Unclaimed. |
| T-11 | Snapshot PDF/image export and safe sharing | T-07,T-10 | Not started | Unclaimed. |
| T-12 | Device/offline/fidelity/accessibility/privacy/release gates | T-05,T-08,T-09,T-10,T-11 | Not started | Unclaimed; no application validation run. |

## Claim and completion record template

### Active claim T-01 / T01-20261114-A

Claimed 2026-11-14T13:51:41Z by the integration coordinator, session `LOCAL_SESSION`, in branch `feat/paperlight-v1` and worktree `X:\local-workspace\.worktrees\paperlight-v1`. Input source package technical SHA-256 `54f5d3f72134075b40ca178f8e68f39a626352de2632b2e7db4fe598aba263c3`; original implementation-plan SHA-256 `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; seed commit `a74c341e780f0ea7466ce6814d998e63afb27d27`. Prerequisites: explicit local launch is granted; software acquisition, APK signing and device operations are not.

Permitted coordinator paths: root build/settings/ignore/instruction files, `gradle/`, the five module build files, T-01 app policy/launcher/manifest/resources and tests, live `docs/specs/` authority records, `validation/` and required notices under `licenses/`. These are not permission to bypass failing-test or acquisition gates. A delegated toolchain reviewer may write only its named report under `validation/reviews/`, not shared configuration. No other production task is claimed.

Last verified milestone: unchanged source package hash, all 24 copied source files and the documentation-only seed commit; primary worktree is isolated and root checkout clean. Received the [independent compatibility review](../../validation/reviews/t01-toolchain-review.md), selected the documented AGP8.13.2/Gradle8.13/Kotlin2.3.21 family, and obtained operator approval for the exact direct-artifact [P-02 bill](../../validation/bootstrap/acquisition-plan.md). The [saved-artifact review](../../validation/reviews/setup-artifact-review.md) requires explicit first-wrapper authority reconciliation; its corrective record is under scoped re-review before acquisition. Next action: close that finding, acquire the approved distribution/dependencies, then run the intended failing BootstrapPolicyTest before policy implementation. Evidence/restart map: [validation/progress.md](../../validation/progress.md). T-01 acceptance, app tests, APK and all device/visual gates remain **not run**.

For a task append: owner; claim UTC time; attempt ID; input package/contract hashes; branch/worktree if applicable; exact permitted file set; last verified milestone; next action; focused test names/commands; result/evidence paths; reviewer findings/dispositions; and acceptance state. A resumed worker verifies current source/build state before trusting a prior checklist.

Use `in-progress`, `blocked`, `ready-for-review`, `verified`, or `superseded`; explain a block and its smallest remedy. Test output and implementation acceptance are different. An optional model can be deliberately deferred with evidence, but T-08's registry/unsupported-state tests still must pass. Do not use a deferred model to justify checking an unrelated mandatory feature complete.

- [ ] T-01 accepted.
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
