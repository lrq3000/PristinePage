# Interaction, accessibility and algorithm comparison

## Navigation and primary journeys

Use four destinations: Library → Capture/Import → Page editor → Export review. Multi-page document overview is part of the editor, not a separate account/cloud workspace. The first run offers Take photo and Import photos; permission refusal still leaves import and library available. Avoid an onboarding wall or model download prerequisite.

Capture shows a live preview, manual shutter, available flash modes and add-page count. A proposed page outline is optional and never blocks the shutter. After capture/import show the accepted still, not a stale preview frame. Suggest corners, let the user edit/reset them, and make “Use whole image” explicit. Low confidence is an explanation, not a fabricated probability. Manual correction remains usable without learned assets.

Document overview shows numbered pages with thumbnails and pending/edited/error states. Add pages, rotate, duplicate, reorder and remove are explicit actions. Reordering has accessible Move earlier/later alternatives. Removing a page offers Undo while still logically retained; permanent document deletion needs confirmation and states whether exports outside the app remain. Share/export always operates on a reviewed document snapshot.

## Editor layout

```text
Back          Page 2 of 5            Undo
┌──────────────────────────────────────┐
│       synchronized image viewport    │
│      pinch / pan / source toggle     │
└──────────────────────────────────────┘
Input ◉ Result       100% detail
Looks          Shape          Detail
[No enhancement] [White paper] [Natural] →
                 selected ✓
Strength ─────────●────        Info
Page strip:  1  [2]  3  4  5
Discard draft                        Apply
```

This wireframe is not an output example or a fixed visual theme. Compose Material components are a sensible baseline; final colors are a separate design decision. Use a single large viewport and compact result cards. Algorithm names belong in Info; benefit-oriented labels are concise but cannot promise a result that was not executed.

Looks selects one AppearanceRecipe plus explicit grayscale/binary final recipe where appropriate. Shape chooses None, Perspective, Manual curve, or a validated learned provider with accepted rough crop. Detail selects Off, bounded luma sharpening and optional denoise; unsupported combinations are disabled with a reason. Advanced settings reveal numeric values/units and execution order, never a hidden second sharpening pass. Strength is the shared candidate-after-clipping blend, not a geometry crossfade. Disable it for final binary output unless a separately named nonbinary recipe exists.

Suggested shipped Looks cards: No enhancement, White paper, Natural lightness, Grayscale, Black & white. Keep Local-max baseline in Advanced/diagnostics if its quality does not earn a user-facing slot. Neural cards appear only when installed, allowed and validated for that device/output; a research build can show a gated card explaining why it is unavailable, but cannot show another method's output under its label.

## Comparison semantics

In Looks, “Input” is the complete appearance-stage input after the selected geometry; “No enhancement” preserves that raster. A separate “Original photograph” view shows the orientation-normalized uncropped source. In Shape, compare geometry using a synchronized normalized page viewport where possible; do not imply two changed geometries have pixelwise registration. Info reports that distinction.

Card-to-recipe mapping is explicit. **No enhancement** is a full photometric reference preset: `appearance=NoneV1,denoise=null,detail=null,binary=null`, preserving the selected geometry input exactly. It remains available even when finishing is enabled. White paper/Natural select their named appearance and `binary=null` while retaining compatible user-selected denoise/detail; Grayscale selects `NoneV1` plus `GrayscaleV1`, retaining compatible denoise/detail; Black & white selects `NoneV1` plus `SauvolaV1` with detail absent and only supported denoise retained. Show every changed/disabled stage before Apply; incompatible masks require an explicit choice, not silent mask removal. If the user enables denoise/detail after the reference preset, the effective state is labeled **Appearance off · finishing on**, never “No enhancement.” Its distinct full-bypass reference card remains available. These are complete recipe presets, not a claim that every card changes appearance alone.

Every carousel variant uses the same source, accepted geometry and output raster size. Freeze unrelated stage settings for appearance-only comparison. The explicitly labeled full-bypass reference and grayscale/binary preset mappings above are whole-recipe comparisons; do not attribute their finishing/output-mode differences to appearance alone. If a provider recipe changes integrated detail/geometry, likewise label the entire recipe comparison. Hold-to-view-input is optional; a tap toggle and TalkBack action offer the same function.

