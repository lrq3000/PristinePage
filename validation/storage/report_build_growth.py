"""Report logical file bytes in selected build outputs. Reads only; never removes files."""

from argparse import ArgumentParser
from collections import defaultdict
import json
from pathlib import Path
import shutil
import stat


def linked(info):
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


def report(root, outputs):
    groups = defaultdict(int)
    skipped = []
    seen = set()
    for output in outputs:
        start = root
        for component in output.parts:
            start = start / component
            try:
                info = start.lstat()
            except FileNotFoundError:
                break  # A cold worktree may not have this output yet.
            if linked(info):
                skipped.append(start.relative_to(root).as_posix())
                break
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError(f"output directory expected: {output.as_posix()}")
        else:
            pending = [start]
            while pending:
                path = pending.pop()
                info = path.lstat()
                relative = path.relative_to(root)
                if linked(info):
                    skipped.append(relative.as_posix())
                elif stat.S_ISDIR(info.st_mode):
                    pending.extend(sorted(path.iterdir(), reverse=True))
                elif stat.S_ISREG(info.st_mode):
                    identity = (info.st_dev, info.st_ino)
                    if info.st_nlink > 1:
                        if not info.st_ino:
                            raise ValueError(f"cannot deduplicate linked file: {relative.as_posix()}")
                        if identity in seen:
                            continue
                        seen.add(identity)
                    group = (output / path.relative_to(start).parts[0]).as_posix()
                    groups[group] += info.st_size
    return {
        "root": root.as_posix(), "outputs": [path.as_posix() for path in outputs],
        "logical_bytes": dict(groups), "total_logical_bytes": sum(groups.values()),
        "skipped_links": sorted(skipped), "volume_free_bytes": shutil.disk_usage(root).free,
    }


def main():
    cli = ArgumentParser(description=__doc__)
    cli.add_argument("outputs", type=Path, nargs="+", help="non-overlapping output directories relative to the checkout")
    cli.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    cli.add_argument("--baseline", type=Path, help="previous JSON report for the same checkout and output paths")
    args = cli.parse_args()
    try:
        root = args.root.resolve(strict=True)
        outputs = sorted(args.outputs)
        for index, output in enumerate(outputs):
            if output.anchor or not output.parts or ".." in output.parts:
                raise ValueError(f"output is outside the approved checkout subdirectories: {output}")
            if any(output.is_relative_to(previous) for previous in outputs[:index]):
                raise ValueError(f"output directories overlap: {output}")
        current = report(root, outputs)
        if args.baseline:
            before = json.loads(args.baseline.read_text(encoding="utf-8"))
            if (before["root"], before["outputs"]) != (current["root"], current["outputs"]):
                raise ValueError("baseline belongs to another checkout or different output paths")
            old, new = before["logical_bytes"], current["logical_bytes"]
            current["delta_bytes"] = {key: new.get(key, 0) - old.get(key, 0) for key in sorted(old.keys() | new.keys())
                                      if new.get(key, 0) != old.get(key, 0)}
    except (OSError, ValueError, KeyError, TypeError) as error:
        cli.error(str(error))
    print(json.dumps(current, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
