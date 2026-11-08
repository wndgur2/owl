#!/usr/bin/env python3
"""hardware/case/print-drawing.pdf 만들기: python3 hardware/case/print_drawing.py

3D 출력 모델(owl-case.scad)의 치수 도면, A4 가로 두 쪽: ① 몸통, ② 뚜껑과 버튼 캡.
선 모양은 OpenSCAD로 모델을 투상·단면해서 그대로 가져오고, 치수 숫자는 owl-case.scad 위쪽 값에서 읽어요.
그래서 값을 바꾸면 이 도면도 같이 바뀌어요. SVG(1 단위 = 1 mm)를 Chrome으로 PDF로 만들어요.
필요: OpenSCAD 2025 이후, Chrome(없으면 SVG까지만).
"""
import base64
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).parent
SCAD = HERE / "owl-case.scad"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
INK, GRAY, RED = "#1d1d1d", "#6b6b6b", "#c62828"
SANS = "-apple-system, 'Segoe UI', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif"
PAGE_W, PAGE_H = 297, 210
TMP = Path(tempfile.mkdtemp())

# ---------- owl-case.scad 값 ----------
V = {
    m[1]: json.loads(m[2])
    for m in re.finditer(r"^(\w+)\s*=\s*([\d.\[\], ]+);", SCAD.read_text(encoding="utf-8"), re.M)
}
INNER, WALL, FLOOR, LID = V["inner"], V["wall"], V["floor_t"], V["lid_t"]
OX, OY, OZ = INNER[0] + 2 * WALL, INNER[1] + 2 * WALL, INNER[2] + FLOOR + LID
SPLIT = FLOOR + INNER[2]
R_OUT, R_IN, EDGE = V["corner_r"], V["inner_r"], V["edge_r"]
FIT = V["fit"]
BOARD_Y = (INNER[1] - V["board"][1]) / 2
USB_W, USB_H = V["usb_hole"]
USB_CY = WALL + BOARD_Y + V["board"][1] / 2
USB_CZ = FLOOR + V["battery"][2] + V["board_foam"] + V["board"][2] + V["usb"][2] / 2
LOOP_LEN, LOOP_W, LOOP_T = V["loop"]
LOOP_D, LOOP_CH = V["loop_hole_d"], V["loop_chamfer"]
LOOP_CX, LOOP_CZ = OX + LOOP_LEN - LOOP_T / 2, (OZ - LID) / 2
NAIL = (OX - 9, OX - 5, 0.6)                       # 손톱 홈 x 시작·끝, 깊이·높이
SNAP_X = (WALL + INNER[0] / 2 - 5, WALL + INNER[0] / 2 + 5)
BUMP_X = (WALL + INNER[0] / 2 - 4, WALL + INNER[0] / 2 + 4)
LIP_T, LIP_H = V["lip_t"], V["lip_h"]
LIP_OUT = (INNER[0] - 2 * FIT, INNER[1] - 2 * FIT)
LIP_CUT = (USB_CY - V["usb"][1] / 2 - 0.5, USB_CY + V["usb"][1] / 2 + 0.5)
CAP_FD, CAP_FT = V["cap_flange"]
CAP_STEM = V["btn_hole_d"] - 2 * V["cap_clear"]
CAP_H = CAP_FT + LID + V["cap_proud"]


def on_lid(p):
    """보드 좌표 → 바깥 좌표"""
    return p[0] + WALL, BOARD_Y + p[1] + WALL


BTN = (V["button_xy"][0] + WALL, V["button_xy"][1] + WALL)
DIMPLE_D = V["btn_hole_d"] + 2.4
HOLES = [  # 표시, 이름, x, y, 지름, 비고
    ("A", "초록 LED", *on_lid(V["led_user"]), V["led_d"], "관통 *"),
    ("B", "충전 LED", *on_lid(V["led_charge"]), V["led_d"], "관통 *"),
    ("C", "마이크", *on_lid(V["mic"]), V["mic_d"], f"관통 *, 안쪽 ⌀{V['mesh_d']:g} 깊이 {V['mesh_depth']:g}"),
    ("D", "버튼", *BTN, V["btn_hole_d"], f"관통, 바깥 오목 ⌀{DIMPLE_D:g} 깊이 0.3"),
]


