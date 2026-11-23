"""Rasterize the authored smoke references using installed fonts; never copies font files."""

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


# Each reference contains visible script text plus an exact identifier. The number is not the only recognition check.
PAGES = {
    "eng": (
        ("Workshop notice\nThe garden opens on Monday.\nReference 2846", "garden"),
        ("Market receipt\nFresh apples and bread\nReceipt 5731", "receipt"),
        ("Library bulletin\nReturn borrowed books on Friday.\nRecord 6928", "borrowed"),
        ("Delivery note\nPlease leave the parcel inside.\nParcel 3479", "parcel"),
        ("Meeting agenda\nDiscuss the winter schedule.\nMeeting 8164", "schedule"),
    ),
    "fra": (
        ("Avis de visite\nLe musée ouvre le lundi.\nRéférence 4537", "musée"),
        ("Ticket de caisse\nPommes fraîches et pain\nTicket 8263", "caisse"),
        ("Bulletin de quartier\nLes travaux commencent en été.\nDossier 5942", "travaux"),
        ("Note de livraison\nLe colis est arrivé ce matin.\nColis 7316", "livraison"),
        ("Ordre du jour\nPréparer le calendrier annuel.\nRéunion 2687", "calendrier"),
    ),
    "jpn": (
        ("図書館のお知らせ\n月曜日に本を返してください。\n番号 3827", "図書館"),
        ("お店の領収書\nりんごとパンを買いました。\n番号 6492", "領収書"),
        ("町の案内\n公園の入口はこちらです。\n番号 5274", "公園"),
        ("配達のお知らせ\n荷物は明日の朝に届きます。\n番号 9738", "荷物"),
        ("会議の予定\n来週の予定を話し合います。\n番号 4615", "来週"),
    ),
    "chi_sim": (
        ("图书馆通知\n请在星期一归还图书。\n编号 6153", "图书馆"),
        ("商店收据\n今天购买苹果和面包。\n单号 4872", "收据"),
        ("社区公告\n公园入口明天开放。\n编号 7634", "公园"),
        ("送货通知\n包裹将在明早送达。\n单号 2958", "送货通知"),
        ("会议安排\n请准备下周的工作计划。\n编号 5386", "计划"),
    ),
}
STYLES = ("clean", "receipt", "tint", "tilt", "perspective")
FONTS = {
    "eng": ("times.ttf", "consola.ttf", "calibri.ttf", "arial.ttf", "georgia.ttf"),
    "fra": ("georgia.ttf", "consola.ttf", "arial.ttf", "times.ttf", "calibri.ttf"),
    "jpn": ("YuGothM.ttc", "msgothic.ttc", "YuGothR.ttc", "YuGothM.ttc", "YuGothB.ttc"),
    "chi_sim": ("simsun.ttc", "msyh.ttc", "msyhl.ttc", "simsun.ttc", "msyhbd.ttc"),
}
SIZE = (1100, 480)


def render(font_path, text, style):
    receipt, tint = style == "receipt", style == "tint"
    background = (249, 243, 220) if tint else (248, 248, 246) if receipt else (255, 255, 255)
    ink = (110, 104, 94) if tint else (28, 30, 32)
    page = Image.new("RGB", SIZE, background)
    draw = ImageDraw.Draw(page)
    if tint:
        # A mild illumination ramp and faded ink, not added noise that would inflate fixture bytes.
        for x in range(SIZE[0]):
            shadow = round(28 * (1 - x / SIZE[0]) ** 2)
            draw.line((x, 0, x, SIZE[1]), fill=tuple(v - shadow for v in background))
    font = ImageFont.truetype(str(font_path), 39 if receipt else 48)
    for index, line in enumerate(text.splitlines()):
        x = 115 if receipt else 87
        y = (93 if receipt else 72) + (78 if receipt else 100) * index
        bounds = draw.textbbox((x, y), line, font=font)
        if bounds[2] >= SIZE[0] - 55 or bounds[3] >= SIZE[1] - 55:
            raise ValueError("A reference line does not fit its page")
        draw.text((x, y), line, font=font, fill=ink)
    if style == "tilt":
        page = page.rotate(1.5, resample=Image.Resampling.BICUBIC, fillcolor=background)
    if style == "perspective":
        # Pillow's inverse map gently tapers the page and tilts the baselines. Keep readable margins.
        page = page.transform(SIZE, Image.Transform.PERSPECTIVE, (1.015, .065, -15, -.032, 1.04, 3, .000025, -.000035),
                              resample=Image.Resampling.BICUBIC, fillcolor=background)
    return page


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("font_directory", type=Path)
    parser.add_argument("output", type=Path, help="A new directory, never an overwrite")
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Choose a new output directory")
    for name in {name for choices in FONTS.values() for name in choices}:
        if not (args.font_directory / name).is_file():
            raise ValueError(f"Missing installed font: {name}")
    args.output.mkdir(parents=True)
    references = []
    for language, pages in PAGES.items():
        for style, name, (text, anchor) in zip(STYLES, FONTS[language], pages, strict=True):
            id = f"{language}-{style}"
            path = args.output / f"{id}.png"
            render(args.font_directory / name, text, style).save(path, dpi=(300, 300), optimize=True)
            png = path.read_bytes()
            references.append(dict(id=id, language=language, style=style, font=name, anchor=anchor,
                                   identifier=text.split()[-1], text=text, bytes=len(png), sha256=hashlib.sha256(png).hexdigest()))
    (args.output / "references.json").write_text(json.dumps(references, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
