#!/usr/bin/env python3
"""hardware/wiring.svg 만들기: python3 hardware/wiring_svg.py

납땜할 때 옆에 두고 보는 2D 배선도. 연결은 wiring.md, 핀 배열은 실물(위에서 본 모습) 그대로이고,
선 경로는 보기 좋게 정리한 것(실제 경로는 case.md). 선은 직각으로만 꺾고, 건너는 곳은 넘어가는 표시를 해요.
3V3을 플래시 8번 VCC에 바로 이으면(3D 모델과 같음) 핀 순서상 평면에선 선 네 군데가 꼭 건너야 해요.
그보다 많이 건너거나 선이 겹치면 실패해요.
"""
from pathlib import Path

RED, BLACK, BLUE = "#d62828", "#1d1d1d", "#1f6fd1"
SANS = "-apple-system, 'Segoe UI', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif"
MONO = "ui-monospace, Menlo, Consolas, monospace"
W, H = 1080, 1080
HOP = 7          # 건너는 곳 반원 반지름
EXPECTED_CROSSINGS = 4

# ---------- 배치 ----------
# XIAO: USB-C 위, 왼쪽 줄 D0–D6, 오른쪽 줄 5V·GND·3V3·D10·D9·D8·D7 (위→아래)
BX0, BX1, BY0, BY1 = 300, 600, 200, 570
PITCH = 50
LEFT = ["D0", "D1", "D2", "D3", "D4", "D5", "D6"]
RIGHT = ["5V", "GND", "3V3", "D10", "D9", "D8", "D7"]
NRF = {"D1": "P1.05", "D3": "P1.07", "D8": "P2.01", "D9": "P2.04", "D10": "P2.02"}
USED = {"D1", "D3", "GND", "3V3", "D10", "D9", "D8"}


def pad_y(name):
    i = LEFT.index(name) if name in LEFT else RIGHT.index(name)
    return BY0 + 35 + i * PITCH


# BAT+·BAT−: 밑면 패드(위치는 추정). BAT+가 오른쪽(오른쪽 줄 쪽)
BAT_P, BAT_N = (480, 367), (420, 367)

# W25Q128, 다리를 위로 뒤집어 위에서 본 모습(dead-bug). 1번에서 시계 방향으로 1..8.
# 3D 모델과 같은 방향: 보드 쪽 줄이 8·7·6·5, 바깥 줄이 1·2·3·4
FX0, FX1, FY0, FY1 = 760, 920, 285, 485
LEG = 30
FLEFT = [(8, "VCC"), (7, "/HOLD"), (6, "CLK"), (5, "DI")]   # 보드 쪽 줄, 위→아래
FRIGHT = [(1, "/CS"), (2, "DO"), (3, "/WP"), (4, "GND")]    # 바깥 줄, 위→아래


def fpin_y(n):
    col = FLEFT if n >= 5 else FRIGHT
    return FY0 + 25 + [p for p, _ in col].index(n) * PITCH


def ftip(n):
    return (FX0 - LEG if n >= 5 else FX1 + LEG, fpin_y(n))


# 마스터 버튼(소프트 터치): 양옆 금속 탭 2개가 스위치 양쪽 (Adafruit 3983 데이터시트)
KX0, KX1, KY0, KY1 = 855, 945, 680, 760
K_UL, K_LR = (875, KY0 - 20), (925, KY1 + 20)

# 배터리
AX0, AX1, AY0, AY1 = 40, 230, 66, 156


def R(name):  # 오른쪽 줄 패드에서 선이 나가는 점
    return (BX1, pad_y(name))


def L(name):
    return (BX0, pad_y(name))


