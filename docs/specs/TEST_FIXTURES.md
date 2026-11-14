# Reproducible fixtures and numerical oracles

These are future receiving-project tests, not outputs produced by this documentation. Implement the generator in `:test-fixtures`, independent of Android fonts, unavailable photographs or neural assets. Keep generation version, parameters, semantic masks, clean/corrupted arrays and decoded-pixel hashes. PNG/raw arrays are arithmetic references; JPEG is an additional codec condition, not a cross-codec exact oracle.

## F-01 — Plain and adversarial small images

Generate constant RGB pages at (0,0,0),(80,80,80),(200,200,200),(255,255,255),(240,220,180),(40,100,220), sizes1×1,1×17,17×1,2×2,31×47,512×768. Generate padded-stride equivalents with different nonzero padding bytes. Generate a black page with one channel200 at its center; one-pixel checkerboards; odd dimensions and extreme aspect ratios; transparent imported pixels with bright hidden RGB.

Expected invariants: padding does not affect pixels; off stages are exact identity; no NaN/overflow; scalar statistics use actual clipped-window count; tiny geometry requests that require dimensions≥2 reject explicitly; source bytes remain unchanged. Do not turn a flat bright page black through a degenerate normalization. For paper_gain_v1 all-white stays white, constant200 maps to245 at full strength, all-zero remains zero. Natural lightness flat-input fallback returns original bytes.

## F-02 — Ground-truth document canvas

Set RGB8 W=512,H=768, white255. Coordinates are integer pixel centers; rectangles are half-open. Preserve a mask for every content item and a background mask excluding all content.

1. Draw black one-pixel frame with outer coordinates x=16..495,y=16..751. Specifically set top/bottom rows16/751 for x16..495 and left/right columns16/495 for y16..751.
2. For j=0..6, gray value v=[0,64,128,176,208,224,240][j], y0=64+48*j, draw `[32,224)×[y0,y0+1)`, `[32,224)×[y0+8,y0+10)`, `[32,224)×[y0+16,y0+19)` with RGB(v,v,v). Retain gray level/thickness identities separately.
3. Add six64×40 color patches at top-left `(288,64+64*j)`: RGB(180,40,40),(40,160,40),(40,70,180),(210,160,30),(160,50,150),(40,160,170).
4. Add a reversed-text diagnostic independent of those masks: rectangle `[32,224)×[440,504)` gray80, with white one/two/three-pixel bars at y452,464,480. Retain interior-fill and white-bar masks. This probes local-threshold dark-fill failures, not a font/OCR corpus.
5. Keep a second canvas with cream background RGB(240,225,190), leaving all content identical. The clean target for Natural/paper-preserving evaluation is cream, not white. White-paper aesthetic evaluation is a different explicitly declared target.

## F-03 — Lighting and noise corruptions

For each clean sample T and channel c, define `ell(x,y)=clamp(0.55+0.35*x/511−0.25*exp(−((x−0.35*511)/(0.18*512))²−((y−0.55*767)/(0.22*768))²),0.20,1)`. Float64 generator arithmetic, then roundByte(T*ell) gives smooth shading. Record ell, so scoring never has to infer the intended corruption.

Color/noise version uses RGB gains g=(1.03,1,0.96), `I=roundByte(max(0,T*ell*g+noise))`. Initialize unsigned32 state=20261114. Each draw updates `state=(1664525*state+1013904223) mod 2^32`; u=(state>>16) mod7. Draw twice per channel sample, noise=u1−u2; traverse y,x,R,G,B. This deterministic bounded noise is not asserted to be a camera-noise distribution.

Abrupt-shadow version multiplies x<256 by0.55 and x≥256 by0.9 before rounding. Glare version clips a known circular area to255; mark it as irrecoverably lost captured detail rather than expect an enhancement algorithm to reconstruct it. Preserve clean masks but mark glare separately in quality reports. Do not reward invented replacement marks for matching a plausible page.

## F-04 — Geometry with known maps

Perspective: clean-canvas corners(0,0),(511,0),(511,767),(0,767) map to(40,40),(480,10),(500,730),(10,760) inside512×768 background32. Preserve forward/inverse transform and page-coverage mask. Use distinctive corner colors in a separate image to reveal swaps/mirroring.

Smooth inverse curl: `sx=x+16*sin(pi*x/511)*sin(2*pi*y/767)`, `sy=y`, same dimensions. Minimum horizontal derivative is at least`1−16*pi/511≈0.9016`, so the analytic map has no local fold. Keep its exact coordinates and known corresponding frame/line samples. Applying a learned model to this fixture is not expected to exactly infer the generator; sampler/known-map tests and learned-inference accuracy are distinct.

Invalid bilinear cell: p00=(0,0),p10=(2,0),p01=(0,2),p11=(0.25,1.25). Its Jacobian determinant is4−1.5u−3.5v: center1.5 is positive, but corner(1,1)=−1. A center-only validity test must fail this test. Include two separated positive-orientation cells whose source footprints overlap to test global overlap detection independently.

Manual edge oracle: P0=(0,0),P2=(100,0),on-curveM=(50,−10) yields controlC=(50,−20) and E(.5)=(50,−10). `w(.5)` for depth .5 is.625. A straight quadrilateral with midpoint handles and depth0 dispatches to the homography path; do not use it to claim a bilinear quadrilateral is projectively identical.

## F-05 — Primitive and stage vectors

