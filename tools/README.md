# tools/

PC(macOS)에서 돌리는 Python 도구예요. 펌웨어와 앱을 **서로 없이** 검증할 때 써요.

- 펌웨어 검증: `sync.py`가 앱 대신 기기에서 세그먼트를 받아요 (PRD T2).
- 앱 검증: `fake_owl.py`가 보드 대신 프로토콜 v1 기기 역할을 해요.
- 둘 다 없이: 테스트가 같은 프로세스 안에서 가짜 기기와 동기화 흐름 전체를 돌려요.

| 파일 | 용도 |
|---|---|
| `owlproto.py` | 프로토콜 레퍼런스 구현. 바이트 레이아웃의 정답지 (계약서라서 여기서는 고치지 않음) |
| `sync.py` | PC 동기화 CLI: `sync`, `status`, `list`, `set-recording`, `set-config`, `render` |
| `fake_owl.py` | 가짜 Owl. macOS에서 진짜 BLE로 광고(`bless`), 또는 Android 에뮬레이터의 가상 블루투스에 붙어 광고(`--transport bumble`) |
| `ble_scan.py` | 근처 Owl 찾기, STATUS 읽기 |
| `owlsync/` | 위 도구들이 쓰는 패키지 (아래 표) |
| `tests/` | `test_owlproto.py`(스펙 숫자), `test_sync.py`(동기화 E2E + 고장 주입), `test_audio.py`(Ogg/Opus), `test_security.py`(본딩 규칙, loopback), `test_bumble.py`(본딩 규칙, 진짜 SMP·ATT), `test_week.py`(QA용 일주일 시드) |

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
| `ble_bumble.py` | Bumble 주변기기: 에뮬레이터(netsim)·USB 동글·테스트용 가상 링크. 프로토콜 2.4장 보안을 펌웨어처럼 지킴 |
| `week.py` | 앱 QA용 일주일 시드와 부팅별 재생 (`fake_owl.py --week`) |

## 설치

