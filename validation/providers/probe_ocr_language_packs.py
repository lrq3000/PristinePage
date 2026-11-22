"""Run the pinned language packs on generated text with an existing desktop Tesseract."""

import argparse
from difflib import SequenceMatcher
import io
import json
from pathlib import Path
import subprocess
import unicodedata

from PIL import Image, ImageDraw, ImageFont, features


SAMPLES = {
    "eng": "The customer paid forty-eight euros.",
    "fra": "Le montant total est de quarante-huit euros.",
    "spa": "El número de factura figura en el recibo.",
    "deu": "Die Rechnung enthält den vollständigen Betrag.",
    "ita": "Il cliente ha pagato il conto con la carta.",
    "por": "O valor total da compra consta no recibo.",
    "nld": "Het totaalbedrag staat onderaan de rekening.",
    "pol": "Łączna kwota znajduje się na paragonie.",
    "tur": "Toplam tutar makbuzun altında yazıyor.",
    "rus": "Итоговая сумма указана в квитанции.",
    "ukr": "Загальна сума зазначена у квитанції.",
    "ell": "Το συνολικό ποσό αναγράφεται στην απόδειξη.",
    "ara": "المبلغ الإجمالي مكتوب على الإيصال",
    "heb": "הסכום הכולל מופיע על הקבלה",
    "hin": "रसीद पर कुल राशि लिखी है।",
    "chi_sim": "收据上注明了付款总额。",
    "jpn": "領収書に合計金額が記載されています。",
    "kor": "영수증에서 결제 금액을 확인하세요.",
    "vie": "Tổng số tiền được ghi trên biên lai.",
    "ind": "Jumlah total tercantum pada tanda terima.",
}
FONTS = {"hin": "Nirmala.ttf", "chi_sim": "msyh.ttc", "jpn": "YuGothM.ttc", "kor": "malgun.ttf"}
RTL = {"ara", "heb"}


def normalized(text):
    return "".join(c for c in unicodedata.normalize("NFC", text).casefold() if not c.isspace())


def control(tesseract, packs, fonts, languages):
    image = Image.new("L", (1500, 128 + 104 * len(languages)), 255)
    drawing = ImageDraw.Draw(image)
    for index, language in enumerate(languages):
        font = ImageFont.truetype(str(fonts / FONTS.get(language, "arial.ttf")), size=58)
        rtl = language in RTL
        drawing.text(
            (1440 if rtl else 60, 56 + 104 * index), SAMPLES[language], font=font,
            direction="rtl" if rtl else "ltr", anchor="ra" if rtl else "la", fill=0,
        )
    png = io.BytesIO()
    image.save(png, format="PNG", dpi=(300, 300))
    config = "+".join(languages)
    result = subprocess.run(
        [tesseract, "stdin", "stdout", "--tessdata-dir", str(packs), "-l", config, "--psm", "6"],
        input=png.getvalue(), capture_output=True, timeout=30,
    )
    # Tesseract may emit successful output after falling back to one of several requested packs.
    if result.returncode or b"failed loading language" in result.stderr.lower() or b"error opening data file" in result.stderr.lower():
        raise ValueError(f"Pack execution failed: {config} (exit {result.returncode})")
    expected = normalized(" ".join(SAMPLES[language] for language in languages))
    seen = normalized(result.stdout.decode("utf-8"))
    return {
        "configuration": config,
        "exact_normalized": seen == expected,
        "similarity": round(SequenceMatcher(None, expected, seen, autojunk=False).ratio(), 4),
        "characters": len(seen),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tesseract", help="Existing desktop executable")
    parser.add_argument("packs", type=Path, help="Directory containing the independently verified packs")
    parser.add_argument("fonts", type=Path, help="Local Windows Fonts directory (no fonts copied or distributed)")
    args = parser.parse_args()
    if not features.check("raqm"):
        raise SystemExit("Pillow with shaping support is required for these controls")
    for name in SAMPLES:
        if not (args.packs / (name + ".traineddata")).is_file():
            raise SystemExit(f"Missing pinned pack: {name}")
    checks = [(code,) for code in SAMPLES] + [("eng", "fra"), ("jpn", "eng"), ("ara", "eng")]
    results = [control(args.tesseract, args.packs, args.fonts, languages) for languages in checks]
    print(json.dumps({"scope": "desktop synthetic controls, not Android acceptance", "results": results}, indent=2))
    return 1 if any(result["characters"] == 0 for result in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
