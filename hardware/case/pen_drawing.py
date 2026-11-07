#!/usr/bin/env python3
"""hardware/case/pen-drawing.pdf 만들기: python3 hardware/case/pen_drawing.py

3D펜으로 케이스를 만들 때 쓰는 A4 도면 두 쪽: ① 치수와 순서, ② 따라 그리는 실제 크기 본.
치수는 owl-case.scad 위쪽 값에서 읽어서, 값을 바꾸면 이 도면도 같이 바뀌어요.
펜으로 못 만드는 곳(USB 구멍 위 얇은 벽, 걸림 돌기, 안쪽 바닥 둥글림)만 도면에서 다르게 그렸어요. 이유는 docs/decisions.md #51.
SVG(1 단위 = 1 mm)를 Chrome으로 PDF로 만들어요. Chrome이 없으면 SVG까지만 만들어요.
"""
import json
import math
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).parent
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
INK, GRAY, FILL, RED = "#1d1d1d", "#6b6b6b", "#dedad2", "#d62828"
SANS = "-apple-system, 'Segoe UI', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif"
PAGE_W, PAGE_H = 210, 297
ZOOM = 2.5       # ① 쪽 그림 배율
NAIL_H = 1.0     # 손톱 홈: 벽 맨 윗줄 한 겹 (출력 모델은 0.6)
TAB_LAP = 1.0    # 고리가 벽 두께 안으로 겹치는 길이
PEN_EDGE = 1.5   # 위아래 모서리를 사포로 가는 목표. 안쪽 바닥이 각져서 모델 edge_r까지 갈면 벽이 너무 얇아요

# ---------- owl-case.scad 값 ----------
V = {
    m[1]: json.loads(m[2])
    for m in re.finditer(r"^(\w+)\s*=\s*([\d.\[\], ]+);", (HERE / "owl-case.scad").read_text(encoding="utf-8"), re.M)
}
INNER, WALL, FLOOR, LID = V["inner"], V["wall"], V["floor_t"], V["lid_t"]
OX, OY, OZ = INNER[0] + 2 * WALL, INNER[1] + 2 * WALL, INNER[2] + FLOOR + LID
SPLIT = FLOOR + INNER[2]
R_OUT, R_IN = V["corner_r"], V["inner_r"]
BOARD_Y = (INNER[1] - V["board"][1]) / 2
USB_W, USB_H = V["usb_hole"]
USB_CY = WALL + BOARD_Y + V["board"][1] / 2
USB_Z0 = FLOOR + V["battery"][2] + V["board_foam"] + V["board"][2] + V["usb"][2] / 2 - USB_H / 2
LOOP_LEN, LOOP_W, LOOP_T = V["loop"]
LOOP_CX = OX + LOOP_LEN - LOOP_T / 2
LOOP_CZ = (OZ - LID) / 2
NAIL_X = (OX - 9, OX - 5)
SPLIT_INSET = PEN_EDGE - math.sqrt(PEN_EDGE ** 2 - (SPLIT - (OZ - PEN_EDGE)) ** 2)   # 몸통·뚜껑 경계에서 둥근 면이 들어간 길이
LIP_OUT = (INNER[0] - 2 * V["fit"], INNER[1] - 2 * V["fit"])
LIP_R = R_IN - V["fit"]
LIP_T, LIP_H = V["lip_t"], V["lip_h"]
LIP_CUT = (USB_CY - V["usb"][1] / 2 - 0.5, USB_CY + V["usb"][1] / 2 + 0.5)
CAP_STEM = V["btn_hole_d"] - 2 * V["cap_clear"]
CAP_FLANGE_D, CAP_FLANGE_T = V["cap_flange"]
CAP_H = CAP_FLANGE_T + LID + V["cap_proud"]
CAP_FLANGE_MAX = INNER[2] - V["battery"][2] - V["button"][2]   # 버튼 밑 폼을 없앴을 때 날개 최대 두께


def on_lid(p):
    """보드 좌표 → 바깥 좌표"""
    return p[0] + WALL, BOARD_Y + p[1] + WALL


HOLES = [
    ("A", "초록 LED", *on_lid(V["led_user"]), V["led_d"]),
    ("B", "충전 LED", *on_lid(V["led_charge"]), V["led_d"]),
    ("C", "마이크", *on_lid(V["mic"]), V["mic_d"]),
    ("D", "버튼", V["button_xy"][0] + WALL, V["button_xy"][1] + WALL, V["btn_hole_d"]),
]
LOOP_D = V["loop_hole_d"]


def loop_outline():
    """고리 옆모습(XZ): 몸통에서 나오는 넓은 쪽과 끝 원을 잇는 접선. (접점 x, 중심에서 접점 높이, x에서의 반높이를 구하는 함수)"""
    px, py, r = OX - R_OUT, LOOP_T / 2 + 1, LOOP_T / 2
    dx, dy = px - LOOP_CX, py
    d = math.hypot(dx, dy)
    a, h = r * r / d, r * math.sqrt(d * d - r * r) / d
    tx, ty = LOOP_CX + a * dx / d + h * dy / d, a * dy / d - h * dx / d
    if ty < 0 or tx < LOOP_CX - r:
        tx, ty = LOOP_CX + a * dx / d - h * dy / d, a * dy / d + h * dx / d
    return tx, ty, lambda x: py + (ty - py) * (x - px) / (tx - px)


TAN_X, TAN_Y, loop_half = loop_outline()


