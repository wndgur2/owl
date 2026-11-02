// Owl 목걸이 케이스 — 둥근 사각 조약돌 (몸통 + 뚜껑 + 버튼 캡) 과 안에 들어가는 부품·배선
//
// 설명은 hardware/case.md. 값을 바꾼 뒤 hardware/case/build.sh로 STL·그림·뷰어 파일을 다시 만들어요.
// OpenSCAD에서 열면 바로 조립 상태가 보이고, `part`를 바꿔 부품별로 볼 수 있어요.
//
// 좌표: 바깥 왼쪽 아래 모서리가 원점. X = 길이(USB-C 쪽이 0, 고리 쪽이 +),
// Y = 폭(XIAO D0–D6 줄이 0 쪽), Z = 두께(바닥 0, 뚜껑 윗면 +).
// 부품 위치는 "안쪽 좌표"(벽·바닥 안쪽 면이 0)로, 보드 위 부품은 "보드 좌표"(USB-C 쪽 PCB 끝, D0 줄 모서리가 0)로 적어요.

/* [보기] */
// assembly: 조립 / exploded: 분해 / electronics: 케이스 없이 부품·배선 / body·lid·cap: 출력 부품 하나 / print: 출력 배치
part = "assembly"; // [assembly, exploded, electronics, body, lid, cap, print]
// 조립·분해 보기에서 부품 더미(보드·배터리·배선)를 보일지
show_parts = true;
// 조립 보기에서 몸통 앞쪽 절반을 잘라 속을 보일지
cutaway = false;

/* [케이스] */
inner = [34, 22, 11];   // 안쪽 공간 (PRD N2: 부품 기준 34 × 22 × 11)
wall = 1.0;             // 옆벽 (레진·MJF 기준. FDM이면 1.2 이상 권장)
floor_t = 1.0;          // 바닥
lid_t = 1.0;            // 뚜껑 판
corner_r = 3.0;         // 세로 모서리 반경(바깥). 안쪽은 corner_r - wall
edge_r = 1.5;           // 위·아래 모서리 둥글기 — 조약돌 느낌은 주로 여기서
fit = 0.15;             // 뚜껑 립과 몸통 사이 틈(한쪽)
lip_t = 0.8;            // 뚜껑 립 두께
lip_h = 1.0;            // 뚜껑 립 깊이 (USB-C 위에서는 잘라냄)
snap = true;            // 립 긴 면의 걸림 돌기 + 몸통 홈

/* [부품 — 도착하면 실측으로 바꾸기] */
board = [21, 17.8, 1.2];     // XIAO nRF54L15 Sense PCB (두께는 추정)
usb = [7.4, 9.0, 3.2];       // USB-C 리셉터클: 깊이, 폭, 높이(PCB 윗면부터) — 추정
usb_overhang = 0.6;          // USB-C가 PCB 끝보다 튀어나온 길이 — 추정
battery = [30, 20, 5.0];     // TW502030 (두께 5 × 폭 20 × 길이 30)
battery_tab = 1.4;           // 배터리 −X 끝 보호회로 탭(전선 나오는 곳)
board_foam = 1.0;            // 배터리와 보드 사이 양면 폼테이프(BOM 1mm). 배터리 전선이 지나는 홈이 파여 있음
button = [6, 6, 5];          // 소프트 터치 버튼 (마우저 485-3983 = Adafruit 3983)
button_base = 3.3;           // 버튼 몸통 높이 (나머지는 말랑한 누름부)
flash = [5.3, 5.3, 1.8];     // W25Q128 SOIC-8 208mil, 다리를 위로 뒤집어(dead-bug) 붙임
flash_foam = 0.3;            // 플래시 밑 테이프
wire_d = 0.6;                // 30AWG 실리콘 전선 바깥지름

// XIAO 핀: 2.54mm 간격 7개씩. 위에서 USB-C를 위로 두고 볼 때 왼쪽 줄·오른쪽 줄 (위→아래)
pin_pitch = 2.54;
pins_left = ["D0", "D1", "D2", "D3", "D4", "D5", "D6"];
pins_right = ["5V", "GND", "3V3", "D10", "D9", "D8", "D7"];
bat_pads = [[9.5, 10.4], [9.5, 7.4]];   // 밑면 BAT+, BAT− (보드 좌표) — 추정