# ---------- OpenSCAD에서 모양 가져오기 ----------
def scad2d(expr):
    """2D가 되는 OpenSCAD 식 → 윤곽선 목록 [(u, v), ...]. 모델 모듈은 owl-case.scad에서 가져와요"""
    src, out = TMP / "v.scad", TMP / "v.svg"
    src.write_text(f"use <{SCAD}>\n$fn = 96;\n{expr}\n", encoding="utf-8")
    subprocess.run(["openscad", "--backend=manifold", "-o", str(out), str(src)], check=True, capture_output=True)
    d = re.search(r'<path d="([^"]*)"', out.read_text()).group(1)
    return [[(float(a), -float(b)) for a, b in re.findall(r"(-?[\d.e+-]+),(-?[\d.e+-]+)", sub)]
            for sub in d.split("M")[1:]]


def picture(expr, camera):
    """부품 그림 PNG → data URI"""
    src, out = TMP / "p.scad", TMP / "p.png"
    src.write_text(f"use <{SCAD}>\n$fn = 64;\n{expr}\n", encoding="utf-8")
    subprocess.run(["openscad", "--backend=manifold", "--render", "-o", str(out), "--imgsize=900,700", "--viewall", "--autocenter",
                    "--colorscheme=Tomorrow", f"--camera={camera}", str(src)], check=True, capture_output=True)
    return "data:image/png;base64," + base64.b64encode(out.read_bytes()).decode()


FRONT = "rotate([-90, 0, 0])"                                    # (x, z): 앞(-Y)에서 본 모습
SIDE = "multmatrix([[0, 1, 0, 0], [0, 0, 1, 0], [1, 0, 0, 0]])"  # (y, z): USB 쪽(-X)에서 본 모습


def cut_y(y, part):
    return f"projection(cut = true) {FRONT} translate([0, {-y}, 0]) {part}"


def cut_x(x, part):
    return f"projection(cut = true) {SIDE} translate([{-x}, 0, 0]) {part}"


def cut_z(z, part):
    return f"projection(cut = true) translate([0, 0, {-z}]) {part}"


# ---------- 그리기 도구 ----------
out = []
add = out.append


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;")


def text(x, y, s, size=2.5, anchor="start", weight=400, fill=INK, rotate=None):
    rot = f' transform="rotate({rotate} {x:.3f} {y:.3f})"' if rotate is not None else ""
    add(f'<text x="{x:.3f}" y="{y:.3f}" font-size="{size}" text-anchor="{anchor}" font-weight="{weight}" fill="{fill}"{rot}>{esc(s)}</text>')


