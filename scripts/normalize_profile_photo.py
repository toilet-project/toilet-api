"""Bounded stdin -> WebP stdout. Never writes the provider image to disk."""
import io
import sys
import warnings

from PIL import Image, ImageOps


def normalize(data: bytes) -> bytes:
    if not data or len(data) > 2 * 1024 * 1024:
        raise ValueError("size")
    Image.MAX_IMAGE_PIXELS = 4_000_000
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(data)) as original:
            if original.format not in {"JPEG", "PNG", "WEBP"}:
                raise ValueError("format")
            if getattr(original, "n_frames", 1) != 1:
                raise ValueError("animation")
            if original.width * original.height > 4_000_000:
                raise ValueError("pixels")
            upright = ImageOps.exif_transpose(original)
            side = min(256, upright.width, upright.height)
            cropped = ImageOps.fit(upright, (side, side), method=Image.Resampling.LANCZOS)
            clean = Image.new("RGB", cropped.size, "white")
            if "A" in cropped.getbands():
                clean.paste(cropped, mask=cropped.getchannel("A"))
            else:
                clean.paste(cropped.convert("RGB"))
            output = io.BytesIO()
            clean.save(output, "WEBP", quality=80, method=4)
            result = output.getvalue()
            if len(result) > 100_000:
                raise ValueError("output")
            return result


if __name__ == "__main__":
    try:
        if sys.platform == "linux":
            import resource
            resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024,) * 2)
            resource.setrlimit(resource.RLIMIT_CPU, (4, 4))
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        sys.stdout.buffer.write(normalize(sys.stdin.buffer.read(2 * 1024 * 1024 + 1)))
    except Exception:
        # Do not log image bytes, source URLs, EXIF or provider identity.
        sys.exit(1)
