# android/

아직 프로젝트 파일이 없어요. Android Studio에서 **Empty Activity (Compose)** 템플릿으로 이 폴더에 생성해서 시작하세요.

- 패키지: `dev.owl.app` (원하는 대로)
- minSdk 31 권장 (CompanionDeviceService, 새 BLE 권한 모델)
- 언어: Kotlin

화면 디자인(1차 확정)은 [`docs/design/android/`](../docs/design/android/README.md)에 있어요.

앱 아이콘(adaptive icon)은 [`docs/assets/icon/android/res/`](../docs/assets/icon/README.md#android)를 `app/src/main/res/`에 복사해서 써요. 템플릿이 만든 기본 아이콘 파일은 지우고 덮어쓰세요.

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

## STT 엔진: 온디바이스로 결정

`docs/decisions.md` #10 — 클라우드 STT는 쓰지 않아요. Android `SpeechRecognizer`는 **마이크 실시간 입력 전용**이라 저장된 파일을 넣을 수 없어서, whisper.cpp / sherpa-onnx 같은 온디바이스 엔진 중에서 골라요. 배터리·속도·모델 용량을 M6에서 비교해요.

## 첫 마일스톤 (M4)

1. BLE/위치 권한 요청
2. CDM으로 `Owl-*` 기기 연결 등록
3. 연결되면 STATUS 읽어서 화면에 표시
