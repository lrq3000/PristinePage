"""Opt-in desktop CPU oracle for the exact local asset. Never retrieves models or reads photos."""

import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort

EXPECTED_BYTES = 13_404_960
EXPECTED_SHA256 = "f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a"
OUTPUTS = {"mask_logits": [1, 1, 64, 64], "corner_heatmaps": [1, 4, 64, 64]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("asset", type=Path)
    parser.add_argument("--heads-dir", type=Path, help="Write only the synthetic probe's raw FLOAT32 outputs into an existing local directory")
    args = parser.parse_args()
    path = args.asset
    with path.open("rb") as stream:
        asset = stream.read(EXPECTED_BYTES + 1)
    digest = hashlib.sha256(asset).hexdigest()
    if len(asset) != EXPECTED_BYTES or digest != EXPECTED_SHA256:
        raise SystemExit("Local asset does not match the pinned DocQuad identity")
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.load_model_format", "ORT")
    start = time.perf_counter()
    session = ort.InferenceSession(asset, sess_options=options, providers=["CPUExecutionProvider"])
    load_ms = (time.perf_counter() - start) * 1000
    inputs = [(x.name, x.type, x.shape) for x in session.get_inputs()]
    outputs = {x.name: (x.type, x.shape) for x in session.get_outputs()}
    if inputs != [("input", "tensor(float)", [1, 3, 256, 256])] or outputs != {
        key: ("tensor(float)", value) for key, value in OUTPUTS.items()
    }:
        raise SystemExit(f"Unexpected exact graph interface: {inputs}; {outputs}")
    blank = np.zeros((1, 3, 256, 256), dtype=np.float32)
    page = blank.copy()
    page[:, :, 28:226, 52:205] = 0.94
    for row in range(51, 198, 13):
        page[:, :, row : row + 2, 67 : 174 + row % 15] = 0.13
    page[0, 0, 192:207, 168:187] = 0.75
    page[0, 1:, 192:207, 168:187] = 0.16
    narrow = np.full_like(blank, 0.27)
    narrow[:, :, 32:228, 71:192] = 0.94
    for row in range(52, 212, 10):
        narrow[:, :, row : row + 2, 85 : 176 - row % 21] = 0.15
    records = []
    for label, image in (("black", blank), ("synthetic_page", page), ("narrow_page", narrow)):
        start = time.perf_counter()
        mask, heat = session.run(list(OUTPUTS), {"input": image})
        milliseconds = (time.perf_counter() - start) * 1000
        if not np.isfinite(mask).all() or not np.isfinite(heat).all():
            raise SystemExit("Nonfinite output")
        if args.heads_dir is not None:
            with (args.heads_dir / f"{label}.head").open("xb") as output:
                output.write(mask.astype("<f4", copy=False).tobytes())
                output.write(heat.astype("<f4", copy=False).tobytes())
        peaks = [list(map(int, np.unravel_index(np.argmax(channel), channel.shape))) for channel in heat[0]]
        records.append(
            {
                "fixture": label,
                "mask_positive_cells": int(np.count_nonzero(mask > 0)),
                "corner_peaks_row_col": peaks,
                "lowest_peak_logit": round(float(heat[0].max(axis=(1, 2)).min()), 4),
                "cpu_run_ms": round(milliseconds, 1),
            }
        )
    if records[0]["corner_peaks_row_col"] == records[1]["corner_peaks_row_col"]:
        raise SystemExit("The changed synthetic page did not affect any peak positions")
    expected_narrow_peaks = [(8, 18), (8, 48), (57, 48), (57, 18)]
    if records[2]["lowest_peak_logit"] <= 0 or any(
        abs(got[0] - want[0]) > 2 or abs(got[1] - want[1]) > 2
        for want, got in zip(expected_narrow_peaks, records[2]["corner_peaks_row_col"])
    ):
        raise SystemExit("The narrow positive control did not give four nearby positive peaks")
    print(json.dumps({"runtime": ort.__version__, "providers": session.get_providers(), "asset_sha256": digest,
                      "load_ms": round(load_ms, 1), "observations": records}, indent=2))


if __name__ == "__main__":
    main()
