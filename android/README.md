# android/ — Owl 앱

목걸이에서 녹음을 받아 폰에 저장하고, 시각·장소·텍스트로 타임라인을 보여주는 Android 앱이에요.
기준 문서는 [PRD](../docs/prd.md) 5.2(A1–A16)·N4·N8, 프로토콜은 [`protocol/ble-protocol.md`](../protocol/ble-protocol.md), 화면은 [`docs/design/android/`](../docs/design/android/README.md)예요.

| 항목 | 값 |
|---|---|
| 패키지 | `dev.owl.app` |
| minSdk / target·compile | 31 / 36 |
| 빌드 | Gradle 8.14.3 (wrapper), AGP 8.13, Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 (라이트/다크, dynamic color 끔) |
| 저장 | Room(`owl.db`), DataStore(설정), `filesDir/segments/`(세그먼트 원본) |
| 백그라운드 | CompanionDeviceManager + 포그라운드 서비스(`connectedDevice`) + WorkManager |
| DI | 수동 — [`AppContainer.kt`](app/src/main/java/dev/owl/app/AppContainer.kt) 한 파일에서 전부 연결 |

## 빌드·실행

JDK 17 이상(21에서 확인)과 Android SDK(platform 36)가 필요해요. `local.properties`에 `sdk.dir=…`를 두거나 `ANDROID_HOME`을 설정하세요.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest lint
```

설치·실행 (에뮬레이터나 폰이 연결된 상태에서):

```bash
./gradlew installDebug
adb shell am start -n dev.owl.app/.MainActivity
```

CI(`.github/`)도 같은 명령을 돌려요.

### 가짜 기기 모드 (debug 빌드)

목걸이 없이 전체 흐름(등록 → 동기화 → 저장 → ACK → STT → 타임라인)을 돌려볼 수 있어요.
온보딩에서 **"가짜 기기로 시작 (개발용)"** 을 누르면 메모리 안의 `FakeOwlDevice`가 등록돼요.

- 처음 등록하면 이틀 치 기록을 만들어 둬요: 어제 저녁·오늘 녹음, 북마크 2개, 장소 4곳, 그리고 **시각 동기화를 한 번도 못 한 부팅**의 녹음 1개("시각 알 수 없음" 확인용).
- 앱이 떠 있는 동안 4분마다 새 녹음이 생겨요. DATA는 3ms 간격으로 보내서 "녹음 파일 받는 중" 진행 표시가 보여요. 청크를 0.5% 확률로 버려서 이어받기 경로도 늘 지나가요.
- 오디오는 Android 내장 Opus 인코더로 만든 합성음(사람 목소리 비슷한 버즈)이라 재생돼요. 에뮬레이터 인코더가 거의 실시간 속도라 6.4초를 한 번 인코딩해서 반복해 써요.
- 가짜 기기의 "플래시"는 메모리에만 있어요. 앱을 다시 켜면 빈 상태로, 세그먼트 id와 boot_count를 이어서 시작해요(전원이 껐다 켜진 것처럼).
- 텍스트는 `DemoTranscriber`가 넣는 고정 문장이에요. **음성 인식이 아니에요** (진짜 엔진은 M6).

adb로 가짜 기기를 조작할 수 있어요 (결과는 `adb logcat -s OwlFake OwlSync`):

```bash
adb shell am broadcast -a dev.owl.app.debug.FAKE -p dev.owl.app --es cmd register
```

| `cmd` | `--el arg` | 하는 일 |
|---|---|---|
| `register` | | 가짜 기기 등록 (UI 없이) |
| `sync` | | 동기화 한 번 |
| `record` | 초 (기본 60) | 녹음 세그먼트 추가 |
| `bookmark` | | 버튼 짧게 (북마크 이벤트) |
| `disconnect` | 청크 수 (기본 40) | 다음 FETCH 도중 그 청크 수 뒤에 연결 끊기 |
| `drop` | 청크 번호 | 다음 FETCH에서 그 청크 버리기 |
| `fail-connect` | 횟수 (기본 3) | 다음 연결 N번 실패 → 재시도 다 실패하면 "연결 끊김" |
| `reboot` | | 기기 재시작 (boot_count +1) |
| `button-rec` | | 버튼 2초 (녹음 끄기/켜기) |
| `battery` | mV | 배터리 전압 바꾸기 |

이 receiver는 debug 빌드에만 있고, adb shell만 가진 `DUMP` 권한으로 막혀 있어요.

## 구조 (구현된 모습)

```
CompanionDeviceManager ──(목걸이 범위 진입/이탈)──▶ OwlCompanionService
                                                         │ setDevicePresent + 시작
                                                         ▼
                                  SyncService (foreground, type=connectedDevice)
                                    │  근처에 있는 동안 15분마다 + "지금 동기화" + 앱 켤 때
                                    ▼
                         SyncCoordinator ── 재시도(2s, 5s) 후 실패 → "연결 끊김"
                           │                  본딩 거절(NotBondedException) → 재시도 없이 "다시 등록 필요"
                           │ OwlClient (seq 매칭, 타임아웃)
                           │   └─ OwlTransport ── BleTransport (실제 GATT)
                           │                   └─ FakeOwlDevice (메모리, debug)
                           ▼
                         SyncEngine: STATUS → TIME_SYNC → LIST(26개씩) → FETCH(이어받기)
                                     → CRC32 → .part fsync → rename → DB → ACK
                           │
          afterSync ───────┼─ PlaceResolver (가장 가까운 GPS, 이름 붙은 장소)
                           ├─ TranscribeWorker (WorkManager) → Transcriber
                           └─ RetentionManager (A14)

