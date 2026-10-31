# Android 앱 디자인 v1 (1차 확정)

| 항목 | 내용 |
|---|---|
| 상태 | 1차 확정 (2026-10-31) |
| 기준 문서 | [PRD 0.3](../../prd.md) — 화면이 PRD와 어긋나면 PRD를 먼저 맞춰요 |
| 원본 캔버스 | https://claude.ai/artifact/JZfq4wLZMHHXfr3VQUtXsB (비공개, 소유자만 열람) |
| 디자인 시스템 | Material 3 (Compose) 기본값 — 색은 role(`Primary`, `SurfaceContainer`…)로만 써요 |
| 기기 크기 | 390 × 844 (폰 세로) |

이 폴더는 캔버스의 스냅샷이에요. 캔버스에서 고친 뒤 다시 확정하면 이 폴더를 통째로 갈아 끼워요.

## 화면

| 파일 | 화면 | 관련 PRD |
|---|---|---|
| `Main.dc.html` | 타임라인 — 최신이 아래, 위로 스크롤하면 이전 날짜. 헤더 날짜는 스크롤 위치를 따라감. "텍스트 추출 중" / "녹음 파일 받는 중" 상태 | A7, A8a, A9 |
| `Entry.dc.html` | 기록 상세 — 문장 목록 + 재생기. 재생 바 위치에 맞는 문장 강조, 되감기/빨리감기 = 이전/다음 문장 | A8, A9 |
| `EntryPending.dc.html` | 기록 상세 · 텍스트 추출 중 (재생만 가능) | A8a |
| `EntryMenu.dc.html` | 기록 상세 · 메뉴 열림 (음성 보존 / 텍스트 복사 / 기록 삭제) | A14 |
| `EntryDeleted.dc.html` | 기록 상세 · 음성 파일 삭제됨 (플레이어 유지, 재생 버튼만 비활성) | A8, A14 |
| `Search.dc.html` | 검색 — 기간·장소·북마크·보존됨 필터 | A11 |
| `Splash.dc.html` | 스플래시 — 앱 아이콘 + 하단 개인정보 원칙 문구 | N8 |
| `Onboarding.dc.html` | 목걸이 등록 + 자동 동기화 권한 체크리스트 | A1, A2, A5 |
| `Device.dc.html` | 기기 — 상태(녹음 중/일시정지/응답 없음), 배터리, 저장 공간, 동기화, 펌웨어 | A10 |
| `DeviceMenu.dc.html` | 기기 · 기기 목록 펼침 (+ 새로운 기기) | A15 |
| `Settings.dc.html` | 설정 — 음성 보관, 장소 기록, 기록 내보내기, 개인정보 처리 방침, 등록 해제 | A5, A13, A14, N8 |
| `Retention.dc.html` | 설정 · 음성 보관 기간 | A14 |
| `*Dark.dc.html` | 위 화면들의 다크 모드 | — |

`EntryPending` / `EntryMenu` / `EntryDeleted` / `DeviceMenu`는 `Entry`·`Device`를 상태 값만 바꿔 불러오는 얇은 파일이에요. 상태 값(props)은 캔버스의 Tweaks에서도 바꿔 볼 수 있어요.

- `Entry`: `menuOpen`, `audioDeleted`, `noText`
- `Device`: `alive`, `recording`(`recording` / `paused`), `deviceMenuOpen`

## 파일 형식

`.dc.html`은 캔버스 편집기용 형식이라 브라우저에서 바로 열면 제대로 안 보여요(`support.js` 런타임 필요). 화면을 보려면 원본 캔버스를 여세요. 구현할 때는 마크업의 색·크기·간격 값을 참고 자료로 쓰면 돼요. `canvas.json`은 캔버스 배치 정보예요.

## 다크 모드 다시 만들기

`*Dark.dc.html`은 손으로 고치지 않고, 밝은 화면에서 생성해요. M3 기본 light role 색을 dark role 색으로 바꾸고, 화면 간 링크를 다크 화면끼리 잇는 스크립트예요.

```bash
python3 docs/design/android/make_dark.py docs/design/android
```

## 확정 과정에서 정한 것 (요약)

- 용어: "순간 표시" → **북마크**, "원본 음성 계속 보관" → **음성 보존**
- 음성 → 텍스트는 폰 안에서만 (클라우드 없음) — `decisions.md` #10
- 음성 보관 기간은 설정에서 선택(기본 30일), 기록별 "음성 보존" — `decisions.md` #11
- 녹음 파라미터(비트레이트·VAD·세그먼트 길이)는 앱에 노출하지 않음 — `decisions.md` #12
- 시각 동기화·배터리 전압 같은 내부 정보는 사용자에게 보여주지 않음
- 개인정보 원칙 문구("모든 음성, 위치, 텍스트 정보는 디바이스 내부에만 존재해요.")는 기기 화면이 아니라 스플래시와 설정 › 개인정보 처리 방침에 표시 — PRD N8
- 미결정: 기기 "응답 없음" 판정 기준 — PRD 10장 G
