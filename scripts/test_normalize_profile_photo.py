import io
import unittest
from PIL import Image
from scripts.normalize_profile_photo import normalize


class PhotoConversionTest(unittest.TestCase):
    def source(self, size=(600, 400), mode="RGB", kind="PNG"):
        stream = io.BytesIO()
        image = Image.new(mode, size, "red")
        exif = Image.Exif()
        exif[270] = "private description"
        image.save(stream, kind, exif=exif)
        return stream.getvalue()

    def test_derivative_only_square_and_no_metadata(self):
        for kind in ["JPEG", "PNG", "WEBP"]:
            result = normalize(self.source(kind=kind))
            with Image.open(io.BytesIO(result)) as photo:
                self.assertEqual(photo.format, "WEBP")
                self.assertEqual(photo.size, (256, 256))
                self.assertFalse(photo.getexif())
                self.assertNotIn("icc_profile", photo.info)
            self.assertLess(len(result), 100_000)

    def test_small_source_not_upscaled(self):
        with Image.open(io.BytesIO(normalize(self.source((64, 80))))) as photo:
            self.assertEqual(photo.size, (64, 64))

    def test_rejects_nonimages_and_oversized_sources(self):
        for invalid in [b"<svg></svg>", b"", b"x" * (2 * 1024 * 1024 + 1), self.source((2100, 2000))]:
            with self.assertRaises(Exception):
                normalize(invalid)

    def test_exif_rotation_before_crop(self):
        source = Image.new("RGB", (32, 64), "red")
        exif = Image.Exif(); exif[274] = 6
        stream = io.BytesIO(); source.save(stream, "JPEG", exif=exif)
        with Image.open(io.BytesIO(normalize(stream.getvalue()))) as result:
            self.assertEqual(result.size, (32, 32)); self.assertFalse(result.getexif())


if __name__ == "__main__":
    unittest.main()
