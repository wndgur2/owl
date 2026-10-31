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
- 오디오는 Android 내장 Opus 인코더로 만든 합성음(사람 목소리 비슷한 버즈)이라 재생돼요.
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
| `ble/` | `BleTransport`, `CdmRegistrar`(A1), `OwlCompanionService` |
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
- 청크는 순서대로만 받아요(go-back-N). 중간 청크가 빠지면 그 위치부터 FETCH를 다시 보내고, 3초 동안 아무것도 안 오면(마지막 청크 유실 등) 다시 요청해요.
- 다 받으면 CRC32 계산 → 헤더 검증 → `fsync` → `<id>.owls`로 rename(디렉터리도 fsync) → DB에 RECEIVED → **그다음에** ACK.
- 저장 후 ACK 전에 앱이 죽으면, 다음 LIST에 같은 세그먼트가 또 나와요. 이미 저장된 걸 알아보고 다시 받지 않고 ACK만 보내요.
- 기기가 CRC 불일치(4)를 돌려주면 로컬 사본을 버리고 처음부터 다시 받아요. 헤더가 깨진 세그먼트는 ACK하지 않아서 기기에 그대로 남아요.

### 시각 복원 (A4, 프로토콜 4장)

연결할 때마다 TIME_SYNC를 보내고, (boot_count, 기기 uptime, 폰 시각)을 저장해요. 폰 시각은 요청 보낸 시각과 응답 받은 시각의 **중간값**이에요(무선 지연 오차 절반).
같은 boot_count의 sync가 생기는 순간 그 부팅의 세그먼트·이벤트 시각을 전부 채워요. sync가 없는 부팅은 "시각 알 수 없음"으로 남고, 타임라인에서는 받은 시각 위치에 놓여요.

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

## 실제 목걸이로 확인해야 할 것

가짜 기기와 단위 테스트로는 확인할 수 없는 부분이에요. M4에서 보드+폰으로 확인해요.

- CDM 등록 화면에 `Owl-XXXX`가 뜨는지, 등록 후 범위 진입·이탈 시 `OwlCompanionService`가 불리는지 (특히 앱을 최근 앱에서 지운 뒤, A2)
- MTU 247·2M PHY 협상 결과와 실제 처리량. DATA 알림이 빠르게 올 때 유실이 있는지
- 15분 주기가 Doze·삼성 배터리 관리에서도 지켜지는지 (CDM 존재 중 포그라운드 서비스 유지)
- 펌웨어가 FETCH 진행 중에 새 FETCH를 받으면 어떻게 하는지(아래 "프로토콜에서 확인할 것")
- 실제 Opus 세그먼트(펌웨어 인코더) 재생, 프레임 길이 20ms 가정
- 배터리 전압 곡선 보정(TW502030), 남은 시간 추정
- 위치: 백그라운드 위치 권한 + PendingIntent 업데이트가 앱 프로세스 없이 들어오는지

## 아직 안 된 것 / 다음 마일스톤

- **STT 엔진(M6)**: `Transcriber`만 있어요. 실제 기기에서는 녹음이 "텍스트 추출 중"으로 남아요.
- **장소 이름**: 역지오코딩은 네트워크를 써서(N8 위반) 하지 않아요. 지금은 좌표만 보이고, 이름은 `places` 테이블에 있는 곳만 붙어요(가짜 기기 시드). 장소에 이름 붙이는 UI가 필요해요.
- 여러 기기(A15, P2), 하루 요약(A12, P2)은 범위 밖이에요.

## 프로토콜·PRD에서 확인할 것

구현은 현재 문서대로 했고, 아래는 문서가 정하지 않았거나 서로 어긋나는 부분이에요.

1. **FETCH 진행 중 새 FETCH** — 문서에 동작이 없어요. 앱은 빠진 청크가 있으면 진행 중이어도 FETCH를 다시 보내요. 앱은 BUSY(5)를 받으면 ABORT 후 다시 보내도록 했고, 가짜 기기는 "새 FETCH가 이전 것을 대체"로 동작해요. 펌웨어와 맞춰서 문서에 적어야 해요.
2. **LIST의 `size`** — 헤더 32바이트를 포함한 전체 크기로 해석했어요(`owlproto.py` SegmentAssembler와 같음). 문서에 한 줄 적으면 좋겠어요.
3. **세그먼트 길이 vs 타임라인 항목 길이** — 디자인은 "12분", "22분" 항목을 보여주는데 세그먼트 최대 길이는 300초예요(SET_CONFIG 3). 지금은 세그먼트 1개 = 타임라인 항목 1개예요. 이어진 세그먼트를 한 항목으로 합칠지 PRD A7에서 정해야 해요.
4. **북마크가 녹음 구간 밖일 때** — 북마크는 같은 부팅에서 녹음 시간 범위 안에 있을 때만 그 기록에 붙어요. 무음 중에 누른 북마크는 타임라인에 안 보여요. A9에서 따로 표시할지 정해야 해요.
