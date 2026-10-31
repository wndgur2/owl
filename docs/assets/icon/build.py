#!/usr/bin/env python3
"""Owl 앱 아이콘 생성기 — 마스터 도형 하나에서 Android / iOS / PWA 규격 파일을 전부 뽑아요.

    python3 docs/assets/icon/build.py

필요: rsvg-convert (macOS: `brew install librsvg`), Pillow (`pip install pillow`)

도형은 '수막새 부엉이 S1 확정본'(docs/assets/owl-icon.svg)과 같아요.
색은 Material 3 baseline 다크 롤에서 가져왔어요(04 '분홍 연꽃' 조합).
"""
import json
import math
import os
import shutil
import subprocess
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- 색 (M3 dark roles)
COLOR = dict(
    band="#36343b",   # SurfaceContainerHighest — 바깥 테 / 정사각 아이콘 배경
    dots="#cac4d0",   # OnSurfaceVariant        — 연주문
    field="#141218",  # Surface                 — 안쪽 바탕, 음각 디테일
    ring="#49454f",   # OutlineVariant          — 안쪽 가는 원
    owl="#e6e0e9",    # OnSurface               — 부엉이
    lotus="#efb8c8",  # Tertiary                — 연꽃
)
# iOS 18+ tinted 아이콘: 시스템이 색을 입히므로 흑백으로
TINTED = dict(band="#000000", dots="#8e8e8e", field="#000000", ring="#2a2a2a",
              owl="#ffffff", lotus="#c8c8c8")
# Android themed(monochrome) 레이어: 알파만 쓰임 → 흰색 = 남김, 검정 = 구멍(마스크)
MONO = dict(band=None, dots="#fff", field="#000", ring=None, owl="#fff", lotus="#fff")

# ---------------------------------------------------------------- 도형 (256 좌표계, 중심 128,128)
OWL_PATH = ("M90 52 L104 65 Q128 58 152 65 L166 52 C175 75 175 95 166 108 "
            "C182 128 184 158 164 178 C156 188 143 194 128 194 C113 194 100 188 92 178 "
            "C72 158 74 128 90 108 C81 95 81 75 90 52 Z")
DOT_RING_R, DOT_R, N_DOTS = 108, 5.6, 16
CONTENT_R = DOT_RING_R + DOT_R          # 연주문 바깥 끝 반지름 = 세이프존에 맞출 기준


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def petal(x, y, deg, L, W, fill, extra=""):
    return (f'<path fill="{fill}" {extra} transform="translate({f(x)} {f(y)}) rotate({f(deg)})" '
            f'd="M0 0 C{f(W)} {f(-L*0.3)} {f(W*0.7)} {f(-L*0.8)} 0 {f(-L)} '
            f'C{f(-W*0.7)} {f(-L*0.8)} {f(-W)} {f(-L*0.3)} 0 0 Z"/>')