# ---------- 전선 ----------
# (이름, 색, 점들, 점선?, 넘어가는 순위) — 두 선이 건너면 순위가 높은 선에 반원을 그려요
WIRES = [
    ("BAT+", RED, [(AX1, 100), (515, 100), (515, BY0)], False, 0),
    ("BAT+ 밑", RED, [(515, BY0), (515, BAT_P[1]), BAT_P], True, 0),
    ("BAT−", BLACK, [(AX1, 125), (385, 125), (385, BY0)], False, 0),
    ("BAT− 밑", BLACK, [(385, BY0), (385, BAT_N[1]), BAT_N], True, 0),
    ("3V3→8", RED, [R("3V3"), (705, pad_y("3V3")), (705, fpin_y(8)), ftip(8)], False, 0),
    # D10·D8은 5·6번이 패드와 순서가 반대라 한 번 건너요
    ("D10→5", BLUE, [R("D10"), (665, pad_y("D10")), (665, fpin_y(5)), ftip(5)], False, 2),
    ("D8→6", BLUE, [R("D8"), (685, pad_y("D8")), (685, fpin_y(6)), ftip(6)], False, 0),
    # 바깥 줄(1·2·4번)로 가는 선은 칩을 돌아가요. D9는 칩 아래로 (D8을 넘어감)
    ("D9→2", BLUE, [R("D9"), (645, pad_y("D9")), (645, 545), (980, 545), (980, fpin_y(2)), ftip(2)], False, 1),
    # D3은 보드와 칩 아래로 돌아 1번으로
    ("D3→1", BLUE, [L("D3"), (260, pad_y("D3")), (260, 600), (1000, 600), (1000, fpin_y(1)), ftip(1)], False, 0),
    # GND는 칩 위로 넘어 바깥에서 4번으로 (D9·D3를 넘어감)
    ("GND→4", BLACK, [R("GND"), (625, pad_y("GND")), (625, 255), (1020, 255), (1020, fpin_y(4)), ftip(4)], False, 3),
    ("D1→버튼", BLUE, [L("D1"), (240, pad_y("D1")), (240, 640), (K_UL[0], 640), K_UL], False, 0),
    # 버튼 GND: GND 선 모서리에서 갈라져 버튼 오른쪽 아래 다리로
    ("GND→버튼", BLACK, [(1020, fpin_y(4)), (1020, 810), (K_LR[0], 810), K_LR], False, 0),
]
# 칩 위 점퍼(3V3): 8 VCC → 옆 다리 7 /HOLD, 7 → 칩 등 위로 → 3 /WP
JUMPERS = [
    ("8→7", RED, [(FX0 - 15, fpin_y(8)), (FX0 - 15, fpin_y(7))]),
    ("7→3", RED, [(FX0, fpin_y(7)), (840, fpin_y(7)), (840, fpin_y(3)), (FX1, fpin_y(3))]),
]
GND_SPLIT = (1020, fpin_y(4))
SOLDER = [w[2][-1] for w in WIRES if not w[0].startswith("BAT")] + \
         [w[2][0] for w in WIRES if not w[0].startswith("BAT") and w[0] != "GND→버튼"] + \
         [(FX0 - 15, fpin_y(8)), (FX0 - 15, fpin_y(7)), BAT_P, BAT_N]


# ---------- 검사: 직각, 겹침 없음, 건너는 곳 수 ----------
def segs(pts):
    return list(zip(pts, pts[1:]))


def net(name):
    return {"BAT+": "BAT+", "BAT−": "BAT−", "8→7": "3V3", "7→3": "3V3"}.get(name.split(" ")[0], name.split("→")[0])


def check():
    allw = [(n, p) for n, _, p, _, _ in WIRES] + [(n, p) for n, _, p in JUMPERS]
    for n, p in allw:
        for a, b in segs(p):
            assert a[0] == b[0] or a[1] == b[1], f"{n}: 직각이 아니에요 {a}->{b}"
    crossings = []
    for i, (n1, p1) in enumerate(allw):
        for n2, p2 in allw[i + 1:]:
            same = net(n1) == net(n2)
            for a, b in segs(p1):
                for c, d in segs(p2):
                    h1, h2 = a[1] == b[1], c[1] == d[1]
                    if h1 == h2:  # 평행: 같은 줄 위에 겹치면 안 돼요(같은 선끼리 끝이 닿는 건 괜찮음)
                        k = 1 if h1 else 0
                        if a[k] == c[k]:
                            lo1, hi1 = sorted((a[1 - k], b[1 - k]))
                            lo2, hi2 = sorted((c[1 - k], d[1 - k]))
                            gap = max(lo1, lo2) - min(hi1, hi2)
                            assert gap > 0 or (gap == 0 and same), f"{n1}와 {n2}가 겹쳐요"
                        continue
                    hs, vs = ((a, b), (c, d)) if h1 else ((c, d), (a, b))
                    y, x = hs[0][1], vs[0][0]
                    if min(hs[0][0], hs[1][0]) <= x <= max(hs[0][0], hs[1][0]) and \
                       min(vs[0][1], vs[1][1]) <= y <= max(vs[0][1], vs[1][1]):
                        if (x, y) in (a, b, c, d):
                            assert same, f"{n1}와 {n2}가 닿아요 {(x, y)}"
                            continue
                        crossings.append((n1, n2, (x, y)))
    assert len(crossings) == EXPECTED_CROSSINGS, crossings
    return crossings


