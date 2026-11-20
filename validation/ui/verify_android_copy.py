"""PristinePage ships English text only. Platform UI and user-entered content are out of scope."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
resources = ROOT / "app/src/main/res"

for folder in resources.glob("values-*"):
    qualifiers = folder.name.split("-")[1:]
    if any(
        (q.startswith("b+") and q.split("+", 2)[1] != "en")
        or (re.fullmatch("[a-z]{2,3}", q) and q not in {"en", "car"})
        for q in qualifiers
    ):
        for xml in folder.glob("*.xml"):
            if any(element.tag in {"string", "plurals", "string-array"} for element in ET.parse(xml).getroot()):
                raise SystemExit(f"Non-English app copy in {xml.relative_to(ROOT)}")

print("Android copy: no non-English application text resource sets.")
