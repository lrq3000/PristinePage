# Photometric algorithms and deterministic arithmetic

Specification version 1.0; authored 2026-11-14. Normative shared types, failure semantics and image ownership are in [PIPELINE_CONTRACTS.md](PIPELINE_CONTRACTS.md). This document defines the model-free algorithms for FR-06/FR-08, T-02/T-07 and their arithmetic fixtures. Learned alternatives are separately gated in [MODEL_PROVIDERS.md](MODEL_PROVIDERS.md). Geometry is completed first; this document never estimates or applies a dewarp.

**Evidence status:** the cited public source passages were captured on 2026-11-06 and re-inspected for this specification on 2026-11-14. The recipes below are engineering designs, not measured Android quality/performance results. Their scalar fixtures were checked independently; no application, native backend, handset, checkpoint or representative-page benchmark was run. The conservative defaults are proposed defaults, not optimized parameters.

## 1. Required processing order and intent

Execute `geometry → one appearance recipe → optional luma denoise → optional bounded luma detail → optional grayscale/Sauvola`. Every stage reads its complete immutable stage input, not partially overwritten output. Denoise and detail are absent by default. There is no hidden ink curve, black-point subtraction, CLAHE, automatic contrast stretch, extra white lift, speck deletion or hole filling. Preserve No enhancement and the original photograph as independent choices.

Geometry includes `GeometryRecipe.outputQuarterTurns` (0..3 clockwise) after its base map and before this document's stages. Appearance W/H, statistics, kernel pixels and provider size branches refer to the **already post-rotation OutputSpec raster**. Do not rotate appearance input again, reapply EXIF, modify sourcePixelHash or move source-space protection/control coordinates. The geometry owner composes rotation into the identical RGB/mask inverse map once; appearance only consumes its aligned results. Record outputQuarterTurns through the recipe/render identity, not as a second independent photometric flag.

`paper_gain_v1` intentionally offers a white-paper aesthetic and can remove genuine substrate tint. `natural_l_v1` changes perceptual lightness while retaining the original chromatic axes; it retains an unwanted illumination color cast as well. A single uncalibrated photograph cannot uniquely separate substrate color from illumination color. Neither route guarantees authentic color or recovery of clipped/occluded information. Colored paper, photographs, faint marks and abrupt shadows require explicit visual comparison rather than automatic selection by “whiteness”.

### 1.1 Common numeric helpers

- Input/output are opaque encoded-sRGB RGB8, not linear RGB, with checked positive dimensions and stride. Treat every byte as unsigned. `N = width.toLong()*height` and every buffer extent/row offset are checked before narrowing or allocating. A resource rejection is not permission to lower export resolution silently.
- `roundPositive(x)=floor(x+0.5)` for finite nonnegative x; `roundByte(x)=clamp(roundPositive(max(0,x)),0,255)`. Clamp a negative candidate before invoking the nonnegative helper. These names express the shared `roundByte` rule, not language-default banker rounding.
- `roundSigned(x)=sign(x)*floor(abs(x)+0.5)`; its ties are away from zero, including `−0.5→−1`. Signed rounding is used only where explicitly stated below.
- `Y8(R,G,B)=floor((77R+150G+29B)/256)`. The largest numerator is 65280; use at least 32-bit arithmetic. This encoded-byte luma approximation is not linear luminance and is not interchangeable with CIE lightness.
- `reflect101(i,n)`: if n=1 return 0; otherwise set `p=2*(n−1)`, `t=((i%p)+p)%p`, return `t<n ? t : p−t`. Replicated borders instead use `clamp(i,0,n−1)`. A clipped window uses only actual in-bounds samples. These are three different contracts.
- Persistent image/field intermediates are float32 unless stated otherwise; statistics/reductions are float64 or wide integers. Where a formula assigns a persistent float buffer, round once to float32. Accumulate in the stated order, do not enable reassociation/fast-math/FMA that changes fixture bytes, and reject NaN/Inf before quantization. Transcendental float-buffer fixtures allow absolute error ≤1e−6; byte fixtures require exact equality.
- Default scalar resampling is bilinear with half-pixel destination-to-source mapping `(j+.5)*inputSize/outputSize−.5` and clamped indices, with exact identity at the same size. Do not import a learned grid's corner-aligned resize here.

### 1.2 One adapter-owned strength/protection blend

For any supported appearance, denoise or detail stage, first compute its complete **range-clipped candidate**, including the recipe's stated byte quantization. Then the stage adapter alone computes `a(x,y)=strength*(1−protection(x,y))` and `out_c=roundByte((1−a)*input_c+a*candidate_c)`. No protection mask means zero protection. A generic coordinator must not blend this result again. After successful recipe/capability preflight, a strength of zero returns identical input bytes without computing statistics, inference or a color-space roundtrip. It does not make an unknown/unavailable provider or an incompatible mask graph valid. A pixel with protection exactly one is copied byte-for-byte, not reconstructed through Lab.

All strength fields are Kotlin Double/binary64, never Float. The composer promotes the float32 protection sample and candidate to binary64 and uses the separate-operation order, round-to-nearest/ties-to-even and no-FMA rule in PIPELINE_CONTRACTS. This scalar composition is not a float32 persistent image/field intermediate. Boundary oracle: input 3, byte candidate 8, strength 0.1 and protection 0 produces 4; a float32-per-operation result of 3 is nonconforming. Algorithm-specific candidate precision remains unchanged.