// 보드 위 부품 중심 (보드 좌표) — 실측 전 자리표시
led_user = [3.0, 3.2];       // 초록 사용자 LED (D9)
led_charge = [3.0, 14.6];    // 빨간 충전 LED
mic = [17.0, 4.0];           // PDM 마이크 (윗면 포트로 가정)
soc = [12.5, 8.9];           // nRF54L15
imu = [17.5, 13.0];
antenna = [19.6, 8.9];       // 칩 안테나
ufl = [19.2, 15.6];          // U.FL 커넥터
charger = [8.0, 13.5];       // 충전 IC
sw_reset = [8.6, 2.1];       // 보드의 작은 버튼 두 개
sw_user = [8.6, 15.7];

// 버튼·플래시 위치 (안쪽 좌표, 배터리 위 빈 칸)
button_xy = [28.5, 11];      // 버튼 중심
flash_xy = [22.2, 15.4];     // 플래시 칩 모서리

/* [구멍] */
led_d = 1.2;
mic_d = 1.0;
mesh_d = 4.6;           // 방진망 스티커(3.7–5mm) 붙일 뚜껑 안쪽 홈
mesh_depth = 0.3;
btn_hole_d = 3.8;
cap_clear = 0.2;        // 버튼 캡 기둥과 구멍 사이 틈(한쪽)
cap_flange = [5.6, 0.6];  // 버튼 캡 날개: 지름, 두께
cap_proud = 0.4;        // 버튼 캡이 뚜껑 위로 나오는 높이
usb_hole = [9.4, 3.8];  // USB-C 구멍 (플러그 쉘 8.4 × 2.6 + 여유)
usb_chamfer = 0.3;
loop = [4.5, 7, 3];     // 목걸이 고리: 튀어나온 길이, 폭, 두께
loop_hole_d = 2.2;

$fn = 48;
eps = 0.01;

// ---------- 계산값 ----------
outer = [inner.x + 2*wall, inner.y + 2*wall, inner.z + floor_t + lid_t];
split_z = floor_t + inner.z;          // 몸통/뚜껑 나뉘는 높이 (= 뚜껑 판 아랫면)
inner_r = corner_r - wall;
board_pos = [0, (inner.y - board.y)/2, battery.z + board_foam];  // 안쪽 좌표
board_top = board_pos.z + board.z;
usb_top = board_top + usb.z;
usb_cy = board_pos.y + board.y/2;
battery_pos = [(inner.x - battery.x)/2, (inner.y - battery.y)/2, 0];
btn_z = battery.z + (inner.z - cap_flange[1] - battery.z - button.z);   // 버튼 바닥 높이
btn_pad = btn_z - battery.z;                                             // 버튼 밑에 깔 폼 두께
flash_z = battery.z + flash_foam;
flash_c = flash_xy + [flash.x, flash.y]/2;
flash_tip_z = flash_z + flash.z + 0.3;    // 위로 뒤집은 다리 끝 높이
pin0 = (board.x - 6*pin_pitch)/2;

function at_inner(p) = [p.x + wall, p.y + wall, (len(p) > 2 ? p.z : 0) + floor_t];
function on_board(p) = [board_pos.x + p.x, board_pos.y + p.y];
function idx(list, name) = [for (i = [0:len(list) - 1]) if (list[i] == name) i];
// 핀 패드 윗면 (안쪽 좌표)
function pad(name) = let(l = idx(pins_left, name), i = len(l) ? l[0] : idx(pins_right, name)[0])
    [board_pos.x + pin0 + i*pin_pitch, board_pos.y + (len(l) ? 0.7 : board.y - 0.7), board_top + 0.2];
// 플래시 n번 다리 끝 (위로 뒤집었으므로 위에서 보면 1번이 +X 쪽 위, 시계 방향)
function fpin(n) = let(b = n <= 4, k = b ? n - 1 : 8 - n)
    [b ? flash_xy.x + flash.x + 0.6 : flash_xy.x - 0.6, flash_c.y + 1.905 - k*1.27, flash_tip_z + 0.2];
// 버튼 다리 끝 (sx, sy = ±1). 대각선 두 다리가 스위치 양쪽
function bleg(sx, sy) = [button_xy.x + sx*4.3, button_xy.y + sy*2.25, btn_z + 0.35];
// 배선 높이: 보드 쪽에서 플래시 앞(-X) 다리로 가는 선은 아래층, 플래시를 넘어 뒤(+X) 다리로 가는 선은 위층,
// 플래시 위 점퍼는 칩 바로 위. 층이 다르면 위아래로 지나가서 서로 닿지 않아요
lvl_a = board_top + 1.6;
lvl_b = board_top + 2.6;
lvl_j = flash_tip_z + 0.8;
// 패드 a에서 z로 올라가 y 줄로 옮긴 뒤 +X로 b 위까지 가서 내려감 (모두 직각)
function lane(a, y, z, b) = [a, [a.x, a.y, z], [a.x, y, z], [b.x, y, z], b];

