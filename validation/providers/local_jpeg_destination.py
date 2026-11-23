"""Fail-closed, API 28 DocumentsUI destination inspection; this module never confirms Save."""

import re
from urllib.parse import unquote


_PICKERS = {"com.android.documentsui", "com.google.android.documentsui"}
_DOWNLOADS = "com.android.providers.downloads.documents"
_ACTIVITY = re.compile(r"(?m)^  ACTIVITY (?P<component>[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+) [^\r\n]+$")
_FRAGMENT = re.compile(r"(?m)^        #\d+:\s+(?P<name>[A-Za-z]+)\{[^\r\n]+$")
_ARGUMENTS = re.compile(
    r"(?m)^          mArguments=Bundle\[\{[^\r\n]*\broot=Root\{(?P<root>[^}\r\n]+)\} "
    r"@ (?P<root_uri>content://\S+), document=DocumentInfo\{(?P<document>[^}\r\n]+)\} @ (?P<doc_uri>content://[^\s}]+)}]$"
)


def matches_local_downloads(dump, component, resolved_directory):
    """Authenticate the actual current DirectoryFragment, never a matching root title elsewhere in the dump."""
    pkg, sep, cls = component.partition("/")
    if (not sep or pkg not in _PICKERS or cls not in (".picker.PickActivity", f"{pkg}.picker.PickActivity") or
            not re.fullmatch(r"/(?:storage/emulated/0|mnt/shell/emulated/0)/Download/PristinePage-Export-[a-f0-9]{12}",
                             resolved_directory)):
        return False
    activities = list(_ACTIVITY.finditer(dump))
    candidates = [i for i, item in enumerate(activities) if item["component"] == component]
    if len(candidates) != 1:
        return False
    i, = candidates
    block = dump[activities[i].end():activities[i + 1].start() if i + 1 < len(activities) else len(dump)]
    fragments = list(_FRAGMENT.finditer(block))
    arguments = []
    for j, fragment in enumerate(fragments):
        if fragment["name"] == "DirectoryFragment":
            arguments.extend(_ARGUMENTS.finditer(block[fragment.end():fragments[j + 1].start() if j + 1 < len(fragments) else len(block)]))
    if len(arguments) != 1:
        return False
    entry, = arguments
    root = set(entry["root"].split(", "))
    document = set(entry["document"].split(", "))
    directory = resolved_directory.rsplit("/", 1)[1]
    return ({f"authority={_DOWNLOADS}", "rootId=downloads", "isUsb=false", "isSd=false", "isMtp=false"} <= root and
            entry["root_uri"] == f"content://{_DOWNLOADS}/root/downloads" and
            {f"docId=raw:{resolved_directory}", f"name={directory}", "mimeType=vnd.android.document/directory",
             "isContainer=true", "isDirectory=true", "isCreateSupported=true"} <= document and
            unquote(entry["doc_uri"]) == f"content://{_DOWNLOADS}/document/raw:{resolved_directory}")
