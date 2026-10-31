# Owl

**Offline wearable lifelogging.**

하루를 기록하는 목걸이형 웨어러블. 목걸이가 대화를 녹음해 쌓아두면, 폰이 주기적으로 가져가서 텍스트로 바꾸고 위치·시각과 함께 타임라인으로 정리해요.

- 하드웨어: Seeed XIAO nRF54L15 Sense + 16MB SPI 플래시 + 250mAh LiPo + 버튼 1개
- 펌웨어: Zephyr RTOS (nRF Connect SDK)
- 앱: Android (Kotlin)

## 구조

```
protocol/   BLE 프로토콜 명세 — 펌웨어와 앱의 계약서. 여기부터 읽기
firmware/   Zephyr 앱 (현재: M0 하드웨어 bring-up)
android/    Android 앱 (현재: 설계 문서만)
tools/      PC용 Python — 프로토콜 레퍼런스 구현, BLE 테스트
hardware/   배선, BOM, 케이스
docs/       PRD(기준 문서), 아키텍처, 설계 결정, 로드맵
```

## 지금 상태

**무엇을 왜 만드는지는 [docs/prd.md](docs/prd.md)가 기준이에요.** 뼈대 단계예요. 진행 상황은 [docs/roadmap.md](docs/roadmap.md), 왜 이렇게 만들었는지는 [docs/decisions.md](docs/decisions.md).

```bash
# 하드웨어 없이 바로 돌려볼 수 있는 것
python3 tools/tests/test_lpproto.py
```
