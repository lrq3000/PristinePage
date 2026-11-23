"""Stage only the already-verified runtime, packs, notices and synthetic controls for a private Android test build."""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
import sys
import unicodedata


HERE = Path(__file__).resolve().parent
RAW = HERE.parent / "raw/providers"
AAR = ("tesseract4android.aar", 12_848_454, "bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a")
CODES = tuple("eng fra spa deu ita por nld pol tur rus ukr ell ara heb hin chi_sim jpn kor vie ind".split())
MIXED = [("eng", "fra"), ("jpn", "eng"), ("ara", "eng")]
NOTICES = {
    "wrapper-license.txt": (11_357, "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4", "tesseract4android.txt"),
    "tessdata-license.txt": (11_358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "tessdata-fast.txt"),
    "vendored-tesseract.bin": (11_358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", "tesseract.txt"),
    "vendored-leptonica.bin": (1_521, "87829abb5bbb00b55a107365da89e9a33f86c4250169e5a1e5588505be7d5806", "leptonica.txt"),
    "vendored-jpeg.bin": (18_719, "7c25493a9f64fed34d01445467341bda77bc1cdbeccbe33558659ef173fb9ff2", "ijg-jpeg.txt"),
    "vendored-png.bin": (5_345, "16d9daaafbf63a31a5bdc91d4600972548fef5aaa1244202393288dbd079c49a", "libpng.txt"),
}


def read_pins(text, expected):
    lines = text.splitlines()
    if not lines or lines[0] != "code\tbytes\tsha256":
        raise ValueError("Unexpected language-pin header")
    pins = {}
    for line in lines[1:]:
        m = re.fullmatch(r"([a-z][a-z0-9_]{0,31})\t([1-9][0-9]{0,7})\t([a-f0-9]{64})", line)
        if not m or m[1] in pins:
            raise ValueError("Invalid or duplicate language pin")
        pins[m[1]] = (int(m[2]), m[3])
    if tuple(pins) != tuple(expected):
        raise ValueError("Wrong language inventory or order")
    return pins


def verify_stream(source, extent, digest, consume=lambda data: None):
    if not 0 < extent <= 100_000_000:
        raise ValueError("Invalid verification extent")
    checksum = sha256()
    remaining = extent
    while remaining:
        chunk = source.read(min(remaining, 65536))
        if not chunk:
            raise ValueError("Truncated verified input")
        remaining -= len(chunk)
        checksum.update(chunk)
        consume(chunk)
    if source.read(1) or checksum.hexdigest() != digest:
        raise ValueError("Wrong input length or SHA-256")


def verified_bytes(source, extent, digest):
    buffered = BytesIO()
    verify_stream(source, extent, digest, buffered.write)
    return buffered.getvalue()


def inventory_from_receipt(receipt, digest, expected, actual):
    if len(receipt) > 32768 or sha256(receipt).hexdigest() != digest:
        raise ValueError("Staging receipt does not match its independently supplied SHA-256")
    if expected != actual:
        raise ValueError("Missing or unexpected asset in the OCR probe stage")
    decoded = json.loads(receipt)
    if not isinstance(decoded, dict) or set(decoded) != {"assets"}:
        raise ValueError("Invalid OCR probe receipt")
    assets = decoded["assets"]
    if not isinstance(assets, dict) or set(assets) != expected:
        raise ValueError("The receipt does not describe the exact expected asset inventory")
    for value in assets.values():
        if not (isinstance(value, list) and len(value) == 2 and type(value[0]) is int and 0 < value[0] <= 10_000_000
                and isinstance(value[1], str) and re.fullmatch(r"[a-f0-9]{64}", value[1])):
            raise ValueError("Invalid staged asset identity")
    return {name: tuple(value) for name, value in assets.items()}


def pack_pins():
    return read_pins((HERE / "tessdata-fast-4.1.0-pins.tsv").read_text(encoding="utf-8"), CODES)


def validate_location(output):
    if output.parent != RAW or output.resolve() != output or not re.fullmatch(r"ocr-engine-[a-z0-9-]+", output.name):
        raise ValueError("Stage must be a nonredirected child of ignored local validation storage")


def verify_file(path, extent, digest):
    if not path.is_file() or path.resolve() != path:
        raise ValueError("Missing or redirected verified input")
    with path.open("rb") as stream:
        verify_stream(stream, extent, digest)


def verify_stage(stage, expected_receipt):
    validate_location(stage)
    assets = stage / "assets"
    receipt_file = stage / "receipt.json"
    aar, extent, digest = AAR
    if not re.fullmatch(r"[a-f0-9]{64}", expected_receipt) or {p.name for p in stage.iterdir()} != {"assets", "receipt.json", aar}:
        raise ValueError("Incomplete or unexpected top-level OCR stage")
    if assets.resolve() != assets or not assets.is_dir() or receipt_file.resolve() != receipt_file or not receipt_file.is_file():
        raise ValueError("Redirected OCR stage component")
    verify_file(stage / aar, extent, digest)
    pins = pack_pins()
    known = {f"ocr-probe/tessdata/{code}.traineddata": pin for code, pin in pins.items()}
    known.update({f"ocr-probe/notices/{name}": (size, sha) for size, sha, name in NOTICES.values()})
    controls = [*pins, *("-".join(codes) for codes in MIXED), "blank"]
    expected = {*known, "ocr-probe/manifest.json", *(f"ocr-probe/controls/{case}.png" for case in controls)}
    directories = {parent.as_posix() for name in expected for parent in Path(name).parents if parent != Path(".")}
    actual = set()
    for path in assets.rglob("*"):
        name = path.relative_to(assets).as_posix()
        if path.resolve() != path:
            raise ValueError("Redirected OCR asset")
        if path.is_file() and name in expected:
            actual.add(name)
        elif not path.is_dir() or name not in directories:
            raise ValueError("Unexpected OCR asset entry")
    with receipt_file.open("rb") as stream:
        pinned = inventory_from_receipt(stream.read(32769), expected_receipt, expected, actual)
    for name, identity in pinned.items():
        if name in known and identity != known[name]:
            raise ValueError("Stage owner changed an exact publisher pin")
        verify_file(assets / name, *identity)


def read_exact_file(path, extent, digest):
    with path.open("rb") as stream:
        return verified_bytes(stream, extent, digest)


def normalize(text):
    return "".join(c for c in unicodedata.normalize("NFC", text.lower()) if not c.isspace())


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "--verify":
        verify_stage(Path(sys.argv[2]).absolute(), sys.argv[3])
        print("Verified exact private OCR stage")
        return

    from probe_ocr_language_packs import SAMPLES, features, render_control

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path)
    parser.add_argument("fonts", type=Path)
    parser.add_argument("name", help="Fresh child directory under the ignored validation/raw/providers")
    args = parser.parse_args()
    if not re.fullmatch(r"ocr-engine-[a-z0-9-]+", args.name) or not features.check("raqm"):
        raise ValueError("Use an OCR engine run name and an existing Pillow with shaping support")
    output = RAW / args.name
    validate_location(output)
    if output.exists():
        raise ValueError("Output must be a fresh nonredirected local validation directory")
    pins = pack_pins()
    if tuple(SAMPLES) != CODES:
        raise ValueError("Changed language control inventory")
    aar, extent, digest = AAR
    binary = read_exact_file(args.inputs / aar, extent, digest)
    # The exact allowlist is about 63 MB. Authenticate each bounded publisher input before any staging file is created.
    assets = {}
    for code, (extent, digest) in pins.items():
        name = code + ".traineddata"
        assets[f"ocr-probe/tessdata/{name}"] = read_exact_file(args.inputs / name, extent, digest)
    for name, (extent, digest, target) in NOTICES.items():
        assets[f"ocr-probe/notices/{target}"] = read_exact_file(args.inputs / name, extent, digest)
    cases = []
    for languages in [(p,) for p in pins] + MIXED + [()]:
        name = "-".join(languages) if languages else "blank"
        png = render_control(args.fonts, languages)
        assets[f"ocr-probe/controls/{name}.png"] = png
        cases.append({"id": name, "languages": "+".join(languages) or "eng",
                      "expected": normalize(" ".join(SAMPLES[language] for language in languages)),
                      "imageSha256": sha256(png).hexdigest(), "imageBytes": len(png)})
    assets["ocr-probe/manifest.json"] = json.dumps(
        {"packs": [{"code": code, "bytes": extent, "sha256": digest} for code, (extent, digest) in pins.items()], "cases": cases},
        ensure_ascii=False, indent=2,
    ).encode()
    output.mkdir(parents=True)
    with (output / aar).open("xb") as target:
        target.write(binary)
    for name, data in assets.items():
        path = output / "assets" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as target:
            target.write(data)
    receipt = json.dumps({"assets": {name: [len(data), sha256(data).hexdigest()] for name, data in assets.items()}},
                         sort_keys=True, separators=(",", ":")).encode()
    with (output / "receipt.json").open("xb") as target:
        target.write(receipt)
    digest = sha256(receipt).hexdigest()
    verify_stage(output, digest)
    print(f"Prepared {len(pins)} exact packs and {len(cases)} synthetic controls: {args.name}")
    print(f"OCR_STAGE_RECEIPT_SHA256={digest}")


if __name__ == "__main__":
    main()
