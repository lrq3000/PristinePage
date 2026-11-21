"""Desktop CPU witness for the exact published Paddle UVDoc ONNX; synthetic pixels only."""

import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort

EXPECTED_BYTES = 31_684_150
EXPECTED_SHA256 = "54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("asset", type=Path)
    with parser.parse_args().asset.open("rb") as stream:
        asset = stream.read(EXPECTED_BYTES + 1)
    digest = hashlib.sha256(asset).hexdigest()
    if len(asset) != EXPECTED_BYTES or digest != EXPECTED_SHA256:
        raise SystemExit("Local asset does not match the pinned Paddle UVDoc identity")
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    start = time.perf_counter()
    session = ort.InferenceSession(asset, sess_options=options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - start) * 1000
    for actual, name in ((session.get_inputs(), "image"), (session.get_outputs(), "fetch_name_0")):
        if len(actual) != 1 or actual[0].name != name or actual[0].type != "tensor(float)":
            raise SystemExit(f"Unexpected graph port: {actual}")
        if len(actual[0].shape) != 4 or actual[0].shape[1] != 3:
            raise SystemExit(f"Unexpected graph shape: {actual[0].shape}")

    observations = []
    for height, width in ((96, 128), (145, 89)):
        coords = np.indices((height, width))
        # Asymmetric colored edges and interior lines make H/W or output-plane mistakes visible.
        image = np.full((1, 3, height, width), 0.62, dtype=np.float32)
        image[0, 0, :, : width // 3] = 0.9
        image[0, 2, :, width // 2 :] = 0.25
        image[0, :, (coords[0] % 15 == 7) & (coords[1] > 8) & (coords[1] < width - 9)] = 0.08
        start = time.perf_counter()
        (output,) = session.run(["fetch_name_0"], {"image": image})
        run_ms = (time.perf_counter() - start) * 1000
        if output.shape != image.shape or output.dtype != np.float32 or not np.isfinite(output).all():
            raise SystemExit("Unexpected output extents, dtype or nonfinite values")
        if output.min() < -1e-6 or output.max() > 1 + 1e-6:
            raise SystemExit("Output left the expected encoded-color range")
        if np.array_equal(output, image):
            raise SystemExit("No output change on synthetic input")
        observations.append({"input_shape": list(image.shape), "output_shape": list(output.shape),
                             "min": float(output.min()), "max": float(output.max()),
                             "mean_abs_change": float(np.mean(np.abs(output - image))), "cpu_run_ms": round(run_ms, 1)})
    # For a shared nonnegative grid sampler, constant ordered planes preserve their ratios even at black outside support.
    markers = np.empty((1, 3, 97, 131), dtype=np.float32)
    markers[0, 0], markers[0, 1], markers[0, 2] = 0.8, 0.4, 0.2
    (sampled,) = session.run(["fetch_name_0"], {"image": markers})
    if sampled.shape != markers.shape or not np.isfinite(sampled).all() or sampled[0, 0].max() <= 0.5:
        raise SystemExit("Constant channel marker control failed")
    ratio_error = max(np.max(np.abs(sampled[0, 0] - 2 * sampled[0, 1])),
                      np.max(np.abs(sampled[0, 1] - 2 * sampled[0, 2])))
    if ratio_error > 1e-6:
        raise SystemExit("Output changed channel order or failed constant-plane ratio")
    print(json.dumps({"runtime": ort.__version__, "providers": session.get_providers(), "asset_sha256": digest,
                      "load_ms": round(load_ms, 1), "max_constant_channel_ratio_error": float(ratio_error),
                      "observations": observations}, indent=2))


if __name__ == "__main__":
    main()
