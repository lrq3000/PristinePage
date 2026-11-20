# Atelier Bézier implementation checkpoint

> **For agentic workers:** Use `executing-plans` and `test-driven-development`, without replacing the accepted scanner implementation plan.

**Goal:** Edit and reopen an actual four-edge cubic frame, with the same render path for preview and full-target output.

**Architecture:** A versioned source-bound contour carries explicit chord state, knots and controls. The shared renderer certifies the projective map before sampling; the editor draws the same authored curves and uses the ordinary recipe history and save gate.

**Tech stack:** Kotlin, Kotlin Serialization, Compose, Room, existing CPU geometry and masks.

**Spec:** [Atelier](../../specs/ATELIER.md), [cubic mapping](../../specs/ATELIER_BEZIER.md), and [pipeline contracts](../../specs/PIPELINE_CONTRACTS.md).

## Global constraints

Preserve legacy codec hashes and immutable oriented-source identities. No new dependency, device, acquisition or publication scope. Keep app-owned copy in English. Production admission depends on the actual map proof and normal render budget; unsupported edits must not look committed.

## Review focus

- A split chord is still exact, even with a short terminal knot interval.
- A full-frame inward bow remains usable at preview scale; strong/folding curves fail without substituting the midpoint algorithm.
- RGB and both typed masks share the actual coordinates under odd turns.
- Editing a node translates only its attached handles; cancellation and Undo restore the full contour.
- An unsupported contour must not write a new accepted revision, and a saved contour must reopen with its exact parameters.

## Tasks

- [x] Host candidate: true authored cubic evaluation, exact straight dispatch, outward continuous stretch and extent proof, and cancellable reference support.
- [x] Typed codec and raster consumer: JVM round-trip and legacy canonical golden, real curved raster, mask/rotation alignment and strong-bow rejection.
- [ ] Editor: focusable side/segment and named point controls, insert/move/remove, visible cubic contour, guarded Apply and retained history are wired. Host gesture/Apply tests, lint and Android test compilation passed; exact native reopen still needs execution.
  - `BezierContour.moveNode(side, index, point)` moves the join and its adjacent arms by the same delta; `removeNode(side, index)` merges the interval and keeps the two outer arms. The editor validates normalized positions, uses `EditorSession`/`RecipeHistory` for every mutation, and draws these coordinates on the fitted oriented source.
- [ ] Native acceptance: independently review numeric, persistence and UI boundaries and exercise AT-04 on the authorized target. Do not publish a host-only checkpoint as device acceptance.
