#!/usr/bin/env bash
# owl-case.scad → stl/ (출력용) + viewer/parts.json (3D 뷰어용 부품 목록·모양) + renders/ (문서용 그림)
# 필요: OpenSCAD 2025 이후 (brew install --cask openscad@snapshot), python3
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p stl renders
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

scad() { openscad --backend=manifold "$@" owl-case.scad 2>&1 | { grep -E "WARNING|ERROR" || true; }; }

for p in body lid cap; do
  scad --export-format=binstl -o "stl/owl-$p.stl" -D "part=\"$p\""
done

# 뷰어: 부품 목록과 부품별 모양(조립 위치 그대로의 STL을 base64로)을 parts.json 하나에
openscad -o "$tmp/list.echo" -D 'part="list"' owl-case.scad 2>/dev/null
for id in $(sed -n 's/^ECHO: "PART|\([^|]*\)|.*/\1/p' "$tmp/list.echo"); do
  scad --export-format=binstl -o "$tmp/$id.stl" -D "part=\"c:$id\""
done
python3 - "$tmp" viewer/parts.json <<'EOF2'
import base64, json, sys
tmp, out = sys.argv[1], sys.argv[2]
parts, labels, size = [], [], None
for line in open(f"{tmp}/list.echo", encoding="utf-8"):
    line = line.strip()
    if not line.startswith('ECHO: "'):
        continue
    f = line[7:-1].split("|")
    if f[0] == "PART":
        stl = base64.b64encode(open(f"{tmp}/{f[1]}.stl", "rb").read()).decode()
        parts.append(dict(id=f[1], name=f[2], color=f[3], group=f[4], explode=float(f[5]), info=f[6], stl=stl))
    elif f[0] == "LABEL":
        labels.append(dict(text=f[1], pos=[float(v) for v in f[2:5]]))
    elif f[0] == "SIZE":
        size = [float(v) for v in f[1:4]]
json.dump(dict(size=size, labels=labels, parts=parts), open(out, "w", encoding="utf-8"), ensure_ascii=False)
EOF2

render() {  # 파일 이름, 카메라(이동 x,y,z,회전 x,y,z,거리), 추가 -D...
  local out=$1 cam=$2; shift 2
  scad --render -o "renders/$out.png" --imgsize=1200,800 --viewall --autocenter --colorscheme=Tomorrow --camera="$cam" "$@"
}
render assembled   0,0,0,55,0,215,0
render exploded    0,0,0,60,0,30,0  -D 'part="exploded"'
render section     0,0,0,80,0,-25,0 -D 'cutaway=true'
render electronics 0,0,0,50,0,30,0  -D 'part="electronics"'
render usb-end     0,0,0,90,0,-90,0
render print       0,0,0,40,0,20,0  -D 'part="print"'

# 3D펜 도면, 출력 모델 치수 도면 (SVG + PDF)
python3 pen_drawing.py
python3 print_drawing.py
