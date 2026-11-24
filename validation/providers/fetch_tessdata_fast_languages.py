"""Acquire only the missing 4.1.0 recognition languages from the pinned publisher tree."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import subprocess

from prepare_ocr_android_probe import RAW, pack_pins

REVISION = "65727574dfcd264acbb0c3e07860e4e9e9b22185"
TREE = f"https://api.github.com/repos/tesseract-ocr/tessdata_fast/git/trees/{REVISION}?recursive=1"
RAW_URL = f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{REVISION}/"


def fetch(url, *args):
    command = ["svipall", "fetch", url, "--timeout", "90000", "--wait", "180", *args]
    run = subprocess.run(command, capture_output=True, encoding="utf-8", timeout=300)
    if run.returncode:
        raise ValueError(f"Publisher transfer command failed: {(run.stderr or run.stdout)[:400]}")
    result = json.loads(run.stdout)
    if result.get("status") not in (None, 200, "200") or result.get("blocked_reason") or result.get("error"):
        raise ValueError(f"Publisher request refused: {result.get('blocked_reason') or result.get('status')}")
    return result


def inventory(tree):
    if tree.get("sha") != REVISION or tree.get("truncated") is not False:
        raise ValueError("Wrong revision or incomplete publisher tree")
    entries = {}
    for item in tree["tree"]:
        match = re.fullmatch(r"([a-z][a-z0-9_]{0,31})\.traineddata", item["path"])
        if not match or match[1] == "osd":
            continue
        code = match[1]
        size, oid = item["size"], item["sha"]
        if (item["type"] != "blob" or item["mode"] != "100644" or code in entries
                or type(size) is not int or not 0 < size <= 32 * 1024 * 1024
                or not re.fullmatch(r"[0-9a-f]{40}", oid)):
            raise ValueError(f"Invalid publisher entry: {code}")
        entries[code] = (size, oid)
    if len(entries) != 124 or sum(size for size, _ in entries.values()) != 344_846_851:
        raise ValueError("Unexpected recognition-language count or extent")
    return entries


def authenticated_sha256(path, size, oid):
    if path.resolve() != path.absolute() or not path.is_file() or path.stat().st_size != size:
        raise ValueError(f"Wrong language extent or path: {path.name}")
    git_blob = hashlib.sha1(f"blob {size}\0".encode("ascii"))
    sha256 = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            git_blob.update(block)
            sha256.update(block)
    if git_blob.hexdigest() != oid:
        raise ValueError(f"Wrong publisher blob ID: {path.name}")
    return sha256.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", help="Fresh or resumable ocr-publisher-languages-* directory in validation/raw/providers")
    args = parser.parse_args()
    if not re.fullmatch("ocr-publisher-languages-[a-z0-9-]+", args.destination):
        parser.error("Use an ignored ocr-publisher-languages-* child")
    root = RAW / args.destination
    if root.resolve() != root.absolute():
        parser.error("Refusing redirected destination")
    raw = fetch(TREE, "--full")["content"]
    entries = inventory(json.loads(raw) if isinstance(raw, str) else raw)
    base = pack_pins()
    if not base.keys() <= entries.keys() or any(entries[code][0] != size for code, (size, _) in base.items()):
        raise ValueError("Baseline packs disagree with the release")
    root.mkdir(exist_ok=True)

    def retrieve(code):
        size, oid = entries[code]
        destination = root / (code + ".traineddata")
        if not destination.exists():
            response = fetch(RAW_URL + destination.name, "--out", str(destination))
            if not response.get("file", {}).get("complete"):
                raise ValueError(f"Incomplete publisher transfer: {code}")
        return code, (size, authenticated_sha256(destination, size, oid))

    additions = sorted(entries.keys() - base.keys())
    with ThreadPoolExecutor(max_workers=2) as pool:
        pins = {**base, **dict(pool.map(retrieve, additions))}
    receipt = ("code\tbytes\tsha256\n" + "".join(f"{code}\t{size}\t{digest}\n" for code, (size, digest) in pins.items())).encode()
    path = root / "pins.tsv"
    if path.exists():
        if path.read_bytes() != receipt:
            raise ValueError("Existing receipt does not match; preserve it")
    else:
        with path.open("xb") as out:
            out.write(receipt)
    print(f"Authenticated {len(additions)} additional recognition blobs; {sum(entries[c][0] for c in additions)} raw bytes.")


if __name__ == "__main__":
    main()
