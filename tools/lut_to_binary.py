#!/usr/bin/env python3
"""Converts the bundled .cube looks into the app's compact .lut assets.

    python3 tools/lut_to_binary.py tools/luts recorder/src/main/assets/creative/luts

The .cube files under tools/luts are the sources (with their licence notices);
the app ships the .lut files this writes. A .lut holds exactly what
CubeLut.parse makes of the .cube -- every value rounded to 8 bits the way the
app rounds it -- so the app reads the same look from a sixth of the bytes,
without parsing text. CubeLutAssetsTest checks that for every bundled look.

Format (big-endian):
    "TLUT"            magic
    u8   1            version
    u8   N            points per side, 2..33
    u16  len, UTF-8   name: the TITLE line, empty when there is none
    u16  len, UTF-8   notice: the file's leading comment lines (licence, source)
    N^3 x (r, g, b)   one byte each, in file order, red changing fastest
"""
import math
import pathlib
import re
import struct
import sys


def f32(x):
    """Rounds a Python float to the nearest float32, as Kotlin's Float does."""
    return struct.unpack(">f", struct.pack(">f", x))[0]


def to_byte(token):
    # Kotlin: (value.coerceIn(0f, 1f) * 255f).roundToInt(), all in float32;
    # roundToInt is Math.round: nearest, halves up.
    value = f32(float(token))
    if not math.isfinite(value):
        raise ValueError(f"not a number: {token}")
    value = min(max(value, 0.0), 1.0)
    return int(math.floor(f32(value * f32(255.0)) + 0.5))


def convert(text):
    text = text.lstrip("\ufeff")
    size = 0
    name = ""
    notice = []
    colors = bytearray()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") and size == 0 and not colors:
            notice.append(stripped.lstrip("#").strip())
        clean = line.split("#", 1)[0].strip()
        if not clean:
            continue
        tokens = re.split(r"\s+", clean)
        if tokens[0] == "TITLE":
            name = clean.split(" ", 1)[1].strip().strip('"')[:80]
        elif tokens[0] == "LUT_3D_SIZE":
            if size or len(tokens) != 2:
                raise ValueError("bad LUT_3D_SIZE")
            size = int(tokens[1])
            if not 2 <= size <= 33:
                raise ValueError("unsupported size")
        elif tokens[0] in ("DOMAIN_MIN", "DOMAIN_MAX"):
            expected = 0.0 if tokens[0] == "DOMAIN_MIN" else 1.0
            if len(tokens) != 4 or any(f32(float(t)) != expected for t in tokens[1:]):
                raise ValueError("unsupported domain")
        else:
            if not size or len(colors) >= 3 * size ** 3 or len(tokens) != 3:
                raise ValueError(f"unexpected line: {line!r}")
            colors.extend(to_byte(t) for t in tokens)
    if not size or len(colors) != 3 * size ** 3:
        raise ValueError("incomplete cube")
    name_bytes = name.encode("utf-8")
    notice_bytes = "\n".join(notice).encode("utf-8")
    return (
        b"TLUT"
        + struct.pack(">BB", 1, size)
        + struct.pack(">H", len(name_bytes)) + name_bytes
        + struct.pack(">H", len(notice_bytes)) + notice_bytes
        + bytes(colors)
    )


def main(source, target):
    source = pathlib.Path(source)
    target = pathlib.Path(target)
    target.mkdir(parents=True, exist_ok=True)
    for cube in sorted(source.glob("*.cube")):
        data = convert(cube.read_text(encoding="utf-8"))
        out = target / (cube.stem + ".lut")
        out.write_bytes(data)
        print(f"{cube.name}: {cube.stat().st_size} bytes -> {out.name}: {len(data)} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
