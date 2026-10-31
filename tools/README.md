# tools/

PC(macOS)에서 돌리는 Python 도구예요. 앱 없이 펌웨어만 따로 검증할 때 써요.

| 파일 | 용도 |
|---|---|
| `lpproto.py` | 프로토콜 레퍼런스 구현. 바이트 레이아웃의 정답지 |
| `tests/test_lpproto.py` | `protocol/ble-protocol.md`의 숫자(청크 236바이트, 헤더 32바이트 등)를 검증 |
| `ble_scan.py` | 근처 Pendant 찾기, STATUS 읽기 |

```bash
cd tools
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m pytest tests          # 하드웨어 없이 돌아가요
python ble_scan.py --status     # 보드가 광고 중일 때
```

예정: `sync.py` (LIST → FETCH → ACK 전체 흐름, 받은 Opus를 `.ogg`로 저장해서 귀로 확인).