def loop_profile_d(px, pz, x0):
    """고리 옆모습 경로. px·pz는 mm → 쪽 좌표, x0은 몸통 쪽 끝. 위 접점에서 바깥을 돌아 아래 접점으로"""
    hw, r = loop_half(x0), abs(px(LOOP_T / 2) - px(0))
    large = 1 if TAN_X < LOOP_CX else 0
    return (f"M{px(x0):.3f} {pz(LOOP_CZ + hw):.3f}L{px(TAN_X):.3f} {pz(LOOP_CZ + TAN_Y):.3f}"
            f"A{r:.3f} {r:.3f} 0 {large} 1 {px(TAN_X):.3f} {pz(LOOP_CZ - TAN_Y):.3f}L{px(x0):.3f} {pz(LOOP_CZ - hw):.3f}Z")

# 벽 띠: 더미(안쪽 치수)에 바깥에서 볼 때 왼쪽→오른쪽으로 감는 순서. 이음매는 뒤쪽 긴 벽 가운데
LONG, SHORT, ARC = INNER[0] - 2 * R_IN, INNER[1] - 2 * R_IN, math.pi * R_IN / 2
BAND = [("긴 벽 뒤", LONG / 2), ("", ARC), ("USB 쪽 벽", SHORT), ("", ARC), ("긴 벽 앞 (마이크 쪽)", LONG), ("", ARC),
        ("고리 쪽 벽", SHORT), ("", ARC), ("긴 벽 뒤", LONG / 2)]
BAND_LEN = sum(w for _, w in BAND)
BAND_H = INNER[2]
S_USB = LONG / 2 + ARC + SHORT / 2
S_FRONT = LONG / 2 + ARC + SHORT + ARC
S_LOOP = S_FRONT + LONG + ARC + SHORT / 2

assert abs(BAND_LEN - (2 * (LONG + SHORT) + 2 * math.pi * R_IN)) < 1e-9, "벽 띠 길이가 둘레와 달라요"
assert USB_W / 2 <= SHORT / 2, "USB 구멍이 둥근 모서리에 걸려요"
assert USB_Z0 + USB_H <= SPLIT, "USB 구멍이 벽 위로 나가요"
assert LIP_T + V["fit"] + WALL < min(h[2] for h in HOLES[:3]) - V["led_d"] / 2, "립이 LED 구멍에 걸려요"

# ---------- 그리기 도구 ----------
out = []
add = out.append


def text(x, y, s, size=2.8, anchor="start", weight=400, fill=INK, rotate=None):
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    rot = f' transform="rotate({rotate} {x:.3f} {y:.3f})"' if rotate is not None else ""
    add(f'<text x="{x:.3f}" y="{y:.3f}" font-size="{size}" text-anchor="{anchor}" font-weight="{weight}" fill="{fill}"{rot}>{s}</text>')


