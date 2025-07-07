# Scouter Plugin Server Alert Slack

## 소개
이 플러그인은 Scouter 서버의 알림을 Slack으로 전송하는 기능을 제공합니다.

## 주요 기능
- 다양한 유형의 알림 지원 (스레드 수, 응답시간, 에러, GC 시간)
- 알림 레벨별 차별화된 처리 (FATAL, WARN, INFO)
- 알림 히스토리 관리
- Slack 채널별 알림 설정 지원

## 시스템 구조

### 핵심 컴포넌트

#### AbstractAlertHandler
- 알림 처리를 위한 추상 기본 클래스
- 공통 알림 로직 구현
- 하위 클래스에서 구체적인 알림 처리 방식 정의

#### AlertContext
- 알림 관련 데이터를 캡슐화하는 클래스
- 알림 처리에 필요한 모든 컨텍스트 정보 포함

### 알림 핸들러

#### ThreadCountAlertHandler
- 스레드 수 관련 알림 처리
- 임계값: historyCount > 1일 때 FATAL 레벨 사용
- 스레드 수 증가 추이 모니터링

#### ElapsedTimeAlertHandler
- 응답시간 관련 알림 처리
- 레벨 결정 로직:
  - historyAvg에 따라 FATAL/WARN/INFO 레벨 결정
  - INFO 레벨은 알림 스킵
- 성능 저하 상황 모니터링

#### ErrorAlertHandler
- 에러 관련 알림 처리
- 항상 ERROR 레벨 사용
- 에러 메시지를 알림 제목으로 사용
- 상세한 에러 정보 포함

#### GCTimeAlertHandler
- GC 시간 관련 알림 처리
- 임계값: historyCount > 0일 때 FATAL 레벨 사용
- interval 정보 로깅
- GC 성능 문제 모니터링

## 설정 방법

### 기본 설정
```properties
# Slack 웹훅 URL 설정
ext_plugin_slack_webhook_url=https://hooks.slack.com/services/...

# 알림 채널 설정
ext_plugin_slack_channel=#monitoring

# 알림 사용자명 설정
ext_plugin_slack_botName=Scouter
```

### 모니터링 그룹 설정
```properties
# 모니터링 그룹별 채널 설정
ext_plugin_slack_channel_group_a=#group-a-monitoring
ext_plugin_slack_channel_group_b=#group-b-monitoring
```

## 알림 레벨별 특징

### FATAL
- 긴급한 조치가 필요한 심각한 문제
- 즉시 알림 발송
- 상세 정보 포함

### WARN
- 주의가 필요한 상황
- 문제 발생 가능성 경고
- 기본 정보 포함

### INFO
- 참고용 정보
- 필요한 경우에만 알림 발송
- 간단한 정보 포함

## 히스토리 관리
- 각 알림 유형별 히스토리 보관
- 문제 패턴 분석에 활용
- 알림 중복 방지 지원

## 사용 예시

### 스레드 수 알림
```
[FATAL] Thread Count Alert
- Instance: OrderService
- Current Count: 100
- Threshold: 80
- History Count: 3
```

### 응답시간 알림
```
[WARN] Elapsed Time Alert
- Instance: PaymentService
- Current Time: 5000ms
- Average Time: 4500ms
- Threshold: 3000ms
```

### 에러 알림
```
[ERROR] Exception Alert
- Instance: UserService
- Error: NullPointerException
- Location: UserController.java:150
- Stack Trace: ...
```

### GC 시간 알림
```
[FATAL] GC Time Alert
- Instance: AuthService
- Current Time: 500ms
- Interval: 60s
- History Count: 2
```

## 주의사항
1. Slack 웹훅 URL은 보안을 위해 외부에 노출되지 않도록 주의
2. 알림 임계값 설정 시 서비스 특성 고려
3. 알림 채널 설정 시 올바른 채널명 확인
