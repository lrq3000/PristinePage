"""Generate one new synthetic portable folder from the current exported Room DDL."""

from argparse import ArgumentParser
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess

from test_ocr_index import create_tables, sync_triggers


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "data/schemas/app.pristinepage.data.db.PristineDatabase/8.json"
LIBRARY = "9e940d7b-76c7-45ea-b5e4-0ad86f26ea13"
GENERATION = "25c49d9b-02d8-418f-b982-154980d89cfb"


def make_fixture(destination: Path):
    cli = shutil.which("sqlite3")
    if not cli:
        raise RuntimeError("The existing FTS4-capable SQLite CLI is required")
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
    commands = [*create_tables(schema["version"]), *sync_triggers(), *schema["setupQueries"],
                "PRAGMA user_version=8",
                "INSERT INTO Document VALUES ('4df08ec0-7f09-4a96-8cb1-1a6e675220da','Synthetic folder test',1,'ACTIVE',0,0,NULL)",
                "PRAGMA integrity_check", "PRAGMA foreign_key_check"]
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
    return {f"catalogs/{catalog.name}": digest, f"generations/{marker.name}": sha256(marker.read_bytes()).hexdigest()}


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    print(json.dumps(make_fixture(parser.parse_args().destination), sort_keys=True))