| Test ID | Setup | Expected assertion |
|---|---|---|
| ALG-01 | RGB unsigned/padded rows versus packed rows | Same image; no row-padding contribution. |
| ALG-02 | Paper estimate200, pixel195, target245 | Full-strength candidate239; paper pixel200→245. |
| ALG-03 | Pixel200,gain1.5,strength.5 or protection.5 at strength1 | Candidate255; result228, not250; exactly one blend. |
| ALG-04 | Whole-image strength0 or fully protected appearance | Exact stage-input bytes, bypass unnecessary inference/color conversion. |
| ALG-12 | Input3,candidate8,strength Double0.1,protection Float0 | Shared ordered binary64 composer gives mixed3.5→byte4; float32-per-operation byte3 is nonconforming. |
| ALG-05 | Positive samples1,9 | mean(log)=log3≈1.098612; log(mean)=log5≈1.609438; do not conflate. |
| ALG-06 | Flat natural-lightness input | Range guard returns original, including constant gray/colored paper. |
| ALG-07 | Soft detail d=−21,tau3,amount.4,cap8 | Unclipped delta−7.2; signed integer delta−7 before RGB headroom. |
| ALG-08 | Positive requested detail8 at RGB(250,100,20) | Headroom limits delta5; result(255,105,25). |
| ALG-09 | Bilateral on constant RGB | Zero luma delta and exact unchanged RGB. |
| ALG-10 | Uniform gray80,Sauvolak.2,R128 | sigma0,T64,outputwhite; known dark-fill limitation. |
| ALG-11 | Uniform gray0,Sauvola | T0,equalityblack; never replace strict `>` with `>=`. |
| GEO-01 | Full-frame512×768 perspective | Target512×768, identity geometry within declared sampler conversion tolerance. |
| GEO-02 | Normalizedcorner-alignedgx=−1,+1,Ws4000 | xs0,3999; half-pixel formula is a different convention. |
| GEO-03 | Invalid bilinear cell above | Reject despite positive center determinant. |
| GEO-04 | Crop(100,200,401,601),normalizedgrid(0,0) | Source center(300,500); crop-space coordinates must be lifted. |
| GEO-05 | DocQuad W1000,H500 input256square | s=.256,oy64; modelcenter(128,128) maps source(500,250). |
| GEO-06 | One protected mark through known geometry | Mask/image coincide; outside mask protection1; no byte quantization. |
| GEO-07 | Clockwise quarter-turn, final normalized top-left(0,0) | Base coordinate(0,1); odd turn swaps target aspect; EXIF is not reapplied. Four quarter turns restore geometry identity. |
| STATE-01 | Previewgeneration8 completes after9 | Generation8 never replaces9 or publishes success for9. |
| STATE-02 | Frozen export[pageB,pageA],then library reorders | Export remainsB,A with frozen recipes; newer document revision stays separate. |
| STATE-03 | Otherwise identical EXPORT requests with FAIL versus KEEP_STAGE_INPUT | Distinct pre-execution Job keys; same-policy retries reuse only their own job, without a false corruption conflict. Execution hash is added only to completed-raster identity. |

Candidate-specific byte rounding, filter border and local-window vectors in PHOTOMETRIC_ALGORITHMS must also pass. Source-parity public models have their own goldens tied to exact weights/runtime; none of these synthetic vectors validates a learned function.

## F-06 — Import, persistence and export adversaries

Create EXIF orientation1..8 images with unmistakable corner colors; non-sRGB SDR profiles; unsupported HDR/profile cases; extension/magic mismatches; truncated files; huge declared dimensions; revoked URI grants; short-reading streams; disk-full and cancellation at each journal boundary; duplicated pages sharing one source; process death before/after source rename and Room commit; export revision changes mid-job; provider failure after geometry; JPEG with fixed dimensions and a recorded nonlossless tolerance; lossless PNG exact decoded pixels; PDF with mixed page aspects/order and independently rasterized verification.

Use test doubles to inject storage/permission/failure boundaries, then verify representative real provider behavior on devices. Do not allocate an actually enormous bitmap just to test a preallocation guard. Check a fallback's exact stage-input identity and DEGRADED status; export requires its explicit acceptance.

Accepted-fallback fixture: an EXPORT request for a100×200 post-rotation page fails base geometry and retains an unrotated300×200 source. Persist that exact lossless raster/DEGRADED execution under a review lease. Freeze must reject old100×200 settings, require renewed review of300×200 plus omitted rotation/stages and admission, and pin the exact RenderId/hash with KEEP_STAGE_INPUT. Encoding then reuses300×200 without rerender, resize or rotation even if the provider later recovers. Releasing preview caches cannot evict this pin; changed/missing accepted bytes fail explicitly. A resource/cancellation/invalid-input failure is never eligible for this exception.

## Real-document set and provenance

Acquire permission-cleared camera photos covering receipts/faint print, pencil, colored paper/ink, highlighter, stamps/signatures retained as content, tables/photographs, soft/hard/book-gutter shadows, glossy glare, curved pages and awkward viewpoints. Include clean pages to detect unnecessary degradation. Keep train/development/release-acceptance sources separate if tuning any model/threshold; variations of one page are correlated, not independent evidence.

Each manifest entry: source ID/origin/display rights, original byte hash, oriented RGB hash, decode/color version, clean target if available and its limitations, semantic masks, recipe/effective parameters, geometry/size, provider weight/runtime/precision, output hash, benchmark device if measured, reviewer decision and known failing cases. Never invent a paired clean target for an uncalibrated camera photo. Generated test patterns are not generated documents pretending to be real algorithm examples.