```bash
cd tools
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt                 # bleak, pytest, bumble (Linux CI도 이것만)
pip install -r requirements-peripheral.txt      # fake_owl.py BLE 모드용 bless (macOS, 선택)
pip install -r requirements-bumble.txt          # fake_owl.py --transport bumble (에뮬레이터, 선택)
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

`test_security.py`는 BLE 보안(프로토콜 2.4장)을 loopback으로 확인해요: 처음 연결하면 페어링·본딩,
본딩된 PC는 다시 페어링하지 않음, 모르는 기기는 거절되고(재시도 없이 바로 실패) 아무것도 안 지워짐,
STATUS는 페어링 없이 읽힘, 켠 뒤 2분 안의 새 페어링이 옛 본딩을 대체, 2분 뒤엔 거절, UNPAIR 후 다시 열림.
loopback은 central 이름(`peer`)으로 본딩을 흉내 내요. 암호화 자체는 흉내 내지 않아요.

`test_bumble.py`는 같은 규칙을 **진짜 BLE 호스트 스택 두 개**로 확인해요. Bumble의 가상 컨트롤러끼리 프로세스 안에서
연결돼서(라디오·에뮬레이터 없음) 실제 SMP 페어링·암호화·ATT 오류가 오가요. 테스트용 central은 Android처럼 ATT 0x05·0x0F를
받으면 페어링(키 없음)이나 암호화(키 있음)를 하고, Security Request를 받으면 저장된 키로 암호화해요. 확인하는 것: 첫 동기화에서
본딩 + 전체 다운로드(MTU 247), 모르는 상대는 STATUS만 읽고 CCCD·CONTROL은 0x05, 본딩된 폰은 Security Request를 받고 암호화
전엔 0x0F, 창 밖의 새 폰은 SMP 0x08로 거절되고 옛 본딩·키는 그대로, 껐다 켜면 새 폰이 옛 본딩을 대체(그 부팅에선 옛 폰이 못
되돌림), LE legacy(0x03)·본딩 안 하는 요청(0x08) 거절, UNPAIR 응답 뒤 키 삭제·연결 끊김, 60초(테스트는 0.3초) 암호화
안 된 연결 끊기(HCI 0x05), FETCH 도중 끊김 → 이어받기.

## sync.py

```bash
python sync.py sync --out owl-data            # 가장 센 Owl-* 에 연결해서 전부 받고 ACK
python sync.py sync --out owl-data --no-ack   # 받기만 하고 기기에서 안 지움 (안전한 시험용)
python sync.py --name Owl-1A2B status         # 특정 기기 (--address 도 됨, macOS는 UUID). 페어링 없이 읽혀요
python sync.py pair                           # 이 PC를 기기와 본딩 (한 번)
python sync.py unpair                         # 기기가 모든 본딩을 지우고, 이 PC도 기기를 잊음
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
- **페어링(프로토콜 2.4장)**: 기기는 본딩된 폰·PC하고만 동기화해요. 새 페어링은 본딩된 기기가 없을 때,
  또는 기기를 켠 뒤 2분 안에만 돼요(그러면 이전 폰의 본딩을 대체). 거절되면 `not paired:` 안내와 함께 종료 코드 2로
  끝나고 다시 시도하지 않아요.
  - Linux(BlueZ)·Windows: `pair`가 `BleakClient.pair()`를 불러요. Just Works라 BlueZ 에이전트 없이 된다고 봤지만
    실제 기기로는 확인 안 됨.
  - macOS: 코드로 페어링하는 API가 없어서(bleak 소스 확인) 처음 구독할 때 CoreBluetooth가 알아서 페어링해요(시스템 창).
    `unpair`의 PC 쪽 삭제도 안 돼서 시스템 설정 › 블루투스에서 "이 기기 지우기"를 해야 해요.
  - PC를 페어링하면 폰의 본딩이 지워져요(본딩은 한 번에 하나, `decisions.md` #35). PC로 시험한 뒤엔 폰 앱에서 다시 등록하세요.

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

### Android 에뮬레이터에서 앱의 진짜 BLE 경로 시험하기 (`--transport bumble`)

Android 에뮬레이터의 블루투스는 가상 컨트롤러예요(netsim, 에뮬레이터 33+ 기본). [Bumble](https://google.github.io/bumble/)
(Google의 Python 블루투스 호스트 스택)이 같은 가상 라디오에 두 번째 컨트롤러로 붙어서, 가짜 Owl이 에뮬레이터 폰 바로 옆의 BLE 기기가
돼요. 앱은 코드 수정 없이 진짜 경로(CDM 스캔·선택 창, `connectGatt`, MTU, `createBond()`, 암호화, 알림)로 이 가짜와 통신해요.
Mac의 블루투스 권한도 필요 없어요(라디오가 netsim 안에만 있어서).

```bash
pip install -r requirements.txt -r requirements-bumble.txt
# 에뮬레이터를 먼저 켜요 (예: emulator -avd Medium_Phone). netsim은 $TMPDIR/netsim.ini에 gRPC 포트를 적고, Bumble이 그걸 읽어요.
python fake_owl.py --transport bumble --state /tmp/owl.json            # Owl-FA4E (C0:0F:F0:00:FA:4E)
python fake_owl.py --transport bumble --state /tmp/owl.json --pairing-window 10   # 창 밖 거절을 빨리 보려면
```

- `--state X.json`이면 본딩 키가 `X.keys.json`(Bumble `JsonKeyStore`)에 남아요. 다시 띄우는 것 = 껐다 켜기(부팅 번호 +1, 페어링 창 열림).
  주소는 이름 끝 4자리에서 만들어서(`Owl-FA4E` → `C0:0F:F0:00:FA:4E`) 다시 띄워도 폰의 본딩이 그대로 맞아요.
- 실행 중 표준 입력 명령: `reboot`(연결 끊고 껐다 켜기), `record`(녹음 꺼짐·가득 참이면 안 함), `bookmark`, `rec`(버튼 2초:
  녹음 끄기/켜기, 이벤트 원인 0), `battery MV`(3600 아래로 내려가면 배터리 부족 이벤트), `storage KB`(플래시 크기, 가득 참 시험),
  `status`(STATUS·본딩·녹음 상태 출력), `drop N`(다음 FETCH에서 DATA N개 뒤 연결 끊기, 이어받기 시험),
  `lose N`(다음 FETCH의 N번째 DATA 하나만 안 보내기, 빈 곳 다시 받기 시험), `next`(`--week`: 다음 부팅으로 바로).
- 앱의 debug 빌드에서 `adb shell am broadcast -a dev.owl.app.debug.FAKE -p dev.owl.app --es cmd sync`로 바로 동기화할 수 있어요
  (등록된 기기가 진짜든 가짜든 같은 명령).

#### 일주일 치 데이터 (`--week`, 앱 QA용)

```bash
python fake_owl.py --transport bumble --name Owl-0A7E --state /tmp/qa.json --week --locations-out /tmp/qa_fixes.csv \
    --phone-clock-offset "$(python3 -c "import time,subprocess; t=time.time(); e=float(subprocess.check_output(['adb','shell','date','+%s.%N'])); print(round(e-(t+time.time())/2,3))")"
```

[`owlsync/week.py`](owlsync/week.py)가 오늘을 기준으로 7일 전 아침부터 지금(20분 전)까지 녹음·북마크·녹음 꺼짐·재부팅(시각 동기화를 못 한 부팅 포함)·
배터리 부족·저장 공간 가득 참·2분 묶기 경계·위치 없는 시간을 만들어요. 날짜별 내용과 시나리오는 [`docs/qa/scenarios.md`](../docs/qa/scenarios.md).

- **같은 데이터**: 오늘 날짜·`--seed`(기본 7)·state(부팅 번호·다음 id)가 같으면 같아요. 다시 하려면 state·키 파일을 지우고 앱도 지워요
  (id가 이미 받은 것과 겹치면 앱은 받은 것으로 알고 ACK만 해요).
- **부팅을 하나씩**: 폰은 그 부팅 안의 TIME_SYNC로만 시각을 복원해요(프로토콜 4장). 그래서 가짜 기기가 지난 부팅을 "지금 uptime = 지금 −
  켜진 시각"으로 하나씩 보여주고, 그 부팅에서 TIME_SYNC가 있던 연결이 끊기면 다음 부팅으로 넘어가요. 동기화 세 번이면 일주일이 다 들어와요.
  동기화를 못 한 부팅은 건너뛰고 그 녹음은 다음 부팅과 같이 넘겨요("시각 알 수 없음").
- **규모**: 시각은 실제처럼, 오디오는 짧게(대화 = 60–110초 간격의 2–3초 세그먼트). 약 530개, 2MB.
- **폰 시계**: 에뮬레이터 시계는 이 컴퓨터와 몇 초 달라요(이번엔 −9.4초). `--phone-clock-offset`을 주면 녹음이 폰 시각으로 정확히 그 시각(14:00:00)에 놓여요.
- **위치**: 지난 날의 폰 GPS는 에뮬레이터 `geo fix`로 못 만들어서(지금 시각으로만 찍힘) CSV로 쓰고 앱 debug 명령 `import-locations`로 넣어요
  ([`android/README.md`](../android/README.md) 가짜 기기 모드 표).
- 테스트: `tests/test_week.py`(결정성, 경계 사례가 데이터에 있는지, 부팅별 재생이 PC 엔진으로 정확한 시각을 내는지).

가짜 기기가 펌웨어(`firmware/src/ble.c`)처럼 하는 것 (프로토콜 2.4장):

| 규칙 | 구현 |
|---|---|
| LE Secure Connections + Just Works + 본딩 필수 | Pairing Request의 AuthReq를 보고 거절. 본딩 안 함 → SMP 0x08, legacy → 0x03 (Zephyr `smp_pairing_req()`의 `BONDING_REQUIRED`·`SC_PAIR_ONLY`와 같은 순서·코드) |
| 페어링 창 120초, 창에서 새 본딩 하나 | `OwlDevice.pairing_open()`이 닫혀 있으면 SMP 0x08 (Zephyr `pairing_accept()` → `PAIR_NOT_ALLOWED` → `smp_err_get()` = 0x08) |
| 새 본딩은 성공한 뒤에만 옛 본딩을 지움 | SMP 완료 이벤트에서 `OwlDevice.on_bonded()` → 키 저장소에서 다른 상대 삭제 |
| CCCD 쓰기·CONTROL 쓰기는 암호화 필요, STATUS 읽기는 열림 | 직접 검사해서 본딩 없으면 ATT 0x05, 본딩은 있는데 암호화 전이면 0x0F (Bumble 기본 권한은 항상 0x0F라 안 씀) |
| 본딩된 폰에만 Security Request | 연결되면 키 저장소에 그 폰이 있을 때만 보냄. 폰의 RPA(바뀌는 주소)는 Bumble이 IRK로 풀어서 identity 주소로 비교 |
| 60초 안에 암호화 안 되면 HCI 0x05로 끊기 | `--unencrypted-timeout`(기본 60) |
| UNPAIR | 응답이 나간 뒤(`drain`) 모든 키 삭제 → 연결 끊기 |
| 연결은 하나 | 두 번째 연결은 바로 끊음 |

에뮬레이터(netsim)에서 흉내가 다른 것:

- **기기가 키를 잃었을 때**: 진짜 컨트롤러는 LTK 요청을 거절하면 폰 쪽 암호화가 `0x06`(PIN or Key Missing)으로 실패해요.
  netsim의 컨트롤러(rootcanal)는 거절을 0 LTK로 보내고 폰 쪽은 `0x05`(Authentication Failure)로 끝나요
  (AOSP `tools/rootcanal/model/controller/link_layer_controller.cc` 확인). 그래서 Android의 키 분실 처리(`ACTION_KEY_MISSING`)는
  에뮬레이터에서 볼 수 없어요. 앱은 `ACTION_ENCRYPTION_CHANGE`(status 5)로도 알아채서 "다시 등록 필요"까지는 시험돼요
  (`decisions.md` #44).
- MTU·PHY·처리량은 netsim 값이에요(MTU 247, 2M PHY는 받아들여짐). 실제 라디오의 유실·간섭은 없어요.

### macOS BLE에서 흉내 못 내는 것

- **페어링 창·본딩 하나 규칙·페어링 거절 (프로토콜 2.4장)**: CoreBluetooth가 모든 페어링을 스스로 받아들이고
  주변기기 쪽에 거절할 방법을 주지 않아요(bless·CoreBluetooth API 확인). 그래서 가짜 기기는 아무 폰과도 본딩돼요.
  암호화 요구는 흉내 내요: CONTROL 쓰기는 `write_encryption_required`, 알림 특성 셋은 CoreBluetooth의
  NotifyEncryptionRequired 속성이라 구독하려면 먼저 페어링해야 해요. STATUS는 기기처럼 페어링 없이 읽혀요(확인 안 됨: 권한 문제로
  실제 BLE로 돌려보지 못함). STATUS bit4(페어링 열림)는 기기 모델 값이라 macOS가 맺은 본딩을 몰라요.

- **MTU·2M PHY·DLE·연결 간격**: 주변기기(peripheral) 쪽에서 요청할 수 없어요. 폰(central)이 정해요.
  가짜 기기는 central의 `maximumUpdateValueLength`를 읽어서 청크 크기를 맞춰요 (MTU 247일 때만 236B).
- **같은 Mac끼리 연결 (확인 안 됨)**: Mac은 자기가 광고하는 기기에 붙을 수 없다고 알려져 있어요. 권한 문제로 직접
  확인하지 못했어요. `sync.py`는 다른 컴퓨터에서, 아니면 폰 앱으로 시험하세요.
- **이름 (확인 안 됨)**: 광고 패킷의 local name은 `Owl-FA4E`예요. 연결 후 읽는 GAP 기기 이름은 Mac 이름일 거예요.
- **연결 끊김 콜백 없음**: 모든 특성 구독이 풀리면 끊긴 것으로 봐요.
- **블루투스 권한**: macOS는 앱마다 블루투스 권한을 줘요. Terminal.app에서 실행하고 권한 창을 허용하세요.
  권한이 없으면 20초 뒤 안내 메시지와 함께 끝나요. Claude 앱 안의 터미널에서는 권한 상태가 '결정 안 됨'에서
  바뀌지 않아 블루투스가 켜지지 않았어요(이 Mac에서 확인).
- 전송 큐가 차면 CoreBluetooth의 `updateValue`가 NO를 돌려줘요(Apple 문서). `bless`는 그 값을 돌려주기만 하고
  다시 보내지 않아서(소스 확인), 가짜 기기가 기다렸다가 다시 보내요.

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
