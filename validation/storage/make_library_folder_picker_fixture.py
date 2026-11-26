"""Generate one new synthetic portable folder from the current exported Room DDL."""

from argparse import ArgumentParser
from hashlib import sha256
import json
from pathlib import Path
import shutil
from struct import pack
import subprocess
from zlib import compress, crc32

from test_ocr_index import BLOB, DOC, DRAFT, PAGE, SOURCE, create_tables, sync_triggers


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "data/schemas/app.pristinepage.data.db.PristineDatabase/8.json"
LIBRARY = "9e940d7b-76c7-45ea-b5e4-0ad86f26ea13"
GENERATION = "25c49d9b-02d8-418f-b982-154980d89cfb"


def synthetic_scan(alternate=False):
    """One 3×2 RGB image and two canonical revisions. The synthetic binding is not a mobile codec witness."""
    width, height = 3, 2
    rgb = bytes((19, 43, 71, 255, 250, 240, 211, 233, 191, 17, 90, 126, 50, 8, 200, 99, 123, 137))
    blob, source, page, transaction, filename = BLOB, SOURCE, PAGE, DRAFT, "Synthetic.png"
    if alternate:
        # Keep the album title and its identity, but make an independent scan and original for a missing-object transfer.
        blob, source, page, transaction = (f"daaaaaaa-aaaa-4aaa-8aaa-{number:012d}" for number in range(1, 5))
        filename = "Additional synthetic.png"
        rgb = bytes((23,)) + rgb[1:]

    def chunk(kind, data):
        return pack(">I", len(data)) + kind + data + pack(">I", crc32(kind + data))

    payload = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    # Force more than one bounded stream chunk without changing the six normalized pixels.
    payload += chunk(b"tEXt", b"Comment\0" + b"R" * 70_000)
    payload += chunk(b"IDAT", compress(b"\0" + rgb[:9] + b"\0" + rgb[9:])) + chunk(b"IEND", b"")
    digest = sha256(payload).hexdigest()
    fingerprint = b"srgb-v1"
    pixel = sha256(b"pristinepage-source-pixels-v1\0" + pack(">I", len(fingerprint)) + fingerprint +
                   pack(">IIQ", width, height, len(rgb)) + rgb).hexdigest()
    rows = [
        f"INSERT INTO SourceBlob VALUES ('{blob}','{digest}',{len(payload)},'image/png',3,2,'sources/{blob}/original.bin','READY',1,'{transaction}',0)",
        f"INSERT INTO Source VALUES ('{source}','{blob}','{pixel}',3,2,1,'srgb-v1',NULL,3,'READY',0)",
        f"INSERT INTO Page VALUES ('{page}','{DOC}','{source}',2,'ACTIVE',0,NULL,0)",
        f"INSERT INTO LibraryScanName VALUES ('{page}','{DOC}','{filename}','{filename.lower()}')",
        f"INSERT INTO SourceReference VALUES ('PAGE','{page}','{source}',0)",
    ]
    for turns in range(2):
        recipe = json.dumps(
            dict(appearance=dict(kind="none_v1"), binary=None, denoise=None, detail=None, schemaVersion=1,
                 geometry=dict(kind="none_v1", outputQuarterTurns=turns, schemaVersion=1,
                               source=dict(orientationColorConversionVersion=fingerprint.decode("ascii"),
                                           sourcePixelHash=pixel, sourceSize=dict(height=height, width=width)))),
            separators=(",", ":"), sort_keys=True,
        ).encode("ascii")
        revision = turns + 1
        recipe_digest = sha256(b"pristinepage-recipe-json-v1\0" + pack(">I", len(recipe)) + recipe).hexdigest()
        rows += [
            f"INSERT INTO RecipeRevision VALUES ('{page}',{revision},'{source}',1,X'{recipe.hex()}','{recipe_digest}',{'1' if turns else 'NULL'},0)",
            f"INSERT INTO SourceReference VALUES ('RECIPE_HISTORY','recipe-v1/{page}/{revision}','{source}',0)",
        ]
    return digest, payload, rows


def make_fixture(destination: Path, *, with_scan=False, alternate_scan=False):
    if alternate_scan and not with_scan:
        raise ValueError("The alternate fixture must contain a scan")
    cli = shutil.which("sqlite3")
    if not cli:
        raise RuntimeError("The existing FTS4-capable SQLite CLI is required")
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
    scan = synthetic_scan(alternate_scan) if with_scan else None
    commands = [*create_tables(schema["version"]), *sync_triggers(), *schema["setupQueries"],
                "PRAGMA user_version=8", "PRAGMA foreign_keys=ON", "BEGIN",
                f"INSERT INTO Document VALUES ('{DOC}','Synthetic folder test',{2 if scan else 1},'ACTIVE',0,0,NULL)",
                *(scan[2] if scan else []), "COMMIT", "PRAGMA integrity_check", "PRAGMA foreign_key_check"]
    destination.mkdir()
    catalogs = destination / "catalogs"
    generations = destination / "generations"
    catalogs.mkdir()
    generations.mkdir()
    catalog = catalogs / f"{GENERATION}.sqlite"
    result = subprocess.run([cli, "-batch", "-bail", str(catalog)], text=True, encoding="utf-8",
                            input=";\n".join(sql.strip().rstrip(";") for sql in commands) + ";\n",
                            capture_output=True, timeout=15, check=True)
    if result.stdout.strip() != "ok":
        raise RuntimeError("The synthetic catalog failed integrity or foreign-key checks")
    data = catalog.read_bytes()
    digest = sha256(data).hexdigest()
    marker = generations / f"{GENERATION}.complete"
    marker.write_bytes(f"pristinepage-library-v1\n{LIBRARY}\n{GENERATION}\n-\n8\n{len(data)}\n{digest}\n".encode("ascii"))
    hashes = {f"catalogs/{catalog.name}": digest, f"generations/{marker.name}": sha256(marker.read_bytes()).hexdigest()}
    if scan:
        objects = destination / "objects"
        objects.mkdir()
        object_digest, payload, _ = scan
        (objects / object_digest).write_bytes(payload)
        hashes[f"objects/{object_digest}"] = object_digest
        unowned = b"Unreferenced synthetic object"
        unowned_digest = sha256(unowned).hexdigest()
        (objects / unowned_digest).write_bytes(unowned)
        hashes[f"objects/{unowned_digest}"] = unowned_digest
    return hashes


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    print(json.dumps(make_fixture(parser.parse_args().destination), sort_keys=True))
