"""Stage and verify the opt-in PristinePage debug OCR inputs, without probe fixtures."""

import argparse
from hashlib import sha256
from pathlib import Path
import re
import shutil
from zipfile import ZipFile

from prepare_ocr_android_probe import AAR, NOTICES, RAW, pack_pins, verify_file, verify_stage, verify_stream


CLANG_NOTICE = (130_424, "f96f763beb66a7ba7a667647fc64c0226ace875e590c831fdd9579ec1c1d91e1")
NATIVE = ("libjpeg.so", "libleptonica.so", "libpngx.so", "libtesseract.so")
# From the retained no-OCR app; candidate verification also refuses extra non-OCR native members.
BASE_DEBUG_NATIVE = {
    "lib/arm64-v8a/libandroidx.graphics.path.so": (10_096, "41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6"),
    "lib/arm64-v8a/libimage_processing_util_jni.so": (32_528, "0292b063fafccf734472cbb59588e756c2dbca907c72021ba6fbf126385508a2"),
    "lib/arm64-v8a/libsurface_util_jni.so": (4_896, "a5c9d1928ea92ec7a94dff8cf666e7739cd734198b9f0ae1d4e28ea3c86516ba"),
}
ASSET_PREFIX = "ocr-local/"


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


def catalog_bytes():
    pins = "\n".join(
        f'        VerifiedOcrPackDirectory.Pin(OcrLanguagePack("{code}", "{digest}"), {extent}L),'
        for code, (extent, digest) in pack_pins().items()
    )
    return ("""package app.pristinepage.image

import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.image.ocr.VerifiedOcrPackDirectory

// Generated only for the verified local candidate from the checked exact pack pins.
internal object LocalOcrCatalog {
    val pins = listOf(
""" + pins + "\n    )\n}\n").encode()


def identity(data):
    return len(data), sha256(data).hexdigest()


def local_assets():
    expected = {f"assets/{ASSET_PREFIX}tessdata/{code}.traineddata": pin for code, pin in pack_pins().items()}
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
    return {**local_assets(), AAR[0]: AAR[1:], "classes.jar": identity(classes),
            "catalog/LocalOcrCatalog.kt": identity(catalog_bytes())}


def prepare(stage, probe, receipt, notice):
    stage_location(stage)
    verify_stage(probe, receipt)
    verify_file(notice, *CLANG_NOTICE)
    stage.mkdir()
    for relative in local_assets():
        rest = relative.removeprefix("assets/" + ASSET_PREFIX)
        source = notice if rest == "notices/clang-r522817c-NOTICE.txt" else probe / "assets/ocr-probe" / rest
        target = stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as origin, target.open("xb") as destination:
            shutil.copyfileobj(origin, destination)
    with (probe / AAR[0]).open("rb") as source, (stage / AAR[0]).open("xb") as target:
        shutil.copyfileobj(source, target)
    with ZipFile(stage / AAR[0]) as aar, (stage / "classes.jar").open("xb") as target:
        target.write(aar.read("classes.jar"))
    (stage / "catalog").mkdir()
    with (stage / "catalog/LocalOcrCatalog.kt").open("xb") as target:
        target.write(catalog_bytes())
    require_exact_tree(stage, stage_inventory(stage))


def verify_candidate(stage, apk):
    require_exact_tree(stage, stage_inventory(stage))
    expected = {**local_assets(), **BASE_DEBUG_NATIVE}
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
    mode.add_argument("--apk", type=Path)
    mode.add_argument("--without-ocr", type=Path)
    mode.add_argument("--default-debug", type=Path)
    parser.add_argument("--probe-receipt")
    parser.add_argument("--clang-notice", type=lambda p: Path(p).absolute())
    args = parser.parse_args()
    if args.from_probe:
        if not args.probe_receipt or not args.clang_notice:
            parser.error("Preparation needs the independently recorded probe receipt and notice")
        prepare(args.stage, args.from_probe, args.probe_receipt, args.clang_notice)
    elif args.apk:
        verify_candidate(args.stage, args.apk)
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
