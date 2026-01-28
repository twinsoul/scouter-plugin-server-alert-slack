# Scouter Plugin Server Alert Slack

## Introduction
This plugin provides functionality to send Scouter server alerts to Slack channels.

## Key Features
- Support for various alert types (Thread Count, Response Time, Error, GC Time)
- Differentiated handling by alert levels (FATAL, WARN, INFO)
- Alert history management
- Slack channel configuration support per monitoring group

## System Diagrams

![System Architecture Diagram](architecture_diagram.png)

### Class Diagram

![Class Diagram](class_diagram.png)
```mermaid
classDiagram
    class SlackPlugin {
        -Configure conf
        -MonitoringGroupConfigure groupConf
        -LinkedMap~String, AlertHistory~ alertHistoryLinkedMap
        -ThreadCountAlertHandler threadCountHandler
        -ElapsedTimeAlertHandler elapsedTimeHandler
        -GCTimeAlertHandler gcTimeHandler
        -ErrorAlertHandler errorHandler
        +alert(AlertPack pack)
        +object(ObjectPack pack)
        +xlog(XLogPack pack)
        +counter(PerfCounterPack pack)
        -checkThreadCount()
        -println(Object o)
    }

    class AbstractAlertHandler {
        #LinkedMap alertHistoryMap
        +handleAlert(AlertContext context)
    }

    class AlertContext {
        -String alertPattern
        -String objName
        -String objType
        -int interval
        -String metricValue
        -int threshold
        -int objHash
    }

    class Message {
        -String text
        -String channel
        -String username
        -String icon_url
        -String icon_emoji
    }

    AbstractAlertHandler <|-- ThreadCountAlertHandler
    AbstractAlertHandler <|-- ElapsedTimeAlertHandler
    AbstractAlertHandler <|-- GCTimeAlertHandler
    AbstractAlertHandler <|-- ErrorAlertHandler
    
    SlackPlugin --> AbstractAlertHandler
    SlackPlugin --> AlertContext
    SlackPlugin --> Message
```

### Sequence Diagram
```mermaid
sequenceDiagram
    participant Client
    participant SlackPlugin
    participant AlertHandler
    participant Slack
    participant NaverWorks

    Client->>SlackPlugin: Event Triggered(alert/xlog/object/counter)
    SlackPlugin->>SlackPlugin: Check Alert Conditions
    
    alt Alert Conditions Met
        SlackPlugin->>AlertHandler: handleAlert(context)
        AlertHandler-->>SlackPlugin: AlertPack
        
        par Send to Slack
            SlackPlugin->>Slack: HTTP POST (webhook)
            Slack-->>SlackPlugin: Response
        and Send to NaverWorks
            SlackPlugin->>NaverWorks: HTTP POST (API)
            NaverWorks-->>SlackPlugin: Response
        end
    end
```

### Activity Diagram

![Activity Diagram](activity_diagram.png)
```mermaid
flowchart TD
    Start([Start]) --> EventCheck{Event Type?}
    
    EventCheck -->|Thread Count| TC[Check Thread Count]
    EventCheck -->|XLog| XL[Process XLog Event]
    EventCheck -->|Object| OBJ[Process Object State]
    EventCheck -->|Counter| CNT[Process Counter Data]
    
    TC --> TCCheck{Exceeds<br/>Threshold?}
    TCCheck -->|Yes| Alert
    TCCheck -->|No| End
    
    XL --> XLCheck{Error or<br/>Elapsed Time<br/>Exceeded?}
    XLCheck -->|Yes| Alert
    XLCheck -->|No| End
    
    OBJ --> OBJCheck{State<br/>Changed?}
    OBJCheck -->|Yes| Alert
    OBJCheck -->|No| End
    
    CNT --> CNTCheck{GC Time<br/>Exceeded?}
    CNTCheck -->|Yes| Alert
    CNTCheck -->|No| End
    
    Alert[Create Alert] --> History[Check History]
    History --> IntervalCheck{Resend<br/>Interval<br/>Exceeded?}
    IntervalCheck -->|Yes| Send[Send Message]
    IntervalCheck -->|No| End
    
    Send --> ParallelSend{Parallel Send}
    ParallelSend -->|Slack| Slack[Send to Slack]
    ParallelSend -->|NaverWorks| Works[Send to NaverWorks]
    
    Slack --> End([End])
    Works --> End
```

## System Architecture

### Core Components

#### AbstractAlertHandler
- Abstract base class for alert handling
- Implements common alert logic
- Defines alert handling patterns for subclasses

#### AlertContext
- Encapsulates alert-related data
- Contains all context information needed for alert processing

### Alert Handlers

#### ThreadCountAlertHandler
- Handles thread count related alerts
- Threshold: Uses FATAL level when historyCount > 1
- Monitors thread count increase trends

#### ElapsedTimeAlertHandler
- Handles response time related alerts
- Level determination logic:
  - Determines FATAL/WARN/INFO level based on historyAvg
  - Skips INFO level alerts
- Monitors performance degradation situations

#### ErrorAlertHandler
- Handles error related alerts
- Always uses ERROR level
- Uses error message as alert title
- Includes detailed error information

#### GCTimeAlertHandler
- Handles GC time related alerts
- Threshold: Uses FATAL level when historyCount > 0
- Includes interval logging
- Monitors GC performance issues

## Configuration

### Basic Setup
```properties
# Slack webhook URL configuration
ext_plugin_slack_webhook_url=https://hooks.slack.com/services/...

# Alert channel configuration
ext_plugin_slack_channel=#monitoring

# Alert username configuration
ext_plugin_slack_botName=Scouter
```

### Monitoring Group Configuration
```properties
# Channel configuration per monitoring group
ext_plugin_slack_channel_group_a=#group-a-monitoring
ext_plugin_slack_channel_group_b=#group-b-monitoring
```

## Alert Level Characteristics

### FATAL
- Critical issues requiring immediate action
- Immediate alert dispatch
- Includes detailed information

### WARN
- Situations requiring attention
- Warns of potential issues
- Includes basic information

### INFO
- Reference information
- Alerts sent only when necessary
- Includes simple information

## History Management
- Maintains history for each alert type
- Used for problem pattern analysis
- Supports alert deduplication

## Usage Examples

### Thread Count Alert
```
[FATAL] Thread Count Alert
- Instance: OrderService
- Current Count: 100
- Threshold: 80
- History Count: 3
```

### Response Time Alert
```
[WARN] Elapsed Time Alert
- Instance: PaymentService
- Current Time: 5000ms
- Average Time: 4500ms
- Threshold: 3000ms
```

### Error Alert
```
[ERROR] Exception Alert
- Instance: UserService
- Error: NullPointerException
- Location: UserController.java:150
- Stack Trace: ...
```

### GC Time Alert
```
[FATAL] GC Time Alert
- Instance: AuthService
- Current Time: 500ms
- Interval: 60s
- History Count: 2
```

## Important Notes
1. Keep Slack webhook URL secure and prevent external exposure
2. Consider service characteristics when setting alert thresholds
3. Verify channel names when configuring alert channels
