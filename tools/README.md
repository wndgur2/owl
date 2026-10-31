# tools/

PC(macOS)에서 돌리는 Python 도구예요. 펌웨어와 앱을 **서로 없이** 검증할 때 써요.

- 펌웨어 검증: `sync.py`가 앱 대신 기기에서 세그먼트를 받아요 (PRD T2).
- 앱 검증: `fake_owl.py`가 보드 대신 프로토콜 v1 기기 역할을 해요.
- 둘 다 없이: 테스트가 같은 프로세스 안에서 가짜 기기와 동기화 흐름 전체를 돌려요.

| 파일 | 용도 |
|---|---|
| `owlproto.py` | 프로토콜 레퍼런스 구현. 바이트 레이아웃의 정답지 (계약서라서 여기서는 고치지 않음) |
| `sync.py` | PC 동기화 CLI: `sync`, `status`, `list`, `set-recording`, `set-config`, `render` |
| `fake_owl.py` | 가짜 Owl. macOS에서 진짜 BLE로 광고 (`bless`) |
| `ble_scan.py` | 근처 Owl 찾기, STATUS 읽기 |
| `owlsync/` | 위 도구들이 쓰는 패키지 (아래 표) |
| `tests/` | `test_owlproto.py`(스펙 숫자), `test_sync.py`(동기화 E2E + 고장 주입), `test_audio.py`(Ogg/Opus) |

`owlsync/` 안:

| 모듈 | 역할 |
|---|---|
| `transport.py` | Transport 인터페이스(폰/PC 쪽) + 같은 프로세스 loopback + 고장 주입(`Faults`) |
| `client.py` | CONTROL 요청/응답(seq 매칭, BUSY 재시도), LIST 페이징, FETCH 빈틈 감지 |
| `engine.py` | 동기화 흐름: 이어받기(재연결·재실행 후에도), 디스크 저장+fsync **뒤에** ACK, 백오프 재시도 |
| `device.py` | 기기 쪽 프로토콜 v1 모델 (가짜 Owl의 본체) |
| `synth.py` | 가짜 녹음 만들기: 목소리 비슷한 톤 또는 WAV → Opus(libopus 있으면) / PCM16 |
| `ogg.py`, `opus.py` | 순수 Python Ogg Opus 먹서/파서, WAV 쓰기 / libopus ctypes 바인딩(선택) |
| `timesync.py`, `render.py` | TIME_SYNC 기록과 시각 복원 / `.owls` → `.ogg`·`.wav`·`.json`·`.csv` |
| `ble.py`, `ble_peripheral.py` | `bleak` 중앙(central) 전송 / `bless` 주변기기(peripheral) |

## 설치

```bash
cd tools
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt                 # bleak, pytest (Linux CI도 이것만)
pip install -r requirements-peripheral.txt      # fake_owl.py BLE 모드용 bless (macOS, 선택)
brew install opus ffmpeg                        # 선택: 가짜 기기가 진짜 Opus를 만들고, ffprobe로 확인
```

`libopus`가 없으면 가짜 기기는 PCM16(codec 0) 세그먼트를 만들고, Opus 관련 테스트는 건너뛰어요.
`.ogg` 만들기(먹서)는 순수 Python이라 `libopus` 없이도 돼요.

## 테스트 (하드웨어 없이)

```bash
python3 tools/tests/test_owlproto.py   # 저장소 루트에서
python -m pytest tools/tests
```

`test_sync.py`가 loopback으로 확인하는 것: 정상 동기화, ACK 전에 파일이 디스크에 있는지,
DATA 유실(가운데·마지막 청크·무작위 15%), FETCH 중 연결 끊김 → 끊긴 위치부터 이어받기,
프로세스를 새로 띄워도 `.part` 파일로 이어받기, CRC 불일치 → 격리 후 다시 받기, BUSY 재시도,
ACK 응답 유실, CONTROL 응답 유실(타임아웃), 26개 넘는 LIST 페이징, 작은 MTU, 시각 복원,
SET_RECORDING 이벤트, 기기 결과 코드.

## sync.py

```bash
python sync.py sync --out owl-data            # 가장 센 Owl-* 에 연결해서 전부 받고 ACK
python sync.py sync --out owl-data --no-ack   # 받기만 하고 기기에서 안 지움 (안전한 시험용)
python sync.py --name Owl-1A2B status         # 특정 기기 (--address 도 됨, macOS는 UUID)
python sync.py list
python sync.py set-recording off
python sync.py set-config bitrate 12000       # 개발용 (decisions.md #12). 키: bitrate, vad, segment-len
python sync.py render --out owl-data          # raw/ 에서 .ogg/.csv 다시 만들기 (오프라인)
python sync.py --fake sync --out /tmp/owl     # 보드 없이: 같은 프로세스의 가짜 기기와 동기화
```

동작 순서(프로토콜 5장): 연결 → STATUS 읽기 → TIME_SYNC → LIST(26개씩, 빈 응답까지) →
세그먼트마다 FETCH → `raw/*.owls` 저장 + fsync → ACK → 변환.

- **빈틈 감지**: DATA offset이 건너뛰면(또는 2초간 안 오면) ABORT 후 첫 빈 offset부터 다시 FETCH.
- **이어받기**: 끊기면 받은 앞부분을 `raw/seg_N.owls.part`에 저장하고, 다음 연결(다음 실행이라도)에서 그 offset부터 받아요.
- **ACK 순서**: 임시 파일에 쓰고 fsync → 이름 바꾸기 → 폴더 fsync → 그 다음 ACK. 순서가 바뀌면 PRD N4 위반.
- **CRC 불일치**: 기기가 결과 4를 주면 파일을 `.bad*`로 옮겨 두고 처음부터 다시 받아요(2번까지).
- **재시도**: 연결·응답 실패는 지수 백오프로 다시 연결(`--attempts`, 진전이 있으면 횟수 초기화). BUSY는 요청 단위로 재시도.

