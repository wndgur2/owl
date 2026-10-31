# 에뮬레이터 스크린샷 (가짜 기기 모드)

2026-10-31, Android 에뮬레이터(API 37, 1080×2400), debug 빌드, `FakeOwlDevice`로 찍었어요. 파일은 540×1200으로 줄였어요.
텍스트는 `DemoTranscriber`의 고정 문장이에요(STT 엔진은 M6).

| 화면 | 라이트 | 다크 |
|---|---|---|
| 스플래시 (N8 문구) | [splash-light](splash-light.png) | [splash-dark](splash-dark.png) |
| 온보딩 (등록 전) | [onboarding-light](onboarding-light.png) | [onboarding-dark](onboarding-dark.png) |
| 온보딩 (가짜 기기 등록, 권한 완료) | [onboarding-done-light](onboarding-done-light.png) | |
| 타임라인 | [timeline-light](timeline-light.png) | [timeline-dark](timeline-dark.png) |
| 타임라인 · 녹음 파일 받는 중 | [timeline-receiving-light](timeline-receiving-light.png) | |
| 기록 상세 · 재생 중 (문장 강조, 북마크 줄) | [entry-light](entry-light.png) | [entry-dark](entry-dark.png) |
| 기록 상세 · 메뉴 | [entry-menu-light](entry-menu-light.png) | [entry-menu-dark](entry-menu-dark.png) |
| 기록 상세 · 음성 삭제됨 (A14 "추출 후 바로 삭제") | [entry-deleted-light](entry-deleted-light.png) | |
| 검색 | [search-light](search-light.png) | [search-dark](search-dark.png) |
| 기기 | [device-light](device-light.png) | [device-dark](device-dark.png) |
| 기기 · 연결 끊김 (재시도 3번 실패) | [device-disconnected-light](device-disconnected-light.png) | |
| 설정 | [settings-light](settings-light.png) | [settings-dark](settings-dark.png) |
| 음성 보관 | [retention-light](retention-light.png) | [retention-dark](retention-dark.png) |
| 사용 설명 (A16) | [guide-light](guide-light.png) | [guide-dark](guide-dark.png) |
| 개인정보 처리 방침 (N8) | [privacy-light](privacy-light.png) | [privacy-dark](privacy-dark.png) |

위 표의 첫 스크린샷들(2026-10-31)은 이전 버전이라 이름 없는 장소가 좌표("39.2373, -123.1500", 에뮬레이터 기본 GPS)로 보여요. 지금은 "알 수 없는 장소"로 보여요(아래 표).

## 타임라인 항목 합치기, 녹음 밖 북마크, 장소 이름 (2026-11-01)

| 화면 | 라이트 | 다크 |
|---|---|---|
| 타임라인 · 19:15 항목 = 세그먼트 2개(150초 + 90초, 간격 40초) 합침, 21:05·21:11 녹음 밖 북마크, "알 수 없는 장소" | [timeline-entries-light](timeline-entries-light.png) | |
| 타임라인 · 22:41 기록에서 "Dorm 302"로 이름 붙인 뒤 (근처 북마크에도 적용) | [timeline-places-light](timeline-places-light.png) | [timeline-places-dark](timeline-places-dark.png) |
| 기록 상세 · 이 장소 이름 붙이기 | [entry-name-place-light](entry-name-place-light.png) | |
| 기록 상세 · 합친 항목 (4:00, 경계 뒤 문장 시각 19:17:14 → 19:18:03) | [entry-merged-light](entry-merged-light.png) | [entry-merged-dark](entry-merged-dark.png) |
| 검색 · 북마크만 (녹음 밖 북마크 포함) | [search-bookmarks-light](search-bookmarks-light.png) | [search-bookmarks-dark](search-bookmarks-dark.png) |
| 설정 › 장소 | [places-light](places-light.png) | [places-dark](places-dark.png) |

장소 이름은 adb로 한글을 입력할 수 없어서 영어("Dorm 302")로 넣었어요.

## 동기화 확인 로그

전체 동기화 (첫 등록, 시드 데이터):

```
OwlSync : sync ok: SyncReport(listed=9, fetched=9, acked=9, skipped=0, ...)
```

FETCH 도중 끊김 + 청크 유실 (`drop 3`, `disconnect 50`, 그다음 `sync`):

```
OwlSync : segment 13: gap at byte 708, re-FETCH                 ← 청크 3(708 = 3×236) 유실 → 그 위치부터 다시
OwlSync : sync (MANUAL) attempt 1 failed: disconnected           ← 50청크 뒤 링크 끊김
OwlSync : segment 13: resuming at byte 12508 of 100920           ← 2초 뒤 재시도, 저장된 위치(708 + 50×236)부터 이어받기
OwlSync : segment 13: gap at byte 44604, re-FETCH                ← 무작위 유실(0.5%)도 복구
OwlSync : segment 13: saved 100920 bytes, crc a6d57624, sending ACK
OwlSync : sync (MANUAL) ok: SyncReport(listed=1, fetched=1, acked=1, skipped=0, ...)
```

연결 끊김 (`fail-connect 3`, 그다음 "지금 동기화"):

```
OwlSync : sync (MANUAL) attempt 1 failed: fake: device not reachable
OwlSync : sync (MANUAL) attempt 2 failed: fake: device not reachable
OwlSync : sync (MANUAL) attempt 3 failed: fake: device not reachable   → 기기 화면 "연결 끊김" + 연결 버튼
```

그 밖에 확인한 것: 녹음 스위치(SET_RECORDING → 이벤트 세그먼트 "녹음 꺼짐(앱)"이 다음 동기화로 들어옴), 연결 버튼으로 재연결, Opus 재생·문장 이동, 내보내기(SAF로 `Download/owl-export-2026-10-31.json` 저장), "목걸이 찾기"가 CDM 선택 화면을 `Owl-.*` + 서비스 UUID 필터로 여는 것(에뮬레이터엔 BLE 스캐너가 없어 기기는 못 찾음).