assert(usb_top <= inner.z - 0.3, str("USB-C 윗면(", usb_top, ")이 뚜껑에 닿아요"));
assert(usb_overhang <= wall, "USB-C가 벽보다 더 튀어나와요");
assert(board_pos.y >= inner_r || board_pos.x > 0, "보드 모서리가 둥근 안쪽 모서리에 걸려요");
assert(btn_pad >= 0, str("버튼이 너무 높아요 (", -btn_pad, "mm)"));
assert(board_pos.x + board.x <= flash_xy.x - 0.6, "플래시 다리가 보드와 겹쳐요");
assert(button_xy.x - 4.3 >= board.x, "버튼이 보드와 겹쳐요");
assert(lvl_b + wire_d/2 <= inner.z - 0.2, "배선 위층이 뚜껑에 닿아요");
assert(lvl_a - wire_d/2 >= board_top + 1.25, "배선 아래층이 보드 위 부품(U.FL)에 닿아요");

echo(str("바깥 크기 ", outer.x, " × ", outer.y, " × ", outer.z, " mm (고리 제외), 고리 포함 길이 ", outer.x + loop.x));
echo(str("부품 높이 ", usb_top, " / 안쪽 ", inner.z, " mm, 버튼 밑 폼 ", btn_pad, " mm"));

