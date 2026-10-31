# firmware/

Zephyr RTOS + nRF Connect SDK 앱. 보드: Seeed XIAO nRF54L15 Sense. NCS **v3.4.1**(Zephyr 4.4)로 고정.

현재 상태: M1(녹음·저장)·M2(VAD·Opus)·M3(BLE 프로토콜 v1, 버튼·LED)와 M5의 기기 쪽 로직(배터리 경고·차단, System OFF, GRTC 깨우기)까지 구현. **빌드와 호스트 테스트까지만 확인했고 보드에서는 아직 한 번도 돌려보지 않았어요.** 보드가 오면 아래 [bring-up 체크리스트](#bring-up-체크리스트-m0m3)부터.

## 빌드

### Docker (NCS 설치 없이, 권장)

공식 툴체인 이미지(`ghcr.io/nrfconnect/sdk-nrf-toolchain:v3.4.1`, amd64 전용 — Apple Silicon에선 에뮬레이션이라 첫 빌드 2~3분)에 native_sim 테스트용 `make`·32비트 라이브러리만 얹은 로컬 이미지 `owl-ncs:v3.4.1`(`scripts/Dockerfile`)을 써요. NCS 작업 공간(약 5GB)은 Docker 볼륨 `owl-ncs-v3.4.1`에 두고, `scripts/ncs.sh`가 레포를 그 작업 공간 안(`/ncs/owl`)에 마운트해서 명령을 실행해요.

```bash
firmware/scripts/ncs.sh --setup      # 이미지 빌드 + west init -m sdk-nrf --mr v3.4.1 + west update (한 번만)

# 본 앱: 배터리 빌드(UART 끔) / USB 디버깅 빌드(시리얼 로그)
firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/fw firmware'
firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/fw-uart firmware -- -DEXTRA_CONF_FILE=uart.conf'

# M0 bring-up 앱
firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/bringup firmware/bringup -- -DEXTRA_CONF_FILE=uart.conf'

# 하드웨어 없는 테스트 (native_sim, 54개: 로직 45 + 가상 플래시 위 storage 8 + libopus 1)
firmware/scripts/ncs.sh 'west twister -T firmware/tests -p native_sim -O build/twister'
```

결과물은 레포 루트 `build/<이름>/merged.hex`(gitignore). 굽기는 macOS에서 `west flash -d build/fw` 또는 nRF Connect for Desktop Programmer.

### macOS 직접 설치

1. VS Code + **nRF Connect for VS Code Extension Pack**, 확장에서 Toolchain과 NCS **v3.4.1** 설치
2. 보드 정의는 SDK에 있음(`xiao_nrf54l15/nrf54l15/cpuapp`). 플래시용 OpenOCD: `brew install openocd`

```bash
west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware -- -DEXTRA_CONF_FILE=uart.conf   # USB 디버깅
west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware                                  # 배터리 단독
west flash
```

UART는 기본 빌드에서 꺼요. Seeed 위키: UART를 켜면 배터리만으로 부팅이 안 될 수 있음(`docs/decisions.md` #9).

### 첫 빌드는 인터넷이 필요해요 (libopus)

NCS에는 Opus가 없어서 `cmake/opus.cmake`가 **libopus 1.5.2 공식 릴리스 tarball을 SHA-256으로 고정해서** 빌드할 때 받아요. west 모듈로 넣지 않은 이유: 작업 공간을 `west init -m sdk-nrf` 그대로 두려고(CI·README가 단순), 레포에 8MB짜리 외부 코드를 넣지 않으려고. 해시가 고정이라 재현성은 서브모듈과 같아요. 오프라인이면 `-- -DFETCHCONTENT_SOURCE_DIR_OPUS=/path/to/opus-1.5.2`.

빌드 옵션(Cortex-M33 128MHz용):

| 옵션 | 이유 |
|---|---|
| `FIXED_POINT` | 정수 연산. 16kHz 음성에선 float와 음질 같고, 스레드 전환 때 FPU 레지스터 저장이 없음 |
| `DISABLE_FLOAT_API` | float 전용 음색 분석(`analysis.c`)이 빠짐 — CPU를 크게 아낌 |
| `VAR_ARRAYS` | 임시 버퍼를 호출한 스레드 스택(C99 VLA)에. 그래서 audio 스레드 스택이 24KB |
| `OPUS_ARM_INLINE_ASM/EDSP` | M33의 DSP 명령(SMULL/SMULWB/SMLAWB)으로 고정소수점 곱셈 1사이클 |
| `-O2` (이 라이브러리만) | 나머지는 `-Os` 유지 |

인코더 설정: VOIP, 16kHz 모노, wideband, 16kbps 제약 VBR, 복잡도 3(`CONFIG_OWL_OPUS_COMPLEXITY`), DTX·FEC 끔(VAD가 무음을 이미 버림). 세그먼트마다 인코더를 리셋해서 세그먼트 하나가 혼자 디코딩돼요.

## 크기 (NCS v3.4.1)

| 빌드 | Flash (RRAM 1524KB) | RAM (256KB) |
|---|---|---|
| 본 앱 기본 | 293,612 B (18.8%) | 172,088 B (65.7%) |
| 본 앱 `uart.conf` | 327,804 B (21.0%) | 179,824 B (68.6%) |
| bring-up 기본 / `uart.conf` | 48,520 / 59,212 B | 23,904 / 24,064 B |

RAM 큰 항목: 세그먼트 인덱스 32KB(`CONFIG_OWL_SEG_INDEX_MAX`=4096×8B), Opus 인코더 상태 26KB(`CONFIG_OWL_OPUS_ENC_MEM`; native_sim 측정 24,548B, 보드에선 부팅 로그 `opus: N B state`로 확인), audio 스택 24KB, 링 8KB, PDM 블록 7.5KB, 프리롤 6.4KB, 쓰기 버퍼 4KB.

## 구조

```
PDM 마이크 ─▶ audio 스레드: VAD ─▶ Opus ─▶ [RAM 링 8KB] ─▶ writer 스레드 ─▶ storage (LittleFS, W25Q128)
버튼 ─▶ ui (상태 기계) ─▶ main: 북마크·녹음 끄기/켜기·전원 끄기 ─▶ storage 이벤트
폰 ─BLE─▶ ble: CONTROL 큐 ─▶ ble_ctl 스레드 (LIST/ACK/...) ─┐
                             ble_tx 스레드 (FETCH → DATA) ◀──┘── storage 읽기
배터리 ADC ─▶ main (app 워크큐, 60초마다) ─▶ 경고 / System OFF
```

| 파일 | 내용 | 하드웨어 의존 |
|---|---|---|
| `src/owl_proto.[ch]` | 프로토콜 상수·바이트 레이아웃(STATUS, 세그먼트 헤더, 이벤트, LIST 항목), CRC32 | 없음 (테스트됨) |
| `src/control.[ch]` | CONTROL 요청 파싱·응답 인코딩. 실제 동작은 ops 표로 위임 | 없음 (테스트됨) |
| `src/seg_index.[ch]` | 닫힌 세그먼트의 정렬 인덱스(RAM). LIST 페이징 | 없음 (테스트됨) |
| `src/state.[ch]` | 저장 상태(boot_count, 다음 id, 녹음 켜짐, 꺼진 이유, 설정) 인코딩, SET_CONFIG 검증 | 없음 (테스트됨) |
| `src/power_policy.[ch]` | 3.6V 경고·3.5V 차단·3.8V 자동 켜짐, 부팅 시 "켤까?" 결정 | 없음 (테스트됨) |
| `src/button_sm.[ch]` | 버튼 짧게/2초/5초 상태 기계 | 없음 (테스트됨) |
| `src/led_pattern.[ch]` | LED 패턴 시퀀서(`decisions.md` #21) | 없음 (테스트됨) |
| `src/vad.[ch]` | 에너지+ZCR VAD, 세그먼트 경계(시작·hangover·최대 길이) | 없음 (테스트됨) |
| `src/storage.[ch]` | LittleFS: 세그먼트 파일, 이벤트, 상태 파일, 복구, 공간 관리 | 플래시 |
| `src/audio.[ch]` | PDM 캡처, VAD 적용, Opus/PCM 인코딩, writer 스레드 | 마이크 |
| `src/ble.[ch]` | GATT 서비스, 광고, 연결 설정, FETCH 스트리밍(흐름 제어) | 라디오 |
| `src/ui.[ch]` | LED GPIO와 버튼 GPIO(디바운스 30ms) | GPIO |
| `src/power.[ch]` | 배터리 ADC, System OFF, GRTC 깨우기 | ADC·GRTC |
| `src/main.c` | 부팅 순서, 모듈 연결 | |
| `bringup/` | **M0 bring-up 앱**(따로 빌드): 배터리, 플래시 JEDEC·읽기쓰기, 마이크 rms, 버튼, System OFF 깨우기 | |
| `tests/unit/` | native_sim ztest 7개 스위트(순수 로직). `vectors.h`는 `tools/owlproto.py`로 생성 | |
| `tests/opus/` | libopus를 같은 `cmake/opus.cmake`·같은 인코더 설정으로 native_sim에서 빌드: 16kbps 근처, 20ms wideband 패킷, 디코딩 레벨 확인 | |
| `tests/storage/` | `storage.c`를 native_sim의 가상 플래시 + LittleFS에서: 세그먼트 바이트, 재부팅 id, 이벤트, 삭제, **전원 끊김 복구**, 가득 참/재개 | |
| `Kconfig` | 앱 옵션(`CONFIG_OWL_*`): 코덱, 복잡도, PDM 채널, 마이크 게인, VAD hangover 등 | |

### 플래시 배치와 파일

| 영역 | 내용 |
|---|---|
| `0x000000–0xFFEFFF` | LittleFS `/lfs` (블록 4KB × 4095) |
| `0xFFF000–0xFFFFFF` | bring-up 앱의 지우기·쓰기 테스트용 4KB (본 앱이 안 씀) |

- `/lfs/state` — 28바이트 상태(CRC 포함). LittleFS가 닫을 때 통째로 바꿔서 전원이 끊겨도 옛 값 아니면 새 값
- `/lfs/seg/0000002a.1` — 세그먼트 id 0x2a, 타입 1(오디오). `.2` = 이벤트. 쓰는 중이면 `.1.w`
- 세그먼트 id는 쓰기 **전에** 다음 id를 저장해서, 재부팅해도 같은 id가 다시 나오지 않아요
- 부팅 때 `.w` 파일은 온전한 프레임(이벤트는 10바이트 단위)까지 잘라서 살려요
- **헤더의 `payload_len`은 플래시에선 `0xFFFFFFFF`** 로 두고, 읽어서 보낼 때 `파일 크기 − 32`로 채워요. LittleFS는 파일 중간을 고치면 그 뒤 블록을 전부 다시 써서(파일이 블록 역방향 연결 리스트) 닫을 때 헤더를 고치면 세그먼트를 두 번 쓰는 셈이에요. 폰이 받는 바이트는 프로토콜 3장과 똑같아요
- 오디오는 4KB씩 파일에 쓰고 16KB(`CONFIG_OWL_SEG_SYNC_KB`, 약 8초 분량)마다 `fs_sync`. 전원이 갑자기 끊기면 최대 그만큼 + RAM 버퍼 4KB를 잃어요(배터리 차단은 먼저 정리하니 해당 없음). sync마다 반쯤 찬 마지막 블록을 복사하기 때문에 더 자주 하면 플래시가 닳아요. 이벤트는 하나씩 바로 sync(`decisions.md` #19)
- 남은 용량 계산(`fs_statvfs`)은 파일 시스템 전체를 훑어서 가끔만 하고, 그 사이엔 추정값을 써요. STATUS와 오디오 스레드는 스핀락으로 보호한 복사본을 읽어서 플래시 작업을 기다리지 않아요
- 남은 공간이 128KB(`CONFIG_OWL_STORAGE_RESERVE_KB`) 아래면 녹음 멈춤 + STATUS bit3 + 이벤트 4, 384KB 위로 회복되면 다시 녹음. 이벤트는 가득 차도 계속 기록
- 열린 이벤트 로그는 **폰이 연결될 때**(그리고 LIST_SEGMENTS를 받을 때도) 닫혀서 목록에 올라가요. 목록에 오른 세그먼트는 크기가 바뀌지 않고, 그 뒤 이벤트는 새 이벤트 세그먼트로 가요

### 스레드와 우선순위

| 스레드 | 우선순위 | 하는 일 |
|---|---|---|
| audio | 3 | PDM 읽기(20ms 블록 12개, 드라이버 큐 12 = 240ms 여유), VAD, Opus |
| writer | 6 | 링에서 꺼내 플래시에 쓰기. 플래시 지우기(최대 ~0.4초)가 오디오를 막지 않게 분리 |
| ble_tx | 7 | DATA 청크 전송. 알림 6개까지 동시에(크레딧) |
| ble_ctl | 8 | CONTROL 처리(LIST, ACK의 CRC 계산·삭제 등) |
| app 워크큐 | 9 | 버튼 동작, 배터리 확인 |

숫자가 작을수록 우선. 협력형(cooperative)인 시스템 워크큐에서는 플래시 작업을 하지 않아요(오디오가 굶어요).

## 동작 요약

- **녹음**: 20ms마다 VAD. 말소리 60ms 연속이면 세그먼트 시작(직전 200ms 프리롤 포함), 1.5초 조용하면 끝, 300초(`SET_CONFIG` 키 3)면 끊고 바로 새 세그먼트
- **VAD 민감도**(키 2): 0~3 = 잡음 바닥 대비 에너지 ×8 / ×5 / ×3 / ×2. 잡음 바닥은 조용하면 빨리 내려가고 시끄러우면 천천히(초당 ×1.5) 올라가서, 선풍기 같은 일정한 소리는 ~14초 뒤 무음 취급
- **버튼**: 짧게 = 북마크(이벤트 1), 2초 = 녹음 끄기/켜기(이벤트 2/3 arg 0), 5초 = 기기 끄기(이벤트 6 arg 0). 동작은 **놓을 때** 일어나고, 2초·5초에 도달하면 LED로 "지금 놓으면 무엇이 되는지" 알려줘요
- **꺼진 상태에서 켜기**: 버튼을 누르면 칩이 깨어나(=재부팅) 부팅 후 5초까지 계속 눌려 있는지 확인. 아니면 다시 System OFF
- **배터리**: 60초마다. 3.6V 아래 이벤트 5 + 30초마다 3번 깜빡(3.7V 위로 올라가면 해제), 3.5V 아래 3번 연속(5초 간격)이면 정리 후 System OFF + GRTC 1시간 깨우기. 깨어나서 3.8V 이상이면 켜짐(이벤트 7 arg 3)
- **BLE**: `Owl-XXXX`로 1초마다 광고(서비스 UUID 포함, 31바이트 꽉 참). 연결되면 2M PHY·DLE 251 요청, 연결 간격 15–30ms 요청. 한 번에 폰 하나
- **결과 코드**: 짧은 요청 = 2, 모르는 op = 1, 없는 세그먼트 = 3, CRC 불일치 = 4(지우지 않음), 플래시 오류·큐 가득 = 5. 프로토콜에 "잘못된 값" 코드가 없어서 범위를 벗어난 `SET_CONFIG`·`SET_RECORDING` 값도 2

## Bring-up 체크리스트 (M0–M3)

USB에 꽂고 `uart.conf` 빌드로 시리얼 로그(115200)를 보면서 해요. 배터리 단독 확인은 마지막에 기본 빌드로.

### M0 — 부품 (`bringup/`)

| 확인 | 로그 / LED | 안 되면 |
|---|---|---|
| 리셋 원인 | `reset cause: 0x...` | |
| 배터리 전압 | `battery: 3xxx mV` (USB만 꽂고 배터리 없으면 ~4.2V나 잡음) | 값이 흔들리면 `CONFIG_OWL_VBAT_SETTLE_MS`(본 앱) / bring-up의 `k_msleep(10)`을 100으로 |
| 플래시 | `flash: JEDEC ef 40 18`, `flash: erase/write/read OK` | `ef 70 18`이면 -IM 변형 → overlay `jedec-id` 수정. `not ready`면 배선(CS=D3) |
| 마이크 | `mic: 16000 samples, peak P, rms R`. 조용할 때와 말할 때 R을 둘 다 적어두기(VAD 튜닝값) | R이 0 근처면 채널 반대: bring-up `PDM_CHAN_LEFT`→`RIGHT`, 본 앱 `CONFIG_OWL_PDM_CHANNEL_RIGHT=y` |
| 버튼 | 짧게 누르면 `button: pressed -> mic test` | 배선(D1–GND) |
| System OFF 깨우기 | 5초 누르면 `System OFF`, LED 꺼짐 → 다시 누르면 재부팅하며 `(wakeup from System OFF)` | |
| 전체 | LED 2초마다 짧게 = 통과 / 빠르게 = 실패 | |
| 배터리 단독 부팅 | 기본 빌드(UART 끔)를 굽고 USB 빼고 배터리만 → LED 2초 깜빡 | 보드 버전(V1.0/V1.2) 기록(`decisions.md` #9) |

### M1 — 녹음·저장 (본 앱, 필요하면 `-DCONFIG_OWL_CODEC_PCM16=y`)

| 확인 | 로그 |
|---|---|
| 마운트 | `mounted: N / 16380 KiB free, boot_count B` (처음엔 포맷 때문에 몇 초) |
| 부팅 판단 | `boot: was 0, 3xxx mV, button no -> ON`, 이어서 `event 7 arg 4` |
| boot_count | 리셋할 때마다 `boot_count`가 1씩 올라감 |
| 세그먼트 | 말하면 `segment N closed`(말 끝나고 1.5초 뒤). 리셋 후 `N segments on flash`가 그대로 |
| 전원 끊김 복구 | 말하는 중에 리셋 → 다음 부팅에 `recovered segment N: a -> b bytes` |
| 녹음 끄기 유지 | 버튼 2초 → `recording off (button)`, 리셋해도 `recording off` |

### M2 — Opus·VAD

| 확인 | 로그 / 기준 |
|---|---|
| 인코더 | `opus: S B state, 16000 bps, complexity 3` → S를 보고 `CONFIG_OWL_OPUS_ENC_MEM` 줄이기 |
| CPU | 말하는 동안 10초마다 `encode: avg A us, max M us ... 0 dropped`. **A < 10000, M < 20000** 이어야 해요. 넘으면 `CONFIG_OWL_OPUS_COMPLEXITY`를 1~2로 |
| 스택 | 60초마다 thread analyzer: `audio` 스택 사용량이 24576보다 충분히 작아야 함 |
| 무음 제거 | 조용히 1분 → 세그먼트 안 생김. 선풍기 켜면 처음 ~14초만 녹음되고 끊김 |
| 용량 | 1분 말하기 ≈ 120KB(16kbps) |

VAD가 너무 둔하거나 예민하면 M0의 rms를 보고 `src/vad.c`의 `min_energy`를 고치거나, 마이크 신호가 작으면 `CONFIG_OWL_MIC_GAIN_SHIFT=1~2`.

### M3 — BLE·버튼·LED

| 확인 | 로그 / LED |
|---|---|
| 광고 | `advertising as Owl-XXXX`, `tools/ble_scan.py`에 보임 |
| 연결 | `connected`, `mtu: tx 247`, `phy: tx 2 rx 2`, `data len: tx 251`, `conn params: interval 15–30 ms` |
| 동기화 | `tools/sync.py`: `time sync: ...` → LIST → `fetch N: sent 0..size in T ms (B/s)` → `segment N deleted` |
| 이어받기 | 전송 중 폰 끄기 → 다시 연결해 offset부터 FETCH → ACK OK |
| CRC 불일치 | 틀린 CRC로 ACK → `keeping it`, 세그먼트 남아 있음 |
| 북마크 | 짧게: LED 아주 짧게 1번, `event 1 arg 0` |
| 녹음 끄기/켜기 | 2초에 LED 1번 깜빡(힌트) → 놓으면 끄기 = 짧게 2번 / 켜기 = 길게 1번 |
| 기기 끄기 | 5초에 빠르게 3번 → 놓으면 길게 1번 후 꺼짐, 광고 사라짐. 다시 5초 누르면 길게 3번 + `event 7 arg 0` |
| 녹음 중 표시 | 10초마다 아주 짧게 1번 |
| 가득 참 | (PCM16 빌드로 빨리 채우기) `storage full`, 30초마다 길게 2번, STATUS bit3 → ACK로 비우면 다시 녹음 |

M5에서: 실제 전류 측정, 3.5V 차단과 1시간 깨우기(가변 전원으로), 배터리 부족 LED.

## 확인한 것과 가정

**확인함** (SDK 소스를 읽거나 빌드·테스트로):
- 보드 DTS(NCS v3.4.1): 마이크 alias는 `dmic20`뿐(그래서 overlay에 `dmic0`), `pdm_imu_pwr`·`vbat_pwr`는 `regulator-boot-on` = 참조 1개로 시작(`regulator_common.c`), 안테나 스위치 레귤레이터 2개
- PDM 드라이버: STOP은 비동기, 드라이버 RX 큐 기본 4블록(`queue-size`), 큐가 차면 드라이버가 PDM을 멈춤
- `z_nrf_grtc_wakeup_prepare()`는 `CONFIG_NRF_GRTC_START_SYSCOUNTER` + `CONFIG_POWEROFF`에서만 있음
- `spi_nor` 드라이버는 런타임 PM으로 쓰지 않을 때 스스로 deep power-down
- LittleFS: 파일 중간 쓰기는 뒤 블록 전체 재작성, sync 뒤 이어 쓰기는 마지막 블록 복사 → payload_len을 읽을 때 채우는 이유, 16KB sync 간격의 이유
- 바이트 레이아웃 = `tools/owlproto.py`(고정 벡터 테스트), storage 동작(가상 플래시 테스트), libopus 빌드·인코딩·디코딩(native_sim)

**가정 (보드에서 확인 필요)**:
- Opus 복잡도 3이 128MHz M33에서 20ms 안에 충분히 끝남(로그 `encode: avg/max`)
- 마이크가 LEFT 채널(아니면 `CONFIG_OWL_PDM_CHANNEL_RIGHT`), VAD 절대 임계값(`src/vad.c` `min_energy`)
- PDM 클럭 1–3.5MHz 범위가 PCLK32M에서 나옴(빌드만 확인, `dmic_configure` 실행은 안 해봄)
- 배터리 분압 안정 10ms면 충분, `adc_raw_to_millivolts_dt` × 2가 실제 전압과 맞음
- 스레드 스택 크기, BLE 처리량, System OFF 전류, GRTC 1시간 깨우기 실제 동작
- ARM에서 Opus 인코더 상태 크기가 native_sim과 거의 같음(26KB 안). 아니면 부팅 시 에러 로그 후 녹음만 꺼짐

## 알아둘 것

- **보안 없음**: 프로토콜 v1에 페어링·인증이 없어서 근처의 누구나 연결해 녹음을 받아갈 수 있어요(ACK로 지우기도 가능). PRD N8(내 폰에만)과 충돌 — PR 설명에 제안 적음
- 보드 dts의 `pdm_imu_pwr`·`vbat_pwr`는 `regulator-boot-on`이라 참조 1개를 쥔 채 시작해요. 코드가 시작할 때 한 번 내려놓아야 enable/disable이 실제로 켜고 꺼요(마이크·IMU 전원은 녹음할 때만 켬)
- 안테나 스위치(`rfsw_ctl`, `rfsw_pwr`)는 BLE에 필요해서 건드리지 않아요
- 외장 플래시는 `zephyr,pm-device-runtime-auto`로 쓰지 않을 때 자동으로 deep power-down
- 보드에 **충전 상태 핀·USB 감지 핀이 없어요**(회로도 V1.2). STATUS bit0은 늘 0
- SDK가 내는 `Deprecated symbol NRF_PLATFORM_LUMOS` 경고는 보드 기본 설정에서 나와요(앱 설정과 무관)
