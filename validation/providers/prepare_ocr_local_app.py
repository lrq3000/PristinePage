"""Stage and verify the mandatory PristinePage OCR inputs, without probe fixtures."""

import argparse
from hashlib import sha256
from pathlib import Path
import re
import shutil
from zipfile import ZipFile

from prepare_ocr_android_probe import AAR, NOTICES, RAW, pack_pins, read_pins, verify_file, verify_stage, verify_stream


CLANG_NOTICE = (130_424, "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1")
NATIVE = ("libjpeg.so", "libleptonica.so", "libpngx.so", "libtesseract.so")
# From the retained no-OCR app; candidate verification also refuses extra non-OCR native members.
BASE_DEBUG_NATIVE = {
    "lib/arm64-v8a/libandroidx.graphics.path.so": (10_096, "41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6"),
    "lib/arm64-v8a/libimage_processing_util_jni.so": (32_528, "0292b063fafccf734472cbb59588e756c2dbca907c72021ba6fbf126385508a2"),
    "lib/arm64-v8a/libsurface_util_jni.so": (4_896, "a5c9d1928ea92ec7a94dff8cf666e7739cd734198b9f0ae1d4e28ea3c86516ba"),
}
ASSET_PREFIX = "ocr-local/"
FULL_STAGE = "ocr-app-standard-full"
FULL_PINS = (9_616, "7174b1547e187af3671e6e22275b1ee5c8ac41aad4b0e7f8e993c40b37807423")
MAXIMUM_APK_GROWTH = 300_000_000
CAPTURE_BASELINE = (53_730_672, "a58c553b00d548ebf519d3e15eddf74bfe7f4e38fe951256cb720290f9af02fc")


def expanded_pins(receipt):
    verify_file(receipt, *FULL_PINS)
    text = receipt.read_text(encoding="utf-8")
    codes = [line.split("\t", 1)[0] for line in text.splitlines()[1:]]
    base = pack_pins()
    pins = read_pins(text, codes)
    if (len(pins) != 124 or sum(size for size, _ in pins.values()) != 344_846_851
            or any(pins.get(code) != pin for code, pin in base.items())
            or "osd" in pins or max(size for size, _ in pins.values()) > 32 * 1024 * 1024):
        raise ValueError("Unexpected expanded recognition-language inventory")
    return pins


def verify_standard_manifest(badging, manifest):
    selected = re.search(r"(?m)^package: name='(app\.pristinepage(?:\.debug)?)'[^\r\n]* versionName='([^']+)'", badging)
    if not selected:
        raise ValueError("Unexpected standard OCR APK identity")
    app, version = selected.groups()
    # aapt2 prints namespace URIs rather than the manifest's short prefix; qualify the application itself, not a nested component.
    lines = iter(manifest.splitlines())
    application = next((m for line in lines if (m := re.fullmatch(r"( *)E: application(?: .*)?", line))), None)
    name = r'A: (?:android:|http://schemas\.android\.com/apk/res/android:)name(?:\([^)]+\))?="app\.pristinepage\.PristinePageApplication"(?: .*|$)'
    application_name = False
    if application:
        indent = len(application[1])
        for line in lines:
            attribute = line.lstrip(" ")
            if len(line) - len(attribute) <= indent or not attribute.startswith("A: "):
                break
            application_name |= re.fullmatch(name, attribute) is not None
    if (not re.fullmatch(r"\d+\.\d+\.\d+" + ("-debug" if app.endswith(".debug") else ""), version)
            or not application_name or "androidx.work.WorkManagerInitializer" not in manifest
            or re.search(r"android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE)", manifest)):
        raise ValueError("Not the standard offline OCR application")


def require_exact_tree(root, entries):
    if not root.is_dir() or root.resolve() != root.absolute():
        raise ValueError("Missing or redirected local OCR directory")
    dirs = {p.as_posix() for name in entries for p in Path(name).parents if p != Path(".")}
    observed = set()
    for path in root.rglob("*"):
        name = path.relative_to(root).as_posix()
        if path.resolve() != path.absolute():
            raise ValueError("Redirected local OCR input")
        if path.is_file() and name in entries:
            observed.add(name)
        elif not path.is_dir() or name not in dirs:
            raise ValueError("Unexpected local OCR input")
    if observed != entries.keys():
        raise ValueError("Incomplete local OCR input inventory")
    for name, identity in entries.items():
        verify_file(root.absolute() / name, *identity)