def medallion(c, band_circle=True):
    """수막새 부엉이 본체. c = 색 dict, 값이 None이면 그 부위는 그리지 않음."""
    out = []
    if band_circle and c["band"]:
        out.append(f'<circle cx="128" cy="128" r="124" fill="{c["band"]}"/>')
    out.append(f'<g fill="{c["dots"]}">' + "".join(
        f'<circle cx="{f(128 + DOT_RING_R*math.cos(2*math.pi*i/N_DOTS - math.pi/2))}" '
        f'cy="{f(128 + DOT_RING_R*math.sin(2*math.pi*i/N_DOTS - math.pi/2))}" r="{DOT_R}"/>'
        for i in range(N_DOTS)) + "</g>")
    if c["band"] is not None:      # monochrome에서는 바탕 원을 그리지 않음(구멍이 되면 안 되니까)
        out.append(f'<circle cx="128" cy="128" r="94" fill="{c["field"]}"/>')
    if c["ring"]:
        out.append(f'<circle cx="128" cy="128" r="88" fill="none" stroke="{c["ring"]}" stroke-width="2"/>')
    out.append(f'<path d="{OWL_PATH}" fill="{c["owl"]}"/>')
    # 눈 둘레 깃(음각)
    for cx, side in ((110, -1), (146, 1)):
        for base in (30, 60, 90, 120, 150):
            ang = side * base
            a = math.radians(ang)
            out.append(petal(cx + 18*math.sin(a), 92 - 18*math.cos(a), ang, 10, 4, c["field"]))
    for cx in (110, 146):
        out.append(f'<circle cx="{cx}" cy="92" r="13" fill="{c["field"]}"/>')
        out.append(f'<circle cx="{cx}" cy="92" r="6.5" fill="{c["owl"]}"/>')
    out.append(petal(128, 108, 180, 15, 5.5, c["field"]))            # 부리
    for y, n in ((142, 3), (156, 2)):                                 # 가슴 비늘
        x0 = 128 - (n - 1) * 9
        out += [petal(x0 + i*18, y, 180, 12, 5.5, c["field"]) for i in range(n)]
    # 연꽃: 바탕색 테두리로 틈을 만들어 부엉이 앞에 오게, 가운데 잎이 맨 앞
    gap = f'stroke="{c["field"]}" stroke-width="5" stroke-linejoin="round" paint-order="stroke"'
    for d in (-58, 58, 0):
        L = 44 if d == 0 else 40
        a = math.radians(d)
        out.append(petal(128, 214, d, L, 12, c["lotus"], gap))
        out.append(petal(128 + 10*math.sin(a), 214 - 10*math.cos(a), d, L - 20, 2.6, c["field"]))
    return "\n".join(out)


def svg(inner, size=256, title="Owl"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
            f'role="img" aria-labelledby="t"><title id="t">{title}</title>\n{inner}\n</svg>\n')


def scaled(inner, k):
    """256 캔버스 중심 기준으로 k배."""
    return f'<g transform="translate(128 128) scale({k}) translate(-128 -128)">{inner}</g>'


def k_for(safe_radius_ratio):
    """연주문 바깥 끝이 캔버스 한 변 대비 safe_radius_ratio 안에 들어오게 하는 배율."""
    return safe_radius_ratio * 256 / CONTENT_R


# ---------------------------------------------------------------- 변형 SVG
def master():            # 원형, 투명 배경 — README·favicon.svg·PWA "any"
    return svg(medallion(COLOR))


def full_bleed(c, k):    # 정사각 꽉 찬 배경 — iOS, maskable, apple-touch, Play 스토어
    return svg(f'<rect width="256" height="256" fill="{c["band"]}"/>' +
               scaled(medallion(c, band_circle=False), k))


def adaptive_fg(k):      # Android adaptive 전경 — 배경은 @color로 따로
    return svg(scaled(medallion(COLOR, band_circle=False), k))


def adaptive_mono(k):    # Android 13+ themed icon — 알파 마스크
    return svg(f'<defs><mask id="m" maskUnits="userSpaceOnUse" x="0" y="0" width="256" height="256">'
               f'<rect width="256" height="256" fill="#000"/>{scaled(medallion(MONO, band_circle=False), k)}'
               f'</mask></defs><rect width="256" height="256" fill="#fff" mask="url(#m)"/>')


# Android adaptive: 108dp 캔버스, 마스크 모양과 상관없이 보이는 세이프존 = 지름 66dp
K_ADAPTIVE = k_for(33 / 108)
# PWA maskable: 세이프존 = 지름 80% 원 → 반지름 40%. 여유를 두고 38%
K_MASKABLE = k_for(0.38)
# iOS / Play 스토어 / apple-touch: 시스템이 둥근 사각(모서리 반경 ~22%)으로만 자름 → 원은 거의 꽉 채워도 안전
K_SQUARE = k_for(0.43)


# ---------------------------------------------------------------- 래스터화
def render(svg_text, path, px, opaque=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as t:
        t.write(svg_text)
    try:
        subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), t.name, "-o", path], check=True)
    finally:
        os.unlink(t.name)
    if opaque:  # App Store는 알파 채널이 있는 아이콘을 거부해요
        Image.open(path).convert("RGB").save(path, optimize=True)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)


