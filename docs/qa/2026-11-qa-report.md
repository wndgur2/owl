# 2026-11 QA 보고서 — Android 앱 × Bumble 가짜 목걸이

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-11-01 |
| 대상 | Android 앱 debug 빌드. 시작 `main` 090fdb1 + QA용 debug 명령, 버그 수정 후 다시 확인 |
| 환경 | 에뮬레이터 36.1.9, AVD `Owl_QA`(API 37, `Medium_Phone`과 같은 이미지), `emulator-5556`. 가짜 목걸이 `tools/fake_owl.py --transport bumble --name Owl-0A7F` (netsim, 진짜 BLE 경로: CDM·`createBond()`·암호화·GATT) |
| 데이터 | `--week` 일주일 시드: 부팅 4개(그중 1개는 시각 동기화 없음), 오디오 세그먼트 534개 + 이벤트 로그 10개, 폰 GPS 2041개. 시나리오 중 `record`로 녹음 추가 |
| 시나리오 | [scenarios.md](scenarios.md) 89개 |
| 고친 버그 | [wndgur2/owl#14](https://github.com/wndgur2/owl/pull/14) (머지됨) |
| 스크린샷 | [screenshots/](screenshots/) (파일 이름 = 시나리오 ID) |

표기: **[확인]** = 에뮬레이터에서 직접 보거나 로그·DB로 확인, **[추정]** = 코드·문서를 보고 짐작(확인 안 함).

## 요약

- 89개 중 **통과 83**(그중 7개는 QA 중 찾은 버그를 고친 뒤 통과), **부분 3**, **관찰만 3**(PRD가 정하지 않은 동작). 실패로 남은 것은 없어요.
- **버그 8개를 찾아서 모두 고쳤어요**(#14, CI 통과, 머지). 가장 큰 것은 **검색하다 앱이 죽는 문제**(같은 기록 안의 문장 두 개 이상이 검색어에 걸리면).
- **결정이 필요한 것 16개**(빈 기능 F1–F4 + UX U1–U3·U5–U13)는 고치지 않고 아래 "결정 요청"에 추천과 함께 적었어요.
- 진짜 BLE 경로의 핵심(등록·본딩·일주일 동기화·이어받기·청크 유실·재부팅·키 분실·창 밖 거절·등록 해제)은 모두 문서대로 동작했어요.
  유실 0: 가짜 목걸이가 만든 세그먼트 586개(id 1–586)가 앱 DB에 빠짐·중복 없이 있고 모두 ACK, `.part` 0개 [확인].

## 시나리오 결과

| ID | 결과 | 메모 (증거) |
|---|---|---|
| ON-01 | 통과 | 스플래시 아래 원칙 문구 ([ON-01](screenshots/ON-01-splash-dark.png)) |
| ON-02 | 통과 | 체크리스트 4개, 등록 전 "시작하기" 비활성 ([ON-02](screenshots/ON-02-onboarding-dark.png)) |
| ON-03 | 통과 | 알림 두 번 거부 → "허용"이 앱 설정을 엶. 위치 "사용 중에만" → "설정 열기" 안내 → 항상 허용 → 돌아오면 갱신 ([ON-03](screenshots/ON-03-checklist-done-dark.png)) |
| ON-04 | 통과 | CDM 선택 창 → 확인 창 없이 본딩(`canBondWithoutDialog=true`) → "등록 완료 · 펌웨어 0.1.0" → 바로 동기화 ([선택 창](screenshots/ON-04-cdm-chooser-dark.png), [완료](screenshots/ON-04-registered-dark.png)) |
| ON-05 | 통과 | 선택 창 취소 → "아직 등록 안 됨" 그대로 ([ON-05](screenshots/ON-05-cdm-cancel-dark.png)) |
| ON-06 | 통과 | `--pairing-window 20` 지난 뒤 등록 → SMP 0x08 → CDM 되돌림 → 안내. 안내가 짧은 스낵바(U5) ([ON-06](screenshots/ON-06-pairing-refused-dark.png)) |
| ON-07 | 통과 | `reboot` 후 창 안에서 등록 성공 ([ON-07](screenshots/ON-07-reregistered-dark.png)) |
| ON-08 | 통과 | 등록 뒤 다시 켜면 온보딩 없이 메인 |
| SY-01 | 통과 | 부팅 1의 178개를 LIST 페이지 여러 번 → FETCH → 저장 → ACK |
| SY-02 | 통과 | 세 번 동기화로 534개 모두, 시드 시각과 ±1초 안 [확인: DB `startUnixMs` 비교]. 부팅 3은 `NULL`("시각 알 수 없음") |
| SY-03 | 통과 | "지금 동기화" → MANUAL 동기화, 마지막 동기화 시각 갱신 |
| SY-04 | 통과(고침) | 앱을 처음부터 켜면 동기화. **시스템이 프로세스를 죽인 뒤 켜면 안 함 → B2 고침** |
| SY-05 | 통과 | "녹음 파일 받는 중" + 진행 막대, 길이 "약 47초"(추정), 재생 비활성 ([SY-05](screenshots/SY-05-receiving-dark.png)) |
| SY-06 | 통과 | `drop 300` → 같은 동기화의 2번째 시도에서 `segment 323: resuming at byte 944 of 3275` |
| SY-07 | 통과 | `lose 5` → `segment 552: gap at byte 1180, ABORT + re-FETCH` → ACK |
| SY-08 | 통과 | 받는 중 `am force-stop`(14개 남음) → 다시 켜서 14개 마저, 유실·중복 없음 |
| SY-09 | 통과 | 가짜 목걸이 끔 → 연결 15초 타임아웃 × 3 → 약 58초 뒤 "연결 끊김" + "18:52 이후 목걸이 응답이 없어요…" + 연결 버튼 ([SY-09](screenshots/SY-09-disconnected-dark.png)). U8 |
| SY-10 | 통과 | 다시 켜고 연결 버튼 → 동기화, "켜짐" |
| SY-11 | 통과 | 최근 앱에서 쓸어 지움(프로세스 없음) → 8분 뒤 WorkManager가 프로세스를 띄워 PERIODIC 동기화 [확인: `Start proc … for bound-service SystemJobService`] |
| SY-12 | 통과 | DB 586행 = id 1–586, 모두 ACK, 파일 586개, `.part` 0, 가짜 목걸이 ACK 로그 중복 없음 |
| TL-01 | 통과 | 최신이 아래, 위로 날짜를 넘어 D-7까지 ([TL-01](screenshots/TL-01-today-dark.png)) |
| TL-02 | 통과(고침) | 헤더가 스크롤 위치를 따라감, 구분선 "오늘". **날짜로 이동에서 가장 오래된 날을 고르면 다음 날이 보임 → B4 고침**. U3 |
| TL-03 | 통과 | [시각·장소·텍스트], 녹음 없는 구간은 안 보임 |
| TL-04 | 통과 | D-5 14:00:00+14:02:03(간격 120초) 한 항목, 14:04:07(121초) 따로 [확인: DB `entryKey`, 화면] |
| TL-05 | 통과 | D-6 10:28·10:29 간격 87초인데 사이 녹음 꺼짐 → 두 항목 ([TL-05](screenshots/TL-05-oct26-dark.png)) |
| TL-06 | 통과 | 대화마다 세그먼트 묶음이 한 항목 (일주일 45항목) |
| TL-07 | 통과 | 부팅 3의 12개가 "시각 알 수 없음"으로 D-3 13:10 뒤에, 서로 안 묶임(#30) ([TL-07](screenshots/TL-07-unknown-time-dark.png)). U2 |
| TL-08 | 통과(고침) | 항목에 "북마크" 표시. 상세의 북마크 시각 → B6 고침 |
| TL-09 | 통과 | D-6 11:10, D-4 18:40, D-2 16:20 단독 줄, 시각 모르는 2개는 묶음 끝에 |
| TL-10 | 통과 | 북마크 필터: 북마크 있는 항목 + 단독 북마크 ([TL-10](screenshots/TL-10-bookmark-filter-dark.png)) |
| TL-11 | 통과 | "아직 기록이 없어요" / 기기 "등록된 목걸이가 없어요" ([TL-11](screenshots/TL-11-empty-dark.png), [DV-00](screenshots/DV-00-unregistered-dark.png)) |
| TL-12 | 통과 | demo-stt 끄고 동기화 → "텍스트 추출 중" + 회전 표시 ([TL-12](screenshots/TL-12-text-pending-dark.png)) |
| TL-13 | 통과 | 이름 없으면 "알 수 없는 장소". 위치가 없어도 같은 문구, 서로 다른 이름 없는 장소가 한 묶음(U1). 머리글 끝 시각 → B5 고침 |
| TL-14 | 관찰 | 배터리 부족·가득 참·전원 이벤트는 타임라인에 전혀 안 보임(이상한 항목도 없음). PRD에 요구 없음 → F2 |
| EN-01–04 | 통과 | 재생하면 "재생 중" 강조가 따라감, 이전/다음 문장이 묶인 세그먼트를 넘어 이동, 문장마다 실제 시각 ([EN-01](screenshots/EN-01-detail-light.png)) |
| EN-05 | 통과 | 텍스트 없이 "음성은 지금 들을 수 있어요", 재생됨 ([EN-05](screenshots/EN-05-pending-detail-dark.png)) |
| EN-06 | 통과 | 7일 보관으로 지운 항목: "음성 파일 삭제됨", 재생 버튼 비활성, 문장 이동은 됨 ([EN-06](screenshots/EN-06-audio-deleted-dark.png)) |
| EN-07 | 통과 | 메뉴 음성 보존/텍스트 복사/장소 이름/기록 삭제. 삭제 → 묶음 전체·붙은 북마크 삭제, 단독 북마크는 남음 ([메뉴](screenshots/EN-07-entry-menu-dark.png), [삭제 확인](screenshots/EN-07-delete-confirm-dark.png)) |
| EN-08 | 통과(고침) | 상세 북마크 줄 "10:05:00 · 북마크 표시함" (고치기 전 "10:05:44") ([EN-08](screenshots/EN-08-bookmark-in-detail-dark.png)) |
| EN-09 | 통과 | 제목 "시각 알 수 없음", 재생 가능 |
| PL-01 | 통과 | 오늘 집 항목에 "Home" → 반경 100m 지난 기록 12항목, D-1 85m 떨어진 편의점도 "Home" [확인: DB] ([대화 상자](screenshots/PL-01-name-dialog-dark.png)) |
| PL-02 | 통과 | 지금 녹음(18:26)도 "Home" (GPS는 진짜 경로, 아래 주의) |
| PL-03 | 통과 | 설정 › 장소에서 이름 바꾸기·지우기, 지우면 "알 수 없는 장소", 빈 상태 문구 ([PL-03](screenshots/PL-03-settings-places-dark.png)) |
| PL-04 | 통과 | 위치 없는 D-4 14:00: 메뉴 "이 장소 이름 붙이기" 비활성. 칩 문구는 U1 ([PL-04](screenshots/PL-04-no-location-name-dark.png)) |
| PL-05 | 통과 | 앱 프로세스 죽인 뒤 `geo fix` → `Start proc … for broadcast LocationReceiver` + DB 행 [확인]. 에뮬레이터 조건은 아래 |
| PL-06 | 부분 | 설정 스위치 꺼짐/켜짐 표시는 됨. Play 서비스 쪽 위치 요청이 실제로 멈추는지는 확인 못 함 |
| PL-07 | 통과 | 200m 떨어진 공학관(Hall)·도서관(Library): 132 / 38 세그먼트, 섞이지 않음 ([PL-07](screenshots/PL-07-two-places-dark.png)) |
| SE-01 | 통과(고침) | **"MLFQ" 검색하면 앱이 죽음 → B1 고침**. 결과 누르면 그 문장 위치 → **목록이 안 따라감 → B3 고침** ([검색](screenshots/SE-01-search-mlfq-dark.png), [열림](screenshots/SE-01-hit-opens-entry-dark.png)) |
| SE-02 | 통과 | Home + 오늘 3개 / 최근 7일 11개(D-7 20:10 포함, 09:30 제외: 168시간) |
| SE-03 | 통과 | 장소 Home만 12개 ([SE-03](screenshots/SE-03-place-home-dark.png)) |
| SE-04 | 통과 | 보존됨 1개, 북마크만 "10개 기록과 북마크 5개" ([SE-04](screenshots/SE-04-bookmarks-only-dark.png)) |
| SE-05 | 통과 | "찾는 기록이 없어요" ([SE-05](screenshots/SE-05-no-result-dark.png)) |
| SE-06 | 통과 | `%`, `_` 글자 그대로(결과 없음) |
| DV-01 | 통과 | 전원 "켜짐"과 녹음 "녹음 켜짐" 따로 ([DV-01](screenshots/DV-01-device-dark.png)) |
| DV-02 | 통과 | 스위치 끔 → 가짜 목걸이 `recording=False`, 화면 "녹음 꺼짐" ([DV-02](screenshots/DV-02-recording-off-dark.png)) |
| DV-03 | 통과 | 목걸이 `rec`(버튼 2초) → 동기화 후 "녹음 켜짐" |
| DV-04 | 통과 | 꺼진 동안 `record` 안 됨 |
| DV-05 | 부분 | 3550mV → "2% · 약 1시간 남음", 전압 안 보임. **경고 표시는 없음** → F1 ([DV-05](screenshots/DV-05-low-battery-full-dark.png)) |
| DV-06 | 통과 | 가득 참 → "가득 차서 녹음이 멈췄어요", 공간 생기면 사라짐 |
| DV-07 | 통과 | 마지막 동기화, 아직 안 받은 녹음, 펌웨어. 내부 정보 없음 |
| DV-08 | 통과 | 사용법 문구 없음 |
| DV-09 | 통과 | SY-09와 같음 |
| ST-01 | 통과(고침) | 선택지 5개, 기본 30일. "기록 602개"가 세그먼트 수 → B7 고침 ([ST-01](screenshots/ST-01-retention-dark.png)) |
| ST-02 | 통과 | 7일: D-7 13시대 18개 음성 삭제·문장 18개 남음, 보존한 09:30은 남음, 20:10은 남음 [확인: DB·파일]. **바꾼 즉시가 아니라 다음 동기화 때 적용** → U6 |
| ST-03 | 통과 | 추출 후 바로 삭제: 보존 안 한 558개 삭제, 보존 23개 남음, 새 녹음은 텍스트 뒤 삭제 |
| ST-04 | 통과(고침) | JSON(녹음 50 + 단독 북마크 5, 시각 모르는 것은 `start: null`), "기록 55개를 내보냈어요". 단독 북마크 장소 빠짐 → B8 고침 ([ST-04](screenshots/ST-04-export-picker-dark.png)) |
| ST-05 | 통과 | 개인정보 처리 방침 ([ST-05](screenshots/ST-05-privacy-dark.png)) |
| ST-06 | 통과 | 사용 설명 = `decisions.md` #21 표 + 보관 안내 ([ST-06](screenshots/ST-06-help-dark.png)) |
| ST-07 | 통과 | 라이트 바로 적용, 다시 켤 때 시작 창부터 라이트 ([ST-07](screenshots/ST-07-coldstart-light-splash.png)), 시스템 설정 따름이 `cmd uimode night yes/no`를 따름 |
| ST-08 | 통과 | 목걸이 켠 채 해제 → `UNPAIR: all bonds deleted` → 약 2초 뒤 온보딩. 다시 등록(창 닫힘)도 받아짐 |
| ST-09 | 통과 | 목걸이 끈 채 해제 → UNPAIR 15초 타임아웃 → 폰 본딩·CDM 삭제 → 온보딩. 그 15초 동안 표시 없음 → U4 |
| ST-10 | 통과 | `allowBackup=false`(패키지 플래그에 ALLOW_BACKUP 없음), `dataExtractionRules` |
| SEC-01 | 통과 | 가짜 목걸이 키 파일 삭제 → 암호화 실패 status 5 → "다시 등록 필요", 재시도 없음 ([SEC-01](screenshots/SEC-01-key-lost-dark.png)). F3 |
| SEC-02 | 통과 | 해제 → 다시 등록. 첫 시도 실패(O1), 두 번째 성공 |
| SEC-03 | 통과 | 시스템 블루투스 설정에서 "Forget" → 연결 시도 없이 "다시 등록 필요" ([SEC-03](screenshots/SEC-03-system-forget-dark.png)) |
| ED-01 | 통과 | 받는 중 `reboot` → 끊김 → 새 부팅 TIME_SYNC → 이어받기 → 19개 ACK |
| ED-02 | 통과 | DV-06 |
| ED-03 | 부분 | DV-05 (경고 없음) |
| ED-04 | 통과 | EN-07 (삭제 뒤 타임라인 정상) |
| UX-01 | 통과 | 다크·라이트 주요 화면 깨진 색 없음 ([라이트 타임라인](screenshots/TL-01-today-light.png), [라이트 기기](screenshots/DV-01-device-light.png)) |
| UX-02 | 통과 | 글꼴 200%: 막히는 곳 없음(필터 칩은 가로 스크롤). 작은 줄바꿈 → U11 ([타임라인](screenshots/UX-02-font200-timeline-dark.png), [상세](screenshots/UX-02-font200-entry-dark.png)) |
| UX-03 | 통과 | 85% 정상 |
| UX-04 | 관찰 | 가로 모드 지원, 하지만 머리글·탭이 화면 대부분 → U12 ([UX-04](screenshots/UX-04-landscape-timeline-dark.png)) |
| UX-05 | 통과 | 표본: 아이콘 버튼 설명(뒤로·더 보기·재생·이전 문장…), 녹음 줄은 이름 있는 토글, 단독 북마크 줄 설명, 누르는 영역 48dp 이상 |
| UX-06 | 통과 | 용어 통일(북마크·녹음 꺼짐·연결 끊김·음성 보존), 해요체. 옛 용어 없음("일시정지"는 재생 버튼에만) |
| UX-07 | 관찰 | 검색·기기 탭에서 뒤로 → 앱 종료(타임라인으로 안 감) → U9 |

## 버그 (모두 고침, [wndgur2/owl#14](https://github.com/wndgur2/owl/pull/14))

| # | 버그 | 심각도 | 재현 | 증거 | 근거 |
|---|---|---|---|---|---|
| B1 | 검색어가 **한 기록 안의 문장 두 개 이상**에 걸리면 앱이 죽음 | 높음 | 일주일 시드 → 검색 "MLFQ" | `IllegalArgumentException: Key "r-516-0" was already used` (18:43:38, 18:45:03 두 번). 결과 키가 세그먼트 안 문장 번호(`Sentence.index`)라 묶인 기록에서 겹침. 진짜 STT에서도 흔한 경우 | A11, #30 |
| B2 | 시스템이 백그라운드에서 앱 프로세스를 죽인 뒤 앱을 열면 **다시 연결하지 않음** | 중간 | 앱 열기 → 홈 → 프로세스 죽음(`run-as kill -9`, 실제로는 메모리 회수) → 런처에서 열기 | 18:39:11 화면 복원(`Displayed`)인데 `APP_START` 동기화 없음. 화면이 저장 상태로 복원돼 `savedInstanceState != null`이라 건너뜀. 고친 뒤 18:40:52 `sync (APP_START)` | A2a "앱을 켤 때마다" |
| B3 | 검색 결과로 연 기록에서 재생 위치·강조는 맞는데 **문장 목록이 그 문장으로 안 감** | 중간 | 검색 → 결과(14:17 문장) 누르기 | 0:29로 이동, 14:17:13 강조, 화면은 14:00 문장부터. 목록은 재생 중일 때만 따라감 | A8, A11 |
| B4 | 날짜로 이동에서 **가장 오래된 날을 고르면 그다음 날이 보임** | 낮음 | 날짜로 이동 → 10월 25일 | 헤더 "10월 26일", D-6 항목만 보임. 목록이 이미 위 끝에 걸렸는데 한 화면만큼 더 밀어냄 | A7 |
| B5 | 장소 묶음 머리글의 끝 시각 = 시작 + **오디오 길이** | 낮음 | 10:00–10:19 대화 | "10:00 – 10:00" ([전](screenshots/TL-01-today-dark.png)) → "21:00 – 21:09"([후](screenshots/TL-05-oct26-dark.png)). 진짜 목걸이도 VAD로 쉼을 빼서 같은 문제 | A7, #30 |
| B6 | 묶인 기록 사이 쉼에 누른 북마크가 상세에서 **다음 세그먼트 시작 시각**으로, 그 문장 **뒤**에 보임 | 낮음 | D0 10:05:00 북마크 | "10:05:44 · 북마크 표시함" → "10:05:00", 순서도 문장 앞으로 | A9 |
| B7 | 설정 › 음성 보관 "기록 N개"가 세그먼트 수 | 낮음 | 음성 보관 화면 | "기록 602개" → 49개 | #30 ("기록" = 묶음) |
| B8 | 내보내기에서 **녹음 밖 북마크의 장소가 빠짐**(타임라인엔 보임) | 낮음 | 기록 내보내기 | `"kind":"bookmark", "latitude": null` → 11:10 "Hall", 16:20 "Library" | A13 |

각 수정은 에뮬레이터에서 같은 절차로 다시 확인했어요. 단위 테스트는 B5에 `TimelineRowsTest`를 더했고, 나머지는 Compose 화면·DAO 쿼리라 화면으로 확인했어요.

## 빈 기능 (PRD가 정하지 않았거나 덜 정한 것 — 결정 필요)

| # | 내용 | 심각도 | 근거 / 증거 | 추천 |
|---|---|---|---|---|
| F1 | 배터리가 3.6V 아래여도 앱에 **경고가 없음**(2%만 보임). 배터리 부족 이벤트(5)도 안 씀 | 중간 | D10은 "LED·이벤트로 경고", A10은 잔량만. [DV-05](screenshots/DV-05-low-battery-full-dark.png) | 기기 화면 배터리 칸에 "배터리 부족" 한 줄(가득 참 문구와 같은 방식). STATUS 전압 < 3600 또는 이벤트 5 |
| F2 | 기기 이벤트(배터리 부족·저장 공간 가득·기기 꺼짐/켜짐·녹음 꺼짐/켜짐)가 **타임라인에 안 보임** → D-1 16:40–19:00처럼 녹음이 빈 이유를 알 수 없음 | 중간 | PRD A7·A9에 없음. 데이터는 DB `events`에 있음 | 단독 북마크처럼 약한 줄로 "녹음 꺼짐(버튼)", "저장 공간 가득 참" 등 표시. 적어도 녹음 꺼짐·가득 참 |
| F3 | "다시 등록 필요" 상태에서 **바로 다시 등록하는 버튼이 없음**(설정 › 해제 → 온보딩 → 등록, 3단계) | 낮음 | [SEC-01](screenshots/SEC-01-key-lost-dark.png) | 기기 화면 안내 아래 "다시 등록" 버튼(해제 + 등록을 한 번에) |
| F4 | 등록 해제 중(목걸이가 꺼져 있으면 최대 15초) **진행 표시 없음** — 화면이 그대로라 다시 누르기 쉬움 | 낮음 | ST-09 (19:24:57 → 19:25:11) | 해제 버튼에 진행 표시, 끝나면 온보딩 |

## UX (결정 필요)

| # | 내용 | 심각도 | 증거 | 추천 |
|---|---|---|---|---|
| U1 | 이름 없는 서로 다른 장소가 **한 묶음 "알 수 없는 장소"**로 합쳐짐(공학관→학생회관→도서관→카페가 하루 종일 한 머리글 "09:00 – 19:09"). 위치가 아예 없는 기록도 같은 문구 | 중간 | TL-13, [TL-07](screenshots/TL-07-oct29-dark.png), [PL-04](screenshots/PL-04-no-location-dark.png) | 좌표가 100m 넘게 바뀌면 머리글을 새로(이름은 그대로 "알 수 없는 장소"). 위치 없으면 "위치 없음" |
| U2 | 시각 모르는 부팅의 세그먼트가 **하나씩 따로**(대화 2개가 12항목), 그 안의 북마크도 단독으로 | 중간 | [TL-07](screenshots/TL-07-unknown-time-dark.png). `decisions.md` #30 결정대로 | #30 보완: 같은 부팅 안에서는 uptime 간격 2분으로 묶기(시각은 몰라도 간격은 앎) |
| U3 | 앱을 열면 헤더가 **어제 날짜**(오늘 항목이 화면보다 적을 때, 헤더 = 맨 위에 보이는 항목) | 낮음 | [TL-01](screenshots/TL-01-today-dark.png) "10월 31일" | 맨 아래(최신)까지 보이면 "오늘" 날짜, 또는 헤더 = 가운데 항목 |
| U5 | 창 밖 페어링 거절 안내("껐다 켠 뒤 2분 안에…")가 **5–8초 스낵바**로 사라짐. 목걸이를 끄러 간 사이 놓침 | 중간 | [ON-06](screenshots/ON-06-pairing-refused-dark.png) | 등록 카드 안에 남는 안내(다시 시도할 때까지) |
| U6 | 음성 보관 기간을 바꿔도 **다음 동기화(최대 15분)나 하루 작업 때** 적용. 그 사이 상세는 지난 "삭제 예정" 날짜를 보여줌 | 낮음 | ST-02 | 바로 적용. 단 7일·추출 후 바로 삭제처럼 지금 지워지는 게 있으면 "N개 기록의 음성이 지금 지워져요" 확인 창(되돌릴 수 없어서 바로 고치지 않았어요) |
| U7 | 같은 프로세스에서 **백그라운드에서 돌아올 때는** 재연결 안 함(B2는 프로세스가 죽은 경우만 고침) | 낮음 | SY-04 | A2a "앱을 켤 때"에 이것도 넣을지 결정. 넣으면 마지막 동기화 5분 이상일 때만 |
| U8 | 목걸이가 꺼지면 **약 1분간 "연결 중"** 뒤 "연결 끊김"(15초 × 3). 비활성 녹음 스위치는 꺼진 것처럼 보이는데 글자는 "녹음 켜짐" | 낮음 | [SY-09](screenshots/SY-09-disconnected-dark.png) | 연결 타임아웃 15초 → 8초 검토(실기기에서 측정 후). 연결 끊김 때 녹음 줄 글자 "마지막 상태: 켜짐" |
| U9 | 검색·기기 탭에서 뒤로 → **앱 종료** | 낮음 | UX-07 | Android 관례대로 첫 탭(타임라인)으로, 거기서 뒤로면 종료 |
| U10 | 타임라인의 "장소별" 칩이 **검색 탭**으로 이동(장소 메뉴 열림) | 낮음 | [SE-03 메뉴](screenshots/SE-03-place-menu-dark.png) | 타임라인 안에서 장소 필터, 또는 칩 이름을 "장소로 찾기" |
| U11 | 글꼴 200%: 재생 속도 "1.0×"가 두 줄, "약 50시간 남음"·"마지막 동기화" 줄바꿈 | 낮음 | [UX-02](screenshots/UX-02-font200-entry-dark.png), [기기](screenshots/UX-02-font200-device-dark.png) | 속도 버튼 최소 너비, 한 줄 고정 |
| U12 | 가로 모드에서 머리글·필터·하단 탭이 화면 대부분(카드 1.5개, 상세는 문장 1개) | 낮음 | [UX-04](screenshots/UX-04-landscape-entry-dark.png) | 세로 고정, 또는 가로에서 NavigationRail |
| U13 | 날짜 선택에서 **미래 날짜**를 고를 수 있음 | 낮음 | [TL-02](screenshots/TL-02-date-picker-dark.png) | 오늘까지만 선택 가능 |

## 관찰 (원인 확인 못 함)

- **O1 키 분실 뒤 첫 재등록 실패** [확인: 로그] / 원인 [추정]: SEC-02에서 해제(폰 `removeBond` 19:29:5x) 8초 뒤 등록하니 `createBond()`가 30초 BONDING 후
  `BOND_NONE`(status 9, hciReason 104)으로 끝났고, 가짜 목걸이엔 페어링 요청이 오지 않았어요. 앱은 이것을 "목걸이가 거절"로 보고 껐다 켜라고 안내해요.
  두 번째 시도는 바로 성공. 에뮬레이터(netsim)만의 일인지, `removeBond` 직후 경쟁인지 확인 못 했어요. 실기기에서 다시 보고, 반복되면
  "30초 타임아웃"과 "SMP 거절"을 나눠 안내하는 걸 추천해요.
- **에뮬레이터 위치** [확인]: 앱은 절전 정확도(BALANCED)로 위치를 요청해서, 에뮬레이터에선 다른 앱(Google 지도)이 GPS를 켜야 `geo fix`가 앱에 와요.
  플랫폼 테스트 제공자(`cmd location providers … network`)는 Play 서비스 FLP가 쓰지 않았어요. 실폰은 Wi-Fi·기지국으로 오니 앱 버그 아님 [추정].
- **에뮬레이터 시계** [확인]: 이 AVD 시계가 Mac보다 9.4초 늦어서 처음엔 14:00:00 녹음이 13:59로 보였어요. 앱 버그 아님. `--phone-clock-offset`을 더했어요.
- **CDM DEVICE_APPEARED** [확인]: 가짜 목걸이가 계속 켜져 있으면 다시 오지 않아서, 앱을 지운 뒤 동기화는 15분 WorkManager가 했어요(SY-11). 문서와 같음.

## 확인 못 한 것 / 범위 밖

- 하드웨어가 필요한 것: VAD·Opus 인코딩·LED·전압 차단·충전(D1–D3, D9–D12), 실제 처리량·유실, 진짜 컨트롤러의 키 분실 0x06, API 31–35, Doze·제조사 배터리 관리.
- 진짜 STT(M6): 텍스트는 `DemoTranscriber` 고정 문장이에요.
- `FakeOwlDevice`(앱 안 가짜 기기)와 PC `sync.py`는 이번 범위가 아니에요.
- 텍스트 복사는 Android 13+가 자체 미리보기를 띄워서 앱 문구가 없는 게 맞는지만 봤어요(클립보드 내용은 확인 못 함).

## 결정 요청 (사용자)

아래 중 할 것을 골라 주세요. 추천 순서대로예요.

1. **F2** 타임라인에 녹음 꺼짐·저장 공간 가득 등 기기 이벤트 표시
2. **U1** 이름 없는 장소가 바뀌면 묶음 나누기 + 위치 없음 문구
3. **F1** 배터리 부족 표시
4. **U5** 페어링 거절 안내를 남는 안내로 / **F3** "다시 등록" 버튼 / **F4** 해제 진행 표시 (등록 흐름 묶음)
5. **U2** 시각 모르는 부팅도 uptime으로 묶기 (`decisions.md` #30 변경)
6. **U6** 보관 기간 바로 적용 + 확인 창
7. 작은 것 묶음: U3, U7, U8, U9, U10, U11, U12, U13

## 재현

환경·시드·명령은 [scenarios.md](scenarios.md) "준비"와 [`tools/README.md`](../../tools/README.md) "일주일 치 데이터".
QA 중 쓴 debug 명령: `import-locations`, `demo-stt`([`android/README.md`](../../android/README.md)), 가짜 목걸이 표준 입력 `record`·`rec`·`battery`·`storage`·`drop`·`lose`·`reboot`.
