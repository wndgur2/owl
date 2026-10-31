# firmware/

Zephyr RTOS + nRF Connect SDK 앱. 보드: Seeed XIAO nRF54L15 Sense.

## 환경 (macOS)

1. VS Code + **nRF Connect for VS Code Extension Pack** 설치
2. 확장에서 Toolchain과 nRF Connect SDK 설치 (Seeed 위키 기준 **v3.0.1 이상**)
3. 보드 정의는 SDK에 들어 있어요(`xiao_nrf54l15/nrf54l15/cpuapp`). 별도 board root 불필요
4. 플래시용 OpenOCD: `brew install openocd`

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

> 이 코드는 아직 **한 번도 빌드해보지 않았어요.** 보드 도착 후 첫 빌드에서 오류가 나면 그게 M0의 첫 작업이에요.

## M0 체크 결과 읽기

- 시리얼 로그: `flash: JEDEC ef 40 18`, `mic: ... rms N` (말하면 rms가 올라가야 함)
- LED: 2초마다 짧게 깜빡 = 통과 / 빠르게 깜빡 = 실패
- 마이크 rms가 0 근처면 `main.c`의 `PDM_CHAN_LEFT` → `PDM_CHAN_RIGHT`

## 알아둘 것

- 보드 dts에서 마이크/IMU 전원(`pdm_imu_pwr`)과 안테나 스위치가 **부팅 시 항상 켜짐**. 저전력 단계(M5)에서 꺼야 할 대상
- 배터리 전압은 3.5V 아래에서 System OFF 권장 (벅 컨버터 특성, `docs/decisions.md`)