def style(stroke=INK, w=0.35, fill="none", dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'stroke="{stroke}" stroke-width="{w}" fill="{fill}" stroke-linejoin="round"{d}'


def line(x1, y1, x2, y2, **kw):
    add(f'<line x1="{x1:.3f}" y1="{y1:.3f}" x2="{x2:.3f}" y2="{y2:.3f}" {style(**kw)}/>')


def path(d, **kw):
    add(f'<path d="{d}" {style(**kw)} fill-rule="evenodd"/>')


def circle(x, y, d, **kw):
    add(f'<circle cx="{x:.3f}" cy="{y:.3f}" r="{d / 2:.3f}" {style(**kw)}/>')


HIDDEN = dict(stroke=INK, w=0.2, dash="1.2 0.6")
CENTER = dict(stroke=GRAY, w=0.13, dash="3 0.6 0.5 0.6")
THIN = dict(stroke=INK, w=0.18)


def arrow(x, y, dx, dy, size=1.6):
    px, py = -dy * size * 0.22, dx * size * 0.22
    add(f'<path d="M{x:.3f} {y:.3f}L{x + dx * size + px:.3f} {y + dy * size + py:.3f}L{x + dx * size - px:.3f} {y + dy * size - py:.3f}Z" fill="{INK}"/>')


def hdim(x1, y1, x2, y2, yd, label):
    """(x1, y1)·(x2, y2) 두 점 사이 가로 치수. yd는 치수선 높이(쪽 좌표)"""
    if x1 > x2:
        x1, y1, x2, y2 = x2, y2, x1, y1
    for x, y in ((x1, y1), (x2, y2)):
        s = -1 if yd < y else 1
        if abs(yd - y) > 1.2:
            line(x, y + s * 0.6, x, yd + s * 1.2, stroke=INK, w=0.13)
    narrow = x2 - x1 < len(label) * 1.3 + 3.5
    if narrow:
        line(x1 - 4, yd, x2 + 4, yd, stroke=INK, w=0.13)
        arrow(x1, yd, -1, 0)
        arrow(x2, yd, 1, 0)
        text(x2 + 4.6, yd + 0.9, label)
    else:
        line(x1, yd, x2, yd, stroke=INK, w=0.13)
        arrow(x1, yd, 1, 0)
        arrow(x2, yd, -1, 0)
        text((x1 + x2) / 2, yd - 0.7, label, anchor="middle")


def vdim(x1, y1, x2, y2, xd, label):
    """세로 치수. xd는 치수선 위치. 글자는 치수선 왼쪽에 세로로"""
    if y1 > y2:
        x1, y1, x2, y2 = x2, y2, x1, y1
    for x, y in ((x1, y1), (x2, y2)):
        s = -1 if xd < x else 1
        if abs(xd - x) > 1.2:
            line(x + s * 0.6, y, xd + s * 1.2, y, stroke=INK, w=0.13)
    narrow = y2 - y1 < len(label) * 1.3 + 3.5
    if narrow:
        line(xd, y1 - 4, xd, y2 + 4, stroke=INK, w=0.13)
        arrow(xd, y1, 0, -1)
        arrow(xd, y2, 0, 1)
        text(xd - 0.7, y1 - 4.6, label, anchor="start", rotate=-90)
    else:
        line(xd, y1, xd, y2, stroke=INK, w=0.13)
        arrow(xd, y1, 0, 1)
        arrow(xd, y2, 0, -1)
        text(xd - 0.7, (y1 + y2) / 2, label, anchor="middle", rotate=-90)


def leader(x1, y1, x2, y2, label, fill=INK):
    """(x1, y1)을 가리키는 지시선, 글자는 (x2, y2)에서 옆으로"""
    line(x1, y1, x2, y2, stroke=INK, w=0.13)
    arrow(x1, y1, *_unit(x1 - x2, y1 - y2), size=1.4)
    right = x2 >= x1
    shelf = 2.5 if right else -2.5
    line(x2, y2, x2 + shelf, y2, stroke=INK, w=0.13)
    text(x2 + shelf + (0.6 if right else -0.6), y2 + 0.9, label, anchor="start" if right else "end", fill=fill)


def _unit(dx, dy):
    n = (dx * dx + dy * dy) ** 0.5 or 1
    return -dx / n, -dy / n


def center_mark(x, y, d):
    n = d / 2 + 1.2
    line(x - n, y, x + n, y, **CENTER)
    line(x, y - n, x, y + n, **CENTER)


class View:
    """모델 2D 좌표 (u, v) → 쪽 좌표. u0..u1, v0..v1 범위가 (bx, by)에서 시작하는 상자에 들어가요"""

    def __init__(self, bx, by, u, v, s, flip_u=False, flip_v=False):
        self.bx, self.by, self.s = bx, by, s
        (self.u0, self.u1), (self.v0, self.v1) = u, v
        self.flip_u, self.flip_v = flip_u, flip_v

    def x(self, u):
        return self.bx + self.s * ((self.u1 - u) if self.flip_u else (u - self.u0))

    def y(self, v):
        return self.by + self.s * ((v - self.v0) if self.flip_v else (self.v1 - v))

    def p(self, u, v):
        return self.x(u), self.y(v)

    def d(self, polys):
        return "".join("M" + "L".join(f"{self.x(a):.3f} {self.y(b):.3f}" for a, b in poly) + "Z" for poly in polys)

    def outline(self, polys, w=0.35, fill="#fff"):
        path(self.d(polys), stroke=INK, w=w, fill=fill)

    def section(self, polys):
        path(self.d(polys), stroke=INK, w=0.35, fill="url(#hatch)")


def view_title(x, y, s, scale):
    text(x, y, s, 3.2, weight=700)
    text(x + len(s) * 3.0 + 2, y, f"{scale}:1", 2.5, fill=GRAY)


def section_line(a, b, letter, look):
    """단면 위치선(쪽 좌표 a→b)과 보는 방향 화살표(look = (dx, dy))"""
    line(*a, *b, **CENTER)
    for x, y in (a, b):
        ex, ey = x + look[0] * 3.4, y + look[1] * 3.4
        line(x, y, ex, ey, stroke=INK, w=0.18)
        arrow(ex, ey, *look, size=1.6)
        text(ex + look[0] * 2.8 + (1.4 if look[1] else 0), ey + look[1] * 2.8 + 0.9, letter, 3, "middle", weight=700)


def begin(n, part):
    out.clear()
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{PAGE_W}mm" height="{PAGE_H}mm" viewBox="0 0 {PAGE_W} {PAGE_H}" font-family="{SANS}">')
    add('<defs><pattern id="hatch" width="1.1" height="1.1" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
        f'<rect width="1.1" height="1.1" fill="#fff"/><line x1="0" y1="0" x2="0" y2="1.1" stroke="{GRAY}" stroke-width="0.16"/></pattern></defs>')
    add(f'<rect width="{PAGE_W}" height="{PAGE_H}" fill="#fff"/>')
    add(f'<rect x="8" y="8" width="{PAGE_W - 16}" height="{PAGE_H - 16}" {style(w=0.5)}/>')
    title_block(n, part)


def title_block(n, part):
    x, y, w, h = PAGE_W - 8 - 96, PAGE_H - 8 - 24, 96, 24
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" {style(w=0.4)}/>')
    line(x, y + 10, x + w, y + 10, w=0.25)
    line(x + 48, y + 10, x + 48, y + h, w=0.25)
    line(x, y + 17, x + w, y + 17, w=0.25)
    text(x + 3, y + 4.6, "Owl 케이스 — 3D 출력 도면", 3.4, weight=700)
    text(x + 3, y + 8.4, part, 2.8)
    text(x + w - 3, y + 8.4, f"{n} / 2", 2.8, "end", fill=GRAY)
    for i, (k, v) in enumerate([("단위", "mm"), ("투상", "3각법"), ("재료", "레진(SLA) · MJF 나일론"), ("원본", "owl-case.scad")]):
        cx, cy = x + 3 + (i % 2) * 48, y + 14.6 + (i // 2) * 7
        text(cx, cy, k, 2.2, fill=GRAY)
        text(cx + 8, cy, v, 2.5)


def notes(x, y, items):
    text(x, y, "주기", 3.2, weight=700)
    for i, s in enumerate(items, 1):
        text(x, y + 1.5 + i * 4.4, f"{i}. {s}", 2.5)


def image(x, y, w, h, uri, label):
    add(f'<image x="{x}" y="{y}" width="{w}" height="{h}" href="{uri}" preserveAspectRatio="xMidYMid meet"/>')
    text(x + w / 2, y + h + 1, label, 2.5, "middle", fill=GRAY)


def finish():
    add("</svg>")
    return "\n".join(out) + "\n"


# ---------- 1쪽: 몸통 ----------
def page_body():
    begin(1, "부품 1 몸통")
    S = 2
    body = "body();"
    sil_top = scad2d(f"projection() {body}")
    rim = scad2d(cut_z(SPLIT - 0.01, body))
    sil_front = scad2d(f"projection() {FRONT} {body}")
    loop_end = scad2d(cut_y(OY / 2 + LOOP_W / 2 - 0.01, f"intersection() {{ {body} translate([{OX}, -1, -1]) cube([20, {OY + 2}, {OZ + 2}]); }}"))
    sil_side = scad2d(f"projection() {SIDE} {body}")
    usb_holes = [p for p in scad2d(cut_x(WALL / 2, body)) + scad2d(cut_x(0.02, body))
                 if max(abs(u - USB_CY) for u, _ in p) < USB_W / 2 + 0.5 and min(v for _, v in p) > 1]
    sec_a = scad2d(cut_y(OY / 2, body))
    sec_b = scad2d(cut_x(OX / 2, body))
    LX = OX + LOOP_LEN

    # 평면도
    top = View(108, 36, (0, LX), (0, OY), S)
    view_title(top.bx, 16, "평면도", S)
    top.outline(sil_top)
    top.outline(rim, w=0.3, fill="none")
    line(top.x(OX), top.y(OY / 2 + LOOP_W / 2), top.x(OX), top.y(OY / 2 - LOOP_W / 2), w=0.3)
    for u in (LOOP_CX - LOOP_D / 2, LOOP_CX + LOOP_D / 2):
        line(top.x(u), top.y(OY / 2 + LOOP_W / 2), top.x(u), top.y(OY / 2 - LOOP_W / 2), **HIDDEN)
    line(top.x(LOOP_CX), top.y(OY / 2 + LOOP_W / 2) - 1.5, top.x(LOOP_CX), top.y(OY / 2 - LOOP_W / 2) + 1.5, **CENTER)
    section_line((top.x(-1.5), top.y(OY / 2)), (top.x(LX + 1.5), top.y(OY / 2)), "가", (0, -1))
    section_line((top.x(OX / 2), top.y(OY + 1.5)), (top.x(OX / 2), top.y(-1.5)), "나", (1, 0))
    hdim(*top.p(0, OY - R_OUT), *top.p(OX, OY - R_OUT), top.y(OY) - 7, f"{OX:g}")
    hdim(*top.p(0, OY - R_OUT), *top.p(LX, OY / 2 + LOOP_W / 2), top.y(OY) - 13, f"{LX:g}")
    vdim(*top.p(R_OUT, 0), *top.p(R_OUT, OY), top.x(0) - 10, f"{OY:g}")
    vdim(*top.p(LX, OY / 2 - LOOP_W / 2), *top.p(LX, OY / 2 + LOOP_W / 2), top.x(LX) + 6, f"{LOOP_W:g}")
    hdim(*top.p(NAIL[0], 0), *top.p(NAIL[1], 0), top.y(0) + 5, f"{NAIL[1] - NAIL[0]:g}")
    hdim(*top.p(0, 0.5), *top.p(NAIL[0], 0), top.y(0) + 10, f"{NAIL[0]:g}")
    leader(*top.p(R_OUT * 0.29, R_OUT * 0.29), top.x(-3.5), top.y(-3.6), f"R{R_OUT:g}")
    leader(*top.p(WALL + R_IN * 0.29, OY - WALL - R_IN * 0.29), top.x(6), top.y(OY - 6), f"안쪽 R{R_IN:g}")
    text(top.x(OX / 2), top.y(OY / 2) + 6, f"안쪽 {INNER[0]:g} × {INNER[1]:g}", 2.5, "middle", fill=GRAY)

    # 정면도 (앞 -Y 면)
    front = View(108, 106, (0, LX), (0, SPLIT), S)
    view_title(front.bx, 101, "정면도", S)
    front.outline(sil_front)
    front.outline(loop_end, w=0.3, fill="none")
    path(f"M{front.x(NAIL[0]):.3f} {front.y(SPLIT):.3f}V{front.y(SPLIT - NAIL[2]):.3f}H{front.x(NAIL[1]):.3f}V{front.y(SPLIT):.3f}", w=0.3)
    center_mark(*front.p(LOOP_CX, LOOP_CZ), LOOP_T)
    vdim(*front.p(R_OUT, 0), *front.p(R_OUT, SPLIT), front.x(0) - 6, f"{SPLIT:g}")
    vdim(*front.p(LOOP_CX, 0), *front.p(LOOP_CX, LOOP_CZ), front.x(LX) + 6, f"{LOOP_CZ:g}")
    vdim(*front.p(LOOP_CX, LOOP_CZ - LOOP_T / 2), *front.p(LOOP_CX, LOOP_CZ + LOOP_T / 2), front.x(LX) + 12, f"⌀{LOOP_T:g}")
    hdim(*front.p(0, 0), *front.p(LOOP_CX, LOOP_CZ), front.y(0) + 6, f"{LOOP_CX:g}")
    vdim(*front.p(NAIL[0], SPLIT - NAIL[2]), *front.p(NAIL[0], SPLIT), front.x(NAIL[0]) - 4, f"{NAIL[2]:g}")
    leader(*front.p(LOOP_CX + LOOP_D / 2 * 0.7, LOOP_CZ + LOOP_D / 2 * 0.7), front.x(LX) + 3, front.y(SPLIT) - 4,
           f"⌀{LOOP_D:g} 관통, 양 끝 C{LOOP_CH:g}")
    leader(*front.p(EDGE * 0.29, EDGE * 0.29), front.x(-3.5), front.y(-4), f"R{EDGE:g}")

    # 좌측면도 (USB 쪽 -X 면)
    side = View(30, 106, (0, OY), (0, SPLIT), S, flip_u=True)
    view_title(side.bx, 101, "좌측면도 (USB 쪽)", S)
    side.outline(sil_side)
    for p in usb_holes:
        side.outline([p], w=0.3, fill="none")
    center_mark(*side.p(USB_CY, USB_CZ), USB_H)
    hdim(*side.p(USB_CY + USB_W / 2, USB_CZ), *side.p(USB_CY - USB_W / 2, USB_CZ), side.y(0) + 6, f"{USB_W:g}")
    hdim(*side.p(OY, 0), *side.p(0, 0), side.y(0) + 12, f"{OY:g}")
    vdim(*side.p(USB_CY + USB_W / 2, USB_CZ - USB_H / 2), *side.p(USB_CY + USB_W / 2, USB_CZ + USB_H / 2), side.x(OY) - 5, f"{USB_H:g}")
    vdim(*side.p(OY, 0), *side.p(USB_CY + USB_W / 2, USB_CZ - USB_H / 2), side.x(OY) - 11, f"{USB_CZ - USB_H / 2:g}")
    leader(*side.p(USB_CY - USB_W / 2 + 0.55, USB_CZ + USB_H / 2 - 0.55), side.x(0) + 2, side.y(SPLIT) - 3, f"R{USB_H / 2:g}")

    # 단면 가–가 (폭 가운데, 긴 방향)
    sa = View(108, 154, (0, LX), (0, SPLIT), S)
    view_title(sa.bx, 146, "단면 가–가", S)
    sa.section(sec_a)
    hdim(*sa.p(WALL, SPLIT), *sa.p(OX - WALL, SPLIT), sa.y(SPLIT) - 4, f"{INNER[0]:g}")
    vdim(*sa.p(WALL + 8, FLOOR), *sa.p(WALL + 8, SPLIT), sa.x(WALL + 8), f"{INNER[2]:g}")
    vdim(*sa.p(OX / 2, 0), *sa.p(OX / 2, FLOOR), sa.x(OX / 2) + 8, f"{FLOOR:g}")
    hdim(*sa.p(OX - WALL, 4), *sa.p(OX, 4), sa.y(4), f"{WALL:g}")
    vdim(*sa.p(0, USB_CZ + USB_H / 2), *sa.p(0, SPLIT), sa.x(0) - 4, f"{SPLIT - USB_CZ - USB_H / 2:.1f}")
    leader(*sa.p(OX - WALL - V["inner_floor_r"] * 0.29, FLOOR + V["inner_floor_r"] * 0.29), sa.x(OX - 9), sa.y(FLOOR) - 7,
           f"안쪽 바닥 R{V['inner_floor_r']:g}")

    # 단면 나–나 (긴 방향 가운데, 폭 방향)
    sb = View(228, 36, (0, OY), (0, SPLIT), S, flip_u=True)
    view_title(sb.bx, 16, "단면 나–나", S)
    sb.section(sec_b)
    leader(*sb.p(OY - WALL - 0.15, SPLIT - LIP_H / 2), sb.x(OY) - 3, sb.y(SPLIT) - 6, "걸림 홈 R0.3")
    hdim(*sb.p(OY - WALL, FLOOR + 3), *sb.p(OY, FLOOR + 3), sb.y(FLOOR + 3), f"{WALL:g}")
    vdim(*sb.p(0, SPLIT - LIP_H / 2), *sb.p(0, SPLIT), sb.x(0) + 5, f"{LIP_H / 2:g}")
    text(sb.x(OY / 2), sb.y(0) + 6, f"걸림 홈 x {SNAP_X[0]:g}–{SNAP_X[1]:g}, 양쪽 긴 벽 안쪽", 2.5, "middle")

    image(222, 92, 62, 48, picture('color("#d9d3c7") body();', "0,0,0,55,0,215,0"), "몸통")
    notes(30, 156, [
        f"원점: USB 쪽 끝(x), 앞면(y), 바닥(z)",
        f"세로 모서리 R{R_OUT:g}, 위·아래 바깥 모서리 R{EDGE:g}",
        f"벽·바닥 {WALL:g}, 안쪽 {INNER[0]:g} × {INNER[1]:g} × {INNER[2]:g}",
        "손톱 홈은 앞면 위 모서리",
        f"USB 구멍 바깥 모서리 C{V['usb_chamfer']:g}",
    ])
    return finish()


# ---------- 2쪽: 뚜껑과 버튼 캡 ----------
def page_lid():
    begin(2, "부품 2 뚜껑 · 부품 3 버튼 캡")
    S, S4 = 2, 4
    lid = "lid();"
    sil = scad2d(f"projection() {lid}")
    dimple = scad2d(cut_z(OZ - 0.15, lid))
    plate = scad2d(cut_z(SPLIT + V["mesh_depth"] / 2, lid))
    lip = scad2d(cut_z(SPLIT - LIP_H / 2, lid))
    sec_a = scad2d(cut_y(BTN[1], lid))
    sec_b = scad2d(cut_x(OX / 2, lid))

    # 평면도 (바깥 면)
    top = View(30, 36, (0, OX), (0, OY), S)
    view_title(top.bx, 16, "뚜껑 평면도", S)
    top.outline(sil)
    top.outline([p for p in dimple if max(abs(u - BTN[0]) for u, _ in p) < DIMPLE_D / 2 + 0.2], w=0.3, fill="none")
    for tag, _, x, y, d, _ in HOLES:
        center_mark(*top.p(x, y), d)
        text(top.x(x) + S * d / 2 + 1, top.y(y) - S * d / 2 - 0.6, tag, 3, weight=700, fill=RED)
    section_line((top.x(-1.5), top.y(BTN[1])), (top.x(OX + 1.5), top.y(BTN[1])), "가", (0, -1))
    section_line((top.x(OX / 2), top.y(OY + 1.5)), (top.x(OX / 2), top.y(-1.5)), "나", (1, 0))
    hdim(*top.p(0, OY - R_OUT), *top.p(OX, OY - R_OUT), top.y(OY) - 7, f"{OX:g}")
    vdim(*top.p(R_OUT, 0), *top.p(R_OUT, OY), top.x(0) - 10, f"{OY:g}")

    # 저면도 (안쪽 면, 위아래 뒤집힘)
    bot = View(130, 36, (0, OX), (0, OY), S, flip_v=True)
    view_title(bot.bx, 16, "뚜껑 저면도", S)
    bot.outline(sil)
    bot.outline(plate, w=0.3, fill="none")
    path(bot.d(lip), stroke=INK, w=0.3, fill="#e6e2da")
    lx0, ly0 = WALL + FIT, WALL + FIT
    hdim(*bot.p(lx0, ly0 + 3), *bot.p(lx0 + LIP_OUT[0], ly0 + 3), bot.y(0) - 5, f"{LIP_OUT[0]:g}")
    vdim(*bot.p(lx0 + LIP_OUT[0] - 3, ly0), *bot.p(lx0 + LIP_OUT[0] - 3, ly0 + LIP_OUT[1]), bot.x(OX) + 6, f"{LIP_OUT[1]:g}")
    vdim(*bot.p(lx0, LIP_CUT[0]), *bot.p(lx0, LIP_CUT[1]), bot.x(0) - 6, f"{LIP_CUT[1] - LIP_CUT[0]:g}")
    hdim(*bot.p(BUMP_X[0], OY - WALL - FIT), *bot.p(BUMP_X[1], OY - WALL - FIT), bot.y(OY) + 5, f"돌기 {BUMP_X[1] - BUMP_X[0]:g}")
    hdim(*bot.p(0, OY), *bot.p(BUMP_X[0], OY - WALL - FIT), bot.y(OY) + 10, f"{BUMP_X[0]:g}")
    leader(*bot.p(lx0 + LIP_OUT[0] - LIP_T / 2, OY - 6), bot.x(OX - 8), bot.y(OY - 4), f"립 두께 {LIP_T:g}")
    mic = HOLES[2]
    leader(*bot.p(mic[2] - V["mesh_d"] / 2 * 0.7, mic[3] + V["mesh_d"] / 2 * 0.7), bot.x(mic[2] - 7), bot.y(mic[3] + 6),
           f"⌀{V['mesh_d']:g} 깊이 {V['mesh_depth']:g}")

    # 구멍 표
    x, y = 222, 26
    text(x, y, "구멍 (관통, 뚜껑 윗면 기준)", 3.2, weight=700)
    cols = [x, x + 6, x + 22, x + 32, x + 42]
    y += 6
    for c, h in zip(cols, ["", "이름", "x", "y", "⌀"]):
        text(c, y, h, 2.3, fill=GRAY)
    line(x, y + 1.2, x + 60, y + 1.2, stroke=GRAY, w=0.15)
    for tag, name, hx, hy, d, note in HOLES:
        y += 5.2
        text(cols[0], y, tag, 2.8, weight=700, fill=RED)
        text(cols[1], y, name, 2.5)
        for c, v in zip(cols[2:], [hx, hy, d]):
            text(c, y, f"{v:.1f}", 2.5)
        if note != "관통 *" and note != "관통":
            y += 4
            text(cols[1], y, note.replace("관통 *, ", "").replace("관통, ", ""), 2.2, fill=GRAY)
    y += 6
    text(x, y, "* A·B·C는 보드 실측 전 추정 위치", 2.3, fill=GRAY)

    # 단면 가–가 (버튼 중심, 긴 방향)
    sa = View(30, 106, (0, OX), (SPLIT - LIP_H, OZ), S4)
    view_title(sa.bx, 98, "뚜껑 단면 가–가", S4)
    sa.section(sec_a)
    vdim(*sa.p(OX / 2, SPLIT), *sa.p(OX / 2, OZ), sa.x(OX / 2), f"{LID:g}")
    vdim(*sa.p(OX - WALL - FIT, SPLIT - LIP_H), *sa.p(OX - WALL - FIT, SPLIT), sa.x(OX) + 5, f"{LIP_H:g}")
    hdim(*sa.p(BTN[0] - V["btn_hole_d"] / 2, SPLIT), *sa.p(BTN[0] + V["btn_hole_d"] / 2, SPLIT), sa.y(SPLIT - LIP_H) + 5, f"⌀{V['btn_hole_d']:g}")
    hdim(*sa.p(BTN[0] - DIMPLE_D / 2, OZ), *sa.p(BTN[0] + DIMPLE_D / 2, OZ), sa.y(OZ) - 5, f"⌀{DIMPLE_D:g} 깊이 0.3")
    hdim(*sa.p(OX, SPLIT - LIP_H), *sa.p(OX - WALL - FIT, SPLIT - LIP_H), sa.y(SPLIT - LIP_H) + 5, f"{WALL + FIT:g}")
    text(sa.x(1), sa.y(SPLIT - LIP_H) + 5.9, "USB 쪽은 립 없음", 2.3, fill=GRAY)
    leader(*sa.p(EDGE * 0.29, OZ - EDGE * 0.29 + 0.1), sa.x(-1) + 1, sa.y(OZ) - 6, f"R{EDGE:g}")

    # 단면 나–나 (가운데, 폭 방향)
    sb = View(190, 106, (0, OY), (SPLIT - LIP_H, OZ), S4, flip_u=True)
    view_title(sb.bx, 98, "뚜껑 단면 나–나", S4)
    sb.section(sec_b)
    hdim(*sb.p(OY - WALL - FIT, SPLIT - LIP_H), *sb.p(OY - WALL - FIT - LIP_T, SPLIT - LIP_H), sb.y(SPLIT - LIP_H) + 5, f"{LIP_T:g}")
    hdim(*sb.p(OY, SPLIT - LIP_H), *sb.p(OY - WALL - FIT, SPLIT - LIP_H), sb.y(SPLIT - LIP_H) + 11, f"{WALL + FIT:g}")
    leader(*sb.p(WALL + FIT - 0.2, SPLIT - LIP_H / 2), sb.x(6), sb.y(SPLIT - LIP_H) + 8, "걸림 돌기 R0.25")

    # 버튼 캡
    cap = View(30, 150, (-CAP_FD / 2, CAP_FD / 2), (0, CAP_H), 5)
    view_title(cap.bx, 140, "버튼 캡 정면도", 5)
    cap.outline([[(-CAP_FD / 2, 0), (CAP_FD / 2, 0), (CAP_FD / 2, CAP_FT), (CAP_STEM / 2, CAP_FT), (CAP_STEM / 2, CAP_H),
                  (-CAP_STEM / 2, CAP_H), (-CAP_STEM / 2, CAP_FT), (-CAP_FD / 2, CAP_FT)]])
    line(cap.x(0), cap.y(CAP_H) - 2, cap.x(0), cap.y(0) + 2, **CENTER)
    hdim(*cap.p(-CAP_FD / 2, 0), *cap.p(CAP_FD / 2, 0), cap.y(0) + 6, f"⌀{CAP_FD:g}")
    hdim(*cap.p(-CAP_STEM / 2, CAP_H), *cap.p(CAP_STEM / 2, CAP_H), cap.y(CAP_H) - 5, f"⌀{CAP_STEM:g}")
    vdim(*cap.p(-CAP_FD / 2, 0), *cap.p(-CAP_FD / 2, CAP_FT), cap.x(-CAP_FD / 2) - 5, f"{CAP_FT:g}")
    vdim(*cap.p(CAP_FD / 2, 0), *cap.p(CAP_STEM / 2, CAP_H), cap.x(CAP_FD / 2) + 6, f"{CAP_H:g}")

    image(110, 138, 70, 52, picture(f'color("#d9d3c7") lid(); color("#ffffff") translate([{BTN[0]}, {BTN[1]}, {SPLIT - CAP_FT}]) cap();',
                                    "0,0,0,235,0,215,0"), "뚜껑 안쪽과 버튼 캡")
    notes(222, 140, [
        "원점: USB 쪽 끝(x), 앞면(y). 평면도 왼쪽 아래",
        f"뚜껑 판 {LID:g}, 바깥 모양은 몸통과 같음",
        f"립과 몸통 안벽 사이 틈 {FIT:g} (한쪽)",
        "출력: 뚜껑은 윗면, 캡은 날개를 바닥에",
    ])
    return finish()


# ---------- 내보내기 ----------
def to_pdf(pages, dest):
    if not Path(CHROME).exists():
        print("Chrome이 없어 PDF는 건너뛰어요")
        return
    html = ("<!doctype html><meta charset='utf-8'><style>@page{size:297mm 210mm;margin:0}html,body{margin:0}"
            "svg{display:block;break-after:page}svg:last-child{break-after:auto}</style>" + "".join(pages))
    src = TMP / "print.html"
    src.write_text(html, encoding="utf-8")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", f"--user-data-dir={TMP}/profile",
                    f"--print-to-pdf={TMP}/print.pdf", src.as_uri()], check=True, capture_output=True)
    shutil.copy(TMP / "print.pdf", dest)
    print(f"{dest.name} 저장")


try:
    pages = [page_body(), page_lid()]
    for i, svg in enumerate(pages, 1):
        (HERE / f"print-drawing-{i}.svg").write_text(svg, encoding="utf-8")
    to_pdf(pages, HERE / "print-drawing.pdf")
finally:
    shutil.rmtree(TMP, ignore_errors=True)
