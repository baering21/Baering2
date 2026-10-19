# 배달링2 비콘 수신 앱 (Baering2)

기존 비콘 "배달링2"(광고 이름 `baering2`)의 신호를 받는 갤럭시 전용 테스트 앱. 배달링3(`BAERING3`) 앱(`Desktop\BaeringTester\RingTesterApp`)과 같은 구조·빌드 방식.

## 작업 규칙
- 사용자와는 **한국어**로, 쉬운 말로.
- Kotlin + Jetpack Compose, minSdk 31 / targetSdk 37, AGP 9.4.1 · Gradle 9.8.1 · Kotlin 2.4.21 (배달링3 앱과 동일).
- 빌드: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` → `Baering2App\gradlew.bat assembleDebug`
- 결과물은 **버전을 붙여** `바탕화면\Baering2_v<버전>.apk`와 `바탕화면\Baering2Tester\Baering2_v<버전>.apk`로 복사. 버전은 `app/build.gradle.kts`의 `versionName`/`versionCode`를 올린다.
- 패키지 `com.example.baering2` (배달링3 앱과 함께 설치 가능), 앱 이름 "배달링2".

## 비콘 사양
- 광고 예: `09 09 62 61 65 72 69 6E 67 32 05 FF 00 00 55 DD`, 약 66ms 간격, 연결 안 함.
  - `09 09 "baering2"` Complete Local Name
  - `05 FF 00 00 | VCC | GPIO` 제조사 데이터, 회사 ID 0x0000
  - VCC 1바이트(예 0x55), SPST 스위치 GPIO 1바이트(예 0xDD, 비트마다 스위치 1개)
- Android: `getManufacturerSpecificData(0x0000)` → `[VCC, GPIO]`
- 미확정: VCC 원시값 → 전압 환산식, GPIO 비트별 스위치 대응·극성(눌림=0/1). 앱은 현재 원시값만 표시.

## 앱 처리 (v0.1)
- ScanFilter(이름 `baering2` / 제조사 0x0000) + LOW_LATENCY + ALL_MATCHES, 포그라운드 서비스, 스캔 ON/OFF 저장.
- 같은 광고가 계속 오므로 **GPIO 값이 바뀔 때만** 이벤트 기록(어느 비트가 0→1/1→0), 비트별 변화 횟수, 마지막 변화 강조·깜빡임.
- 기기(MAC)별: RSSI, 받은 광고 수, 평균 수신 간격, VCC/GPIO, Raw. 3초 무신호 → "신호 끊김" 기록, 돌아오면 "다시 잡힘".
- VCC 변화는 상세 로그에만(5초에 1번까지).
- 오버레이 위젯(다른 앱 위에 표시)으로 백그라운드 수신 확인.

## 진행 상황
- v0.1 (2026-10-19) 빌드 완료, 실기기 미검증.
- v0.2 (2026-10-19) 같은 버튼 반복 미인식 수정, 빌드 완료, 실기기 미검증.
  - 신호를 인식하면 **0.3초 뒤 비교 기준값 초기화**(`BeaconManager.RESET_MS`) → 같은 GPIO 값이 다시 와도 새로 인식("같은 신호 재인식").
  - 같은 값이 계속 들어오면 0.3초마다 재인식됨 (배달링2가 누를 때만 광고하는지, 계속 광고하는지 실기기 확인 필요).
  - 기기 카드에 **GPIO 값별 인식 횟수**, 상단에 신호 인식 총 횟수. 알림에도 인식 횟수.