LocationRecorder: FusedLocationProvider + PendingIntent → LocationReceiver → Room
SyncWorker: 15분 주기 WorkManager (CDM이 못 깨웠을 때의 안전망)
```

| 패키지 | 내용 |
|---|---|
| `protocol/` | 프로토콜 v1 코덱. 안드로이드 의존 없음. `tools/owlproto.py`와 바이트 단위로 같음 |
| `device/` | `OwlTransport`(GATT 경계), `OwlClient`(요청/응답), `Battery`(전압→%, 남은 시간) |
| `device/fake/` | `FakeOwlDevice`(펌웨어 역할), `FakeDemo`(시드·adb 명령), `OpusToneSource` |
| `ble/` | `BleTransport`, `CdmRegistrar`(A1), `BondManager`(본딩, 프로토콜 2.4장), `OwlCompanionService` |
| `sync/` | `SyncEngine`, `SyncCoordinator`, `SegmentFiles`, `SyncService`, `SyncWorker` |
| `data/` | Room 엔티티·DAO, `RoomSyncDb`, `TimelineRepositoryImpl`, `SettingsStore`, `PlaceResolver` |
| `audio/` | `OpusPlayer` — MediaCodec Opus 디코더 + AudioTrack, 문장 탐색 |
| `stt/` | `Transcriber` 인터페이스, `PlaceholderTranscriber`, `DemoTranscriber`, `TranscribeWorker` |
| `retention/` | 보관 기간 규칙(A14), `RetentionWorker` |
| `export/` | `JsonExporter` — SAF로 사용자가 고른 파일에 JSON (A13) |
| `ui/` | Compose 화면 (스플래시, 온보딩, 타임라인, 기록 상세, 검색, 기기, 설정, 보관 기간, 개인정보, 사용 설명) |
| `model/` | UI가 보는 모델과 인터페이스 (`Contracts.kt`) |

### 유실 없는 동기화 (N4, A3)

- 받는 동안 바이트는 `segments/<기기>/<id>.part`에 순서대로 붙여요. 파일 길이 = 받은 위치라서, 끊겼다 다시 연결하면 **그 위치부터** FETCH해요.
- 청크는 순서대로만 받아요(go-back-N). 중간 청크가 빠지면 ABORT 후 그 위치부터 FETCH를 다시 보내고, 2초 동안 DATA가 없으면(마지막 청크 유실 등) 똑같이 ABORT + 다시 FETCH해요. DATA에는 끝 표시가 없어서 LIST의 `size`만큼 다 채우면 끝이에요.
- LIST는 "더 있음" 표시가 없고 한 페이지가 26개보다 적을 수 있어서(MTU에 맞춤), 빈 페이지가 올 때까지 마지막 id로 계속 물어봐요.
- 다 받으면 CRC32 계산 → 헤더 검증 → `fsync` → `<id>.owls`로 rename(디렉터리도 fsync) → DB에 RECEIVED → **그다음에** ACK.
- 저장 후 ACK 전에 앱이 죽으면, 다음 LIST에 같은 세그먼트가 또 나와요. 이미 저장된 걸 알아보고 다시 받지 않고 ACK만 보내요.
- 기기가 CRC 불일치(4)를 돌려주면 로컬 사본을 버리고 처음부터 다시 받아요. 헤더가 깨진 세그먼트는 ACK하지 않아서 기기에 그대로 남아요.

### 시각 복원 (A4, 프로토콜 4장)

연결할 때마다 TIME_SYNC를 보내고, (boot_count, 기기 uptime, 폰 시각)을 저장해요. 폰 시각은 요청 보낸 시각과 응답 받은 시각의 **중간값**이에요(무선 지연 오차 절반).
같은 boot_count의 sync가 생기는 순간 그 부팅의 세그먼트·이벤트 시각을 전부 채워요. sync가 없는 부팅은 "시각 알 수 없음"으로 남아요.
세그먼트 id는 재부팅해도 계속 커지니까(프로토콜 3.1) id 순서 = 녹음 순서예요. 그래서 시각을 모르는 녹음은 바로 앞 id의 녹음 뒤에 놓아요(받은 시각에 놓으면 더 최근 녹음보다 아래로 가요).

## 타임라인 항목, 북마크, 장소 (사용자 결정 반영)

**항목 = 이어진 세그먼트 묶음** (PRD A7). 목걸이는 VAD 쉼이 생길 때마다, 그리고 최대 300초마다 세그먼트를 새로 시작해서 대화 하나가 여러 세그먼트가 돼요. 그래서 타임라인에서는 가까운 세그먼트를 한 항목으로 합쳐요 ([`data/EntryGrouping.kt`](app/src/main/java/dev/owl/app/data/EntryGrouping.kt), 단위 테스트 `EntryGroupingTest`).
- 앞 세그먼트 끝 → 다음 세그먼트 시작 간격이 `ENTRY_MERGE_GAP_MS`(2분) 이하이면 합쳐요. 복원된 실제 시각으로 재요.
- 시각을 모르는 부팅의 세그먼트는 합치지 않아요. 부팅이 다르거나, 사이에 "녹음 꺼짐" 이벤트가 있어도 합치지 않아요.
- 상세 화면은 합친 세그먼트를 이어서 하나의 녹음처럼 재생하고(사이 간격은 건너뜀), 문장·북마크 시각도 이어진 시간축으로 보여줘요. 음성 보존·삭제·텍스트 복사는 항목 전체에 적용돼요.
- 묶음은 `EntryIndexer`가 동기화가 끝날 때마다, 앱을 켤 때, 삭제한 뒤에 다시 계산해서 DB(`segments.entryKey`)에 적어 둬요. 받는 중인 세그먼트는 동기화가 끝날 때까지 따로 보일 수 있어요.

**녹음 밖의 북마크** (A9). 북마크가 어느 항목의 시간 범위(항목 안의 간격 포함)에도 들지 않으면, 그 시각에 독립된 북마크 줄로 보여요. 북마크 필터와 검색("북마크만", 검색어 없음)에도 나와요. 기록을 지우면 그 기록에 붙은 북마크도 같이 지워요.

**장소 이름은 사용자가 붙여요** (A5/A7). 역지오코딩은 네트워크를 써서(N8) 하지 않아요.
- 이름이 없으면 "알 수 없는 장소"로 보여요(좌표는 상세 화면에서만).
- 기록 상세에서 "이 장소 이름 붙이기"를 하면 그 위치 반경 `PLACE_RADIUS_M`(100m) 안의 모든 기록에 이름이 붙어요(지난 기록과 앞으로의 기록 모두).
- 100m는 건물 하나 + 절전 모드 GPS 오차 정도로 잡은 값이에요. 실내 GPS 오차가 더 클 수 있어서 실제 폰으로 확인해야 해요(가정).
- 설정 › 장소에서 이름을 바꾸거나 지울 수 있어요. 지우면 그 기록들은 다시 "알 수 없는 장소"가 돼요.

DB는 v2예요. v1에서 올라올 때 칸만 추가하는 마이그레이션(`MIGRATION_1_2`)이라 저장된 녹음은 그대로 남아요.

## 에뮬레이터 확인

스크린샷(라이트·다크)과 동기화 로그는 [`docs/screenshots/`](docs/screenshots/README.md)에 있어요.
가짜 기기로 전체 동기화(LIST → FETCH → 저장 → ACK), FETCH 도중 끊김 후 이어받기, 청크 유실 복구, 재시도 실패 → "연결 끊김", 녹음 스위치, 재생, 보관 기간 삭제, 내보내기를 확인했어요.

### 진짜 BLE 경로: 에뮬레이터 + Bumble 가짜 목걸이

`FakeOwlDevice`는 `OwlTransport` 아래를 건너뛰어서 무선 경로(`BleTransport`, `CdmRegistrar`, `BondManager`)는 지나가지 않아요.
이 경로는 [`tools/fake_owl.py --transport bumble`](../tools/README.md#android-에뮬레이터에서-앱의-진짜-ble-경로-시험하기---transport-bumble)로 시험해요.
Bumble이 에뮬레이터의 가상 블루투스(netsim)에 BLE 기기로 붙고, 앱은 **코드 수정이나 debug 우회 없이** 실제 목걸이처럼 등록·동기화해요.

```bash
emulator -avd Medium_Phone &                      # 먼저 에뮬레이터
cd tools && pip install -r requirements.txt -r requirements-bumble.txt
python fake_owl.py --transport bumble --state /tmp/owl.json
# 앱: 온보딩 › 목걸이 찾기 › Owl-FA4E 허용.  동기화: adb shell am broadcast -a dev.owl.app.debug.FAKE -p dev.owl.app --es cmd sync
```

2026-11-01, 에뮬레이터 36.1.9 · API 37 · debug 빌드에서 확인한 것 (로그는 앱 logcat과 가짜 기기 출력):

| 흐름 | 결과 |
|---|---|
| CDM 등록 | 선택 창에 `Owl-FA4E`가 뜸(이름 패턴 + 서비스 UUID 필터) → 허용 → `createBond()`가 **확인 창 없이** 진행(`canBondWithoutDialog=true`, #40의 AOSP 분석과 같음) → LESC Just Works 본딩 → 등록 완료 |
| 첫 동기화 | MTU 247, 2M PHY, TIME_SYNC → LIST → FETCH → 저장 → ACK 7개, 타임라인에 표시 |
| 본딩된 재연결 | 가짜 기기가 폰의 RPA를 identity 주소로 풀고 Security Request → 폰이 저장된 키로 암호화(다시 페어링 안 함) → 동기화. 가짜 기기를 다시 띄워도(키 파일) 같음 |
| FETCH 도중 끊김 | `drop 40` → 앱이 다음 시도에서 `resuming at byte 9440 of 32207`로 이어받음 |
| 녹음 스위치 | 끄기·켜기가 SET_RECORDING으로 가짜 기기에 반영 |
| 등록 해제(UNPAIR) | 가짜 기기가 응답 뒤 키 삭제·연결 끊기, 폰은 `CompanionDeviceManager.removeBond`로 본딩 삭제(이유 9 REMOVED) → CDM 해제 → 온보딩. 다시 등록하면 창과 상관없이 받아짐(본딩 없음) |
| 목걸이가 꺼져 있을 때 등록 해제 | UNPAIR가 15초 뒤 실패해도 폰 본딩·CDM은 지워짐(2.4장 표와 같음) |
| 창 밖 페어링 거절 | 가짜 기기는 옛 본딩을 가진 채 창이 닫힘 → 등록 시도 → SMP Pairing Failed 0x08 → 폰 bond state NONE(이유 1) → 앱이 CDM 등록을 되돌리고 "껐다 켠 뒤 2분 안에" 안내 |
| 껐다 켠 뒤 다시 등록 | `reboot` → 창 안에서 등록 성공, 옛 본딩은 새 본딩이 생긴 뒤에 교체 |

스크린샷: [CDM 선택 창](docs/screenshots/bumble-cdm-light.png), [등록 완료](docs/screenshots/bumble-registered-light.png),
[녹음 꺼짐](docs/screenshots/bumble-recording-off-light.png), [페어링 거절 안내](docs/screenshots/bumble-pairing-refused-light.png),
[키 분실 → 연결 끊김](docs/screenshots/bumble-key-missing-light.png).

확인 못 한 것 / 에뮬레이터라 다른 것:

- **목걸이가 키를 잃은 경우(2.4장 흐름 5)**: 가짜 기기의 키 파일을 지우고 동기화하면 폰이 저장된 키로 암호화하다 실패하고 **폰이
  스스로** 연결을 끊어서 앱은 GATT status 22(0x16, 로컬 호스트가 끊음)를 받아요. 앱은 0x05·0x06만 "본딩 없음"으로 보니까 세 번
  재시도한 뒤 "연결 끊김"을 보여주고 "다시 등록"은 안내하지 않아요([스크린샷](docs/screenshots/bumble-key-missing-light.png)).
  netsim은 이 실패를 진짜 컨트롤러의 0x06 대신 0x05로 내서(`tools/README.md`) Android의 `ACTION_KEY_MISSING` 경로가 돌지 않아요.
  실제 목걸이에서 어떤 status가 오는지는 아직 몰라요.
- 60초 암호화 타임아웃은 앱이 늘 바로 암호화해서 에뮬레이터에선 안 일어나요(`tools/tests/test_bumble.py`에서 확인).
- 2분이 지나 창이 닫히는 것은 `--pairing-window 10`으로 줄여서 확인했어요.
- 처리량·유실은 netsim 값이라 의미 없어요. API 31–35 경로(리플렉션 `removeBond` 등)는 에뮬레이터 API 37만 봤어요.
- 관찰: 폰이 본딩을 지웠다가 다시 페어링하면 폰이 보내는 IRK가 바뀌었어요. 그래서 그 사이(본딩 삭제 뒤, 새 페어링 전)엔 가짜 기기가
  폰의 주소를 풀지 못하고 모르는 상대로 봐요. 규칙엔 영향 없어요(어차피 창 안에서만 받음).

## 테스트

`./gradlew testDebugUnitTest` — JVM 단위 테스트예요.

| 테스트 | 확인하는 것 |
|---|---|
| `ProtocolTest` | STATUS, 모든 CONTROL 요청(SET_RECORDING 포함)·응답, LIST, DATA 청크, 세그먼트 헤더·오디오 프레임·이벤트, CRC32, 시각 복원. 바이트는 `tools/owlproto.py`가 만든 값 |
| `SyncEngineTest` | 가짜 기기 상대로 전체 동기화(LIST 페이지 2개), 청크 유실 복구, 마지막 청크 유실, **FETCH 도중 끊김 → 다음 동기화에서 이어받기**, 저장 후 ACK 전 죽음 → 재ACK, CRC 불일치, 깨진 세그먼트 |
| `BatteryTest`, `RetentionRulesTest`, `JsonExporterTest` | 전압 곡선, 보관 기간 규칙, 내보내기 형식 |

프로토콜이 바뀌면 픽스처를 다시 만들어요 (레포 루트에서):

```bash
python3 android/app/src/test/fixtures/gen_protocol_fixtures.py
```

## 본딩 (프로토콜 2.4장, `decisions.md` #33–#40)

- 등록: CDM 선택 → `BondManager.bond()`가 `createBond()` 후 `ACTION_BOND_STATE_CHANGED`를 기다림(60초) → 성공해야 등록 저장.
  목걸이가 거절하면(다른 폰이 본딩돼 있고 켠 지 2분이 지남) CDM 등록을 되돌리고 "껐다 켠 뒤 2분 안에 다시" 안내.
- 동기화: 폰에 본딩이 없으면 연결하지 않고(백그라운드에서 페어링 창이 뜨지 않게) "다시 등록 필요". GATT status 5·15·137,
  또는 목걸이가 HCI 0x05·0x06으로 끊으면 `NotBondedException` → 재시도하지 않음.
- 등록 해제: UNPAIR(목걸이가 모든 본딩 삭제, 10초 안에 못 하면 건너뜀) → 폰 본딩 삭제(API 36 `CompanionDeviceManager.removeBond`,
  31–35는 `BluetoothDevice.removeBond` 리플렉션) → CDM 해제. 폰 본딩 삭제가 안 되면 로그만 남겨요(시스템 블루투스 설정에서 지울 수 있음).
- 가짜 기기: 본딩을 흉내 내지 않아요. `refuseAsNotBonded`로 거절을, UNPAIR로 STATUS bit4를 시험해요.
  진짜 본딩 흐름은 에뮬레이터 + Bumble 가짜 목걸이로 확인했어요(위 "진짜 BLE 경로").

## 실제 목걸이로 확인해야 할 것

가짜 기기와 단위 테스트로는 확인할 수 없는 부분이에요. M4에서 보드+폰으로 확인해요.

- 등록 후 범위 진입·이탈 시 `OwlCompanionService`가 불리는지, 특히 앱을 최근 앱에서 지운 뒤 (A2). CDM 선택 창과 범위 진입 시
  `DEVICE_APPEARED` 동기화는 에뮬레이터 + Bumble에서 봤어요
- MTU 247·2M PHY 협상 결과와 실제 처리량. DATA 알림이 빠르게 올 때 유실이 있는지
- 15분 주기가 Doze·삼성 배터리 관리에서도 지켜지는지 (CDM 존재 중 포그라운드 서비스 유지)
- 펌웨어가 FETCH 진행 중에 새 FETCH를 받으면 어떻게 하는지(아래 "프로토콜에서 확인할 것")
- 실제 Opus 세그먼트(펌웨어 인코더) 재생, 프레임 길이 20ms 가정
- 배터리 전압 곡선 보정(TW502030), 남은 시간 추정
- 위치: 백그라운드 위치 권한 + PendingIntent 업데이트가 앱 프로세스 없이 들어오는지
- `SyncService`의 15분 대기: Doze 중엔 코루틴 타이머가 멈춰서 `setAndAllowWhileIdle` 알람으로 깨워요. 몇 분 늦을 수 있어요. 동기화 한 번에 최대 10분 wake lock
- GATT 오류(status 133 등) 처리, API 31–32 경로(에뮬레이터는 API 37만 확인)
- 본딩: 확인 창 없는 등록과 창 밖 거절은 에뮬레이터(API 37)에서 확인. 남은 것: 리플렉션 `removeBond`가 제조사 ROM에서도 되는지,
  목걸이가 키를 잃었을 때 Android 버전별 동작(에뮬레이터에선 앱이 "다시 등록" 대신 "연결 끊김"을 보여줌, 위 참고)
- Opus 디코더: Android 디코더는 48kHz 출력만 받아서 OpusHead에 16kHz를 적고 48kHz로 디코딩해요. pre-skip 312, seek pre-roll 80ms

## 아직 안 된 것 / 다음 마일스톤

- **STT 엔진(M6)**: `Transcriber`만 있어요. 실제 기기에서는 녹음이 "텍스트 추출 중"으로 남아요.
- **장소 이름**: 역지오코딩은 네트워크를 써서(N8 위반) 하지 않아요. 지금은 좌표만 보이고, 이름은 `places` 테이블에 있는 곳만 붙어요(가짜 기기 시드). 장소에 이름 붙이는 UI가 필요해요.
- 여러 기기(A15, P2), 하루 요약(A12, P2)은 범위 밖이에요.
- 작은 것들: 동기화 알림 아이콘이 시스템 블루투스 아이콘이에요(전용 단색 아이콘 필요). 재생 중 오디오 포커스·이어폰 분리 처리 없음. 재생 바에 북마크 점 표시 없음(문장 목록에만). 앱 업데이트 후 위치 업데이트 재등록(`MY_PACKAGE_REPLACED`) 없음.

## 프로토콜·PRD에서 확인할 것

pc-tools 작업에서 정한 프로토콜 v1 보완 사항(아직 `protocol/ble-protocol.md`에는 없음, 한 커밋으로 반영 예정)에 맞춰 앱과 가짜 기기를 구현했어요:
LIST는 빈 페이지까지 페이징, FETCH 진행 중 새 FETCH는 기존 스트림을 대체(앱은 다시 받기 전에 ABORT), DATA 끝 표시 없음 + 2초 무응답이면 ABORT 후 다시 FETCH, 중앙 기기가 연결되면 이벤트 로그를 세그먼트로 봉인, 잘못된 값(FETCH offset > size, SET_CONFIG 범위 밖)과 1바이트 요청은 result 2(1바이트면 seq 0), 같은 상태로 SET_RECORDING은 OK + 이벤트 없음, 청크 크기 = MTU − 11이고 offset으로만 재조립.

아직 문서가 정하지 않은 것:

1. **LIST의 `size`** — 헤더 32바이트를 포함한 전체 크기로 해석했어요(`owlproto.py` SegmentAssembler와 같음).
2. **이벤트 세그먼트의 부팅** — 앱은 이벤트 세그먼트 안의 모든 이벤트를 헤더의 `boot_count`로 해석해요. "부팅마다 새 이벤트 파일" 규칙이 필요해요.
