# 검증 결과

2026-11-11 기준 로컬 Windows 환경에서 검증했습니다. Android Studio는 사용하지 않았습니다.

- JDK 17, Gradle 8.11.1, Android SDK 35 / Build Tools 35.0.0으로 `assembleDebug lintDebug` 성공.
- JavaScript 다운로드 브리지 테스트 5개 통과: 다중 청크 바이너리 일치, 취소 후 재시도, 잘못된 MIME 거부, 비동기 읽기 중 취소, 사이트의 프로그램 방식 다운로드 클릭.
- 최종 APK의 Android APK Signature Scheme v2 서명 검증 통과.
- APK 메타데이터에서 패키지 `kr.haeyang.gifstudio`, 버전 `1.0.0`, 최소 SDK 26, 대상 SDK 35, 한글 앱 이름 확인.
- Gradle Wrapper JAR의 SHA-256을 Gradle 공식 배포 체크섬과 대조해 일치 확인.
- 아이콘 PNG를 직접 확인. Android 아이콘은 마스크에 잘리지 않도록 벡터 전경에 안전 여백을 적용.
- GitHub Actions에서 사용하는 액션 버전과 입력 항목을 각 공식 저장소에서 확인.

Lint는 오류 없이 통과했으며, 기능 지원 검사 추적·리소스 버전 구성·고정한 라이브러리 버전 등에 관한 권고 경고가 남아 있습니다. 브리지는 실제 실행 시 `WebViewFeature.isFeatureSupported` 확인 후에만 등록합니다.

GitHub 저장소 생성·업로드 및 GitHub 서버에서의 워크플로 실행은 수행하지 않았습니다. 프로젝트의 README에 업로드 및 실행 절차를 포함했습니다. 실제 Android 기기 또는 에뮬레이터에서의 설치·영상 변환·시스템 파일 선택기 동작은 별도 확인이 필요합니다.

제공 APK는 직접 설치를 위한 개발용 서명 버전입니다. 공개 개발용 키를 사용하며 Play Store용 정식 릴리스 서명은 포함하지 않습니다.