Pinch/pan and before/after division persist across appearance switches. A true 100% detail crop must use the full target context specified in PIPELINE_CONTRACTS; an upscaled 1024-pixel preview is labeled preview, not full-resolution detail. For an expensive global model, queue the full-target job explicitly and show pending/cancel states. Do not pretend arbitrary cropped inference is equivalent.

## Draft, Apply, Undo and navigation

Maintain accepted persisted recipe and editable draft separately. A draft change increments previewGeneration and schedules selected preview; Apply persists a new recipeRevision and records the effective provider identities. Back with unsaved draft offers Keep editing, Discard, or Apply; no silent commit. Reset changes that stage only and is undoable. Undo/Redo replays recipe/state snapshots, never cumulative pixel reprocessing.

Late results are accepted only if source hash, recipe hash/revision, output size and generation all match the request still displayed. Cancellation leaves the previous completed image visible as previous state, not the canceled result. If page order changes during an export, the frozen export retains its reviewed order; the UI reports that newer edits are not in that export.

## Geometry interaction

Corner labels follow the oriented still, not screen min/max ordering. Controls use an expanded touch hit area and magnified local image. Drag constraints reject crossed/degenerate quadrilaterals without losing the last valid state. Offer keyboard/accessibility directional increments, zoom and full-frame reset.

Manual curve adds top/bottom on-curve midpoint handles; depth is an Advanced control with range and meaning. Show a live mesh/edge guide optionally, but use the renderer's actual validated map. Fold/overlap rejection reports “This curve would overlap the page; reduce the adjustment,” retaining the last valid controls. Learned dewarp is a separate selectable geometry: if it fails, offer the retained crop/manual correction, not a success badge for perspective only.

## Error and degraded-output presentation

Cards have idle, queued, processing, ready, unavailable and failed states. Busy cards are not fake processed thumbnails. Explain missing assets, unsupported masks, memory limits and invalid geometry distinctly. A fallback result states the executed path and omitted stages. Export does not accept a degraded page without explicit confirmation of that exact execution snapshot. A failed attempt does not mark the source damaged or erase the previous accepted recipe.

Disk full, URI permission loss and an interrupted external copy identify the affected operation and recovery: retry, choose another destination or keep the complete app-private export. Do not display a finished share action until the complete output is readable/verified. A permanent-delete confirmation names the document and page count; no generic “OK” that hides scope.

## Preview scheduler and cache

Selected card first, then immediate neighbors, then idle neighbors. Debounce slider input 150ms as a DESIGN starting point; immediate touch feedback remains independent. At most one expensive neural job/session active. Small cheap classical card processing may be batched only within the shared resource budget. Drop obsolete queued generations before starting them.

Cache identity includes normalized source pixels/conversion, exact geometry/control/map, appearance/detail/binary versions and parameters, mask hashes, output size/purpose, provider assets/runtime/backend/precision and processing version. “filter-name + pageId” is insufficient. LRU eviction releases only unreferenced resources. Persist recipes, not an unlimited cache. A new model asset or conversion version invalidates related outputs without mutating the source.

## Genuine local examples

Bundle a small, rights-cleared challenge set: smooth/hard shadows, colored paper, faint receipt text/pencil, colored marks/highlighter, a photograph/diagram and a curved page. Also provide TEST_FIXTURES outputs. Run the actual packaged algorithm to generate every shown result; preserve a manifest with input/license/hash, recipe and asset identities, output hash/size and the measured environment if timings are displayed.

“My page” is the default example selector; curated pages explain trade-offs. No generated imitation, hand-retouched screenshot or publisher demo substitutes for a packaged algorithm output. If an asset cannot run, its example is unavailable. Precomputed examples must match the packaged algorithm/version and are labeled precomputed; they do not establish current device speed. Original source remains available for each example.

## Accessibility and UX acceptance

Android's [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) specify minimum 48dp interactive targets. Require this for cards, handles and controls, including invisible hit regions. Test TalkBack focus order/selected/busy/error labels, large-font reflow, contrast, reduced motion, Switch Access-friendly actions and non-gesture alternatives. A swipe, long press, color change or tiny corner hit point cannot be the sole way to use a function.

UX acceptance includes: import after camera denial; valid manual correction without models; same-view comparisons; canceled/stale renders never replacing current state; draft retention through rotation; explicit interrupted-export handling; all shipped examples/algorithms in airplane mode; and a new user distinguishing Original, geometry input, requested recipe and executed fallback.
