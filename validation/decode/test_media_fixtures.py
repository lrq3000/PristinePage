"""Memory-only PNG/identity checks for the exact two approved P-06 synthetic inputs."""

import importlib
from pathlib import Path
import struct
import unittest
import zlib


class MediaFixturesTest(unittest.TestCase):
    def api(self):
        try:
            return importlib.import_module("media_fixtures").SyntheticMediaFixtures
        except ImportError:
            self.fail("Bounded P-06 fixture generator is absent")

    def decode(self, png):
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        offset = 8
        chunks = []
        while offset < len(png):
            size = struct.unpack(">I", png[offset:offset + 4])[0]
            kind = png[offset + 4:offset + 8]
            data = png[offset + 8:offset + 8 + size]
            crc = struct.unpack(">I", png[offset + 8 + size:offset + 12 + size])[0]
            self.assertEqual(crc, zlib.crc32(kind + data) & 0xFFFFFFFF)
            chunks.append((kind, data))
            offset += size + 12
        self.assertEqual(offset, len(png))
        self.assertEqual([kind for kind, data in chunks], [b"IHDR", b"IDAT", b"IEND"])
        self.assertEqual(struct.unpack(">IIBBBBB", chunks[0][1]), (256, 192, 8, 2, 0, 0, 0))
        raw = zlib.decompress(chunks[1][1])
        self.assertEqual(len(raw), (256 * 3 + 1) * 192)
        self.assertTrue(all(raw[y * 769] == 0 for y in range(192)))
        pixels = b"".join(raw[y * 769 + 1:(y + 1) * 769] for y in range(192))
        return pixels

    def test_two_pngs_have_bounded_opaque_rgb_payload_and_no_private_metadata(self):
        for label in ("A", "B"):
            png = self.api().png(label)
            self.assertLessEqual(len(png), 256 * 1024)
            self.decode(png)

    def test_asymmetric_corner_colors_remain_distinct_pixel_identities(self):
        pixels = self.decode(self.api().png("A"))
        for (x, y), expected in [((0, 0), (220, 30, 30)), ((255, 0), (30, 160, 60)), ((0, 191), (30, 60, 220)), ((255, 191), (220, 170, 20))]:
            offset = (y * 256 + x) * 3
            self.assertEqual(tuple(pixels[offset:offset + 3]), expected)

    def test_distinct_labels_have_distinct_reproducible_decoded_and_encoded_bytes(self):
        api = self.api()
        a, b = api.png("A"), api.png("B")
        self.assertNotEqual(a, b)
        self.assertNotEqual(self.decode(a), self.decode(b))
        self.assertEqual(a, api.png("A"))
        self.assertEqual(b, api.png("B"))

    def test_unknown_label_refuses_instead_of_generating_extra_variations(self):
        for label in ("", "C", "../A", "a"):
            with self.assertRaises(ValueError):
                self.api().png(label)

    def test_fresh_writer_refuses_existing_directory_without_overwriting_anything(self):
        with self.assertRaises(FileExistsError):
            self.api().write_fresh(Path(__file__).parent)


if __name__ == "__main__":
    unittest.main()