// ---------- 부품 목록 ----------
// [id, 이름, 색, 묶음, 분해할 때 올라가는 높이(mm), 설명]. build.sh가 이걸로 뷰어 목록(parts.json)을 만들어요.
COMPONENTS = [
    ["body",        "몸통",              "#e8e4dc", "케이스", 0,  "출력 부품. USB-C 구멍, 목걸이 고리, 뚜껑 걸림 홈, 손톱 홈"],
    ["lid",         "뚜껑",              "#d9d3c7", "케이스", 22, "출력 부품. LED 구멍 2개, 마이크 구멍과 안쪽 방진망 홈, 버튼 구멍. 립은 USB-C 위에서 잘림"],
    ["cap",         "버튼 캡",           "#ffffff", "케이스", 18, "출력 부품. 뚜껑 아래에서 끼우고 날개가 걸려 빠지지 않음"],
    ["battery",     "배터리 250mAh",     "#b8bcc2", "전원",   4,  "TW502030 LiPo, 5 × 20 × 30 mm, 몸통 바닥에 폼테이프로 붙임"],
    ["battery_tab", "보호회로 탭",       "#d9a441", "전원",   4,  "DW01 보호회로가 든 끝. 여기서 빨강·검정 전선이 나옴"],
    ["board_foam",  "폼테이프 1mm",      "#f4f1ea", "전원",   7,  "배터리와 보드 사이 절연·고정. 배터리 전선이 지나는 홈 두 줄"],
    ["pcb",         "XIAO nRF54L15 Sense", "#1d5c3a", "보드", 10, "21 × 17.8 mm. 위 부품 배치는 실측 전 추정"],
    ["pads",        "핀 패드",           "#d4af37", "보드",   10, "가장자리 반원 패드 14개(2.54mm 간격)와 밑면 BAT+·BAT− 패드"],
    ["usbc",        "USB-C",             "#c9ccd1", "보드",   10, "충전·펌웨어 굽기. 몸통 구멍으로 0.4mm 안쪽까지 나옴"],
    ["soc",         "nRF54L15",          "#2a2a2a", "보드",   10, "BLE SoC"],
    ["mic",         "PDM 마이크",        "#c0a060", "보드",   10, "P1.12 CLK / P1.13 DAT. 위에 뚜껑 마이크 구멍"],
    ["imu",         "IMU",               "#3a3a3a", "보드",   10, "6축 IMU (I2C30)"],
    ["antenna",     "칩 안테나",         "#f0f0f0", "보드",   10, "2.4GHz. 금속에 가리지 않기"],
    ["ufl",         "U.FL",              "#c9ccd1", "보드",   10, "외장 안테나 커넥터 (안 씀)"],
    ["charger",     "충전 IC",           "#2a2a2a", "보드",   10, "SGM40567, 200mA 고정"],
    ["led_user",    "초록 LED",          "#3ddc5a", "보드",   10, "P2.00, 상태 표시(D9). 위에 뚜껑 LED 구멍"],
    ["led_charge",  "빨간 충전 LED",     "#e5483b", "보드",   10, "충전 IC가 직접 켬. 위에 뚜껑 LED 구멍"],
    ["sw_board",    "보드 버튼",         "#e0e0e0", "보드",   10, "리셋·USR 버튼. 케이스 안에서는 누를 수 없음"],
    ["flash",       "W25Q128 플래시",    "#303030", "플래시", 10, "16MB SPI, SOIC-8. 다리를 위로 뒤집어 배터리 위에 붙임"],
    ["flash_legs",  "플래시 다리",       "#c9ccd1", "플래시", 10, "1 /CS, 2 DO, 3 /WP, 4 GND, 5 DI, 6 CLK, 7 /HOLD, 8 VCC"],
    ["button",      "마스터 버튼",       "#1f1f1f", "버튼",   10, "소프트 터치 6 × 6 × 5 mm. 대각선 두 다리를 D1·GND에"],
    ["button_dome", "버튼 누름부",       "#6b6b6b", "버튼",   10, "말랑한 누름부. 버튼 캡 날개가 위에 얹힘"],
    ["button_legs", "버튼 다리",         "#c9ccd1", "버튼",   10, "납작하게 바깥으로 구부림"],
    ["w_bat_p",     "BAT+ 전선",         "#d83a34", "배선",   4,  "배터리 + → 보드 밑면 BAT+. 폼테이프 홈으로 지나감. 극성 꼭 확인"],
    ["w_bat_n",     "BAT− 전선",         "#222222", "배선",   4,  "배터리 − → 보드 밑면 BAT−"],
    ["w_3v3",       "3V3 → VCC",         "#d83a34", "배선",   10, "3V3 → 플래시 8번 VCC"],
    ["j_vcc",       "VCC 점퍼",          "#d83a34", "배선",   10, "플래시 8번 VCC → 7번 /HOLD, 3번 /WP (둘 다 3V3에 묶음)"],
    ["w_gnd",       "GND → 플래시",      "#222222", "배선",   10, "GND → 플래시 4번 GND"],
    ["j_gnd",       "GND 점퍼",          "#222222", "배선",   10, "플래시 4번 GND → 버튼 다리"],
    ["w_clk",       "D8 → CLK",          "#2f6fd6", "배선",   10, "D8 (P2.01) → 플래시 6번 CLK"],
    ["w_do",        "D9 → DO",           "#2f6fd6", "배선",   10, "D9 (P2.04, MISO) → 플래시 2번 DO"],
    ["w_di",        "D10 → DI",          "#2f6fd6", "배선",   10, "D10 (P2.02, MOSI) → 플래시 5번 DI"],
    ["w_cs",        "D3 → /CS",          "#2f6fd6", "배선",   10, "D3 (P1.07) → 플래시 1번 /CS. 반대쪽 줄이라 버튼 바깥으로 돌아감"],
    ["w_d1",        "D1 → 버튼",         "#2f6fd6", "배선",   10, "D1 (P1.05, 내부 풀업) → 버튼 다리"],
    ["solder",      "납땜 자리",         "#a7abb0", "배선",   10, "보드 패드와 플래시·버튼 다리의 전선 끝"],
];

// 뷰어 이름표 [글자, 안쪽 좌표 위치]
LABELS = [
    for (n = ["D1", "D3", "D8", "D9", "D10", "3V3", "GND"]) [n, pad(n) + [0, 0, 0.3]],
    ["BAT+", [on_board(bat_pads[0]).x, on_board(bat_pads[0]).y, board_pos.z - 0.6]],
    ["BAT−", [on_board(bat_pads[1]).x, on_board(bat_pads[1]).y, board_pos.z - 0.6]],
    ["1 /CS", fpin(1)], ["2 DO", fpin(2)], ["3 /WP", fpin(3)], ["4 GND", fpin(4)],
    ["5 DI", fpin(5)], ["6 CLK", fpin(6)], ["7 /HOLD", fpin(7)], ["8 VCC", fpin(8)],
];

// ---------- 모양 도구 ----------

// 세로 모서리 반경 r, 아래·위 모서리 둥글기 rb·rt인 상자
module pebble(size, r, rb, rt) {
    hull() for (x = [r, size.x - r], y = [r, size.y - r])
        translate([x, y, 0]) rotate_extrude()
            intersection() {
                square([r, size.z]);
                hull() {
                    square([eps, size.z]);
                    translate([r - rb, rb]) circle(rb);
                    translate([r - rt, size.z - rt]) circle(rt);
                }
            }
}