Protection is a finite float32 scalar in [0,1], warped through the identical geometry as the image with outside value 1; it is not a page mask and is never byte-quantized. This version uses protection only for the final change blend: protected pixels still contribute to neighborhood estimates/global statistics. A protected photograph can therefore bias nearby estimates; reducing strength or choosing No enhancement is safer than assuming the mask excludes it statistically. Mask-excluded estimation would require a new algorithm version.

Example: input 200, unbounded gain result 300, byte candidate 255, strength .5, protection 0 gives `roundByte(227.5)=228`, not 250. Input 200, candidate 245, strength .5, protection .5 gives `roundByte(211.25)=211`. This rule must also hold when the candidate came from a learned provider.

## 2. Sealed recipes and preflight validation

The following are parameter contracts, not application implementation. Use sealed serializable families with stable discriminator strings and version 1; persist all effective defaults. Unknown fields, algorithms, versions or nonfinite numeric parameters reject rendering. `null` is the only disabled representation for the optional three families; do not maintain a second `enabled` flag with conflicting semantics.

```text
AppearanceRecipe =
  NoneV1(id="none_v1")
  PaperGainV1(target=245, maxGain=3.0, strength=1.0)
  NaturalLV1(amplitudeL255=45.0, lightnessBlend=0.30, strength=1.0)
  DocumentColorV1(strength=1.0)
  PaperShadowV1(strength=1.0)
  LocalMaxGainV1(radiusPx=15, target=245, maxGain=3.0, strength=1.0)
  ProviderAppearanceV1(providerId, contractVersion, assetSha256,
                       variantId, profileSha256, strength=1.0)

DenoiseRecipe = BilateralLumaV1(radiusPx=1, sigmaRange=10.0,
                                sigmaSpatialPx=1.0, strength=1.0)
DetailRecipe = LumaDetail7V1(amount=0.40, thresholdY=3,
                            maxDelta=8, strength=1.0)
BinaryRecipe = GrayscaleV1()
             | SauvolaV1(window=AutoShortSideV1, k=0.20, rangeR=128.0)
WindowPolicy = AutoShortSideV1 | ExplicitOdd(sidePx)
```

- Every strength is finite in [0,1]. `NoneV1` has no strength or tuning fields and returns identical RGB bytes.
- Paper/local-max: target integer 240..255; maxGain finite 1..3. No exposure-lowering gain is permitted. The shipped default target is 245, not `1.05*255`; source white-lift behavior requires a different, nondefault recipe ID.
- Natural: amplitude finite 0..60 in lightness scaled to 0..255; lightnessBlend finite 0..0.60. Default 45/.30. Zero amplitude is **not** identity unless lightnessBlend is also zero: it moves lightness toward the global mean. Strength zero or lightnessBlend zero is exact identity.
- Local-max radius integer 1..127 in current stage-raster pixels. It is an optional diagnostic baseline, not another mandatory carousel default.
- Bilateral: radius integer 1 or 2; sigmaRange finite 5..15 byte-luma units; sigmaSpatial finite 1..3 raster pixels. No learned noise estimate and no implicit activation.
- Detail: amount finite 0..0.8; thresholdY integer 0..6; maxDelta integer 0..12. Amount zero or maxDelta zero is exact identity. The seven-tap kernel is version-fixed, not a UI slider.
- Sauvola: k finite 0.10..0.30, rangeR exactly 128; ExplicitOdd side is odd and 3..1001. AutoShortSide is a persisted policy, not an unresolved null; resolve its actual window at execution and record that integer. Grayscale has no threshold/strength parameters.
- Provider recipe binds an exact installed asset and a registry-owned, hash-identified typed profile; arbitrary parameter maps are forbidden. The profile defines supported sizes, precision, integrated stages and adapter constants. See the provider document.

Preflight rejects: detail plus Sauvola; any `BinaryRecipe` plus a protection mask; detail plus a protection mask in this version; an image-output geometry provider plus a mask without an identical paired-mask transform; a provider plus an unsupported mask; unavailable or unvalidated provider assets; duplicate external denoise/detail after a profile that declares those integrated operations; implicit composition of two appearance recipes; source-parity provider profiles exposing product strength/protection; and any graph whose dimensions or resource budget fail admission. Grayscale may follow detail because that stage is luminance-derived. A supported optional bilateral stage may use protection through the common adapter. The UI disables incompatible choices but validators remain authoritative; never silently discard persisted settings to make a request fit.

## 3. `paper_gain_v1`: exact grid estimator, conservative composer

The estimator follows the inspected MakeACopy `whitenPaper`/`paperOfBlock`/`fillFromNeighbours`/`boxBlur` design [P1]. The composer deliberately changes its white target and omits the original ink/sharpening preset. Calling upstream `apply` or `flatten` is **not** equivalent: both include additional ink darkening, and `apply` also sharpens. This recipe requires no model.

### 3.1 Grid and paper selection

