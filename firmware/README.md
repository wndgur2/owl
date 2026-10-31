# firmware/

Zephyr RTOS + nRF Connect SDK 앱. 보드: Seeed XIAO nRF54L15 Sense.

## 환경 (macOS)

1. VS Code + **nRF Connect for VS Code Extension Pack** 설치
2. 확장에서 Toolchain과 nRF Connect SDK 설치 (Seeed 위키 기준 **v3.0.1 이상**)
3. 보드 정의는 SDK에 들어 있어요(`xiao_nrf54l15/nrf54l15/cpuapp`). 별도 board root 불필요
4. 플래시용 OpenOCD: `brew install openocd`

## Docker로 빌드 (보드·NCS 설치 없이)

NCS 버전은 **v3.4.1**로 고정해요. 공식 툴체인 이미지(`ghcr.io/nrfconnect/sdk-nrf-toolchain:v3.4.1`, amd64 전용 — Apple Silicon에선 에뮬레이션)를 쓰고, NCS 작업 공간(약 5GB)은 Docker 볼륨 `owl-ncs-v3.4.1`에 둬요. `firmware/scripts/ncs.sh`가 레포를 작업 공간 안(`/ncs/owl`)에 마운트해서 명령을 실행해요.

```bash
docker pull --platform linux/amd64 ghcr.io/nrfconnect/sdk-nrf-toolchain:v3.4.1
firmware/scripts/ncs.sh --setup      # west init -m sdk-nrf --mr v3.4.1 && west update (한 번만)
firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/fw firmware'
firmware/scripts/ncs.sh 'west build -p -b xiao_nrf54l15/nrf54l15/cpuapp -d build/fw-uart firmware -- -DEXTRA_CONF_FILE=uart.conf'
```

결과물: `build/fw/merged.hex` (레포 루트의 `build/`는 gitignore). 굽기는 macOS에서 `west flash -d build/fw` 또는 nRF Connect for Desktop으로.

## 빌드와 굽기

```bash
# USB 디버깅용 (시리얼 로그 켬)
west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware -- -DEXTRA_CONF_FILE=uart.conf
west flash

# 배터리 단독용 (UART 끔 — 위키: UART 켜면 배터리로 부팅 안 될 수 있음)
west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware
```

## 구조

| 파일 | 내용 |
|---|---|
| `prj.conf` | 기본 설정 = 배터리 빌드 |
| `uart.conf` | 디버깅 오버레이 |
| `boards/xiao_nrf54l15_nrf54l15_cpuapp.overlay` | 외장 플래시(D3 CS), 마스터 버튼(D1), 배터리 ADC |
| `src/main.c` | **M0 bring-up**: 배터리 전압, 플래시 JEDEC/읽기쓰기, 마이크 1초 레벨 측정, 버튼 |

> NCS v3.4.1에서 빌드 확인(기본 / `uart.conf` 둘 다). 보드에서 실행은 아직 안 해봤어요.

## M0 체크 결과 읽기

- 시리얼 로그: `flash: JEDEC ef 40 18`, `mic: ... rms N` (말하면 rms가 올라가야 함)
- LED: 2초마다 짧게 깜빡 = 통과 / 빠르게 깜빡 = 실패
- 마이크 rms가 0 근처면 `main.c`의 `PDM_CHAN_LEFT` → `PDM_CHAN_RIGHT`

## 알아둘 것

- 보드 dts에서 마이크/IMU 전원(`pdm_imu_pwr`)과 안테나 스위치가 **부팅 시 항상 켜짐**. 저전력 단계(M5)에서 꺼야 할 대상
- 배터리: 3.6V 아래 경고, 3.5V 아래 System OFF. 배터리로 꺼졌을 때만 GRTC로 1시간마다 깨어나 3.8V 이상이면 켬 (`docs/decisions.md` #8, #20)
- 보드에 **충전 상태 핀·USB 꽂힘 감지 핀이 없어요**(회로도 V1.2). 충전 LED는 충전 칩(SGM40567)이 직접 켜요
- 회로도 V1.2에 UART 역류 방지(U12)가 추가됐어요. 받은 보드가 V1.2면 "UART 켜면 배터리 부팅 실패"가 안 생길 수 있어요 → M0에서 확인 (`docs/decisions.md` #9)
- `main.c`의 배터리 분압 안정 대기는 10ms예요. Seeed 위키 예제는 100ms라, 값이 흔들리면 늘려요