module rrect(size, r) {
    r = max(r, eps);
    hull() for (x = [r, size.x - r], y = [r, size.y - r]) translate([x, y]) circle(r);
}

module shell() { pebble(outer, corner_r, edge_r, edge_r); }

// 점들을 잇는 전선 (안쪽 좌표)
module wire(pts, d = wire_d) {
    for (i = [0:len(pts) - 2]) hull() {
        translate(at_inner(pts[i])) sphere(d = d, $fn = 10);
        translate(at_inner(pts[i + 1])) sphere(d = d, $fn = 10);
    }
}

// 보드 좌표 중심 c에 놓인 상자 (PCB 윗면 위)
module on_top(c, size) {
    translate(at_inner([board_pos.x + c.x - size.x/2, board_pos.y + c.y - size.y/2, board_top])) cube(size);
}

// ---------- 몸통 ----------
module body() {
    difference() {
        union() {
            intersection() {
                shell();
                translate([-1, -1, -1]) cube([outer.x + 2, outer.y + 2, split_z + 1]);
            }
            loop_tab();
        }
        // 안쪽 공간
        translate([wall, wall, floor_t]) linear_extrude(inner.z + 1) rrect([inner.x, inner.y], inner_r);
        usb_cut();
        if (snap) snap_grooves();
        // 뚜껑 열 때 손톱 넣는 홈 (긴 면 -Y, 고리 쪽)
        translate([outer.x - 9, -eps, split_z - 0.6]) cube([4, 0.6, 0.6 + eps]);
    }
}

module usb_cut() {
    c = at_inner([0, usb_cy, board_top + usb.z/2]);
    translate([-1, c.y, c.z]) rotate([0, 90, 0]) linear_extrude(wall + 2)
        rotate(90) translate(-usb_hole/2) rrect(usb_hole, usb_hole.y/2);
    // 바깥쪽 모따기: 케이블 머리가 벽에 덜 걸리게
    translate([-eps, c.y, c.z]) rotate([0, 90, 0])
        linear_extrude(usb_chamfer, scale = [usb_hole.y/(usb_hole.y + 2*usb_chamfer), usb_hole.x/(usb_hole.x + 2*usb_chamfer)])
            rotate(90) translate(-(usb_hole + [2, 2]*usb_chamfer)/2) rrect(usb_hole + [2, 2]*usb_chamfer, usb_hole.y/2 + usb_chamfer);
}

module loop_tab() {
    z = outer.z/2 - loop.z/2 - lid_t/2;
    difference() {
        hull() {
            translate([outer.x - corner_r, outer.y/2 - loop.y/2 - 1, z]) cube([eps, loop.y + 2, loop.z]);
            translate([outer.x + loop.x - loop.y/2, outer.y/2, z]) cylinder(d = loop.y, h = loop.z);
        }
        translate([outer.x + loop.x - loop.y/2, outer.y/2, z - 1]) cylinder(d = loop_hole_d, h = loop.z + 2);
    }
}

// 긴 면 안쪽에 립 돌기가 걸리는 홈
module snap_grooves() {
    for (y = [wall - eps, wall + inner.y + eps])
        translate([wall + inner.x/2 - 5, y, split_z - lip_h/2]) rotate([0, 90, 0]) cylinder(r = 0.3, h = 10, $fn = 16);
}

// ---------- 뚜껑 ----------
module lid() {
    lip_out = [inner.x - 2*fit, inner.y - 2*fit];
    difference() {
        union() {
            intersection() {
                shell();
                translate([-1, -1, split_z]) cube([outer.x + 2, outer.y + 2, lid_t + 1]);
            }
            // 립: 몸통 안으로 들어가 위치를 잡음. USB-C 위는 잘라 부품 높이를 벌어줌
            difference() {
                translate([wall + fit, wall + fit, split_z - lip_h]) linear_extrude(lip_h + eps) difference() {
                    rrect(lip_out, inner_r - fit);
                    translate([lip_t, lip_t]) rrect(lip_out - [2, 2]*lip_t, inner_r - fit - lip_t);
                }
                translate([wall - eps, wall + usb_cy - usb.y/2 - 0.5, split_z - lip_h - 1]) cube([lip_t + fit + 0.2, usb.y + 1, lip_h + 1]);
            }
            if (snap) for (y = [wall + fit, wall + inner.y - fit])
                translate([wall + inner.x/2 - 4, y, split_z - lip_h/2]) rotate([0, 90, 0]) cylinder(r = 0.25, h = 8, $fn = 16);
        }
        for (p = [led_user, led_charge]) lid_hole(on_board(p), led_d);
        lid_hole(on_board(mic), mic_d);
        translate(at_inner([on_board(mic).x, on_board(mic).y, inner.z - eps])) cylinder(d = mesh_d, h = mesh_depth + eps);
        lid_hole(button_xy, btn_hole_d);
        // 버튼 자리를 손끝으로 찾게 얕은 오목
        translate(at_inner([button_xy.x, button_xy.y, inner.z + lid_t - 0.3])) cylinder(d1 = btn_hole_d, d2 = btn_hole_d + 2.4, h = 0.3 + eps);
    }
}