1. `s=max(16,ceil(max(W,H)/40))`, using checked integer division `(D+39)/40`; `gw=ceil(W/s)`, `gh=ceil(H/s)`, `G=gw*gh≤1600`. Cell `(bx,by)` is `[bx*s,min(W,(bx+1)*s)) × [by*s,min(H,(by+1)*s))`. Every pixel contributes; do not replace this with a thumbnail estimator.
2. For each cell containing n pixels, build a 256-bin histogram of Y8. The local median M is the first ascending bin whose cumulative count reaches `ceil(n/2)`; this is the **lower** median for an even population. The bright cutoff c is the first bin encountered descending from 255 whose inclusive cumulative count A satisfies `4*A≥n`. Include **all** pixels with `Y8≥c`, including every tie. Compute channel sums as int64; the shared selection set, not three independent channel percentiles, defines the paper color.
3. Store each paper channel as `P[j,c]=float32(float32(channelSum)/selectedCount)`, matching the source's float paper means. Compute integer `L[j]=Y8(roundPositive(P_R),roundPositive(P_G),roundPositive(P_B))`. Mark `flat[j]=(L[j]−M[j]≤6)`. This one-sided difference is the actual heuristic; do not substitute absolute deviation or variance.
4. Sort the cell L values ascending; `T=sortedL[floor(G/2)]` is the **upper** median for an even number of cells. Accept cell j iff `L[j]≥0.7*T || (flat[j] && L[j]≥0.3*T)`. Threshold products are float64. A low-contrast photo/logo can pass: accepted does not mean semantically proven paper.

### 3.2 Rejected cells, smoothing and interpolation

Fill rejected cells in synchronous eight-neighbor waves. At each wave, freeze the valid flags from its start; for every previously invalid cell, sum only in-bounds neighbors valid in that frozen set, in dy=−1..1 then dx=−1..1 order. Store float32 sums/divisions. If at least one neighbor is valid, assign their channel means and mark the cell valid **for the next wave only**. Newly filled cells must not propagate again during the same scan. The rectangular grid is connected; with any seed it finishes in at most max(gw,gh)−1 waves. Defensive no-seed behavior fills all channels with T; for ordinary finite statistics a maximum-L cell already passes, so this branch is a helper/fault-injection test, not an expected rescue for a black image.

For each channel, perform two 3×3 uniform grid smoothing passes. Each pass reads a frozen copy; sum only existing grid neighbors in the same row-major order and divide by their actual count. Borders are **clipped and renormalized**, not replicated or reflected. Store float32. Only after both passes compute `g[j,c]=float32(clamp(target/max(1,P[j,c]),1,maxGain))`. Interpolate **gains**, not paper estimates followed by a reciprocal.

At pixel center `(x,y)`, define `u=clamp((x+.5)/s−.5,0,gw−1)` and `v=clamp((y+.5)/s−.5,0,gh−1)`, with float32 coordinate arithmetic. Set `x0=floor(u),x1=min(x0+1,gw−1)`, similarly for y; `tx=u−x0,ty=v−y0`. For each adjacent x column first interpolate vertically `r=g_top+(g_bottom−g_top)*ty`, then horizontally `gain=r_left+(r_right−r_left)*tx`, storing float32 operation results. Candidate is `roundByte(float32(input_c*gain))`. Apply the common blend once. The last partial cell still uses this regular s-based position, not its true clipped-cell centroid.

```text
if strength == 0: return exact input
cells = collect_histograms_and_tied_bright_means(input)
T = upper_median(cells.paperLuma)
valid = quality_rule(cells, T)
paper = synchronous_fill(cells.paper, valid, T)
paper = clipped_grid_box3(clipped_grid_box3(paper))
gains = brighten_only_gains(paper, target, maxGain)
for each raster row:
    check cancellation
    for each pixel and RGB channel:
        candidate = roundByte(input * vertically_then_horizontally_sample(gains))
    adapter_blend_candidate_once()
```

### 3.3 Deterministic fixtures

- PG-01: 4×1 neutral RGB pixels `[200,200,200,195]` form one cell. Cutoff=200, selectedCount=3, P=(200,200,200), M=200, T=200, gain=float32(245/200)=1.225000023841858. Full-strength output neutrals are **[245,245,245,239]**. The faint mark remains six levels below paper. This is an arithmetic example, not a promise for all faint marks.
- PG-02: any constant black image has cutoff 0, paper 0, valid cells, gains 3 and exact black output. A constant neutral 255 image stays 255 because gains cannot fall below 1; the algorithm does not force already-bright paper down to 245.
- PG-03: 1×1 RGB (200,180,160) has one tied selected pixel and outputs **(245,245,245)** at defaults. This deliberately demonstrates substrate-color removal. With protection 1 the output is exactly (200,180,160).
- PG-04: a 32×16 neutral image with left 16×16 cell 200 and right cell 10 has L=[200,10], T=200. The right flat cell is rejected because 10<.3*200; wave fill makes both 200. Output is left 245, right **12**, not 245. A dark region rejected as nonpaper is not automatically treated as shadowed white paper.
- PG-05, helper: paper values [90,unused,unused,210] and valid [true,false,false,true] on a 4×1 grid become **[90,90,210,210]** after one fill wave. A left-to-right implementation yielding [90,90,90,210] is wrong.
- PG-06, interpolation helper: s=16, gw=2, gh=1, supplied gains [1,3] produce gains **1,1.9375,2.0625,3** at x=0,15,16,31 respectively. A paper-first reciprocal interpolation fails this fixture.
- PG-07, shared composer: input 200, supplied gain 1.5, strength .5 gives **228**. Strength zero and all-one protection are exact identity, including colored input and padded row stride. Compare only active pixels; padding is not data.
- PG-08, percentile ties helper: lumas [0,200,200,200] select all three 200 samples, not one. Lumas [0,0,100,200] have local median 0, not 100. Cell L=[10,200] has typical T=200, not 105 or 10.

### 3.4 Cost and limits

