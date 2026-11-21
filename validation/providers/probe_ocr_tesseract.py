"""Run an already installed Tesseract on a generated English control; no local documents or downloads."""

import argparse
import io
import json
import re
import subprocess
import time

from PIL import Image, ImageDraw, ImageFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tesseract", help="Path to an existing Tesseract executable")
    args = parser.parse_args()
    lines = ["PristinePage receipt", "Invoice 46517", "Total 48.25 EUR"]
    image = Image.new("L", (640, 250), 255)
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=36)
    for index, line in enumerate(lines):
        draw.text((38, 32 + index * 57), line, font=font, fill=0)
    encoded = io.BytesIO()
    image.save(encoded, format="PNG", dpi=(300, 300))
    expected = re.findall(r"\w+|[.]", " ".join(lines).lower())
    results = []
    for psm in (3, 6, 11):
        start = time.perf_counter()
        result = subprocess.run([args.tesseract, "stdin", "stdout", "-l", "eng", "--psm", str(psm)],
                                input=encoded.getvalue(), capture_output=True, timeout=20, check=True)
        tokens = re.findall(r"\w+|[.]", result.stdout.decode("utf-8").lower())
        results.append({"page_segmentation": psm, "exact_normalized_tokens": tokens == expected,
                        "returned_tokens": len(tokens), "elapsed_ms": round((time.perf_counter() - start) * 1000)})
    if not results[1]["exact_normalized_tokens"]:
        raise SystemExit(f"Single-block positive control failed: {results}")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