module lid_hole(xy, d) {
    translate(at_inner([xy.x, xy.y, inner.z - 1])) cylinder(d = d, h = lid_t + 2);
}

// ---------- 버튼 캡 (뚜껑 아래에서 끼움, 날개가 빠지지 않게 잡음) ----------
module cap() {
    cylinder(d = cap_flange[0], h = cap_flange[1]);
    cylinder(d = btn_hole_d - 2*cap_clear, h = cap_flange[1] + lid_t + cap_proud);
}

// ---------- 안에 들어가는 부품 ----------
module battery_body() {
    translate(at_inner(battery_pos)) hull() for (x = [0.5, battery.x - 0.5], y = [0.5, battery.y - 0.5])
        translate([x, y, 0]) cylinder(r = 0.5, h = battery.z, $fn = 16);
}

module battery_tab_part() {
    translate(at_inner([battery_pos.x - battery_tab, inner.y/2 - 4, 1.5])) cube([battery_tab, 8, 2]);
}

module board_foam_part() {
    translate(at_inner([board_pos.x + 0.3, board_pos.y + 0.3, battery.z])) difference() {
        cube([board.x - 0.6, board.y - 0.6, board_foam - 0.05]);
        for (p = bat_pads) translate([-1, p.y - 0.3 - 0.7, -1]) cube([p.x + 1.5, 1.4, board_foam + 2]);
    }
}

module pcb() { translate(at_inner(board_pos)) cube(board); }

module pads() {
    for (i = [0:6], side = [0, 1]) {
        x = board_pos.x + pin0 + i*pin_pitch - 0.65;
        y = side == 0 ? board_pos.y : board_pos.y + board.y - 1.0;
        for (z = [board_pos.z - 0.04, board_top]) translate(at_inner([x, y, z])) cube([1.3, 1.0, 0.05]);
        translate(at_inner([x, side == 0 ? board_pos.y - 0.04 : board_pos.y + board.y - 0.01, board_pos.z])) cube([1.3, 0.05, board.z]);
    }
    for (p = bat_pads) translate(at_inner([board_pos.x + p.x - 0.9, board_pos.y + p.y - 0.9, board_pos.z - 0.05])) cube([1.8, 1.8, 0.05]);
}

module usbc() {
    translate(at_inner([board_pos.x - usb_overhang, usb_cy, board_top + usb.z/2])) rotate([0, 90, 0])
        difference() {
            linear_extrude(usb.x) rotate(90) translate(-[usb.y, usb.z]/2) rrect([usb.y, usb.z], usb.z/2);
            translate([0, 0, -1]) linear_extrude(4) rotate(90) translate(-[usb.y - 0.6, usb.z - 0.6]/2) rrect([usb.y - 0.6, usb.z - 0.6], usb.z/2 - 0.3);
        }
    // 가운데 혀
    translate(at_inner([board_pos.x - usb_overhang + 0.4, usb_cy - 3.3, board_top + usb.z/2 - 0.35])) cube([3, 6.6, 0.7]);
}

module sw_board() {
    for (c = [sw_reset, sw_user]) { on_top(c, [2.6, 1.6, 0.5]); on_top(c, [1.0, 0.8, 0.8]); }
}

module flash_part() { translate(at_inner([flash_xy.x, flash_xy.y, flash_z])) cube(flash); }

module flash_legs() {
    for (n = [1:8]) {
        t = fpin(n);
        s = n <= 4 ? 1 : -1;
        x0 = n <= 4 ? flash_xy.x + flash.x : flash_xy.x;
        hull() {
            translate(at_inner([x0, t.y - 0.2, flash_z + flash.z/2 - 0.1])) cube([eps, 0.4, 0.2]);
            translate(at_inner([t.x - 0.1, t.y - 0.2, flash_z + flash.z/2 - 0.1])) cube([0.2, 0.4, 0.2]);
        }
        translate(at_inner([t.x - 0.1, t.y - 0.2, flash_z + flash.z/2 - 0.1])) cube([0.2, 0.4, t.z - 0.2 - (flash_z + flash.z/2 - 0.1)]);
    }
}