Two source scans for cell statistics plus output are O(N), histogram setup O(256G), typical sort O(G log G), wave fill O(GD), and fixed grid smoothing O(G). G≤1600 and D≤39. A serial reference uses input/output 6N bytes plus at most approximately `80G+12W+1024` bytes of estimator/interpolation workspace, excluding object headers, strides, allocator overhead and an optional 4N-byte protection mask. Streaming rows avoid full-resolution float gain planes. Use a bounded worker budget, not a process-global parallel stream; parallel cell work cannot change cell arithmetic order.

Large photos, colored paper, logos, nearly full-cell dark ink, warm/cool mixed light and deep shadows violate the paper prior. Gains amplify noise and can clip colored channels even at target 245. Hard shadow boundaries are too sharp for the coarse grid; ringing is not cured by later sharpening. The brighten-only policy preserves black and already-white input but is not a general flat-image identity. A 1×1 colored input being neutralized is documented behavior, not an input-validation success metric. Recommended precaution: keep No enhancement or Natural available alongside this default rather than deciding that any brightened page is more faithful.

## 4. `natural_l_v1`: independently specified log-lightness alternative

The inspected FairScan color path computes a multiscale `log(L)−mean(log(L))`, not `log(L)−log(mean(L))` [P3]. This recipe independently specifies that mathematical idea with conservative amplitude 45 and internal lightness blend .30. It deliberately does **not** copy the source's cubic overshoot, percentile restretch, 60/.60 preset, Kotlin structure or comments. It is not claimed bit-equivalent to that implementation. There is no shared grayscale “Retinex” preprocessing hidden beneath it.

### 4.1 Exact color conversion `cie_lab_float_v1`

To be independently implementable, this version fixes a float CIE Lab conversion instead of depending on unspecified native 8-bit lookup tables. Decode each encoded sample `q=byte/255` to linear `q/12.92` if q≤.04045, otherwise `((q+.055)/1.055)^2.4`. Compute XYZ in float64:

```text
X = .4124564*r + .3575761*g + .1804375*b
Y = .2126729*r + .7151522*g + .0721750*b
Z = .0193339*r + .1191920*g + .9503041*b
d = 6/29
f(t) = cbrt(t) if t>d^3 else t/(3*d*d)+4/29
fx=f(X/.95047); fy=f(Y); fz=f(Z/1.08883)
L255=float32(2.55*(116*fy-16))
aStar=float32(500*(fx-fy)); bStar=float32(200*(fy-fz))
```

L255 is a float32 perceptual lightness in approximately 0..255, **not** byte Y8, not 0..100 Lab L, and not OpenCV Lab8. The scale is clamped to [0,255] on storing L255 to suppress the matrix's tiny white-end roundoff. Keep aStar/bStar unchanged when constructing a candidate. Their numeric storage is float32 with no +128 encoding.

For inverse conversion from candidate lightness l, use `fy=(l/2.55+16)/116`, `fx=fy+aStar/500`, `fz=fy−bStar/200`; `finv(t)=t^3` if t>d else `3*d*d*(t−4/29)`. Compute `X=.95047*finv(fx),Y=finv(fy),Z=1.08883*finv(fz)`, then float64 linear RGB:

```text
r =  3.2404542*X - 1.5371385*Y - .4985314*Z
g = -.9692660*X + 1.8760108*Y + .0415560*Z
b =  .0556434*X - .2040259*Y + 1.0572252*Z
```

Clamp each linear channel to [0,1]; encode `12.92*q` if q≤.0031308 else `1.055*q^(1/2.4)−.055`; candidate byte=`roundByte(255*encoded)`. Native color primitives may replace this reference only after candidate-byte fixture parity; OpenCV integer Lab8 is not a silent substitute. The standard roles/scales of Lab and XYZ are documented in [P4]; these exact matrix/threshold/storage constants are this recipe's design choices. Conversion is not a color-calibration claim.

### 4.2 Field, conservative lightness mixing and flat identity

1. If strength=0 or lightnessBlend=0 return the exact RGB input. Otherwise derive L255 for all pixels, retaining original RGB for final chroma recomputation. Compute full-image `meanL` in float64; min/max are over all L255 samples. If `maxL−minL≤1e−6`, return exact RGB input, including black and uniformly colored sheets.
2. Form a half-resolution working image with dimensions `Ws=ceil(W/2),Hs=ceil(H/2)`. Working cell (i,j) is the average of `L255+1` over its clipped 2×2 source block; odd last blocks use their actual count. This is an explicitly defined area reduction, not a library-dependent floor-size conversion. Store float32 A, then float32 `Z=ln(A)`. The +1 is applied **before** the logarithm and there is no second +1 after filtering.
3. `D=max(Ws,Hs)`; for q in [80,10,2], define `kq=nextOdd(max(3,floor(D/q)))`, where nextOdd adds one only to an even integer. Let `B_k` be a centered normalized square box with reflect101 on both axes, separable horizontal then vertical, float64 sums and float32 pass outputs. Compute `r=float32((Σq (Z−B_kq(Z)))/3)` in q order. This is `mean(log L)`, not `log(mean L)`.
4. Find full-working-image `rmin,rmax` in float64. If `rmax−rmin≤1e−6`, return exact original RGB, not gray/black and not a normalized epsilon image. This also handles a tiny image whose half-resolution field has only one sample. Otherwise store `n=float32(clamp((r−rmin)/(rmax−rmin),0,1))`.
5. Upsample n to W×H with the common half-pixel bilinear/clamped-edge rule; clamp U to [0,1]. Generate U one row at a time. There is no cubic interpolation or overshoot.
6. `C=meanL+amplitudeL255*(U−.5)` and `F=float32(clamp((1−lightnessBlend)*L255+lightnessBlend*C,0,255))`. Convert `(F, original aStar, original bStar)` to the byte RGB candidate using §4.1. Apply the common RGB strength/protection blend exactly once.

