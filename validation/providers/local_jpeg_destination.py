"""Fail-closed, API 28 DocumentsUI destination inspection; this module never confirms Save."""

import re
from urllib.parse import unquote


_PICKERS = {"com.android.documentsui", "com.google.android.documentsui"}
_DOWNLOADS = "com.android.providers.downloads.documents"
_ACTIVITY = re.compile(r"(?m)^  ACTIVITY (?P<component>[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+) [^\r\n]+$")
_SECTION = re.compile(r"(?m)^      (?:Active Fragments|Added Fragments|FragmentManager misc state)\b[^\r\n]*$")
_SLOT = re.compile(r"(?m)^        #\d+:[^\r\n]*$")
_DIRECTORY = re.compile(r"        #\d+: DirectoryFragment\{(?P<identity>[^}\r\n]+)}")
_ARGUMENTS = re.compile(
    r"(?m)^          mArguments=Bundle\[\{(?:selection=size=0, items=\[\], )?root=Root\{(?P<root>[^}\r\n]+)\} "
    r"@ (?P<root_uri>content://\S+), document=DocumentInfo\{(?P<document>[^}\r\n]+)\} @ "
    r"(?P<doc_uri>content://[^\s},]+)(?:, ignoreState=(?:true|false))?}]$"
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
    # The API 28 dump repeats the displayed instance under Added Fragments, without arguments.
    # Bind its exact identity to the active body rather than reading a hidden or detached fragment.
    sections = list(_SECTION.finditer(block))
    if (len(sections) != 3 or not re.fullmatch(r"      Active Fragments in [a-f0-9]+:", sections[0][0]) or
            sections[1][0] != "      Added Fragments:" or sections[2][0] != "      FragmentManager misc state:"):
        return False
    active = block[sections[0].end():sections[1].start()]
    added = block[sections[1].end():sections[2].start()]
    visible = [f["identity"] for slot in _SLOT.finditer(added) if (f := _DIRECTORY.fullmatch(slot[0]))]
    if len(visible) != 1:
        return False
    slots = list(_SLOT.finditer(active))
    matching = [j for j, slot in enumerate(slots) if (f := _DIRECTORY.fullmatch(slot[0])) and f["identity"] == visible[0]]
    if len(matching) != 1:
        return False
    j, = matching
    # Even an unknown class or a null slot delimits the body; never read another slot's arguments.
    body = active[slots[j].end():slots[j + 1].start() if j + 1 < len(slots) else len(active)]
    if (not re.search(r"(?m)^          mAdded=true mRemoving=false ", body) or
            not re.search(r"(?m)^          mHidden=false mDetached=false ", body)):
        return False
    arguments = list(_ARGUMENTS.finditer(body))
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
