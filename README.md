# Owl

**Offline wearable lifelogging.**

하루를 기록하는 목걸이형 웨어러블. 목걸이가 대화를 녹음해 쌓아두면, 폰이 주기적으로 가져가서 텍스트로 바꾸고 위치·시각과 함께 타임라인으로 정리해요.

- 하드웨어: Seeed XIAO nRF54L15 Sense + 16MB SPI 플래시 + 250mAh LiPo + 버튼 1개
- 펌웨어: Zephyr RTOS (nRF Connect SDK)
- 앱: Android (Kotlin)

## 구조

```
protocol/   BLE 프로토콜 명세 — 펌웨어와 앱의 계약서. 여기부터 읽기
firmware/   Zephyr 앱 (녹음·VAD·Opus·BLE, 보드 검증 전) + `bringup/` M0 점검 앱
android/    Android 앱 (동기화·타임라인 UI, 가짜 기기로 검증)
tools/      PC용 Python — 프로토콜 레퍼런스, `sync.py`(PC 동기화), 가짜 기기
hardware/   배선, BOM, 케이스
docs/       PRD(기준 문서), 아키텍처, 설계 결정, 로드맵
```

## 지금 상태

**무엇을 왜 만드는지는 [docs/prd.md](docs/prd.md)가 기준이에요.** 하드웨어 없이 할 수 있는 M0–M4 구현이 들어왔고, 보드 검증을 기다리는 단계예요. 진행 상황은 [docs/roadmap.md](docs/roadmap.md), 왜 이렇게 만들었는지는 [docs/decisions.md](docs/decisions.md).

```bash
# 하드웨어 없이 바로 돌려볼 수 있는 것
python3 tools/tests/test_owlproto.py
```

## 개발

[![tools](https://github.com/wndgur2/owl/actions/workflows/tools.yml/badge.svg)](https://github.com/wndgur2/owl/actions/workflows/tools.yml)
[![firmware](https://github.com/wndgur2/owl/actions/workflows/firmware.yml/badge.svg)](https://github.com/wndgur2/owl/actions/workflows/firmware.yml)
[![android](https://github.com/wndgur2/owl/actions/workflows/android.yml/badge.svg)](https://github.com/wndgur2/owl/actions/workflows/android.yml)

PR과 `main` 푸시마다 GitHub Actions가 바뀐 폴더의 검사만 돌려요(`.github/workflows/`). 같은 검사를 로컬에서 돌리려면 레포 루트에서:

| 검사 | 언제 돌아가나 | 로컬 명령 |
|---|---|---|
| tools | `tools/`, `protocol/` 변경 | `pip install -r tools/requirements.txt` 후 `python -m pytest tools/tests` |
| firmware | `firmware/`, `protocol/` 변경 | `west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware` (기본), 같은 명령 뒤에 `-- -DEXTRA_CONF_FILE=uart.conf` (UART). `firmware/tests/`가 있으면 `west twister -T firmware/tests -p native_sim` |
| android | `android/` 변경 | `cd android && ./gradlew assembleDebug testDebugUnitTest lint` |

- 펌웨어 CI는 nRF Connect SDK **v3.4.1**을 Nordic 공식 컨테이너(`ghcr.io/nrfconnect/sdk-nrf-toolchain`)에서 빌드해요. 빌드된 `merged.hex`는 Actions 실행 화면의 Artifacts에서 받을 수 있어요
- SDK 버전을 올릴 때는 `firmware.yml`의 `NCS_VERSION`과 `image:` 태그 두 곳을 함께 바꿔요
- 코드 스타일: `.editorconfig`(들여쓰기), `.clang-format`(C, Zephyr 스타일)