# ---------- 그리기 ----------
out = []


def add(s):
    out.append(s)


def text(x, y, s, size=13, anchor="start", weight="normal", fill="#1d1d1d", family=SANS, extra=""):
    add(f'<text x="{x}" y="{y}" font-size="{size}" text-anchor="{anchor}" font-weight="{weight}" '
        f'fill="{fill}" font-family="{family}" {extra}>{s}</text>')


def wire_path(pts, hops):
    """직각 폴리라인. hops 위치에서는 반원으로 넘어가요."""
    d = [f"M{pts[0][0]},{pts[0][1]}"]
    for a, b in segs(pts):
        here = sorted((h for h in hops if (a[0] == b[0] == h[0] and min(a[1], b[1]) < h[1] < max(a[1], b[1])) or
                       (a[1] == b[1] == h[1] and min(a[0], b[0]) < h[0] < max(a[0], b[0]))),
                      key=lambda h: abs(h[0] - a[0]) + abs(h[1] - a[1]))
        for hx, hy in here:
            if a[0] == b[0]:
                s = 1 if b[1] > a[1] else -1
                d.append(f"L{hx},{hy - s * HOP} A{HOP},{HOP} 0 0 {1 if s > 0 else 0} {hx},{hy + s * HOP}")
            else:
                s = 1 if b[0] > a[0] else -1
                d.append(f"L{hx - s * HOP},{hy} A{HOP},{HOP} 0 0 {1 if s > 0 else 0} {hx + s * HOP},{hy}")
        d.append(f"L{b[0]},{b[1]}")
    return " ".join(d)


