# Resource budgets, scheduling and offline deployment

All numerical targets in this file are DESIGN starting points until measured on retained physical-device builds. Hardware class and latency targets are not promises inferred from parameter counts or desktop paper timings.

## Admission and output selection

Initial test class: physical arm64 Android devices with 4–6GB RAM, including at least one API26-compatible build path and a current API target, plus low-memory/thermal stress. Record actual SoC, OS, RAM, memory class, available memory, ABI, runtime/provider and battery/thermal conditions. An emulator is useful for functional tests, not a substitute for handset resource evidence.

Proposed input admission limits: encoded source at most100MiB; dimensions at most48,000,000 pixels and long edge12000; document at most100 pages. Validate multiplication and file metadata before decoding and actual decoder dimensions afterward. These are safety defaults, not proven maximum supported document sizes. Metadata acceptance is not allocation permission. Unsupported formats/profiles and inconsistent metadata return a controlled error. A later change is a versioned policy with tests.

CARD long edge224 and PREVIEW long edge1024 never upscale. Normal EXPORT uses `s=min(1,4000/max(targetW,targetH),sqrt(12_000_000/(targetW*targetH)))` and floors each target dimension times s; reject if either geometric dimension floors below two rather than clamping or distorting aspect. A target12000×2 floors to4000×0 and is rejected for geometry. Show exact chosen pixel dimensions before export. “Source resolution” is a separate explicit option subject to admission. No stage changes requested final dimensions merely because it runs out of memory. An OutputSpec carries the actual dimensions and purpose; preview-size inference can enter different model branches and is not export parity.

Initial tracked processing allocation budget in MiB is `min(192,max(32,0.5*memoryClassMiB))`. Track owned Java/native image buffers, model tensors/workspace estimates and retained previews together; separately inspect total PSS/graphics/heap/native memory because Android's memory class is not a universal native/PSS ceiling. Keep UI/system headroom. Low-RAM/low-available-memory conditions reduce admission or refuse the explicit request, not secretly shrink its output. Reject before allocating when estimates exceed capacity; reconcile actual peaks against estimates.

## Memory mechanisms

At12MP, RGB8=36,000,000bytes≈34.33MiB; RGBA8=48,000,000≈45.78MiB; float RGB=144,000,000≈137.33MiB; a two-float inverse map=96,000,000≈91.55MiB. A single1×64×1024×1024 float tensor is256MiB. Small parameter files therefore do not establish small activation memory.

Avoid decoding a full-size RGBA bitmap, copying it into RGB, retaining it forever and materializing several full-size float fields. Account for decoder peak/copies, release intermediate bitmaps after owned conversion, apply coarse illumination/maps row-wise, use sliding statistics and filter halos, and reuse explicitly owned scratch buffers. Never recycle a bitmap still referenced by Compose or an export writer. A failed allocation must not corrupt its source or last completed result.

Global histograms/percentiles and paper-grid estimation use the full declared appearance input. Local filters can tile with exact halos and border rules. A global-context/attention model cannot be made equivalent by arbitrary independent tiles. A public patch-inference path must preserve overlap/padding/blending and pass seam/detail tests. Rendering a coarse inverse map per row avoids an8N-byte full map, but its numerical interpolation must match the named adapter.

PDF assembly has its own admission and memory gate in CAPTURE_IMPORT_EXPORT. A100-page library document does not imply the platform writer can export every100-page full-resolution PDF within budget. Export-specific limits are displayed and validated; no partial PDF is reported as a complete document.

## Scheduler and lifecycle

Two CPU processing threads and one active expensive inference session are initial defaults. Avoid row-level unbounded parallel streams and launching every carousel model together. Selected preview preempts obsolete queued neighbors; cooperative cancellation is checked before/after each stage and within long native loops where supported. A provider call that cannot be interrupted must finish privately and its stale result must be discarded. Do not destroy a session while it is executing.

Interactive jobs are lifecycle-aware, generation-tagged and nonpersistent. Committed page/export work is journaled with immutable input identities before enqueue. WorkManager tasks are page/checkpoint units where possible, not a single unlimited full-document operation. Respect current OS quotas, execution limits and foreground-service restrictions; the system may defer jobs. Force-stop is not recoverable by a background-completion promise. On next launch reconcile interrupted state and offer resume/retry.

Proposed UX targets: touch state response≤100ms, selected classical preview near300ms at1024 long edge, explicit progress/cancellation for longer jobs. Measure cold and warm distributions rather than a single best run. A quality model can exceed the fast target and still be a useful opt-in slower option, but its actual delay and thermal behavior must be disclosed. Never show fabricated progress percentages or a static “fast” badge based only on model size.

## Runtime choices

Use a correct CPU reference before delegate optimization. [ONNX Runtime mobile guidance](https://onnxruntime.ai/docs/tutorials/mobile/) supports Android Java/C/C++ integration and recommends evaluation of latency, memory, disk and power; partitioning can cost more than it saves. A DocQuad ORT-format artifact needs a runtime build that supports its format/operators. A file extension is not an ONNX graph conversion.

Use LiteRT only when a validated selected asset requires it. [Standalone LiteRT GPU guidance](https://developers.google.com/edge/litert/android/gpu) includes same-thread Interpreter GPU delegate initialization/invocation constraints; no Google Play-services dependency is allowed in the offline build. Prefer current supported APIs after checking the pinned runtime. [Android NNAPI documentation](https://developer.android.com/ndk/guides/neuralnetworks) states deprecation in Android15; do not make it the new default acceleration path.

Runtime availability is not evidence that reflection padding, dynamic shapes, grid sampling, attention, shifts/rolls or a custom normalization export will work. Export graph nodes, layout and dynamic axes explicitly; compare source→exportedCPU→deviceCPU→accelerator raw outputs and final pixels. Quantization/FP16/tile changes reopen affected fidelity gates. Record full end-to-end costs including prompts, decode/copies, reconstruction and sampler, not only neural forward time.

## Offline and network gate

Production functionality must work after fresh install in airplane mode with bundled/local assets. No first-use asset fetch, remote license check, cloud fallback, account login, hosted examples, remote fonts or dependency telemetry. Audit the merged manifest and packaged SDK initialization; omit INTERNET/ACCESS_NETWORK_STATE unless a separately approved feature genuinely needs them. A missing optional provider returns unavailable and manual/classical functions remain usable.

System document pickers may expose cloud-backed providers; this does not make that provider's cloud operation an app offline capability. Offline import accepts locally readable content and reports unavailable provider bytes without hanging. Sharing transfers only a user-selected finished export to another app, which controls its own network behavior; explain this privacy boundary.

## Benchmark and release records

For each shipped recipe: source fixture/real-page hash, build/toolchain, exact output size, asset/backend/precision, cold load and warm end-to-end P50/P95, number of runs, peak memory, cancellation latency, energy/thermal observations and failure count. Run sustained multi-page cycles, model switching, repeated open/close, low storage, low memory and rotation/background tests. Include at least20 warmed samples per chosen performance condition and a documented cooldown policy; counts are minimum proposed protocol, not already collected data.

Gate release on controlled behavior at admission limits, no stale publication, successful offline cold start, no unbounded memory growth over repeated cycles, and readable validated exports. Final acceptable quality/speed trade-offs require real measurements. Keep a simpler classical recipe when a neural option offers no useful nondominated outcome.
