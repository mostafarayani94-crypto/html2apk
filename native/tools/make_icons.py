#!/usr/bin/env python3
"""بيولّد أيقونات PNG بسيطة (من غير مكتبات) للقالب والتطبيق الباني. الاستخدام: make_icons.py <res-dir> <hex-color>"""
import struct, sys, zlib, os

def png(size, bg, fg):
    rows = []
    r = size * 0.22  # نصف قطر الزوايا
    c = size / 2
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            # مربع بزوايا مدورة
            dx, dy = abs(x + .5 - c), abs(y + .5 - c)
            half = size / 2 - 1
            inside = dx <= half and dy <= half
            if inside and dx > half - r and dy > half - r:
                inside = (dx - (half - r)) ** 2 + (dy - (half - r)) ** 2 <= r * r
            if not inside:
                row += bytes([0, 0, 0, 0]); continue
            # علبة بيضاء بسيطة في النص
            bx, by = abs(x + .5 - c), abs(y + .5 - c)
            box = bx < size * .22 and by < size * .22
            border = box and (bx > size * .17 or by > size * .17)
            lid = bx < size * .22 and size * -.22 <= (y + .5 - c) < size * -.1
            col = fg if (border or lid) else bg
            row += bytes(col + (255,))
        rows.append(bytes(row))
    raw = b''.join(rows)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')

res, hexcol = sys.argv[1], sys.argv[2].lstrip('#')
bg = tuple(int(hexcol[i:i + 2], 16) for i in (0, 2, 4))
for d, s in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    p = os.path.join(res, 'mipmap-' + d)
    os.makedirs(p, exist_ok=True)
    open(os.path.join(p, 'ic_launcher.png'), 'wb').write(png(s, bg, (255, 255, 255)))
print('icons ok')