def draw():
    crossings = check()
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
    add(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
    text(40, 40, "Owl 배선도", 22, weight="700")
    text(170, 40, "위에서 본 모습 · USB-C가 위 · 연결은 wiring.md, 실제 경로·길이는 case.md", 13, fill="#555")

    # 배터리
    add(f'<rect x="{AX0}" y="{AY0}" width="{AX1 - AX0}" height="{AY1 - AY0}" rx="10" fill="#eef1f5" stroke="#8a94a3" stroke-width="1.5"/>')
    text(AX0 + 14, AY0 + 24, "LiPo 250mAh", 14, weight="700")
    text(AX0 + 14, AY0 + 44, "보호회로 탭", 12, fill="#555")
    text(AX0 + 14, AY0 + 78, "실제로는 보드·플래시 밑", 11, fill="#777")
    text(AX1 - 8, 104, "+", 15, anchor="end", weight="700", fill=RED)
    text(AX1 - 8, 130, "−", 15, anchor="end", weight="700", fill=BLACK)

    # XIAO 보드
    add(f'<rect x="{BX0}" y="{BY0}" width="{BX1 - BX0}" height="{BY1 - BY0}" rx="12" fill="#e3f0e4" stroke="#2f6b3a" stroke-width="2"/>')
    add(f'<rect x="400" y="{BY0 - 18}" width="100" height="40" rx="8" fill="#c9ccd1" stroke="#7d828a" stroke-width="1.5"/>')
    text(450, BY0 + 10, "USB-C", 12, anchor="middle", weight="700", fill="#444")
    text(450, BY1 - 40, "XIAO nRF54L15", 14, anchor="middle", weight="700", fill="#2f6b3a")
    text(450, BY1 - 22, "Sense", 14, anchor="middle", weight="700", fill="#2f6b3a")
    for side, names in (("L", LEFT), ("R", RIGHT)):
        for n in names:
            y = pad_y(n)
            used = n in USED
            x = BX0 if side == "L" else BX1 - 14
            add(f'<rect x="{x}" y="{y - 13}" width="14" height="26" rx="2" fill="{"#d4a017" if used else "#eadca8"}" '
                f'stroke="{"#9c7410" if used else "#c9b77c"}"/>')
            tx, anchor = (BX0 + 22, "start") if side == "L" else (BX1 - 22, "end")
            col = "#1d1d1d" if used else "#9aa39b"
            if n in NRF:
                text(tx, y + 1, n, 14, anchor, "700", col, MONO)
                text(tx, y + 15, NRF[n], 11, anchor, fill="#4a6b50", family=MONO)
            else:
                text(tx, y + 5, n, 14, anchor, "700" if used else "normal", col, MONO)
    # 밑면 BAT 패드 (점선)
    for (x, y), lab, col in ((BAT_P, "BAT+", RED), (BAT_N, "BAT−", BLACK)):
        add(f'<rect x="{x - 9}" y="{y - 9}" width="18" height="18" fill="none" stroke="{col}" stroke-width="1.5" stroke-dasharray="3 2"/>')
        text(x, y + 26, lab, 12, "middle", "700", col, MONO)
    text(450, BAT_P[1] + 42, "밑면 패드 (위치 추정)", 11, "middle", fill="#555")

    # 플래시
    add(f'<rect x="{FX0}" y="{FY0}" width="{FX1 - FX0}" height="{FY1 - FY0}" rx="4" fill="#4a4a4f" stroke="#2b2b2e" stroke-width="1.5"/>')
    for col, side in ((FLEFT, "L"), (FRIGHT, "R")):
        for n, lab in col:
            y = fpin_y(n)
            x0, x1 = (FX0 - LEG, FX0) if side == "L" else (FX1, FX1 + LEG)
            add(f'<rect x="{x0}" y="{y - 4}" width="{LEG}" height="8" fill="#b9bcc2" stroke="#7d828a"/>')
            tx, anchor = (FX0 + 8, "start") if side == "L" else (FX1 - 8, "end")
            text(tx, y - 7, f"{n} {lab}" if side == "L" else f"{lab} {n}", 13, anchor, "700", "#ffffff", MONO)
    add(f'<circle cx="{FX1 - 12}" cy="{fpin_y(1) + 13}" r="5" fill="#ffffff"/>')
    text(FX1 - 22, fpin_y(1) + 17, "1번", 11, "end", fill="#ffffff")
    text((FX0 + FX1) / 2, FY1 + 20, "W25Q128 (SOIC-8) · 다리가 위", 13, "middle", "700")

    # 버튼
    for (x, y) in (K_UL, K_LR):
        y0 = y if y < KY0 else KY1
        add(f'<rect x="{x - 12}" y="{y0}" width="24" height="20" fill="#b9bcc2" stroke="#7d828a"/>')
    add(f'<rect x="{KX0}" y="{KY0}" width="{KX1 - KX0}" height="{KY1 - KY0}" rx="4" fill="#3b3b40" stroke="#222"/>')
    add(f'<circle cx="{(KX0 + KX1) / 2}" cy="{(KY0 + KY1) / 2}" r="22" fill="#6b6b72" stroke="#222"/>')
    text(KX0 - 12, (KY0 + KY1) / 2 - 4, "마스터 버튼", 13, "end", "700")
    text(KX0 - 12, (KY0 + KY1) / 2 + 13, "양옆 탭 2개", 11, "end", fill="#555")

    # 전선
    hops = {}
    for n1, n2, p in crossings:
        rank = {w[0]: w[4] for w in WIRES}
        over = n1 if rank.get(n1, 0) > rank.get(n2, 0) else n2
        hops.setdefault(over, []).append(p)
    for n, c, pts, dashed, _ in WIRES:
        dash = ' stroke-dasharray="7 5"' if dashed else ""
        add(f'<path d="{wire_path(pts, hops.get(n, []))}" fill="none" stroke="{c}" stroke-width="3.2" '
            f'stroke-linejoin="round" stroke-linecap="round"{dash}/>')
    for n, c, pts in JUMPERS:
        add(f'<path d="{wire_path(pts, [])}" fill="none" stroke="{c}" stroke-width="3.2" stroke-linejoin="round" '
            f'stroke-linecap="round"/>')
    add(f'<circle cx="{GND_SPLIT[0]}" cy="{GND_SPLIT[1]}" r="5" fill="{BLACK}"/>')  # 갈림점
    for x, y in SOLDER:
        add(f'<circle cx="{x}" cy="{y}" r="4.2" fill="#c0c4ca" stroke="#555" stroke-width="1"/>')

    # 전선 이름
    lab = [
        (395, 92, "BAT+ (빨강)", RED), (300, 140, "BAT− (검정)", BLACK),
        (840, 249, "GND → 4 GND · 버튼", BLACK), (840, 539, "D9 → 2 DO (MISO)", BLUE),
        (480, 593, "D3 → 1 /CS", BLUE), (480, 633, "D1 → 버튼", BLUE),
    ]
    for x, y, s, c in lab:
        text(x, y, s, 12, "middle", "700", c, extra='paint-order="stroke" stroke="#ffffff" stroke-width="4"')
    text(652, pad_y("3V3") - 6, "3V3", 11, "middle", "700", RED)
    text(FX0 - 22, (fpin_y(8) + fpin_y(7)) / 2 + 4, "점퍼", 11, "end", "700", RED)

    # 범례·메모 (글꼴이 넓어도 잘리지 않게 두 줄)
    y = 870
    add(f'<line x1="40" y1="{y - 30}" x2="{W - 40}" y2="{y - 30}" stroke="#ddd"/>')
    rows = [[(RED, "전원 (3V3, BAT+)", ""), (BLACK, "GND (BAT−)", ""), (BLUE, "신호", "")],
            [("#777", "보드 밑으로 지나감", "dash"), ("#555", "납땜", "dot"), (BLACK, "건너감 (안 이어짐)", "hop")]]
    for r, row in enumerate(rows):
        for i, (c, s, kind) in enumerate(row):
            x, yy = 40 + i * 240, y + r * 30
            if kind == "dot":
                add(f'<circle cx="{x + 17}" cy="{yy}" r="4.2" fill="#c0c4ca" stroke="{c}"/>')
            elif kind == "hop":
                add(f'<line x1="{x}" y1="{yy}" x2="{x + 34}" y2="{yy}" stroke="{c}" stroke-width="3.2"/>')
                add(f'<path d="{wire_path([(x + 17, yy - 12), (x + 17, yy + 12)], [(x + 17, yy)])}" fill="none" stroke="{BLUE}" stroke-width="3.2"/>')
            else:
                dash = ' stroke-dasharray="7 5"' if kind == "dash" else ""
                add(f'<line x1="{x}" y1="{yy}" x2="{x + 34}" y2="{yy}" stroke="{c}" stroke-width="3.2"{dash}/>')
            text(x + 44, yy + 4, s, 13)
    notes = [
        "· 플래시는 다리를 위로 뒤집어(dead-bug) 붙여서, 위에서 보면 데이터시트 그림과 좌우가 바뀌어요. 1번 점은 뒤집으면 가려지니 먼저 1번 다리 쪽에 표시해요.",
        "· 플래시 방향은 3D 모델과 같아요: 보드 쪽 줄이 8 VCC · 7 /HOLD · 6 CLK · 5 DI, 바깥 줄이 1 /CS · 2 DO · 3 /WP · 4 GND.",
        "· 3V3은 8 VCC로 바로 가요. 7 /HOLD는 옆 다리 8에서, 3 /WP는 칩 등 위로 7에서 점퍼해요. /WP·/HOLD를 3V3에 묶어야 쓰기 보호와 일시 정지가 꺼져요.",
        "· 소프트 터치 버튼은 양옆 탭 2개가 스위치 양쪽이에요. 누를 때만 이어지고 방향은 상관없어요. 택트 스위치(다리 4개)면 대각선 두 다리를 써요.",
        "· 버튼 GND는 GND 선에서 갈라져요. GND 아무 데나 이어도 같고, 3D 모델에서는 플래시 4번 다리에서 짧게 점퍼해요.",
        "· D1은 펌웨어가 내부 풀업을 켜서, 버튼을 누르면 GND로 내려가 0이 돼요(active low). 배터리 극성은 납땜 전에 멀티미터로 확인!",
    ]
    for i, s in enumerate(notes):
        text(40, y + 68 + i * 24, s, 13, fill="#333")
    add("</svg>")


draw()
Path(__file__).with_name("wiring.svg").write_text("\n".join(out) + "\n", encoding="utf-8")
print("wiring.svg 저장")
