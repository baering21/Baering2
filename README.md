# 배달링2 비콘 수신 앱 (Baering2)

BLE 비콘 "배달링2"(광고 이름 `baering2`)의 신호를 받아 VCC · 스위치(GPIO) 상태를 보여주는 안드로이드 테스트 앱입니다. 연결 없이 광고만 수신합니다.

## 비콘 형식
```
09 09 62 61 65 72 69 6E 67 32 05 FF 00 00 55 DD
└─ Complete Local Name "baering2"  └─ 제조사 데이터: 회사 ID 0x0000 | VCC(1B) | GPIO(1B)
```
- `scanRecord.getManufacturerSpecificData(0x0000)` → `[VCC, GPIO]`
- GPIO는 SPST 스위치 상태(비트마다 스위치 1개)

## 주요 기능
- 상시 BLE 스캔(포그라운드 서비스, 화면 꺼져도 수신)
- 신호 인식 후 0.3초 뒤 기준값 초기화 → 같은 버튼을 반복해 눌러도 인식 (`BeaconManager.RESET_MS`)
- 기기별 RSSI · 수신 간격 · VCC · GPIO 8비트 · 값별 인식 횟수 · Raw 데이터
- 변화 기록 / 상세 로그 공유, 다른 앱 위에 뜨는 위젯

## 빌드
- Android Studio (JDK 25 내장 JBR), AGP 9.4.1 · Gradle 9.8.1 · Kotlin 2.4.21 · Compose
- minSdk 31 (Android 12) / targetSdk 37
- `Baering2App` 폴더를 Android Studio로 열거나 명령줄에서:
  ```
  cd Baering2App
  gradlew.bat assembleDebug
  ```
  결과: `Baering2App/app/build/outputs/apk/debug/app-debug.apk`
- BLE는 에뮬레이터에서 안 되므로 실제 폰에서 테스트하세요.

## 코드 구성 (`Baering2App/app/src/main/java/com/example/baering2`)
| 파일 | 역할 |
|---|---|
| `Beacon2Packet.kt` | 제조사 데이터 해석 (VCC, GPIO) |
| `BeaconManager.kt` | 스캔 · 수신 · 인식/초기화 · 기록, 상태를 `StateFlow`로 내보냄 |
| `BeaconService.kt` | 포그라운드 서비스 + 알림 |
| `MainActivity.kt` | Compose 화면 |
| `LastChangeBox.kt` | 마지막 인식 표시 · 비트 램프 · 테마 |
| `OverlayWidget.kt` | 다른 앱 위에 뜨는 위젯 |

자세한 사양과 진행 상황은 [CLAUDE.md](CLAUDE.md)를 참고하세요.