def verify_ocr_members(apk, expected):
    names = apk.namelist()
    if len(set(names)) != len(names):
        raise ValueError("Duplicate APK ZIP entries")
    observed = {name for name in names if name.startswith(("assets/ocr-local/", "assets/ocr-probe/", "lib/"))
                or name.lower().endswith(".traineddata")}
    if observed != expected.keys():
        raise ValueError("Unexpected or incomplete packaged OCR payload")
    for name, (extent, digest) in expected.items():
        if apk.getinfo(name).file_size != extent:
            raise ValueError("Wrong packaged OCR extent")
        with apk.open(name) as source:
            verify_stream(source, extent, digest)


def catalog_bytes(pins=None):
    rows = "\n".join(
        f'        VerifiedOcrPackDirectory.Pin(OcrLanguagePack("{code}", "{digest}"), {extent}L),'
        for code, (extent, digest) in (pins if pins is not None else pack_pins()).items()
    )
    return ("""package app.pristinepage.image

import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.image.ocr.VerifiedOcrPackDirectory

// Generated only for the verified local candidate from the checked exact pack pins.
internal object LocalOcrCatalog {
    val pins = listOf(
""" + rows + "\n    )\n}\n").encode()


def identity(data):
    return len(data), sha256(data).hexdigest()


def local_assets(pins=None):
    expected = {f"assets/{ASSET_PREFIX}tessdata/{code}.traineddata": pin
                for code, pin in (pins if pins is not None else pack_pins()).items()}
    expected.update({f"assets/{ASSET_PREFIX}notices/{name}": (size, digest) for size, digest, name in NOTICES.values()})
    expected[f"assets/{ASSET_PREFIX}notices/clang-r522817c-NOTICE.txt"] = CLANG_NOTICE
    return expected


def stage_location(stage):
    if stage.parent != RAW or stage.resolve() != stage or not re.fullmatch(r"ocr-app-[a-z0-9-]+", stage.name):
        raise ValueError("Use a nonredirected ocr-app-* directory under ignored validation storage")


def stage_inventory(stage):
    stage_location(stage)
    verify_file(stage / AAR[0], *AAR[1:])
    with ZipFile(stage / AAR[0]) as aar:
        classes = aar.read("classes.jar")
    pins = expanded_pins(stage / "pins.tsv") if stage.name == FULL_STAGE else None
    return {**local_assets(pins), AAR[0]: AAR[1:], "classes.jar": identity(classes),
            "catalog/LocalOcrCatalog.kt": identity(catalog_bytes(pins)), **({"pins.tsv": FULL_PINS} if pins else {})}


def stage_files(stage, aar, files, pins=None, receipt=None):
    stage.mkdir()
    for relative, source in files.items():
        target = stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as origin, target.open("xb") as destination:
            shutil.copyfileobj(origin, destination)
    with aar.open("rb") as source, (stage / AAR[0]).open("xb") as target:
        shutil.copyfileobj(source, target)
    with ZipFile(stage / AAR[0]) as aar, (stage / "classes.jar").open("xb") as target:
        target.write(aar.read("classes.jar"))
    (stage / "catalog").mkdir()
    with (stage / "catalog/LocalOcrCatalog.kt").open("xb") as target:
        target.write(catalog_bytes(pins))
    if receipt is not None:
        with (stage / "pins.tsv").open("xb") as target:
            target.write(receipt.read_bytes())
    require_exact_tree(stage, stage_inventory(stage))


def prepare(stage, probe, receipt, notice):
    stage_location(stage)
    verify_stage(probe, receipt)
    verify_file(notice, *CLANG_NOTICE)
    assets = {relative: probe / "assets/ocr-probe" / relative.removeprefix("assets/" + ASSET_PREFIX)
              for relative in local_assets()}
    assets[f"assets/{ASSET_PREFIX}notices/clang-r522817c-NOTICE.txt"] = notice
    stage_files(stage, probe / AAR[0], assets)


def prepare_from_publisher(stage, inputs, notice):
    """Stage known publisher files entirely offline, without using or generating any test images."""
    stage_location(stage)
    aar = inputs / AAR[0]
    files = {f"assets/{ASSET_PREFIX}tessdata/{code}.traineddata": inputs / f"{code}.traineddata" for code in pack_pins()}
    files.update({f"assets/{ASSET_PREFIX}notices/{name}": inputs / source for source, (_, _, name) in NOTICES.items()})
    files[f"assets/{ASSET_PREFIX}notices/clang-r522817c-NOTICE.txt"] = notice
    verify_file(aar, *AAR[1:])
    for relative, pin in local_assets().items():
        verify_file(files[relative], *pin)
    stage_files(stage, aar, files)