module button_part() {
    translate(at_inner([button_xy.x - button.x/2, button_xy.y - button.y/2, btn_z])) cube([button.x, button.y, button_base]);
}

module button_dome() {
    translate(at_inner([button_xy.x, button_xy.y, btn_z + button_base - eps])) cylinder(d1 = 4.6, d2 = 3.8, h = button.z - button_base, $fn = 32);
}

module button_legs() {
    for (sx = [-1, 1], sy = [-1, 1]) {
        t = bleg(sx, sy);
        hull() {
            translate(at_inner([button_xy.x + sx*button.x/2, t.y - 0.35, btn_z])) cube([eps, 0.7, 0.25]);
            translate(at_inner([t.x, t.y - 0.35, btn_z])) cube([eps, 0.7, 0.25]);
        }
    }
}

// 배선 경로 (안쪽 좌표)
bat_wire_y = [for (p = bat_pads) board_pos.y + p.y];
WIRES = [
    ["w_bat_p", [[battery_pos.x - 0.6, bat_wire_y[0], 2.5], [0.6, bat_wire_y[0], 2.5], [0.6, bat_wire_y[0], battery.z + 0.45],
                 [on_board(bat_pads[0]).x, bat_wire_y[0], battery.z + 0.45], [on_board(bat_pads[0]).x, bat_wire_y[0], board_pos.z - 0.3]]],
    ["w_bat_n", [[battery_pos.x - 0.6, bat_wire_y[1], 2.5], [0.6, bat_wire_y[1], 2.5], [0.6, bat_wire_y[1], battery.z + 0.45],
                 [on_board(bat_pads[1]).x, bat_wire_y[1], battery.z + 0.45], [on_board(bat_pads[1]).x, bat_wire_y[1], board_pos.z - 0.3]]],
    // 보드 오른쪽 줄 → 플래시 앞 다리: 줄마다 다리 높이(y)에 맞춘 평행선. 먼 패드일수록 안쪽 줄이라 서로 건너지 않음
    ["w_3v3", lane(pad("3V3"), fpin(8).y, lvl_a, fpin(8))],
    ["w_di", lane(pad("D10"), fpin(5).y, lvl_a, fpin(5))],
    ["w_clk", lane(pad("D8"), fpin(6).y, lvl_a, fpin(6))],
    // 플래시 뒤 다리로는 위층에서 칩을 넘어감
    ["w_do", lane(pad("D9"), fpin(2).y, lvl_b, fpin(2))],
    // GND 패드는 USB-C 바로 옆이라 안쪽으로 못 들어가서, 가장자리 바깥 줄로 가서 플래시 뒤로 내려옴
    ["w_gnd", let(y = fpin(8).y + 0.45, x = fpin(4).x + 0.7) [pad("GND"), [pad("GND").x, pad("GND").y, lvl_b], [pad("GND").x, y, lvl_b],
              [x, y, lvl_b], [x, fpin(4).y, lvl_b], [fpin(4).x, fpin(4).y, lvl_b], fpin(4)]],
    // 왼쪽 줄: D1은 한 줄 안쪽, D3(/CS)은 가장자리 줄로 고리 쪽 벽을 따라 돌아 플래시 뒤 1번으로
    ["w_d1", let(y = pad("D1").y + 0.8) [pad("D1"), [pad("D1").x, pad("D1").y, lvl_a], [pad("D1").x, y, lvl_a],
              [bleg(-1, -1).x, y, lvl_a], [bleg(-1, -1).x, bleg(-1, -1).y, lvl_a], bleg(-1, -1)]],
    ["w_cs", let(x = inner.x - 0.6) [pad("D3"), [pad("D3").x, pad("D3").y, lvl_a], [x, pad("D3").y, lvl_a],
              [x, fpin(1).y, lvl_a], [fpin(1).x, fpin(1).y, lvl_a], fpin(1)]],
    // 3 /WP → 칩 위를 가로질러 7 /HOLD → 8 VCC (모두 3V3)
    ["j_vcc", let(ym = (fpin(3).y + fpin(7).y)/2, xm = flash_xy.x + 0.2)
              [fpin(3), [fpin(3).x, fpin(3).y, lvl_j], [flash_xy.x + flash.x - 0.6, fpin(3).y, lvl_j], [flash_xy.x + flash.x - 0.6, ym, lvl_j],
               [xm, ym, lvl_j], [xm, fpin(7).y, lvl_j], [fpin(7).x, fpin(7).y, lvl_j], fpin(7),
               [fpin(7).x, fpin(7).y, lvl_j - 0.3], [fpin(8).x, fpin(8).y, lvl_j - 0.3], fpin(8)]],
    // 4 GND → 버튼 +X+Y 다리 (버튼 몸통 바깥으로)
    ["j_gnd", let(x = button_xy.x + button.x/2 + 0.7) [fpin(4), [fpin(4).x, fpin(4).y, lvl_j], [x, fpin(4).y, lvl_j],
              [x, bleg(1, 1).y, lvl_j], [x, bleg(1, 1).y, bleg(1, 1).z]]],
];

