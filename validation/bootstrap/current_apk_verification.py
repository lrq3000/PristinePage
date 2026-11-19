"""Parse supplied aapt2 badging text; this metadata is not an APK verification receipt."""

import re


_PACKAGE_ID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
_PACKAGE_FIELD = re.compile(r"[ \t]+([A-Za-z][A-Za-z0-9_]*)='([^'\x00-\x1f\x7f]*)'")
_ROW_LABEL = re.compile(r"[ \t]*([\w.-]+)")
_TEST_PACKAGE = "app.pristinepage.debug.test"


def _package_fields(line: str) -> dict[str, str]:
    if not line.startswith("package:"):
        raise ValueError("malformed package row")
    fields = {}
    offset = len("package:")
    # Consume every character, so a valid prefix cannot hide a malformed or repeated assignment.
    while offset < len(line):
        match = _PACKAGE_FIELD.match(line, offset)
        if not match:
            raise ValueError("malformed package field")
        key, value = match.groups()
        if key in fields:
            raise ValueError(f"duplicate package field: {key}")
        fields[key] = value
        offset = match.end()
    return fields


def parse_badging(
    text: str, *, package: str, min_sdk: int, target_sdk: int
) -> dict[str, str | int | None]:
    """Return the matching package and SDK levels plus observed version fields; refuse missing or ambiguous metadata."""
    if type(text) is not str or type(package) is not str:
        raise TypeError("text and package must be strings")
    if type(min_sdk) is not int or type(target_sdk) is not int:
        raise TypeError("min_sdk and target_sdk must be integers")
    if not _PACKAGE_ID.fullmatch(package) or not 0 < min_sdk <= target_sdk <= 0x7FFF_FFFF:
        raise ValueError("invalid expected package or SDK levels")

    text = text.replace("\r\n", "\n")
    if "\r" in text or "\0" in text:
        raise ValueError("invalid badging line encoding")
    required = ("package", "minSdkVersion", "targetSdkVersion")
    rows = {}
    for line in text.split("\n"):
        # Find the whole label even without a colon, but retain the original line for strict validation.
        label = _ROW_LABEL.match(line)
        if label and (key := label[1]) in required:
            if key in rows:
                raise ValueError(f"duplicate badging row: {key}")
            rows[key] = line
    if len(rows) != len(required):
        raise ValueError("missing package, minSdkVersion, or targetSdkVersion row")
    for key, expected in (("minSdkVersion", min_sdk), ("targetSdkVersion", target_sdk)):
        if rows[key] != f"{key}:'{expected}'":
            raise ValueError(f"malformed or mismatched {key} row")

    fields = _package_fields(rows["package"])
    if fields.get("name") != package:
        raise ValueError("package does not match expected artifact")
    name = fields.get("versionName")
    code = fields.get("versionCode")
    if package == _TEST_PACKAGE and name == code == "":
        # aapt2 prints both keys with empty values for the retained AndroidTest APK.
        name = version_code = None
    elif name and code and re.fullmatch(r"[0-9]{1,10}", code) and int(code) <= 0xFFFF_FFFF:
        version_code = int(code)
    else:
        raise ValueError("missing or malformed package version fields")
    return dict(package=package, version_name=name, version_code=version_code, min_sdk=min_sdk, target_sdk=target_sdk)