def style(stroke=INK, w=0.25, fill="none", dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'stroke="{stroke}" stroke-width="{w}" fill="{fill}"{d}'


def line(x1, y1, x2, y2, **kw):
    add(f'<line x1="{x1:.3f}" y1="{y1:.3f}" x2="{x2:.3f}" y2="{y2:.3f}" {style(**kw)}/>')


def rect(x, y, rw, rh, **kw):
    add(f'<rect x="{x:.3f}" y="{y:.3f}" width="{rw:.3f}" height="{rh:.3f}" {style(**kw)}/>')


def circle(x, y, d, **kw):
    add(f'<circle cx="{x:.3f}" cy="{y:.3f}" r="{d / 2:.3f}" {style(**kw)}/>')


def path(d, **kw):
    add(f'<path d="{d}" {style(**kw)}/>')


def rrect_d(x, y, w, h, r):
    return (f"M{x + r:.3f} {y:.3f}H{x + w - r:.3f}A{r} {r} 0 0 1 {x + w:.3f} {y + r:.3f}V{y + h - r:.3f}"
            f"A{r} {r} 0 0 1 {x + w - r:.3f} {y + h:.3f}H{x + r:.3f}A{r} {r} 0 0 1 {x:.3f} {y + h - r:.3f}"
            f"V{y + r:.3f}A{r} {r} 0 0 1 {x + r:.3f} {y:.3f}Z")


def arrow(x, y, dx, dy, size=1.4):
    px, py = -dy * size * 0.3, dx * size * 0.3
    add(f'<path d="M{x:.3f} {y:.3f}L{x + dx * size + px:.3f} {y + dy * size + py:.3f}L{x + dx * size - px:.3f} {y + dy * size - py:.3f}Z" fill="{GRAY}"/>')


def hdim(x1, x2, y, label, ref=None):
    """x1–x2 가로 치수선. ref는 치수 보조선이 시작하는 y"""
    if ref is not None:
        for x in (x1, x2):
            line(x, ref, x, y + (0.8 if y > ref else -0.8), stroke=GRAY, w=0.12)
    line(x1, y, x2, y, stroke=GRAY, w=0.15)
    arrow(x1, y, 1, 0)
    arrow(x2, y, -1, 0)
    text((x1 + x2) / 2, y - 0.8, label, 2.6, "middle", fill=GRAY)


def vdim(y1, y2, x, label, ref=None):
    if ref is not None:
        for y in (y1, y2):
            line(ref, y, x + (0.8 if x > ref else -0.8), y, stroke=GRAY, w=0.12)
    line(x, y1, x, y2, stroke=GRAY, w=0.15)
    arrow(x, y1, 0, 1)
    arrow(x, y2, 0, -1)
    text(x - 0.8, (y1 + y2) / 2, label, 2.6, "middle", fill=GRAY, rotate=-90)


def leader(x1, y1, x2, y2, label, anchor="start", size=2.6, fill=INK):
    line(x1, y1, x2, y2, stroke=GRAY, w=0.15)
    text(x2 + {"start": 0.8, "end": -0.8, "middle": 0}[anchor], y2 + (0.9 if anchor != "middle" else (-0.8 if y2 < y1 else 2.6)), label, size, anchor, fill=fill)


def mark(x, y, d):
    """구멍 표시: 원과 십자"""
    circle(x, y, d, stroke=RED, w=0.2, fill="#fff")
    n = d / 2 + 0.8
    line(x - n, y, x + n, y, stroke=RED, w=0.1)
    line(x, y - n, x, y + n, stroke=RED, w=0.1)


def heading(n, title, sub):
    text(10, 15, title, 5.6, weight=700)
    text(10, 21, sub, 3)
    line(10, 24, PAGE_W - 10, 24, w=0.3)
    text(PAGE_W - 10, 15, f"Owl 케이스 · 3D펜 · {n}/2쪽", 2.8, "end", fill=GRAY)


def begin():
    out.clear()
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{PAGE_W}mm" height="{PAGE_H}mm" viewBox="0 0 {PAGE_W} {PAGE_H}" font-family="{SANS}">')
    add(f'<rect width="{PAGE_W}" height="{PAGE_H}" fill="#fff"/>')


def finish():
    add("</svg>")
    return "\n".join(out) + "\n"


# ---------- 단면 (px·pz는 mm → 쪽 좌표. 왼쪽이 0, 위로 갈수록 z가 커져요) ----------
def solid(px, pz, x0, x1, z0, z1):
    rect(px(x0), pz(z1), px(x1) - px(x0), pz(z0) - pz(z1), fill=FILL, w=0.25)


def edge_arc(px, pz):
    a = (px(1) - px(0)) * PEN_EDGE
    return lambda x, z, sweep: f"A{a:.3f} {a:.3f} 0 0 {sweep} {px(x):.3f} {pz(z):.3f}"


def lid_section(px, pz, length, cx):
    """뚜껑(버튼 구멍 양쪽)과 버튼 캡. cx는 버튼 중심"""
    arc, bd = edge_arc(px, pz), V["btn_hole_d"]
    path(f"M{px(cx - bd / 2):.3f} {pz(SPLIT):.3f}L{px(SPLIT_INSET):.3f} {pz(SPLIT):.3f}{arc(PEN_EDGE, OZ, 1)}L{px(cx - bd / 2):.3f} {pz(OZ):.3f}Z", fill=FILL, w=0.25)
    path(f"M{px(cx + bd / 2):.3f} {pz(OZ):.3f}L{px(length - PEN_EDGE):.3f} {pz(OZ):.3f}{arc(length - SPLIT_INSET, SPLIT, 1)}L{px(cx + bd / 2):.3f} {pz(SPLIT):.3f}Z", fill=FILL, w=0.25)
    solid(px, pz, cx - CAP_FLANGE_D / 2, cx + CAP_FLANGE_D / 2, SPLIT - CAP_FLANGE_T, SPLIT)
    solid(px, pz, cx - CAP_STEM / 2, cx + CAP_STEM / 2, SPLIT, SPLIT + LID + V["cap_proud"])


def lip_section(px, pz, x_out):
    """립 하나. x_out은 벽 쪽 면"""
    solid(px, pz, x_out - LIP_T, x_out, SPLIT - LIP_H, SPLIT)


def long_section(px, pz):
    """가–가: 긴 방향, 폭 가운데(버튼·줄 구멍). 왼쪽이 USB 쪽"""
    arc, e = edge_arc(px, pz), PEN_EDGE
    # 몸통: USB 쪽 벽 위에서 바깥을 따라 바닥, 고리 쪽 벽 위까지 갔다가 안쪽으로 돌아옴 (위는 트임)
    path(f"M{px(0):.3f} {pz(USB_Z0):.3f}L{px(0):.3f} {pz(e):.3f}{arc(e, 0, 0)}L{px(OX - e):.3f} {pz(0):.3f}{arc(OX, e, 0)}"
         f"L{px(OX):.3f} {pz(OZ - e):.3f}{arc(OX - SPLIT_INSET, SPLIT, 0)}L{px(OX - WALL):.3f} {pz(SPLIT):.3f}"
         f"L{px(OX - WALL):.3f} {pz(FLOOR):.3f}L{px(WALL):.3f} {pz(FLOOR):.3f}L{px(WALL):.3f} {pz(USB_Z0):.3f}Z", fill=FILL, w=0.25)
    lid_section(px, pz, OX, HOLES[3][2])
    lip_section(px, pz, OX - WALL - V["fit"])
    path(loop_profile_d(px, pz, OX), fill=FILL, w=0.25)
    circle(px(LOOP_CX), pz(LOOP_CZ), (px(1) - px(0)) * LOOP_D, stroke=RED, w=0.25, fill="#fff")


def width_section(px, pz):
    """나–나: 폭 방향, 버튼 중심 X(손톱 홈도 지나요). USB 쪽에서 본 모습이라 오른쪽이 마이크 쪽"""
    arc, e = edge_arc(px, pz), PEN_EDGE
    # 몸통: 왼쪽 벽 위에서 바깥을 따라 바닥, 오른쪽 벽의 손톱 홈 아래까지 갔다가 안쪽으로 돌아옴
    path(f"M{px(WALL):.3f} {pz(SPLIT):.3f}L{px(SPLIT_INSET):.3f} {pz(SPLIT):.3f}{arc(0, OZ - e, 0)}L{px(0):.3f} {pz(e):.3f}{arc(e, 0, 0)}"
         f"L{px(OY - e):.3f} {pz(0):.3f}{arc(OY, e, 0)}L{px(OY):.3f} {pz(SPLIT - NAIL_H):.3f}L{px(OY - WALL):.3f} {pz(SPLIT - NAIL_H):.3f}"
         f"L{px(OY - WALL):.3f} {pz(FLOOR):.3f}L{px(WALL):.3f} {pz(FLOOR):.3f}Z", fill=FILL, w=0.25)
    lid_section(px, pz, OY, OY / 2)
    lip_section(px, pz, WALL + V["fit"] + LIP_T)
    lip_section(px, pz, OY - WALL - V["fit"])


# ---------- 1쪽: 치수와 순서 ----------
def top_view(ox, oy):
    s = ZOOM
    px = lambda x: ox + s * x
    py = lambda y: oy + s * (OY - y)
    text(ox, oy - 12.5, "위에서 본 모습 (뚜껑 바깥 면)", 3.2, weight=700)
    rect(px(OX - R_OUT), py(OY / 2 + LOOP_W / 2), s * (R_OUT + LOOP_LEN), s * LOOP_W, fill="#fff", w=0.3)
    path(rrect_d(px(0), py(OY), s * OX, s * OY, s * R_OUT), fill="#fff", w=0.35)
    path(rrect_d(px(WALL), py(OY - WALL), s * INNER[0], s * INNER[1], s * R_IN), stroke=GRAY, w=0.15, dash="1.2 0.8")
    for tag, _, x, y, d in HOLES:
        mark(px(x), py(y), s * d)
        text(px(x) + s * d / 2 + 0.6, py(y) - s * d / 2 - 0.3, tag, 2.8, weight=700, fill=RED)
    # 줄 구멍: 양옆으로 뚫려서 위에서는 숨은선
    for x in (LOOP_CX - LOOP_D / 2, LOOP_CX + LOOP_D / 2):
        line(px(x), py(OY / 2 + LOOP_W / 2), px(x), py(OY / 2 - LOOP_W / 2), stroke=RED, w=0.2, dash="1 0.6")
    text(px(LOOP_CX), py(OY / 2 + LOOP_W / 2) - 0.9, "E", 2.8, "middle", weight=700, fill=RED)
    # 단면 위치
    line(px(-1.5), py(OY / 2), px(OX + LOOP_LEN + 2.5), py(OY / 2), stroke=GRAY, w=0.12, dash="3 0.8 0.6 0.8")
    text(px(-1.5), py(OY / 2) - 1, "가", 2.8, "middle", fill=GRAY)
    text(px(OX + LOOP_LEN + 3), py(OY / 2) + 1, "가", 2.8, fill=GRAY)
    nx = HOLES[3][2]
    line(px(nx), py(OY + 0.8), px(nx), py(-1.5), stroke=GRAY, w=0.12, dash="3 0.8 0.6 0.8")
    text(px(nx) + 0.8, py(OY) - 0.6, "나", 2.8, fill=GRAY)
    text(px(nx), py(-1.5) + 2.8, "나–나 단면은 2쪽", 2.5, "middle", fill=GRAY)
    hdim(px(0), px(OX), oy - 3, f"{OX:g}", ref=py(OY) + s * R_OUT)
    hdim(px(0), px(OX + LOOP_LEN), oy - 8, f"고리까지 {OX + LOOP_LEN:g}", ref=oy - 3)
    vdim(py(0), py(OY), ox - 9, f"{OY:g}", ref=px(R_OUT))
    leader(px(R_OUT * 0.3), py(R_OUT * 0.3), px(-1), py(-3.2), f"모서리 R{R_OUT:g} (안쪽 R{R_IN:g})", "start", fill=GRAY)
    text(px(WALL) + 5, py(WALL) - 2.2, f"점선 = 안쪽 {INNER[0]:g} × {INNER[1]:g}", 2.4, fill=GRAY)


def hole_table(x, y):
    text(x, y, "구멍 자리", 3.2, weight=700)
    cols = [x, x + 6, x + 25, x + 36, x + 47]
    y += 5
    for c, h in zip(cols, ["", "무엇", "X", "Y", "지름"]):
        text(c, y, h, 2.5, fill=GRAY)
    line(x, y + 1, x + 60, y + 1, stroke=GRAY, w=0.15)
    for tag, name, hx, hy, d in HOLES:
        y += 5
        text(cols[0], y, tag, 2.8, weight=700, fill=RED)
        text(cols[1], y, name, 2.8)
        for c, v in zip(cols[2:], [hx, hy, d]):
            text(c, y, f"{v:.1f}", 2.8)
    y += 5
    text(cols[0], y, "E", 2.8, weight=700, fill=RED)
    text(cols[1], y, "줄 구멍", 2.8)
    for c, v in zip(cols[2:], [f"{LOOP_CX:.1f}", f"Z {LOOP_CZ:.1f}", f"{LOOP_D:.1f}"]):
        text(c, y, v, 2.8)
    y += 6
    for s in [f"X는 USB 쪽 끝, Y는 그림 아랫변에서 재요.",
              f"E만 양옆(Y 방향)으로 뚫어요. Z는 바닥에서 재요.",
              "A·B·C는 보드 실측 전 추정값이에요.",
              f"C 안쪽 지름 {V['mesh_d']:g}는 방진망 붙일 자리라",
              "평평하게 남겨요."]:
        text(x, y, s, 2.5, fill=GRAY)
        y += 3.8


def section_view(ox, oy):
    s = ZOOM
    px = lambda x: ox + s * x
    pz = lambda z: oy + s * (OZ - z)
    text(ox, oy - 3.5, "단면 가–가 (앞에서 본 모습)", 3.2, weight=700)
    long_section(px, pz)
    bx = HOLES[3][2]
    vdim(pz(0), pz(OZ), ox - 5, f"{OZ:g}", ref=px(0))
    hdim(px(WALL), px(OX - WALL), pz(3), f"안쪽 {INNER[0]:g}")
    vdim(pz(FLOOR), pz(SPLIT), px(14), f"안쪽 {INNER[2]:g}")
    text(px(2.2), pz(9.4), "USB 트임", 2.6, fill=RED)
    leader(px(OX - WALL - 0.5), pz(SPLIT - LIP_H / 2), px(OX - 7.5), pz(6.6), f"립 {LIP_T:g} × {LIP_H:g}", "end", fill=GRAY)
    leader(px(bx - 1.5), pz(SPLIT - CAP_FLANGE_T / 2), px(bx - 4.5), pz(8.8), "버튼 캡", "end", fill=GRAY)
    text(px(OX) + 0.8, pz(LOOP_CZ - LOOP_T / 2) + 3.4, f"줄 구멍 ⌀{LOOP_D:g}", 2.5, fill=RED)
    text(px(OX / 2), pz(0) + 3.2, f"바닥 {FLOOR:g} · 벽 {WALL:g} · 뚜껑 {LID:g}", 2.5, "middle", fill=GRAY)
    leader(px(0.4), pz(0.4), px(0.4), pz(0) + 2.2, f"위아래 R{PEN_EDGE:g}", "start", fill=GRAY)


def usb_view(ox, oy):
    s = ZOOM
    px = lambda y: ox + s * y
    pz = lambda z: oy + s * (OZ - z)
    text(ox, oy - 3.5, "USB 쪽 끝", 3.2, weight=700)
    path(rrect_d(px(0), pz(OZ), s * OY, s * OZ, s * PEN_EDGE), fill="#fff", w=0.35)
    line(px(SPLIT_INSET), pz(SPLIT), px(OY - SPLIT_INSET), pz(SPLIT), w=0.2)
    y0, y1, r = OY / 2 - USB_W / 2, OY / 2 + USB_W / 2, USB_H / 2
    add(f'<path d="M{px(y0):.3f} {pz(SPLIT):.3f}V{pz(USB_Z0 + r):.3f}A{s * r} {s * r} 0 0 0 {px(y0 + r):.3f} {pz(USB_Z0):.3f}'
        f'H{px(y1 - r):.3f}A{s * r} {s * r} 0 0 0 {px(y1):.3f} {pz(USB_Z0 + r):.3f}V{pz(SPLIT):.3f}" {style(stroke=RED, w=0.3, fill="#fff")}/>')
    hdim(px(y0), px(y1), pz(0) + 4, f"{USB_W:g}", ref=pz(USB_Z0 + r))
    hdim(px(0), px(OY), pz(0) + 9, f"{OY:g}", ref=pz(0))
    vdim(pz(0), pz(USB_Z0), px(y0) - 3, f"{USB_Z0:g}", ref=px(y0 + r))
    text(px(OY / 2), pz(SPLIT) + 3.6, "위까지 트임", 2.5, "middle", fill=RED)


def front_view(ox, oy):
    s = ZOOM
    px = lambda x: ox + s * x
    pz = lambda z: oy + s * (OZ - z)
    text(ox, oy - 3.5, "앞에서 본 모습 (마이크 쪽 긴 면)", 3.2, weight=700)
    path(loop_profile_d(px, pz, OX - TAB_LAP), fill="#fff", w=0.3)
    path(rrect_d(px(0), pz(OZ), s * OX, s * OZ, s * PEN_EDGE), fill="#fff", w=0.35)
    circle(px(LOOP_CX), pz(LOOP_CZ), s * LOOP_D, stroke=RED, w=0.25, fill="#fff")
    line(px(SPLIT_INSET), pz(SPLIT), px(OX - SPLIT_INSET), pz(SPLIT), w=0.2)
    rect(px(NAIL_X[0]), pz(SPLIT), s * (NAIL_X[1] - NAIL_X[0]), s * NAIL_H, stroke=RED, w=0.25)
    hdim(px(NAIL_X[0]), px(NAIL_X[1]), pz(SPLIT) + 6, f"{NAIL_X[1] - NAIL_X[0]:g}", ref=pz(SPLIT - NAIL_H))
    hdim(px(0), px(NAIL_X[0]), pz(SPLIT) + 6, f"{NAIL_X[0]:g}", ref=pz(0) - 0.01)
    text(px(NAIL_X[0] + 2), pz(SPLIT - NAIL_H) + 9.5, "손톱 홈", 2.5, "middle", fill=RED)
    vdim(pz(0), pz(SPLIT), ox - 5, f"몸통 {SPLIT:g}", ref=px(0))
    vdim(pz(0), pz(LOOP_CZ), px(OX + LOOP_LEN) + 3.5, f"{LOOP_CZ:g}", ref=px(LOOP_CX))
    vdim(pz(LOOP_CZ - LOOP_T / 2), pz(LOOP_CZ + LOOP_T / 2), px(OX + LOOP_LEN) + 7.5, f"⌀{LOOP_T:g}", ref=px(LOOP_CX))


def differences(x, y):
    text(x, y, "출력 모델과 다른 점", 3.2, weight=700)
    y += 5
    for s in [
        "· 벽·판 두께 1.0은 목표예요. 두꺼워지면",
        f"  바깥을 갈아요. 안쪽 {INNER[0]:g} × {INNER[1]:g}은 그대로.",
        f"· USB 구멍 위 벽({SPLIT - USB_Z0 - USB_H:.1f})은 펜으로 못 만들어",
        "  위까지 터 두고, 뚜껑으로 덮어요.",
        "· 걸림 돌기·홈은 빼고 립 마찰로 닫아요.",
        f"· 안쪽 바닥 둥글림 R{V['inner_floor_r']:g}은 빼요. 그래서 위아래",
        f"  모서리(모델 R{V['edge_r']:g})는 사포로 R{PEN_EDGE:g}까지만 갈아요.",
        f"· 손톱 홈은 윗줄 한 겹({NAIL_H:g})을 비워요.",
    ]:
        text(x, y, s, 2.6)
        y += 4.2


def steps(x, y):
    text(x, y, "만드는 순서", 3.6, weight=700)
    y += 5.6
    for head, s in [
        ("준비", f"2쪽을 배율 100%로 인쇄하고 50 mm 막대를 자로 재요. 지우개를 {INNER[0]:g} × {INNER[1]:g} × {INNER[2]:g}, 모서리 R{R_IN:g}로 깎아 더미를 만들어요."),
        ("① 바닥", "본 위에 투명 파일을 덮고(떼기 쉽게) 바깥선까지 채워요. 파일에 닿는 면이 매끈해요."),
        ("② 벽", "띠를 오려 글자가 보이게 더미에 감고 마스킹테이프로 덮어요. 바닥 판 점선 위에 얹고 아래부터 한 줄씩 쌓아요."),
        ("", "USB 칸은 비우고 손톱 홈은 맨 윗줄만 비워요. 식으면 더미를 빼고 USB-C 플러그가 끝까지 들어가는지 봐요(구멍 둘레 벽은 1 mm 이하로)."),
        ("③ 뚜껑", "매끈한 면이 바깥이 되도록 안쪽 면 그림 위에 그려요. 버튼 구멍은 비우고, 몸통에 맞춰 보며 회색 립을 올려요."),
        ("④ 고리", f"지름 2 철사를 심으로 감아 폭 {LOOP_W:g}, 지름 {LOOP_T:g}까지 쌓아요. 심을 빼고, 구멍이 양옆으로 나도록 고리 쪽 벽 표시에 붙여요."),
        ("⑤ 캡", f"날개를 그린 뒤 가운데 기둥을 높이 {CAP_H:g}까지 올려요. 날개는 {CAP_FLANGE_MAX:g} 이하(두꺼우면 버튼 밑 폼을 줄여요)."),
        ("구멍", "A·B·C는 채운 뒤 보드를 넣어 위치를 재고, 드릴(1 mm)로 뚫어요. 안쪽에 방진망을 붙이고 폼테이프로 막지 않아요."),
    ]:
        if head:
            text(x, y, head, 2.8, weight=700)
        text(x + 13, y, s, 2.7)
        y += 4.7
    text(x, y + 0.6, "주의: 부품 위에 바로 그리지 않아요(노즐 180°C 이상, LiPo 근처 위험). 조립 전에 케이스만 따로 완성해요.", 2.8, weight=700, fill=RED)


def page1():
    begin()
    heading(1, "3D펜 케이스 도면 — 치수와 순서", f"단위 mm. 그림은 {ZOOM:g}배 확대라 자로 재지 말고 숫자를 봐요. 따라 그리는 본은 2쪽이에요.")
    top_view(27, 42)
    hole_table(140, 34)
    section_view(27, 124)
    usb_view(140, 124)
    front_view(27, 176)
    differences(140, 180)
    steps(10, 230)
    return finish()


# ---------- 2쪽: 실제 크기 본 ----------
def scale_bar(x, y):
    rect(x, y, 50, 2, w=0.2)
    for i in range(0, 50, 20):
        rect(x + i, y, 10, 2, fill=INK, w=0)
    text(x, y - 1, "0", 2.4, "middle")
    text(x + 50, y - 1, "50 mm", 2.4, "middle")
    text(x + 25, y + 5, "자로 재서 50 mm가 아니면 다시 인쇄", 2.4, "middle", fill=RED)


def floor_tpl(x, y, labels=True):
    path(rrect_d(x, y, OX, OY, R_OUT), w=0.3)
    path(rrect_d(x + WALL, y + WALL, INNER[0], INNER[1], R_IN), stroke=GRAY, w=0.15, dash="1 0.7")
    if labels:
        text(x, y - 3, "① 바닥", 3.4, weight=700)
        text(x + OX / 2, y + OY / 2 - 1, "바깥선까지 채우기", 2.4, "middle", fill=GRAY)
        text(x + OX / 2, y + OY / 2 + 2.6, f"점선 = 벽·더미 자리", 2.4, "middle", fill=GRAY)
        text(x + OX / 2, y + OY + 4, f"{OX:g} × {OY:g}, 두께 {FLOOR:g}", 2.4, "middle", fill=GRAY)


def lid_tpl(x, y, labels=True):
    """뚜껑 안쪽 면: 매끈한 바깥 면이 종이에 닿으니 위에서 본 모습과 위아래가 뒤집혀 있어요"""
    path(rrect_d(x, y, OX, OY, R_OUT), w=0.3)
    lx, ly = x + WALL + V["fit"], y + WALL + V["fit"]
    ring = rrect_d(lx, ly, *LIP_OUT, LIP_R) + rrect_d(lx + LIP_T, ly + LIP_T, LIP_OUT[0] - 2 * LIP_T, LIP_OUT[1] - 2 * LIP_T, LIP_R - LIP_T)
    add(f'<path d="{ring}" fill="{FILL}" fill-rule="evenodd" stroke="{GRAY}" stroke-width="0.12"/>')
    rect(x + WALL - 0.01, y + LIP_CUT[0], V["fit"] + LIP_T + 0.2, LIP_CUT[1] - LIP_CUT[0], stroke="none", fill="#fff")
    a, b, c, d = HOLES[:4]
    circle(x + c[2], y + c[3], V["mesh_d"], stroke=GRAY, w=0.15, dash="0.8 0.6")
    for h in (a, b, c):
        mark(x + h[2], y + h[3], h[4])
    mark(x + d[2], y + d[3], d[4])
    if labels:
        text(x, y - 11, "③ 뚜껑과 립", 3.4, weight=700)
        text(x + 28, y - 11, "안쪽 면에서 본 모습이라 1쪽 그림과 위아래가 반대예요", 2.4, fill=GRAY)
        leader(x + a[2], y + a[3] - 1, x - 1, y - 3.5, "A 초록 LED", "middle", size=2.3)
        leader(x + c[2], y + c[3] - 1, x + 20, y - 3.5, "C 마이크 + 방진망 자리", "middle", size=2.3)
        leader(x + b[2], y + b[3] + 1, x - 1, y + OY + 2.5, "B 충전 LED", "middle", size=2.3)
        leader(x + d[2] + 2, y + d[3], x + OX + 3, y + d[3], f"D 버튼 ⌀{d[4]:g} 비우기", "start", size=2.3)
        leader(x + 24, y + OY - WALL - 0.6, x + 24, y + OY + 2.5, f"회색 = 립 {LIP_T:g} × {LIP_H:g}", "middle", size=2.3)
        text(x - 2, y + OY / 2 + 0.8, "USB", 2.3, "end", fill=GRAY)
        text(x + 37, y + OY / 2 + 5.5, "고리 쪽", 2.3, fill=GRAY)
        text(x + 37, y + 3, "A·B·C는 채운 뒤", 2.3, fill=GRAY)
        text(x + 37, y + 6.2, "실측해서 드릴로", 2.3, fill=GRAY)


def band_tpl(x, y, labels=True):
    rect(x + BAND_LEN, y, 5, BAND_H, stroke=GRAY, w=0.15, fill="#efefef")
    text(x + BAND_LEN + 2.5, y + BAND_H / 2 + 0.8, "풀칠", 2.2, "middle", fill=GRAY)
    rect(x, y, BAND_LEN, BAND_H, w=0.3)
    s = 0
    for name, w in BAND:
        if name:
            ty = y + BAND_H / 2 + 0.8 if "앞" in name else y + 2.3 if "고리" in name else y + BAND_H - 1.5
            text(x + s + w / 2, ty, name, 2.3, "middle", fill=GRAY)
        else:
            rect(x + s, y, w, BAND_H, stroke="none", fill="#efefef")
            line(x + s, y, x + s, y + BAND_H, stroke=GRAY, w=0.12, dash="0.8 0.6")
            line(x + s + w, y, x + s + w, y + BAND_H, stroke=GRAY, w=0.12, dash="0.8 0.6")
        s += w
    assert abs(s - BAND_LEN) < 1e-9
    rect(x, y, BAND_LEN, BAND_H, w=0.3)
    # USB 칸 (위까지 트임)
    r, zb = USB_H / 2, USB_Z0 - FLOOR
    x0, x1, yb = x + S_USB - USB_W / 2, x + S_USB + USB_W / 2, y + BAND_H - zb
    path(f"M{x0:.3f} {y:.3f}V{yb - r:.3f}A{r} {r} 0 0 0 {x0 + r:.3f} {yb:.3f}H{x1 - r:.3f}A{r} {r} 0 0 0 {x1:.3f} {yb - r:.3f}V{y:.3f}",
         stroke=RED, w=0.3, fill="#fff")
    line(x0, y, x1, yb, stroke=RED, w=0.12)
    line(x1, y, x0, yb, stroke=RED, w=0.12)
    # 손톱 홈
    nx0 = x + S_FRONT + NAIL_X[0] - WALL - R_IN
    rect(nx0, y, NAIL_X[1] - NAIL_X[0], NAIL_H, stroke=RED, w=0.25, fill="#fff")
    # 고리 붙는 자리
    hw = loop_half(OX)
    lz = LOOP_CZ - FLOOR - hw
    rect(x + S_LOOP - LOOP_W / 2, y + BAND_H - lz - 2 * hw, LOOP_W, 2 * hw, stroke=INK, w=0.2, dash="1 0.6")
    if labels:
        text(x, y - 9, "② 벽 띠", 3.4, weight=700)
        text(x + 15, y - 9, "오려서 글자가 보이게 더미에 감아요. 아래 변이 바닥 쪽이에요. 회색 칸이 둥근 모서리예요.", 2.4, fill=GRAY)
        leader(x + S_USB, y, x + S_USB, y - 3.5, "USB 칸 비우기", "middle", size=2.3, fill=RED)
        leader(nx0 + 2, y, nx0 + 2, y - 3.5, "손톱 홈: 윗줄만 비우기", "middle", size=2.3, fill=RED)
        leader(x + S_LOOP, y + BAND_H - lz, x + S_LOOP, y + BAND_H + 3, "고리 붙는 자리", "middle", size=2.3)
        text(x, y + BAND_H + 4, f"길이 {BAND_LEN:.1f}, 높이 {BAND_H:g}. 이음매는 뒤쪽 긴 벽 가운데", 2.4, fill=GRAY)


def loop_tpl(x, y, labels=True):
    """고리 옆모습. x는 벽 바깥 면(OX) 위치, 벽 쪽으로 TAB_LAP만큼 겹쳐요"""
    cy, x0, hw = y + LOOP_T / 2 + 1, OX - TAB_LAP, loop_half(OX - TAB_LAP)
    m = lambda v: x + v - OX
    path(loop_profile_d(m, lambda z: cy + LOOP_CZ - z, x0), w=0.3)
    line(m(OX), cy - loop_half(OX), m(OX), cy + loop_half(OX), stroke=GRAY, w=0.12, dash="0.8 0.6")
    mark(m(LOOP_CX), cy, LOOP_D)
    if labels:
        text(x - TAB_LAP, y - 3, "④ 고리", 3.4, weight=700)
        text(x + 8, y + 1, f"옆모습이에요. 이 모양 그대로 폭 {LOOP_W:g}만큼 뻗어요", 2.4, fill=GRAY)
        text(x + 8, y + 4.4, f"구멍 ⌀{LOOP_D:g}은 양옆으로 (지름 2 철사를 심으로)", 2.4, fill=GRAY)
        text(x + 8, y + 7.8, "점선까지 벽 두께 안으로 겹쳐 붙여요", 2.4, fill=GRAY)


def cap_tpl(x, y, labels=True):
    cx, cy = x + CAP_FLANGE_D / 2, y + CAP_FLANGE_D / 2
    circle(cx, cy, CAP_FLANGE_D, w=0.3)
    circle(cx, cy, CAP_STEM, stroke=GRAY, w=0.15, dash="0.8 0.6")
    if labels:
        text(x, y - 3, "⑤ 버튼 캡", 3.4, weight=700)
        text(x + 9, y + 1.4, f"날개 ⌀{CAP_FLANGE_D:g}, 두께 {CAP_FLANGE_T:g}", 2.4, fill=GRAY)
        text(x + 9, y + 4.8, f"점선 기둥 ⌀{CAP_STEM:g}, 전체 높이 {CAP_H:g}", 2.4, fill=GRAY)


def sections_tpl(x, y):
    text(x, y - 4, "⑥ 단면", 3.4, weight=700)
    text(x + 15, y - 4, "실제 크기예요. 만든 케이스가 식으면 이 그림에 대 보면서 사포로 둥글기와 높이를 맞춰요.", 2.4, fill=GRAY)
    wx = x + 58
    long_section(lambda v: x + v, lambda z: y + OZ - z)
    width_section(lambda v: wx + v, lambda z: y + OZ - z)
    text(x, y + OZ + 4, "가–가 긴 방향 (왼쪽이 USB 쪽)", 2.4, fill=GRAY)
    text(wx, y + OZ + 4, "나–나 폭 방향 (오른쪽이 마이크 쪽)", 2.4, fill=GRAY)
    tx = wx + OY + 12
    for i, t in enumerate([f"위아래 모서리 R{PEN_EDGE:g}", f"전체 높이 {OZ:g}, 몸통 {SPLIT:g}", f"안쪽 {INNER[0]:g} × {INNER[1]:g} × {INNER[2]:g}", f"손톱 홈은 나–나 오른쪽 위"]):
        text(tx, y + 2 + i * 3.6, t, 2.4, fill=GRAY)


def page2():
    begin()
    heading(2, "3D펜 케이스 도면 — 실제 크기 본", "배율 100%(실제 크기)로 인쇄해요. 맞춤 인쇄·페이지에 맞춤을 끄고, 본 위에 투명 파일을 덮고 그려요.")
    scale_bar(148, 31)
    floor_tpl(14, 56)
    lid_tpl(80, 56)
    band_tpl(14, 102)
    loop_tpl(15, 132)
    cap_tpl(105, 133)
    sections_tpl(14, 155)
    line(10, 177, PAGE_W - 10, 177, stroke=GRAY, w=0.15, dash="2 1.2")
    text(10, 183, "여분: 실패하거나 뚜껑·띠를 다시 만들 때 써요.", 3, weight=700)
    floor_tpl(14, 188, False)
    lid_tpl(60, 188, False)
    floor_tpl(106, 188, False)
    lid_tpl(152, 188, False)
    band_tpl(14, 218, False)
    band_tpl(14, 234, False)
    loop_tpl(15, 250, False)
    loop_tpl(40, 250, False)
    loop_tpl(65, 250, False)
    cap_tpl(90, 251, False)
    cap_tpl(105, 251, False)
    cap_tpl(120, 251, False)
    text(10, PAGE_H - 28, "색·기호", 2.8, weight=700)
    text(10, PAGE_H - 23.5, "굵은 선 = 바깥 끝까지 채우는 선   ·   점선 = 안쪽 기준선(벽·립·기둥 자리)   ·   빨강 = 구멍·비우는 곳", 2.5, fill=GRAY)
    text(10, PAGE_H - 19.5, f"치수는 owl-case.scad 값에서 만들어요. A·B·C 위치와 USB 높이는 부품 실측 전 추정값이에요. 다시 만들기: python3 hardware/case/pen_drawing.py", 2.3, fill=GRAY)
    return finish()


# ---------- 내보내기 ----------
def to_pdf(pages, dest):
    if not Path(CHROME).exists():
        print("Chrome이 없어 PDF는 건너뛰어요")
        return
    html = "<!doctype html><meta charset='utf-8'><style>@page{size:210mm 297mm;margin:0}html,body{margin:0}svg{display:block;break-after:page}svg:last-child{break-after:auto}</style>" + "".join(pages)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "pen.html"
        src.write_text(html, encoding="utf-8")
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", f"--user-data-dir={tmp}/profile",
                        f"--print-to-pdf={tmp}/pen.pdf", src.as_uri()], check=True, capture_output=True)
        shutil.copy(f"{tmp}/pen.pdf", dest)
    print(f"{dest.name} 저장")


pages = [page1(), page2()]
for i, svg in enumerate(pages, 1):
    (HERE / f"pen-drawing-{i}.svg").write_text(svg, encoding="utf-8")
to_pdf(pages, HERE / "pen-drawing.pdf")