출력 폴더:

```
owl-data/
  raw/seg_000012.owls       기기에서 받은 바이트 그대로 (원본)
  audio/seg_000012.ogg      Opus 세그먼트 → 어디서나 재생되는 Ogg Opus
  audio/seg_000014.wav      PCM16 세그먼트
  events/seg_000013.json    이벤트 세그먼트 (이름·원인·복원된 시각), .csv 도 같이
  segments.csv              세그먼트 목록: 부팅 번호, 시작 시각, 길이, 파일
  events.csv                모든 이벤트를 시간순으로
  timesync.json             TIME_SYNC 기록 (boot_count, uptime_ms, PC 시각)
```

시각 복원(프로토콜 4장): 같은 `boot_count`의 TIME_SYNC 기록 중 uptime이 가장 가까운 것으로 역산해요.
PC 시각은 요청·응답 왕복의 중간값을 써요. 그 부팅에 sync가 한 번도 없으면 `unknown`.

## fake_owl.py — 보드 없이 앱 시험하기

```bash
python fake_owl.py                              # Owl-FA4E 로 광고, 톤 세그먼트 4개 + 이전 부팅 1개 + 이벤트
python fake_owl.py --wav speech.wav --segments 6 --seconds 5
python fake_owl.py --record-every 60            # 켜져 있는 동안 1분마다 새 세그먼트 (15분 주기 시험)
python fake_owl.py --state fake_state.json      # 실행할 때마다 재부팅처럼 boot_count+1, id 이어짐
```

가짜 기기가 하는 것: STATUS(읽기·알림), CONTROL 전부(결과 코드 포함), DATA 청크(MTU에 맞춰 최대 236B),
ACK하면 삭제, ABORT, SET_RECORDING → 이벤트 2/3(arg 1), SET_CONFIG 범위 검사, `boot_count`/uptime,
이전 부팅(sync 없음) 세그먼트로 "unknown" 시각 시험.

loopback 고장 주입(`transport.Faults`)은 테스트 전용이에요. BLE 모드에는 없어요.

### macOS BLE에서 흉내 못 내는 것

- **MTU·2M PHY·DLE·연결 간격**: 주변기기(peripheral) 쪽에서 요청할 수 없어요. 폰(central)이 정해요.
  가짜 기기는 central의 `maximumUpdateValueLength`를 읽어서 청크 크기를 맞춰요 (MTU 247일 때만 236B).
- **같은 Mac끼리 연결 불가**: Mac은 자기가 광고하는 기기에 붙을 수 없어요. `sync.py`는 다른 컴퓨터에서,
  아니면 폰 앱으로 시험하세요.
- **이름**: 광고 패킷의 local name은 `Owl-FA4E`지만, 연결 후 읽는 GAP 기기 이름은 Mac 이름이에요.
- **연결 끊김 콜백 없음**: 모든 특성 구독이 풀리면 끊긴 것으로 봐요.
- **블루투스 권한**: macOS는 앱마다 블루투스 권한을 줘요. Terminal.app에서 실행하고 권한 창을 허용하세요.
  권한이 없으면 20초 뒤 안내 메시지와 함께 끝나요(다른 앱 안의 터미널은 권한을 못 받을 수 있어요).
- 전송 큐가 차면 CoreBluetooth가 알림을 거절하는데, `bless`는 이걸 무시해요. 가짜 기기는 기다렸다가 다시 보내요.

## 프로토콜 문서에서 정해지지 않은 부분 (가짜 기기·클라이언트가 고른 동작)

`protocol/ble-protocol.md`를 고치려면 펌웨어·앱·도구를 한 커밋에서 같이 바꿔야 해서, 여기서는 고르기만 했어요.

1. **LIST 끝 판단**: "더 있음" 표시가 없어요. MTU가 247보다 작으면 26개가 안 들어가서, 짧은 응답이 끝이라는 보장이 없어요.
   → 클라이언트는 **빈 응답이 올 때까지** 요청, 기기는 MTU에 맞게 개수를 줄여요.
2. **FETCH 중에 FETCH**: 기존 스트림을 어떻게 할지 없어요. → 기기는 새 요청으로 바꿔치기(암묵적 ABORT), 클라이언트는 항상 ABORT 먼저.
3. **스트림 끝**: DATA에 끝 표시가 없어요. → 마지막 바이트를 받거나 2초간 조용하면 끝으로 봐요.
4. **이벤트 로그가 세그먼트가 되는 시점**: 계속 커지는 파일을 LIST에 내보내면 크기가 바뀌어요. → 가짜 기기는 연결될 때 봉인해요.
5. **PCM16(codec 0) payload 형식**: 3.2절은 Opus 기준. → Opus와 같은 `[len u16][바이트]` 프레임에 16비트 LE 샘플로 가정.
6. **"값이 잘못됨" 결과 코드가 없음**: SET_CONFIG 범위 밖·모르는 키, FETCH offset > size → `2`(잘못된 길이)로 응답.
7. **seq도 없는 1바이트 요청**: 응답 seq를 `0`으로.
8. **청크 크기**: "MTU 247이면 236"만 있어요. → MTU−3−8로 줄이고, 클라이언트는 offset만 보고 조립.
9. **SET_RECORDING을 같은 상태로**: 이벤트를 남길지 없어요. → 상태가 바뀔 때만 남겨요.