def prepare_expanded(stage, baseline, additions):
    stage_location(stage)
    if (stage.name != FULL_STAGE or baseline.name == FULL_STAGE or additions.parent != RAW
            or additions.resolve() != additions.absolute()):
        raise ValueError("Use the exact full stage, an independent baseline, and ignored publisher inputs")
    require_exact_tree(baseline, stage_inventory(baseline))
    receipt = additions / "pins.tsv"
    pins = expanded_pins(receipt)
    base = pack_pins()
    expected = local_assets(pins)
    files = {}
    for relative, pin in expected.items():
        code = Path(relative).stem
        source = (additions / Path(relative).name) if code in pins and code not in base else baseline / relative
        verify_file(source, *pin)
        files[relative] = source
    stage_files(stage, baseline / AAR[0], files, pins, receipt)


def verify_expansion_size(baseline, expanded):
    growth = expanded.stat().st_size - baseline.stat().st_size
    if growth < 0 or growth >= MAXIMUM_APK_GROWTH:
        raise ValueError("Expanded APK must grow by less than 300,000,000 bytes")
    return growth


def verify_candidate(stage, apk):
    require_exact_tree(stage, stage_inventory(stage))
    pins = expanded_pins(stage / "pins.tsv") if stage.name == FULL_STAGE else None
    expected = {**local_assets(pins), **BASE_DEBUG_NATIVE}
    with ZipFile(stage / AAR[0]) as aar:
        for name in NATIVE:
            expected[f"lib/arm64-v8a/{name}"] = identity(aar.read(f"jni/arm64-v8a/{name}"))
    with ZipFile(apk) as packaged:
        verify_ocr_members(packaged, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", type=lambda p: Path(p).absolute())
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--from-probe", type=lambda p: Path(p).absolute())
    mode.add_argument("--from-publisher", type=lambda p: Path(p).absolute())
    mode.add_argument("--expand-from-verified-stage", type=lambda p: Path(p).absolute())
    mode.add_argument("--apk", type=Path)
    mode.add_argument("--without-ocr", type=Path)
    mode.add_argument("--default-debug", type=Path)
    parser.add_argument("--probe-receipt")
    parser.add_argument("--clang-notice", type=lambda p: Path(p).absolute())
    parser.add_argument("--language-inputs", type=lambda p: Path(p).absolute())
    parser.add_argument("--baseline-apk", type=Path)
    args = parser.parse_args()
    if args.from_probe:
        if not args.probe_receipt or not args.clang_notice:
            parser.error("Preparation needs the independently recorded probe receipt and notice")
        prepare(args.stage, args.from_probe, args.probe_receipt, args.clang_notice)
    elif args.from_publisher:
        if not args.clang_notice:
            parser.error("Preparation needs the exact pinned Clang notice")
        prepare_from_publisher(args.stage, args.from_publisher, args.clang_notice)
    elif args.expand_from_verified_stage:
        if not args.language_inputs:
            parser.error("Expansion needs the authenticated additional recognition languages")
        prepare_expanded(args.stage, args.expand_from_verified_stage, args.language_inputs)
    elif args.apk:
        verify_candidate(args.stage, args.apk)
        if args.baseline_apk:
            if args.stage.name == FULL_STAGE:
                verify_file(args.baseline_apk.absolute(), *CAPTURE_BASELINE)
            print(f"APK growth: {verify_expansion_size(args.baseline_apk, args.apk):,} bytes")
        elif args.stage.name == FULL_STAGE:
            print("Inventory only: supply the exact retained baseline APK to verify the growth condition")
    elif args.without_ocr:
        with ZipFile(args.without_ocr) as packaged:
            verify_ocr_members(packaged, {})
    elif args.default_debug:
        with ZipFile(args.default_debug) as packaged:
            verify_ocr_members(packaged, BASE_DEBUG_NATIVE)
    else:
        require_exact_tree(args.stage, stage_inventory(args.stage))
    print("Verified exact local OCR input or APK inventory")


if __name__ == "__main__":
    main()
