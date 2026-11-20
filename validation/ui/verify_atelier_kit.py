"""Check the versioned kit against the approved Atelier reference without Gradle or network."""

import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
DESIGN = ROOT / "docs/design"
KIT = DESIGN / "atelier/v1"
SVG = "{http://www.w3.org/2000/svg}"


def check(condition, message):
    if not condition:
        raise ValueError(message)


def contrast(first, second):
    def luminance(color):
        channels = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
        linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
        return sum(w * c for w, c in zip((0.2126, 0.7152, 0.0722), linear))

    low, high = sorted((luminance(first), luminance(second)))
    return (high + 0.05) / (low + 0.05)


def main():
    tokens = json.loads((KIT / "tokens.json").read_text(encoding="utf-8"))
    colors = tokens["colors"]
    mockup = (ROOT / "docs/superpowers/mockups/first-major-redesign.html").read_text(encoding="utf-8")
    atelier = re.search(r"\.phone\[data-theme=C\]\{([^}]+)\}", mockup)[1]
    reference = dict(re.findall(r"--([a-z]+):([^;]+)", atelier))
    roles = {
        "background": "bg", "surface": "paper", "text": "ink", "textMuted": "muted",
        "divider": "line", "primary": "accent", "onPrimary": "on",
        "selectedSurface": "soft", "canvas": "canvas",
    }
    check(tokens["version"] == "1.0.0", "Unexpected Atelier v1 version")
    for role, css in roles.items():
        check(colors[role].lower() == reference[css].lower(), f"{role} drifted from Atelier")
    check(tokens["radiiDp"]["surface"] == int(reference["r"].removesuffix("px")), "Surface radius drift")
    check(tokens["sizesDp"]["touchMin"] >= 48, "Touch target is below 48dp")
    for foreground, background, minimum in (
        ("text", "background", 4.5), ("textMuted", "surface", 4.5),
        ("onPrimary", "primary", 4.5), ("text", "selectedSurface", 4.5),
        ("error", "surface", 4.5), ("controlOutline", "surface", 3), ("focus", "surface", 3),
    ):
        ratio = contrast(colors[foreground], colors[background])
        check(ratio >= minimum, f"Insufficient {foreground}/{background} contrast: {ratio:.2f}")
        print(f"{foreground}/{background}: {ratio:.2f}:1")

    mark = ET.parse(DESIGN / "brand/pristinepage-icon-v1.svg").getroot()
    check(mark.tag == SVG + "svg" and mark.get("viewBox") == "0 0 108 108", "Wrong icon canvas")
    check([node.tag for node in mark] == [SVG + name for name in ("title", "rect", "path")], "Unexpected icon layers")
    path = mark.find(SVG + "path")
    check(path.get("d") == re.search(r"""brand:'<path d="([^"]+)""", mockup)[1], "Brand path drift")
    check(mark.find(SVG + "rect").get("fill") == "#24463A" and path.get("stroke") == "#F3F5DE", "Brand color drift")
    check(not any("href" in key or "url(" in value or key.startswith("on") for node in mark.iter()
                  for key, value in node.attrib.items()), "The icon is not self-contained")

    docs = [ROOT / "docs/specs/ATELIER.md", *KIT.glob("*.md"), DESIGN / "brand/README.md"]
    for doc in docs:
        for link in re.findall(r"\]\(([^\s)]+)\)", doc.read_text(encoding="utf-8")):
            file = link.split("#", 1)[0]
            check(not file or "://" in file or (doc.parent / file).is_file(), f"{doc.name}: broken link {link}")
    print(f"Atelier kit: {len(docs)} documents; palette, brand, links and contrast verified.")


if __name__ == "__main__":
    main()
