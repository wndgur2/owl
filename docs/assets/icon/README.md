# 앱 아이콘

수막새 부엉이(S1 확정본)를 플랫폼별 규격으로 뽑은 파일이에요. 원본은 [`../owl-icon.svg`](../owl-icon.svg)이고, 여기 있는 파일은 전부 `build.py`가 만들어요. **직접 고치지 말고 `build.py`를 고친 다음 다시 돌리세요.**

```bash
brew install librsvg        # rsvg-convert
pip install pillow
python3 docs/assets/icon/build.py
```

색은 Material 3 baseline **다크** 롤에서 가져왔어요.

| 부위 | 롤 | 값 |
|---|---|---|
| 바깥 테 · 정사각 배경 | SurfaceContainerHighest | `#36343B` |
| 연주문(구슬) | OnSurfaceVariant | `#CAC4D0` |
| 안쪽 바탕 · 음각 | Surface | `#141218` |
| 안쪽 가는 원 | OutlineVariant | `#49454F` |
| 부엉이 | OnSurface | `#E6E0E9` |
| 연꽃 | Tertiary | `#EFB8C8` |

## 폴더

```
src/        중간 SVG (master, square, maskable, adaptive-foreground/monochrome, square-tinted)
android/    res/ 를 앱의 app/src/main/res 에 그대로 복사 + Play 스토어용 512
ios/        AppIcon.appiconset 폴더째 Xcode Assets.xcassets 에 넣기
web/        PWA·웹 루트에 복사, head.html 내용을 <head>에
```

## Android

- `minSdk 31`이라 **adaptive icon만** 있으면 돼요(API 26 미만용 레거시 PNG 없음).
- 구성: 배경 `@color/ic_launcher_background` + 전경 `ic_launcher_foreground.png` + 테마 아이콘용 `ic_launcher_monochrome.png`(Android 13+, 알파만 쓰임).
- 전경은 108dp 캔버스 중 **지름 66dp 세이프존** 안에 연주문까지 들어가게 맞췄어요. 런처가 원·둥근 사각·물방울 중 어떤 모양으로 잘라도 그림이 안 잘려요.
- Android Studio 템플릿으로 프로젝트를 만들면 기본 안드로이드 로봇 아이콘이 생겨요. 아래를 지우고 `res/`를 덮어쓰세요.
  - `mipmap-*dpi/ic_launcher*.webp`
  - `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_background.xml`
  - `mipmap-anydpi*/ic_launcher*.xml` (이 폴더 것으로 교체)
- `AndroidManifest.xml`: `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"` (템플릿 기본값 그대로)
- `play-store-512.png`: Play Console 앱 아이콘(512×512, 알파 없음). 모서리는 스토어가 둥글게 잘라요.

## iOS

- Xcode 14+ 단일 사이즈 방식: 1024×1024 하나에서 나머지 크기를 Xcode가 만들어요.
- iOS 18+ 변형: `dark`(이미 다크 톤이라 기본과 같음), `tinted`(흑백, 시스템이 색을 입힘).
- App Store는 **알파 채널이 있는 아이콘을 거부**해서 전부 RGB(불투명)로 저장했어요.
- PRD 9장에서 iOS 앱은 v1 비목표예요. 나중을 위해 만들어 둔 거예요.

## Web / PWA

| 파일 | 용도 |
|---|---|
| `favicon.ico` | 16·32·48 묶음. 구형 브라우저 |
| `favicon.svg` | 최신 브라우저 탭 아이콘 |
| `apple-touch-icon.png` | iOS Safari "홈 화면에 추가" (180, 불투명) |
| `icon-192.png`, `icon-512.png` | manifest `purpose: any` (원형, 투명 배경) |
| `icon-maskable-*.png` | manifest `purpose: maskable` — 지름 80% 세이프존 안에 맞춤 |
| `manifest.webmanifest` | 이름·색·아이콘 목록 |
| `head.html` | `<head>`에 붙일 태그 |

favicon 16px에서는 부엉이 디테일이 뭉개지고 동그란 실루엣 정도만 남아요. 고해상도 화면은 대부분 32px 이상을 써서 실제로는 괜찮아요.