module solder() {
    ends = [for (w = WIRES) if (w[0] != "w_bat_p" && w[0] != "w_bat_n") each [w[1][0], w[1][len(w[1]) - 1]]];
    for (p = concat(ends, [fpin(7)])) translate(at_inner(p)) sphere(d = wire_d + 0.35, $fn = 12);
}

module component(id) {
    w = [for (x = WIRES) if (x[0] == id) x[1]];
    if (len(w)) wire(w[0]);
    else if (id == "body") body();
    else if (id == "lid") lid();
    else if (id == "cap") translate(at_inner([button_xy.x, button_xy.y, inner.z - cap_flange[1]])) cap();
    else if (id == "battery") battery_body();
    else if (id == "battery_tab") battery_tab_part();
    else if (id == "board_foam") board_foam_part();
    else if (id == "solder") solder();
    else if (id == "pcb") pcb();
    else if (id == "pads") pads();
    else if (id == "usbc") usbc();
    else if (id == "soc") on_top(soc, [6, 6, 0.85]);
    else if (id == "mic") on_top(mic, [3.5, 2.65, 1.0]);
    else if (id == "imu") on_top(imu, [2.5, 3, 0.83]);
    else if (id == "antenna") on_top(antenna, [1.6, 3.2, 1.1]);
    else if (id == "ufl") on_top(ufl, [2, 2, 1.25]);
    else if (id == "charger") on_top(charger, [2, 2, 0.75]);
    else if (id == "led_user") on_top(led_user, [1.0, 0.6, 0.4]);
    else if (id == "led_charge") on_top(led_charge, [1.0, 0.6, 0.4]);
    else if (id == "sw_board") sw_board();
    else if (id == "flash") flash_part();
    else if (id == "flash_legs") flash_legs();
    else if (id == "button") button_part();
    else if (id == "button_dome") button_dome();
    else if (id == "button_legs") button_legs();
    else assert(false, str("모르는 부품: ", id));
}

function is_case(id) = id == "body" || id == "lid" || id == "cap";

// ---------- 출력 ----------
if (part == "body") body();
else if (part == "lid") lid();
else if (part == "cap") cap();
else if (part == "print") {
    body();
    // 뚜껑은 윗면을 바닥에 두고, 캡은 날개를 바닥에 두고 출력
    translate([0, outer.y + 6, outer.z]) mirror([0, 0, 1]) lid();
    translate([outer.x + 10, outer.y/2, 0]) cap();
}
else if (part == "list") {
    // build.sh가 읽는 뷰어 목록
    for (c = COMPONENTS) echo(str("PART|", c[0], "|", c[1], "|", c[2], "|", c[3], "|", c[4], "|", c[5]));
    for (l = LABELS) let(p = at_inner(l[1])) echo(str("LABEL|", l[0], "|", p.x, "|", p.y, "|", p.z));
    echo(str("SIZE|", outer.x, "|", outer.y, "|", outer.z));
}
else if (len(part) > 2 && part[0] == "c" && part[1] == ":") {
    // 부품 하나 (조립 위치 그대로) — 뷰어용
    component(chr([for (i = [2:len(part) - 1]) ord(part[i])]));
}
else for (c = COMPONENTS) {
    show = part == "electronics" ? !is_case(c[0]) : (show_parts || is_case(c[0]));
    cut = cutaway && part == "assembly" && (c[0] == "body" || c[0] == "lid");
    if (show) translate([0, 0, part == "exploded" ? c[4] : 0]) color(c[2]) difference() {
        component(c[0]);
        if (cut) translate([-10, -10, -1]) cube([outer.x + 20, outer.y/2 + 10, outer.z + 2]);
    }
}