def main():
    out = HERE
    for d in ("src", "android", "ios", "web"):
        shutil.rmtree(os.path.join(out, d), ignore_errors=True)

    src = {
        "master.svg": master(),
        "square.svg": full_bleed(COLOR, K_SQUARE),
        "maskable.svg": full_bleed(COLOR, K_MASKABLE),
        "square-tinted.svg": full_bleed(TINTED, K_SQUARE),
        "adaptive-foreground.svg": adaptive_fg(K_ADAPTIVE),
        "adaptive-monochrome.svg": adaptive_mono(K_ADAPTIVE),
    }
    for n, s in src.items():
        write(os.path.join(out, "src", n), s)

    # ---- Android (minSdk 31 → adaptive icon만 있으면 됨, 레거시 PNG 불필요)
    res = os.path.join(out, "android", "res")
    for dpi, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        px = int(108 * scale)
        render(src["adaptive-foreground.svg"], f"{res}/mipmap-{dpi}/ic_launcher_foreground.png", px)
        render(src["adaptive-monochrome.svg"], f"{res}/mipmap-{dpi}/ic_launcher_monochrome.png", px)
    adaptive_xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
'''
    write(f"{res}/mipmap-anydpi-v26/ic_launcher.xml", adaptive_xml)
    write(f"{res}/mipmap-anydpi-v26/ic_launcher_round.xml", adaptive_xml)
    write(f"{res}/values/ic_launcher_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- M3 dark SurfaceContainerHighest -->
    <color name="ic_launcher_background">{COLOR["band"].upper()}</color>
</resources>
''')
    render(src["square.svg"], os.path.join(out, "android", "play-store-512.png"), 512, opaque=True)

    # ---- iOS (Xcode 14+ 단일 1024 사이즈, iOS 18 dark / tinted 변형 포함)
    aset = os.path.join(out, "ios", "AppIcon.appiconset")
    render(src["square.svg"], f"{aset}/AppIcon-1024.png", 1024, opaque=True)
    render(src["square.svg"], f"{aset}/AppIcon-1024-dark.png", 1024, opaque=True)  # 이미 다크 톤
    render(src["square-tinted.svg"], f"{aset}/AppIcon-1024-tinted.png", 1024, opaque=True)
    contents = {"images": [
        {"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "dark"}],
         "filename": "AppIcon-1024-dark.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "tinted"}],
         "filename": "AppIcon-1024-tinted.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
    ], "info": {"author": "xcode", "version": 1}}
    write(f"{aset}/Contents.json", json.dumps(contents, indent=2) + "\n")

    # ---- Web / PWA
    web = os.path.join(out, "web")
    write(f"{web}/favicon.svg", src["master.svg"])
    for px in (192, 512):
        render(src["master.svg"], f"{web}/icon-{px}.png", px)
        render(src["maskable.svg"], f"{web}/icon-maskable-{px}.png", px, opaque=True)
    render(src["square.svg"], f"{web}/apple-touch-icon.png", 180, opaque=True)
    tmp = []
    for px in (16, 32, 48):
        p = f"{web}/_fav{px}.png"
        render(src["master.svg"], p, px)
        tmp.append(Image.open(p).convert("RGBA"))
    tmp[-1].save(f"{web}/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)], append_images=tmp[:-1])
    for px in (16, 32, 48):
        os.unlink(f"{web}/_fav{px}.png")
    manifest = {
        "name": "Owl", "short_name": "Owl",
        "start_url": "/", "display": "standalone",
        "background_color": COLOR["field"], "theme_color": COLOR["field"],
        "icons": [
            {"src": "icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
            {"src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
            {"src": "icon-maskable-192.png", "sizes": "192x192", "type": "image/png", "purpose": "maskable"},
            {"src": "icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
        ],
    }
    write(f"{web}/manifest.webmanifest", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    write(f"{web}/head.html", f'''<link rel="icon" href="/favicon.ico" sizes="48x48">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/manifest.webmanifest">
<meta name="theme-color" content="{COLOR["field"]}">
''')
    print("done →", out)


if __name__ == "__main__":
    main()