The internal `.30` mixes the **lightness target into the candidate**; it is not the public RGB strength and must not be applied again as a generic postprocess. No percentile normalization/restretch is applied in v1: it would add a separate nonlinear tone policy and defeat the meaning of a low blend. This version may modestly compress global lightness contrast; it is not a guaranteed brightness lift and does not whiten paper.

```text
if publicStrength == 0 or lightnessBlend == 0: exact input
L, meanL, minL, maxL = scan_L255(input)
if flat(minL,maxL): exact input
Z = log(area2_clipped(L + 1))
r = average_of_three_log_highpasses(Z, reflect101)
if flat(min(r),max(r)): exact input
n = unit_interval(r)
for each original row:
    U = half_pixel_bilinear_row(n)
    F = mix_lightness(L[row], meanL + amplitude*(U-.5), lightnessBlend)
    candidate = Lab_to_RGB_bytes(F, original_chroma_recomputed_from_input)
    adapter_blend_candidate_once()
```

### 4.3 Deterministic fixtures

- NL-01: any exactly constant RGB image, including (0,0,0), (255,255,255), (200,180,160) and a 1×1 image, returns identical bytes at all valid strengths. This is a guard before Lab roundtrip, not approximate color equality.
- NL-02: at defaults a neutral 4×1 RGB image **[100,100,200,200]** has L255≈[108.0552444458,108.0552444458,205.5404205322,205.5404205322], meanL≈156.7978324890. The two working samples are [109.0552444458,206.5404205322]; all three box widths are 3. Their r values are approximately [−.42576122284,+.42576122284], normalized n=[0,1], and upsampled U=[0,.25,.75,1]. Candidate F≈[115.9280242920,119.3030242920,194.2926483154,197.6676483154]. Full-strength byte RGB neutrals are exactly **[108,111,188,191]**. With strength .5 they are **[104,106,194,196]**. Every number refers to this conversion, not native Lab8.
- NL-03: 2×1 neutral [100,200] reduces to one field sample, r range is zero and the output is exactly **[100,200]**, although the original is not flat. Do not divide a zero range by an epsilon and normalize it to black.
- NL-04, log-order helper: positive A=[1,9] has `mean(log A)=ln(3)≈1.098612288668`, while `log(mean A)=ln(5)≈1.609437912434`. They must not be substituted. Reflect101 maps indices [−2,−1,0,1,2,3] for n=3 to **[2,1,0,1,2,1]**.
- NL-05, mixing helper: L=100, meanL=150, U=0, amplitude=45, lightnessBlend=.30 gives C=127.5 and F=**108.25** before conversion; U=1 gives C=172.5 and F=**121.75**. An added percentile stretch is a test failure.
- NL-06: public strength zero, lightnessBlend zero or per-pixel protection one preserves the applicable input bytes, even when the unprotected candidate lies outside RGB gamut before clipping.

### 4.4 Cost and limits

Use running/prefix box sums; never scan k² neighbors per pixel. An extended reflect101 line sum can be evaluated from complete periods plus a prefix remainder, so initialization also remains O(lineLength), even for a singleton dimension or a window larger than that dimension. Three fixed scales, area reduction, conversion and output are O(N). A scheduled implementation retains a 4N-byte L plane and at most four 4M-byte working planes, where `M=ceil(W/2)*ceil(H/2)`; workspace is at most `4N+16M+O(max(W,H))`, plus input/output, optional mask and allocator scratch. Recompute aStar/bStar from the source row rather than keeping two more full-resolution planes. Full-image mean/min/max prohibit arbitrary independent export tiles; use global statistics and halos or run full context before a detail crop.

Min/max normalization can let one extreme feature govern the whole field. The flat guard avoids a divide-by-zero failure but does not make near-flat noisy pages safe. Local high-pass halos, broad contrast compression and gamut clipping remain possible. Desired paper color and unwanted lighting chroma remain entangled. Full-page photos are not assumed paper; show the result as a user-selected alternative. Do not market changed L with fixed a,b as exact RGB hue preservation.

### 4.5 Optional `document_color_v1`

The independently versioned [Document color](../../validation/appearance/atelier-document-color.md) method uses the shared bright-paper estimator and a local chroma/luminance affinity to apply paper illumination and cast correction conservatively. This is an opt-in third color method alongside Paper white and Natural lightness, not a change to their arithmetic or a claim of compatibility with a proprietary filter. The linked contract defines threshold values, the exact one-blend policy and the remaining evidence gates.

### 4.6 Optional `paper_shadow_v1`

[Paper shadow](../../validation/appearance/atelier-paper-shadow.md) is a separate opt-in edge-aligned correction for one well-supported, nearly uniform, axis-aligned illumination step on light paper. It derives paired local RGB references, preserves the lit region exactly, and uses a guarded additive correction on the shaded region. It does not whiten the lit paper or run before or after another appearance recipe. The linked contract specifies support, refusal, composition and validation limits.

## 5. Optional `local_max_gain_v1` baseline

