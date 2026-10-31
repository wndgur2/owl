# android/

아직 프로젝트 파일이 없어요. Android Studio에서 **Empty Activity (Compose)** 템플릿으로 이 폴더에 생성해서 시작하세요.

- 패키지: `dev.owl.app` (원하는 대로)
- minSdk 31 권장 (CompanionDeviceService, 새 BLE 권한 모델)
- 언어: Kotlin

## 설계 (초안)

```
CompanionDeviceManager ──(목걸이 범위 진입)──▶ CompanionDeviceService
                                                      │ 시작
                                                      ▼
                                   SyncService (foreground, type=connectedDevice)
                                     ├─ BleClient: protocol/ble-protocol.md 구현
                                     ├─ SegmentStore: 파일 저장 → 그 다음에 ACK
                                     └─ TimeSync: (boot_count, uptime) ↔ 폰 시각
                                                      │
                     WorkManager ◀────────────────────┘
                       └─ TranscribeWorker: Opus → PCM → STT → Room DB

LocationRecorder: FusedLocationProvider + PendingIntent (앱 프로세스 없어도 기록)
```

## 미결정: STT 엔진

Android `SpeechRecognizer`는 **마이크 실시간 입력 전용**이라 저장된 파일을 넣을 수 없어요. 후보:

| 방법 | 장점 | 단점 |
|---|---|---|
| 온디바이스 Whisper (whisper.cpp / sherpa-onnx) | 오프라인, 무료, 프라이버시 | 배터리·속도, 모델 용량 |
| 클라우드 STT API | 정확도, 구현 쉬움 | 비용, 음성 외부 전송 |

결정되면 `docs/decisions.md`에 기록.

## 첫 마일스톤 (M4)

1. BLE/위치 권한 요청
2. CDM으로 `Owl-*` 기기 연결 등록
3. 연결되면 STATUS 읽어서 화면에 표시
