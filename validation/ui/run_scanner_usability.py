"""Check the exact expanded debug pair on the previously approved retained test guest."""

import argparse
import json
import os
from pathlib import Path
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validation/providers"))
from run_local_ocr_airplane import verify_target
from run_local_ocr_guest import APP, TEST, RUNNER, GUEST_PATTERN, exact_apk, load_allocator, need, passed_case, run_type
from prepare_ocr_local_app import verify_expansion_size

CASES = {
    "import": ("app.pristinepage.GallerySdrImportTest", "importsAnUltraHdrJpegAndReopensTheSdrPixelsWithoutRewritingTheOriginal"),
    "copy": ("app.pristinepage.ocr.OcrSelectionClipboardTest", "onlyExplicitCopyTransfersTheSelectedRangeAndClassificationIsOff"),
    "long-copy": ("app.pristinepage.ocr.OcrSelectionClipboardTest", "longPressInSavedOcrDialogCopiesOnlyTheSelectedWord"),
    "picker": ("app.pristinepage.CaptureEntryJourneyTest", "cameraFreeGalleryAndFilesShowTheDisclosureAndReturnToTheOwnedDraft"),
    "picker-hdr": ("app.pristinepage.CaptureEntryJourneyTest", "actualFilesSelectionCommitsAndReopensTheCompleteSyntheticHdrSource"),
    "picker-provider": ("app.pristinepage.SystemPickerJourneyTest", "fixtureProviderLoadsInItsOwnPackageWithoutTargetApplicationDependencies"),
    "zoom": ("app.pristinepage.EditorComparisonScreenTest", "referenceViewsShowDifferentLiteralGeometryAndApplyOnlyTheCompleteDraft"),
    "title": ("app.pristinepage.LibraryActivityIntegrationTest", "realLibraryActivityCreatesAnEmptyDocumentAndRetainsItsSessionAcrossRecreation"),
    "ocr": ("app.pristinepage.ocr.ExpandedOcrLanguagesTest", "selectsARecentlyBundledLanguageAndRecognizesWithItsActualPack"),
    "routing": ("app.pristinepage.PhotoResultRoutingTest", "pickerCancellationClosesOnlyItsUnselectedBatchWithoutAPageOrProviderRead"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--guest", required=True, help="Exact previously authorized, released qualification guest name")
    parser.add_argument("--app-hash", required=True)
    parser.add_argument("--test-hash", required=True)
    parser.add_argument("--previous-app", required=True)
    parser.add_argument("--previous-test", required=True)
    parser.add_argument("--case", action="append", choices=CASES, required=True)
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest), "Invalid OCR qualification guest name")
    need(len(args.case) == len(set(args.case)), "Do not repeat a case in one smoke batch")
    for value in (args.app_hash, args.test_hash, args.previous_app, args.previous_test):
        need(re.fullmatch(r"[0-9a-f]{64}", value), "Use exact reviewed SHA-256s")
    pair = {
        APP: ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        TEST: ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    }
    hashes = {APP: args.app_hash, TEST: args.test_hash}
    baseline = ROOT / "dist/pristinepage-0.6.0-debug-capture-fix-a58c553b00d5.apk"
    exact_apk(baseline, "a58c553b00d548ebf519d3e15eddf74bfe7f4e38fe951256cb720290f9af02fc",
              ROOT / "validation/raw/providers/ocr-app-standard", False)
    growth = verify_expansion_size(baseline, pair[APP])
    stage = ROOT / "validation/raw/providers/ocr-app-standard-full"
    identities = [exact_apk(path, hashes[package], stage, package == TEST) for package, path in pair.items()]
    prefix = "picker-adapter-scanner-usability-" + uuid.uuid4().hex[:12]
    raw = ROOT / "validation/raw/storage" / prefix
    raw.mkdir(parents=True)
    (raw / f"{prefix}-artifacts.json").write_text(
        json.dumps({"guest": args.guest, "apks": identities, "apkGrowthBytes": growth}, indent=2), encoding="utf-8")
    ns = load_allocator(ROOT, args.guest, raw, "pristinepage-scanner-usability-" + os.environ["OPENCODE_SESSION_ID"])

    class UsabilityRun(run_type(ns)):
        def saved_journey(self, target, *_args, **_kwargs):
            verify_target(self, ns)
            outcomes = {}
            for selected in args.case:
                case = CASES[selected]
                output = self.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class", "#".join(case), RUNNER],
                                      timeout=130, allowed=(0, 1))
                self.path(f"{selected}.txt").write_text(output, encoding="utf-8")
                outcomes[selected] = passed_case(output, case)
                self.save(self.path("outcomes.json"), outcomes, durable=True)
                print(f"{selected}: {'PASS' if outcomes[selected] else 'FAIL'}", flush=True)
                need(outcomes[selected], "Stop at the first failed or unmatched Android case")

    run = UsabilityRun(prefix, None, 0)
    print("Evidence:", raw.relative_to(ROOT), flush=True)
    try:
        run.prepare(False, None, False)
        run.witness(pair, hashes, False, None, None, False, False, None, None, None, args.previous_test, previous_app=args.previous_app)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