This deliberately simple, model-free reference estimates each paper channel as `P_c(x,y)=max(input_c(x+dx,y+dy))` over the **clipped** square `dx,dy∈[−radius,+radius]`. The square includes its center. Compute separate channel maxima; unlike §3 it does not share a luma-selected color sample set. Use `gain_c=float32(clamp(target/max(1,P_c),1,maxGain))`, byte candidate `roundByte(float32(input_c*gain_c))`, then the common blend. There is no blur, median, global tone curve or subsequent ink darkening.

A separable monotonic-deque max filter performs O(N) work, with O(3N+max(W,H)) workspace for a byte horizontal-max raster and line queues, in addition to input/output and any mask. A direct square scan costs O(Nr²) and is not the required scalable implementation. Max-filter borders must remain clipped; padding with white would spuriously defeat correction near the edge.

LM-01: neutral 3×1 [100,200,100], radius1, defaults gives paper [200,200,200] and candidate **[123,245,123]**. LM-02: [100,200,50] gives **[123,245,61]**. LM-03: constant black stays black; constant (200,180,160) becomes (245,245,245); strength zero or protection one is exact identity. LM-04: at radius1 a 1×1 image has a one-sample window, not a three/nine-sample reflected statistic.

This is useful for testing whether a neural method beats a cheap local background hypothesis. It fails on large dark fills, content wider than its window, colored paper and photos, and can generate spatial transitions around highlights. Do not stack it with paper_gain or Natural. Keep it optional rather than multiplying similar default cards without a measured benefit.

## 6. Optional `bilateral_luma_v1`, disabled by default

The noise stage is a fully defined square bilateral filter of integer Y8, not three independent color filters. For radius r, iterate dy=−r..r then dx=−r..r, fold source indices with reflect101, and include every offset separately even when folding produces the same pixel more than once. Weights use the **offset** dx,dy, not the folded coordinate distance:

`w=exp(−(dx²+dy²)/(2*sigmaSpatialPx²) − (Y_neighbor−Y_center)²/(2*sigmaRange²))`.

Accumulate float64 `sumW` and `sumWY`; center weight=1 guarantees sumW>0. `Yf=float32(sumWY/sumW)`. Define `d=clamp(Yf−Y_center, −min(R,G,B), 255−max(R,G,B))`; byte candidate is `roundByte(input_c+d)` for all c, followed by the common blend. The common headroom-clipped additive change reduces channel-specific clipping; it is not a promise of constant perceptual hue. Do not recompute weights from already filtered pixels. No range approximation, lookup quantization, cross-bilateral guidance or subsampling belongs to this ID.

BL-01: neutral 3×1 [0,10,0], radius1, sigmaSpatial=1, sigmaRange=10, full strength, gives floating luma **[4.23883115234,5.76116884766,4.23883115234]** before float32 storage and byte RGB **[4,6,4]**. The y weights cancel for the singleton height. At strength .5 the bytes are **[2,8,2]**. BL-02: any constant RGB image is exact identity; a colored pixel with positive proposed d=10 and RGB=(252,100,50) clips d to3 and has candidate **(255,103,53)**. BL-03: strength zero skips filtering; protection one copies input; nonfinite sigma and radius3 reject. BL-04: n=1 folding keeps all spatial offsets but has zero range difference, so the constant 1×1 image is unchanged.

Work is O(N(2r+1)²), bounded to 9 or 25 samples per pixel by validation. Workspace can be one N-byte luma plane plus line/output scratch; input/output are additional 6N bytes. Check cancellation each row. The stage can erase pencil marks whose contrast resembles noise, including low-amplitude marks in the range kernel. It is not automatically enabled by high gain, neural restoration or Sauvola; retaining grain is preferable to erasing content when the user values faint marks.

## 7. Optional `luma_detail7_v1`, disabled by default

The seven-tap blur is source-grounded [P1], but the soft threshold, cap, symmetric signed ties and nonclipping headroom are independent safeguards. Operate once on the current stage's Y8, after optional noise suppression. Fixed kernel weights are `[1,6,15,20,15,6,1]`, sum64, radius3. Horizontal filtering uses replicated indices and produces byte `H=floor((weightedSum+32)/64)`; vertical filtering of H uses the same rule and replicated borders to produce byte B. Do **not** defer quantization until after both passes or use reflect101 here.

For each pixel, `d=Y8−B`, `e=sign(d)*max(abs(d)−thresholdY,0)`, `raw=clamp(amount*e,−maxDelta,maxDelta)`. Then clip `raw` to the integer headroom interval `[−min(R,G,B),255−max(R,G,B)]` and set `delta=roundSigned(raw)`. Candidate channels are exactly `input_c+delta`, which are in range by construction; apply the common strength blend once. At `abs(d)=thresholdY`, correction is zero. This is a soft threshold, unlike the source's hard skip at |d|<3 followed by .8*d. Never round a signed correction with floor(x+.5).

DT-01: 7×1 neutral input **[100,100,100,164,100,100,100]** gives horizontal and final blur **[101,106,115,120,115,106,101]**. Defaults yield candidate RGB neutrals **[100,99,95,172,95,99,100]**. DT-02: input [0,0,0,64,0,0,0] gives blur [1,6,15,20,15,6,1] and candidate **[0,0,0,72,0,0,0]**; negative corrections at black are headroom-clipped, not wrapped. DT-03, residual helper: amount=.5, threshold3, cap8 gives d=+4→delta+1 and d=−4→delta−1; d=±3→0. DT-04, headroom helper: RGB=(250,100,100), d=33, defaults gives raw8, clipped delta5 and candidate **(255,105,105)**. DT-05: constant images, amount0, cap0 or strength0 are exact identity; width/height1 uses replication. DT-06: detail+Sauvola or detail+protection-mask fails preflight, not a partial render.

