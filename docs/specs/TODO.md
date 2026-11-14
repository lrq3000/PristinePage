# Application implementation task state

This checklist is for the future application, not the authoring of this documentation. All tasks below start unimplemented. Use [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for exact files, interfaces, test steps and commands. Claim metadata belongs here; cross-file requirements/test links belong in [REQUIREMENTS_TRACEABILITY.md](REQUIREMENTS_TRACEABILITY.md).

| ID | Task | Depends on | Initial state | Claim / evidence |
|---|---|---|---|---|
| T-01 | Reproducible Android bootstrap and capability-free launch | None | Not started | Unclaimed; no build run. |
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
