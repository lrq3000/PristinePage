"""Exact two P-06 PNG fixtures, independently drawn without fonts, photographs or private metadata."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


class SyntheticMediaFixtures:
    width = 256
    height = 192
    glyphs = {"A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"), "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110")}

    @classmethod
    def pixels(cls, label):
        if label not in cls.glyphs:
            raise ValueError("Only the two approved fixture labels A/B are supported")
        background = (246, 242, 222) if label == "A" else (230, 245, 250)
        pixels = bytearray(bytes(background) * (cls.width * cls.height))

        def rectangle(left, top, right, bottom, color):
            row = bytes(color) * (right - left)
            for y in range(top, bottom):
                start = (y * cls.width + left) * 3
                pixels[start:start + len(row)] = row

        rectangle(0, 0, 24, 24, (220, 30, 30))
        rectangle(232, 0, 256, 24, (30, 160, 60))
        rectangle(0, 168, 24, 192, (30, 60, 220))
        rectangle(232, 168, 256, 192, (220, 170, 20))
        rectangle(76, 40, 180, 152, (255, 255, 255))
        rectangle(8, 52, 20, 136, (40, 40, 40))
        rectangle(20, 124, 52, 136, (40, 40, 40))
        for y, row in enumerate(cls.glyphs[label]):
            for x, bit in enumerate(row):
                if bit == "1":
                    rectangle(98 + x * 12, 54 + y * 12, 110 + x * 12, 66 + y * 12, (0, 0, 0))
        return bytes(pixels)

    @staticmethod
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    @classmethod
    def png(cls, label):
        pixels = cls.pixels(label)
        row_size = cls.width * 3
        scanlines = b"".join(b"\0" + pixels[y * row_size:(y + 1) * row_size] for y in range(cls.height))
        header = struct.pack(">IIBBBBB", cls.width, cls.height, 8, 2, 0, 0, 0)
        encoded = b"\x89PNG\r\n\x1a\n" + cls.chunk(b"IHDR", header) + cls.chunk(b"IDAT", zlib.compress(scanlines, level=9)) + cls.chunk(b"IEND", b"")
        if len(encoded) > 256 * 1024:
            raise ValueError("Fixture exceeds its approved byte limit")
        return encoded

    @classmethod
    def write_fresh(cls, directory):
        directory = Path(directory)
        # No overwrite or rollback deletion: a partial failed attempt stays visible, and retry requires a new absent operation directory.
        directory.mkdir()
        sources = []
        for label in ("A", "B"):
            name = "source-" + label.lower() + ".png"
            encoded = cls.png(label)
            with (directory / name).open("xb") as output:
                output.write(encoded)
            sources.append({"label": label, "filename": name, "bytes": len(encoded), "encoded_sha256": hashlib.sha256(encoded).hexdigest(), "packed_rgb_sha256": hashlib.sha256(cls.pixels(label)).hexdigest()})
        manifest = {"generator": "p06-synthetic-v1", "width": cls.width, "height": cls.height, "layout": "opaque encoded sRGB RGB8; no alpha, EXIF, text, GPS or other ancillary PNG chunks", "sources": sources}
        with (directory / "manifest.json").open("x", encoding="utf-8", newline="\n") as output:
            json.dump(manifest, output, indent=2)
            output.write("\n")
        return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output_directory", type=Path)
    options = parser.parse_args()
    print(json.dumps(SyntheticMediaFixtures.write_fresh(options.output_directory)))