Work is O(14N), hence O(N); straightforward workspace is two N-byte planes Y and H, with vertical B generated per row rather than another plane. Radius is three pixels in the **current requested output raster**, not three source-photo pixels or a physical millimeter measure. PREVIEW may therefore differ from downsampled EXPORT; DETAIL_CROP uses full-target context. Headroom and cap bound halos but cannot restore lost strokes, undo clipping, or prove that emphasized grain is real detail.

## 8. Final `grayscale_v1` and sliding `sauvola_v1`

Grayscale candidate is `(Y8,Y8,Y8)` with the exact integer coefficients of §1.1. It is a representation conversion, not another appearance recipe and not blended with RGB. GS-01: RGB red (255,0,0)→**(76,76,76)**, green→**(149,149,149)**, blue→**(28,28,28)**, neutral200→(200,200,200). Pure white→255 and black→0. There is no Lab conversion, noise filter, exposure normalization or stretch hidden in this stage. O(N) work; no image-sized workspace is required beyond the output. A mask is rejected rather than leaving mixed color/gray pixels.

Sauvola uses that same Y8 from the complete stage input. For AutoShortSide, `window=nextOdd(clamp(roundPositive(min(W,H)/40.0),15,101))`; it is a nominal square side, not a radius. For an explicit side use it unchanged. `r=(window−1)/2`. At pixel (x,y), `x0=max(0,x−r)`, `x1=min(W,x+r+1)`, `y0=max(0,y−r)`, `y1=min(H,y+r+1)`; all upper bounds are half-open. The actual count is `n=(x1−x0)*(y1−y0)` even for a tiny image/window larger than its dimensions. With int64 sums `S=ΣY8`, `Q=ΣY8²`, compute float64:

```text
mu = S / n
variance = max(0, Q/n - mu*mu)       # population variance, not sample variance
sigma = sqrt(variance)
T = mu * (1 + k*(sigma/128 - 1))
bit = 255 if Y8 > T else 0          # equality is black
RGB = (bit, bit, bit)
```

Do not quantize or round T, and do not force pixels ≥230 white. There is no preliminary upstream `flatten`, ink curve, gray Retinex, bilateral filtering, resizing, white-hole recovery or black-speck deletion. Those would change the evidence-preservation contract. A user can explicitly select conservative appearance or optional denoise before this final stage; the recipe records that choice.

### 8.1 Sliding-sum implementation contract

Retain immutable Y8 in one byte plane. A serial reference holds int64 arrays `colS[W],colQ[W],prefixS[W+1],prefixQ[W+1]`, all initially zero. Initialize active rows to `[0,min(H,r+1))`. At each y, add newly entering complete rows and subtract rows that left, so column sums cover exactly `[y0,y1)`. Rebuild prefixes with prefix[0]=0, `prefix[x+1]=prefix[x]+col[x]`. For each x, `S=prefixS[x1]−prefixS[x0]`, analogously Q; evaluate the formula and emit bytes. Then advance to y+1. Never read the binary output into the statistics.

A row-band implementation independently initializes the clipped halo at its first row and uses the same sums; fixture bytes must match serial execution at band boundaries. Prefer one worker initially. Additional workers require budgeted four arrays each, not one allocation per image row. Reject any theoretical sum bound exceeding int64, including `65025*N`; use wide checked arithmetic rather than assuming handset images cannot overflow.

### 8.2 Deterministic fixtures and known limitations

- SV-01: 3×1 neutral [0,100,200] with default nominal window15 has every clipped window equal to the complete image. `mu=100`, `sigma=sqrt(20000/3)≈81.6496580928`, `T≈92.7577590770`; output **[0,255,255]**.
- SV-02: constant positive c has sigma0, T=.8*c at default k, so output is all **white** even for a constant dark-gray c=10. Constant zero has T=0 and equality produces all **black**. This dark-fill failure is mathematical, not an implementation defect; retain grayscale/color for shaded diagrams and reversed text rather than silently inventing a cleanup policy.
- SV-03: 1×1 c=200, even with explicit window1001, uses count1, S200, Q40000, sigma0, T160, output255. A 1×1 black pixel outputs0. Reflected/replicated full nominal counts would violate the clipped-window contract.
- SV-04: 3×1 [0,100,200] with explicit window3 has clipped counts [2,3,2]; endpoint thresholds are 43.90625 and 131.71875, center threshold≈92.7577590770; output remains **[0,255,255]**. The counts and threshold fixtures distinguish a coincidentally correct binary result from the wrong border implementation.
- SV-05: 15×15 neutral input has 223 pixels255, one pixel0 and center pixel230; explicit window15 and k=.10. The center sees the complete image: `mu=253.7555555556`, `sigma≈17.0361126963`, `T≈231.7573501889`, and center output is **black**. A ≥230 shortcut incorrectly erases it.
- SV-06: threshold helper Y8=0,mu=0,sigma=0 yields black by equality; do not use ≥. Auto windows at minimum dimensions 1,600,2480 are **15,15,63** respectively; at 5000 the maximum is101. Explicit even side16, k=.4 or R255 reject.
- SV-07: increase k on the same ordinary neighborhood with sigma<128: T decreases. Fewer faint dark marks become black, not more. Use a fixture with Y8 between the two computed thresholds; a slider labeled “preserve more ink” must not silently increase k.

Sliding work is O(N) serial, or O(N+B*W*window) with B separately initialized row bands. Workspace is `N + P*(32W+16)` bytes for P simultaneous workers, plus input/output and runtime overhead; this avoids two 8N-byte integral images. Binary output irreversibly discards gray/color distinctions in the rendered/exported derivative, although the immutable source remains. It does not make a document more authentic or OCR-safe.

## 9. Acceptance, reproducibility and implementation obligations

Implement the listed scalar/helper and RGB fixtures before the production loops. Also test odd dimensions/last paper cells, row stride padding, one-pixel axes, all zeros, all white, colored sheets, protected-mask edges, cancellation between rows, invalid/nonfinite parameters and rejected allocations. Verify serial versus bounded parallel paths exactly. Test source/current-stage separation: illumination statistics must not read the preceding row's modified output; neither bilateral nor thresholding may become an accidental iterative filter.

Evaluate permission-cleared real pages with faint pencil/highlighter, tiny punctuation, colored ink/stamps, photos, pastel substrates, dark fills, smooth/hard shadows, highlights and low-light grain. Keep paired full-size original/No enhancement/result crops. Inspect clipping before and after each stage, stroke survival/width, new halos, chroma changes and margin content, not merely global brightness or a cleanliness score. Color fidelity and a neutral-white aesthetic are separate acceptance objectives. No fixture in this document establishes representative-page acceptance.

Execution provenance records algorithm/version, every effective field, stage-input/output dimensions, window/kernel resolution, color-conversion version, source identity, mask identity, warnings, requested OutputSpec purpose/size, executed stages and precision/backend. Fixed-pixel kernels and global statistics mean preview/export equivalence cannot be assumed. A failed stage follows the shared FAIL/KEEP_STAGE_INPUT semantics, not an undocumented aggressive rescue. A fallback is never labeled as the requested algorithm.

Source reuse has a separate rights gate. MakeACopy inspected files carry Apache-2.0 and attribute a prior port; retain applicable notices and audit that provenance if copying. FairScan's inspected file is GPL-3.0-or-later: direct reuse/porting belongs only in a compatible distribution after the final product-license decision. Independent mathematical specification does not certify that a future implementation is non-derivative. An OpenCV dependency has its own version/distribution/third-party notice checks. None of these classical recipes needs weights or a first-run download.

## 10. Public primary evidence

Snapshot date for all entries: **2026-11-06**; critical cached passages re-inspected **2026-11-14**. Pins identify inspected revisions, not “latest”. The package is standalone; these links provide attribution and source verification, not required runtime downloads.

- **P1:** [MakeACopy Enhance.java, pinned revision](https://github.com/egdels/makeacopy/blob/1041932d2c9906d3e907a1bbae59022e845a7e25/app/src/main/java/de/schliweb/makeacopy/utils/image/raster/Enhance.java), source lines 88–205 (luma, grid, selection), 208–294 (gain interpolation, wave fill, grid smoothing), 296–384 (separate ink/detail). [Pinned Apache-2.0 license](https://github.com/egdels/makeacopy/blob/1041932d2c9906d3e907a1bbae59022e845a7e25/LICENSE). The source target `1.05*255`, .6 ink curve and .8 hard-threshold sharpening are not this default recipe.
- **P2:** [MakeACopy BlackWhite.java, pinned revision](https://github.com/egdels/makeacopy/blob/1041932d2c9906d3e907a1bbae59022e845a7e25/app/src/main/java/de/schliweb/makeacopy/utils/image/raster/BlackWhite.java), lines 23–36 (k,R,shortcut), 44–47 (source flatten), 79–124 (clipped sliding sums/equality). The source's separate Gray implementation was not available in the inspected evidence; this specification independently fixes Y8 rather than claiming that missing conversion was verified.
- **P3:** [FairScan PostProcessing.kt, pinned revision](https://github.com/pynicolas/FairScan/blob/bd3dff9d6b20831eb6730c075590eeb974116773/imageprocessing/src/main/java/org/fairscan/imageprocessing/PostProcessing.kt), lines 45–164 (color log-lightness, interpolation, blend, stretch), 183–205 (histogram percentile). [Pinned GPL license](https://github.com/pynicolas/FairScan/blob/bd3dff9d6b20831eb6730c075590eeb974116773/LICENSE). Only the color mathematical distinction motivates Natural; do not import its different grayscale chain.
- **P4:** [OpenCV 4.13.0 color-conversion reference](https://docs.opencv.org/4.13.0/de/d25/imgproc_color_conversions.html), RGB/gray and RGB/CIE Lab sections; [filter reference](https://docs.opencv.org/4.13.0/d4/d86/group__imgproc__filter.html), box/bilateral/border contracts; [core array reference](https://docs.opencv.org/4.13.0/d2/de8/group__core__array.html), border/reduction behavior; [official licensing](https://opencv.org/license/). These support the distinctions among encoded RGB, gray and Lab; they do not establish byte equivalence to the independently fixed conversion above.

Remaining evidence gaps: exact prior-port provenance, the unavailable source Gray helper, a pinned Android color/filter implementation and its fixture parity, actual memory/latency/thermal behavior and representative-page quality. These are open implementation/release checks, not missing prerequisites for executing the fully defined model-free reference arithmetic.
