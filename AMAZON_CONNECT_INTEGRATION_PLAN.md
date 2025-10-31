# Amazon Connect Integration Plan
## Nova S2S VoIP Gateway with Amazon Connect External Voice Transfer

---

## Executive Summary

This plan outlines the integration of the Nova S2S VoIP Gateway with Amazon Connect using **External Voice Transfer** feature. The integration will allow Amazon Connect to transfer calls to the Nova Sonic AI gateway via SIP, collect conversation insights, and then resume the call back to Amazon Connect with updated contact attributes.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CALL FLOW ARCHITECTURE                           │
└─────────────────────────────────────────────────────────────────────────┘

1. Inbound Call
   ↓
   [Customer] → [PSTN/Phone Network]
   ↓
2. Amazon Connect Receives Call
   ↓
   [Amazon Connect Instance]
   ├─ Contact Flow Execution
   ├─ IVR Menu (Optional)
   └─ Decision: Transfer to Nova Gateway
   ↓
3. External Voice Transfer (SIP)
   ↓
   [Amazon Connect External Voice Connector]
   ├─ SIP INVITE → [Nova VoIP Gateway @ 35.155.30.129:5060]
   ├─ Custom SIP Headers (Contact ID, Attributes)
   └─ RTP Media Stream → [UDP 10000-20000]
   ↓
4. Nova Sonic Conversation
   ↓
   [Nova VoIP Gateway]
   ├─ RTP Audio → Nova Sonic S2S (Bedrock)
   ├─ AI Conversation Processing
   ├─ Extract Insights (intent, sentiment, entities)
   └─ Prepare Transfer Back to Connect
   ↓
5. UpdateContactAttributes API Call
   ↓
   [Nova Gateway] → [Amazon Connect API]
   ├─ UpdateContactAttributes(ContactId, Attributes)
   │   └─ NovaConversationSummary: "Customer inquired about..."
   │   └─ NovaIntent: "billing_question"
   │   └─ NovaSentiment: "frustrated"
   │   └─ NovaEntities: "account_number=12345"
   │   └─ NovaTranscript: "Full conversation text"
   └─ Response: HTTP 200 OK
   ↓
6. Resume Call to Amazon Connect
   ↓
   [Nova Gateway] → SIP BYE/REFER → [Amazon Connect]
   ↓
7. Continue Contact Flow
   ↓
   [Amazon Connect Contact Flow]
   ├─ Access Nova Attributes ($.Attributes.NovaIntent)
   ├─ Route Based on Intent/Sentiment
   ├─ Connect to Agent with Context
   └─ Agent sees conversation summary in CCP
```

---

## Integration Components

### 1. Amazon Connect Configuration

#### A. External Voice Transfer Connector
- **Type**: SIP Trunk
- **Name**: `nova-sonic-gateway`
- **Destination**: `35.155.30.129:5060` (Nova Gateway Elastic IP)
- **Transport**: UDP
- **Authentication**: None (IP-based allowlist)
- **Pricing**: $3,100/month + $0.005/min per transfer

#### B. Contact Flow Setup
Create a new contact flow: `Transfer-to-Nova-Gateway`

**Flow Blocks:**
1. **Set Contact Attributes** (before transfer)
   - `OriginalContactId`: `$.ContactId`
   - `TransferTimestamp`: `$.CurrentTimestamp`
   - `CustomerPhoneNumber`: `$.CustomerEndpoint.Address`
   - `ConnectInstanceARN`: `$.InstanceARN`

2. **Invoke AWS Lambda** (optional - for custom logic)
   - Prepare any additional context
   - Log transfer event

3. **Transfer to External** → Configure:
   - **Transfer Type**: External Voice Transfer
   - **Connector**: `nova-sonic-gateway`
   - **Phone Number**: `sip:gateway@35.155.30.129:5060`
   - **Resume Flow After Disconnect**: ✅ **ENABLED**
   - **Timeout**: 5 minutes (max Nova conversation time)
   - **Pass Contact Attributes**: ✅ **ENABLED**

4. **Check Contact Attributes** (after Nova returns call)
   - Check if `NovaIntent` attribute exists
   - Branch based on intent/sentiment

5. **Route to Agent/Queue**
   - Use Nova insights to route intelligently
   - Set agent whisper with conversation summary

#### C. Custom SIP Headers (Contact Attributes)
Amazon Connect will pass these in SIP INVITE:
```
X-Connect-ContactId: 12345678-abcd-1234-abcd-1234567890ab
X-Connect-InitialContactId: 87654321-dcba-4321-dcba-0987654321ba
X-Connect-CustomerPhoneNumber: +14155551234
X-Connect-InstanceARN: arn:aws:connect:us-west-2:123456789012:instance/abc123
```

#### D. User-to-User Information (UUI) Header

Amazon Connect can pass additional custom data via the **User-to-User** SIP header as **hex-encoded JSON**.

**Example SIP INVITE:**
```
INVITE sip:gateway@35.155.30.129:5060 SIP/2.0
...
User-to-User: 7B22636F6E746163744964223A223132333435222C22637573746F6D65724E616D65223A224A6F686E20446F65222C227072696F72697479223A2268696768227D
...
```

**Decoded UUI (hex → JSON):**
```json
{
  "contactId": "12345",
  "customerName": "John Doe",
  "priority": "high",
  "accountNumber": "987654321",
  "previousIntent": "billing_inquiry",
  "customerSegment": "premium"
}
```

**Use Cases:**
- Pass Connect contact attributes to Nova Gateway
- Customer context (name, account, segment, VIP status)
- Previous conversation history
- Routing metadata (priority, skill required)
- Custom business logic parameters

**UUI Data Flow:**
1. Amazon Connect contact flow sets attributes
2. Transfer block encodes attributes as hex-JSON in UUI header
3. Nova Gateway receives SIP INVITE with UUI
4. `UUIParser` decodes hex → JSON → Map
5. Data merged into conversation context
6. Nova can use this data for personalization
7. Data included in UpdateContactAttributes response

### 2. Nova VoIP Gateway Changes

#### A. UUI Parser Implementation
**New Java Class**: `UUIParser.java`

```java
public class UUIParser {
    /**
     * Parse hex-encoded JSON from UUI header
     * Example: 7B226B6579223A2276616C7565227D → {"key":"value"}
     */
    public static Map<String, String> parseUUI(String hexEncodedJson) {
        // 1. Decode hex to string
        String jsonString = hexToString(hexEncodedJson);

        // 2. Parse JSON to Map
        JSONObject jsonObject = new JSONObject(jsonString);

        // 3. Convert to Map
        Map<String, String> result = new HashMap<>();
        for (String key : jsonObject.keySet()) {
            result.put(key, jsonObject.get(key).toString());
        }
        return result;
    }

    /**
     * Convert hex string to UTF-8 string
     */
    public static String hexToString(String hexString) {
        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            int value = Integer.parseInt(hexString.substring(index, index + 2), 16);
            bytes[i] = (byte) value;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Extract UUI and merge with existing context
     * Adds "UUI_" prefix to avoid key conflicts
     */
    public static Map<String, String> extractAndMergeUUI(
        String uuiHeader,
        Map<String, String> existingContext
    ) {
        Map<String, String> merged = new HashMap<>(existingContext);
        Map<String, String> uuiData = parseUUI(uuiHeader);

        // Merge with "UUI_" prefix
        for (Map.Entry<String, String> entry : uuiData.entrySet()) {
            merged.put("UUI_" + entry.getKey(), entry.getValue());
        }
        return merged;
    }
}
```

**Features:**
- Decodes hex-encoded JSON from UUI header
- Handles UTF-8 characters (international names, etc.)
- Error handling for invalid hex/JSON
- Merges UUI data with existing context
- Comprehensive unit tests (see `UUIParserTest.java`)

#### B. SIP Header Processing
**New Java Class**: `ConnectIntegration.java`

```java
public class ConnectIntegration {
    private String contactId;
    private String initialContactId;
    private String customerPhoneNumber;
    private String instanceArn;
    private Map<String, String> uuiData;

    // Parse from SIP INVITE headers
    public static ConnectIntegration fromSipMessage(SipMessage msg) {
        ConnectIntegration integration = new ConnectIntegration();

        // Extract X-Connect-* headers
        integration.contactId = msg.getHeader("X-Connect-ContactId");
        integration.initialContactId = msg.getHeader("X-Connect-InitialContactId");
        integration.customerPhoneNumber = msg.getHeader("X-Connect-CustomerPhoneNumber");
        integration.instanceArn = msg.getHeader("X-Connect-InstanceARN");

        // Parse UUI header
        String uuiHeader = msg.getHeader("User-to-User");
        if (uuiHeader != null) {
            integration.uuiData = UUIParser.parseUUI(uuiHeader);
        }

        return integration;
    }

    public Map<String, String> getAllContext() {
        Map<String, String> context = new HashMap<>();
        context.put("ContactId", contactId);
        context.put("InitialContactId", initialContactId);
        context.put("CustomerPhoneNumber", customerPhoneNumber);

        // Add UUI data with prefix
        if (uuiData != null) {
            for (Map.Entry<String, String> entry : uuiData.entrySet()) {
                context.put("UUI_" + entry.getKey(), entry.getValue());
            }
        }

        return context;
    }
}
```

#### B. Conversation Tracking
**New Java Class**: `NovaConversationTracker.java`

```java
public class NovaConversationTracker {
    private StringBuilder transcript;
    private List<String> detectedIntents;
    private String overallSentiment;
    private Map<String, String> extractedEntities;

    // Track conversation in real-time
    public void processNovaResponse(NovaS2SResponse response) {
        // Build transcript
        // Detect intents from conversation
        // Track sentiment
        // Extract entities (account numbers, dates, etc.)
    }

    public Map<String, String> getContactAttributes() {
        // Return attributes for UpdateContactAttributes API
    }
}
```

#### C. Amazon Connect API Client
**New Java Class**: `AmazonConnectClient.java`

```java
public class AmazonConnectClient {
    private ConnectClient connectClient;

    public void updateContactAttributes(
        String instanceId,
        String contactId,
        Map<String, String> attributes
    ) {
        UpdateContactAttributesRequest request =
            UpdateContactAttributesRequest.builder()
                .instanceId(instanceId)
                .initialContactId(contactId)
                .attributes(attributes)
                .build();

        connectClient.updateContactAttributes(request);
    }
}
```

#### D. Call Flow Modifications
**Update**: `NovaSonicVoipGateway.java`

```java
@Override
public void onUaIncomingCall(UserAgent ua, NameAddress callee,
                             NameAddress caller, MediaDesc[] media_descs) {
    // 1. Extract Connect headers from SIP INVITE
    ConnectIntegration connectInfo =
        ConnectIntegration.fromSipMessage(sipMessage);

    // 2. Check if accepted number
    if (!isAcceptedNumber(callee)) {
        ua.hangup();
        return;
    }

    // 3. Create conversation tracker
    NovaConversationTracker tracker = new NovaConversationTracker();

    // 4. Accept call with Nova media agent
    MediaAgent mediaAgent = new MediaAgent(
        mediaConfig.getMediaDescs(),
        new ConnectAwareStreamerFactory(tracker)
    );
    ua.accept(mediaAgent);

    // 5. When Nova conversation ends...
    mediaAgent.addListener(new MediaAgentListener() {
        @Override
        public void onMediaSessionEnded() {
            // Update Connect attributes
            if (connectInfo.hasContactId()) {
                Map<String, String> attrs = tracker.getContactAttributes();
                connectClient.updateContactAttributes(
                    connectInfo.getInstanceId(),
                    connectInfo.getContactId(),
                    attrs
                );
            }
            // Disconnect (triggers resume in Connect)
            ua.hangup();
        }
    });
}
```

### 3. Contact Attributes Schema

Attributes sent from Nova Gateway → Amazon Connect:

```json
{
  "NovaConversationSummary": "Customer inquired about billing charges for last month. Expressed frustration about unexpected fees.",
  "NovaIntent": "billing_question",
  "NovaSubIntent": "dispute_charges",
  "NovaSentiment": "frustrated",
  "NovaSentimentScore": "0.72",
  "NovaEntities": "{\"account_number\":\"12345\",\"amount\":\"$45.99\",\"date\":\"last_month\"}",
  "NovaTranscript": "Full conversation transcript here...",
  "NovaConversationDuration": "142",
  "NovaTimestamp": "2025-10-30T21:45:00Z",
  "NovaRecommendedAction": "escalate_to_billing_specialist",
  "NovaCustomerVerified": "true"
}
```

---

## Implementation Phases

### Phase 1: Foundation (Week 1)
**Objective**: Basic SIP integration between Connect and Nova Gateway

- [ ] Set up Amazon Connect instance in us-west-2
- [ ] Create External Voice Transfer connector pointing to gateway
- [ ] Create basic contact flow with external transfer
- [ ] Update Nova Gateway to log SIP headers from Connect
- [ ] Test basic call transfer (Connect → Nova → hangup)
- [ ] Verify security group allows Connect traffic

**Deliverables**:
- Working SIP connection
- Call successfully reaches Nova Gateway
- Basic logging of Connect metadata

### Phase 2: Conversation Tracking (Week 2)
**Objective**: Capture and analyze Nova conversations

- [ ] Implement `NovaConversationTracker` class
- [ ] Integrate tracker with Nova response handlers
- [ ] Build transcript from Nova responses
- [ ] Implement basic intent detection
- [ ] Implement sentiment analysis
- [ ] Test conversation data collection

**Deliverables**:
- Conversation transcripts captured
- Basic intent/sentiment extracted
- Data structure for contact attributes

### Phase 3: Connect API Integration (Week 3)
**Objective**: Send data back to Amazon Connect

- [ ] Add AWS Connect SDK dependency to pom.xml
- [ ] Implement `AmazonConnectClient` class
- [ ] Add IAM permissions for Connect API to EC2 role
- [ ] Implement `updateContactAttributes()` call
- [ ] Handle API errors and retries
- [ ] Test attribute updates in Connect console

**Deliverables**:
- Working UpdateContactAttributes API calls
- Attributes visible in Connect contact record
- Error handling for API failures

### Phase 4: Resume Flow Implementation (Week 4)
**Objective**: Return calls to Connect after Nova conversation

- [ ] Configure "Resume flow after disconnect" in transfer block
- [ ] Create post-Nova contact flow branch
- [ ] Implement attribute-based routing logic
- [ ] Add agent whisper with conversation summary
- [ ] Test full round-trip (Connect → Nova → Connect)

**Deliverables**:
- Calls resume to Connect after Nova
- Routing based on Nova insights
- Agent receives conversation context

### Phase 5: Advanced Features (Week 5-6)
**Objective**: Production-ready features

- [ ] Implement customer verification via Nova
- [ ] Add custom prompts based on transfer reason
- [ ] Implement conversation timeout handling
- [ ] Add CloudWatch metrics for monitoring
- [ ] Create dashboard for Nova → Connect transfers
- [ ] Implement fallback logic for API failures
- [ ] Load testing and optimization

**Deliverables**:
- Production-ready integration
- Monitoring and alerting
- Documentation and runbooks

---

## Configuration Files

### `connect-integration.properties`
New configuration file for Connect integration:

```properties
# Amazon Connect Integration Configuration

# Enable/disable Connect integration
connect.integration.enabled=true

# Amazon Connect Instance
connect.instance.id=12345678-abcd-1234-abcd-1234567890ab
connect.instance.region=us-west-2

# API Configuration
connect.api.endpoint=https://connect.us-west-2.amazonaws.com
connect.api.max_retries=3
connect.api.timeout_ms=5000

# Conversation Settings
connect.conversation.max_duration_seconds=300
connect.conversation.transcript.enabled=true
connect.conversation.intent_detection.enabled=true
connect.conversation.sentiment_analysis.enabled=true

# SIP Header Mapping
connect.sip.header.contact_id=X-Connect-ContactId
connect.sip.header.initial_contact_id=X-Connect-InitialContactId
connect.sip.header.customer_phone=X-Connect-CustomerPhoneNumber
connect.sip.header.instance_arn=X-Connect-InstanceARN

# Attribute Keys (must match Connect contact flow)
connect.attribute.summary=NovaConversationSummary
connect.attribute.intent=NovaIntent
connect.attribute.sentiment=NovaSentiment
connect.attribute.transcript=NovaTranscript
connect.attribute.duration=NovaConversationDuration
```

---

## AWS IAM Permissions Required

Add to EC2 instance IAM role:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "connect:UpdateContactAttributes",
        "connect:DescribeContact",
        "connect:GetContactAttributes"
      ],
      "Resource": [
        "arn:aws:connect:us-west-2:*:instance/*/contact/*"
      ]
    }
  ]
}
```

---

## Security Considerations

### 1. Network Security
- [ ] Update security group to allow traffic from Connect IP ranges
- [ ] Verify SIP traffic is only from Amazon Connect
- [ ] Enable SIP-level authentication if supported by Connect

### 2. Data Privacy
- [ ] Ensure PII is handled per compliance requirements
- [ ] Implement conversation data retention policies
- [ ] Encrypt transcripts in transit and at rest
- [ ] Consider PCI compliance for payment conversations

### 3. API Security
- [ ] Use EC2 instance profile (not API keys)
- [ ] Implement least-privilege IAM policies
- [ ] Add CloudWatch alarms for API failures
- [ ] Rate limit API calls to prevent quota exhaustion

---

## Testing Strategy

### Unit Tests
- SIP header parsing
- Conversation tracking logic
- Attribute mapping
- API client methods

### Integration Tests
- Connect → Gateway SIP transfer
- UpdateContactAttributes API calls
- Resume flow after disconnect
- Error scenarios (timeout, API failure)

### End-to-End Tests
1. **Happy Path**: Connect → Nova → Connect → Agent
2. **Timeout**: Nova conversation exceeds 5 minutes
3. **API Failure**: UpdateContactAttributes fails
4. **Customer Hangup**: Customer disconnects during Nova
5. **Invalid Contact**: SIP headers missing/invalid

---

## CloudWatch Logging Implementation

### Maven Dependencies

Add to `pom.xml`:

```xml
<!-- CloudWatch Logs Appender -->
<dependency>
    <groupId>io.github.dibog</groupId>
    <artifactId>cloudwatch-logback-appender</artifactId>
    <version>1.0.6</version>
</dependency>

<!-- AWS SDK for CloudWatch Logs (if not already included) -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cloudwatchlogs</artifactId>
    <version>2.31.19</version>
</dependency>
```

### Logback Configuration

Create/update `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console Appender for local development -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- CloudWatch Appender for Application Logs -->
    <appender name="CLOUDWATCH_APP" class="io.github.dibog.AwsLogAppender">
        <logGroupName>/aws/voip-gateway/application</logGroupName>
        <logStreamName>${INSTANCE_ID:-default}</logStreamName>
        <createLogGroup>true</createLogGroup>
        <layout class="ch.qos.logback.classic.PatternLayout">
            <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </layout>
    </appender>

    <!-- CloudWatch Appender for Conversations (Structured JSON) -->
    <appender name="CLOUDWATCH_CONVERSATIONS" class="io.github.dibog.AwsLogAppender">
        <logGroupName>/aws/voip-gateway/conversations</logGroupName>
        <logStreamName>${INSTANCE_ID:-default}</logStreamName>
        <createLogGroup>true</createLogGroup>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <!-- JSON structured logging -->
        </encoder>
    </appender>

    <!-- CloudWatch Appender for SIP Messages -->
    <appender name="CLOUDWATCH_SIP" class="io.github.dibog.AwsLogAppender">
        <logGroupName>/aws/voip-gateway/sip</logGroupName>
        <logStreamName>${INSTANCE_ID:-default}</logStreamName>
        <createLogGroup>true</createLogGroup>
        <layout class="ch.qos.logback.classic.PatternLayout">
            <pattern>%d{ISO8601} %msg%n</pattern>
        </layout>
    </appender>

    <!-- CloudWatch Appender for Connect API -->
    <appender name="CLOUDWATCH_CONNECT_API" class="io.github.dibog.AwsLogAppender">
        <logGroupName>/aws/voip-gateway/connect-api</logGroupName>
        <logStreamName>${INSTANCE_ID:-default}</logStreamName>
        <createLogGroup>true</createLogGroup>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>

    <!-- Async wrappers for performance -->
    <appender name="ASYNC_CLOUDWATCH_APP" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="CLOUDWATCH_APP"/>
        <queueSize>500</queueSize>
        <discardingThreshold>0</discardingThreshold>
    </appender>

    <appender name="ASYNC_CLOUDWATCH_CONVERSATIONS" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="CLOUDWATCH_CONVERSATIONS"/>
        <queueSize>500</queueSize>
    </appender>

    <!-- Root logger -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_CLOUDWATCH_APP"/>
    </root>

    <!-- Package-specific loggers -->
    <logger name="com.example.s2s.voipgateway.nova" level="DEBUG">
        <appender-ref ref="ASYNC_CLOUDWATCH_CONVERSATIONS"/>
    </logger>

    <logger name="org.mjsip.sip" level="DEBUG">
        <appender-ref ref="CLOUDWATCH_SIP"/>
    </logger>

    <logger name="com.example.s2s.voipgateway.AmazonConnectClient" level="DEBUG">
        <appender-ref ref="CLOUDWATCH_CONNECT_API"/>
    </logger>
</configuration>
```

### Structured Logging for Conversations

**New Java Class**: `ConversationLogger.java`

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.logstash.logback.argument.StructuredArguments;

public class ConversationLogger {
    private static final Logger conversationLog =
        LoggerFactory.getLogger("com.example.s2s.voipgateway.nova");

    public static void logNovaEvent(
        String contactId,
        String eventType,
        String content,
        Map<String, Object> metadata
    ) {
        conversationLog.info("Nova event",
            StructuredArguments.kv("contactId", contactId),
            StructuredArguments.kv("eventType", eventType),
            StructuredArguments.kv("content", content),
            StructuredArguments.kv("timestamp", Instant.now()),
            StructuredArguments.kv("metadata", metadata)
        );
    }

    public static void logBargeIn(String contactId, String partialTranscript) {
        conversationLog.info("Barge-in detected",
            StructuredArguments.kv("contactId", contactId),
            StructuredArguments.kv("eventType", "BARGE_IN"),
            StructuredArguments.kv("partialTranscript", partialTranscript),
            StructuredArguments.kv("timestamp", Instant.now())
        );
    }
}
```

### IAM Permissions for CloudWatch Logs

Add to EC2 instance IAM role:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ],
      "Resource": [
        "arn:aws:logs:us-west-2:*:log-group:/aws/voip-gateway/*"
      ]
    }
  ]
}
```

---

## Nova Sonic Event Handling

### Event Types Overview

Amazon Nova Sonic uses an event-driven architecture with the following event types:

#### **Input Stream Events** (Gateway → Nova)
1. **System Prompt** - Set conversation context
2. **Audio Input** - Streaming audio chunks from caller
3. **Tool Result** - Response from tool execution

#### **Output Stream Events** (Nova → Gateway)

**1. Content Events:**
- `contentStart` - Marks beginning of model response
- `contentBlockStart` - Opens each response part (transcript, tool, audio)
- `contentEnd` - Indicates end of response block with `stopReason`
- `responseEnd` - Finalizes entire response cycle

**2. Speech Recognition (ASR) Events:**
- `textOutput` - Real-time speech-to-text transcripts
- Provides what the customer said

**3. Audio Output Events:**
- `audioOutput` - Base64-encoded TTS audio chunks
- Streamed back to caller via RTP

**4. Tool Use Events:**
- `toolUse` - When model needs to call a function/tool
- Gateway must handle and send result back

**5. Stop Reasons:**
- `END_TURN` - Natural completion of response
- `PARTIAL_TURN` - Intermediate stop (more to come)
- `INTERRUPTED` - **Barge-in detected** - customer spoke while Nova was speaking

### Barge-in Detection & Handling

**What is Barge-in?**
- Customer interrupts Nova while it's speaking
- Model immediately stops generating speech
- Switches to listening mode
- Sends `contentEnd` event with `stopReason: "INTERRUPTED"`

**Why It Matters:**
- Some audio may have been delivered but not yet played
- Need to clear audio queue and stop playback immediately
- Provides natural, responsive conversation experience
- Must maintain conversational context through interruption

**Implementation:**

**New Java Class**: `NovaEventHandler.java`

```java
public class NovaEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(NovaEventHandler.class);

    private final AudioQueue audioQueue;
    private final ConversationTracker tracker;
    private boolean isNovaSpea king = false;
    private StringBuilder currentResponse = new StringBuilder();

    public void handleNovaEvent(NovaEvent event) {
        switch (event.getType()) {
            case CONTENT_START:
                handleContentStart(event);
                break;

            case CONTENT_BLOCK_START:
                handleContentBlockStart(event);
                break;

            case TEXT_OUTPUT:
                handleTextOutput(event);
                break;

            case AUDIO_OUTPUT:
                handleAudioOutput(event);
                break;

            case CONTENT_END:
                handleContentEnd(event);
                break;

            case TOOL_USE:
                handleToolUse(event);
                break;

            case RESPONSE_END:
                handleResponseEnd(event);
                break;
        }
    }

    private void handleContentStart(NovaEvent event) {
        LOG.info("Nova started responding");
        isNovaSpeaking = true;
        currentResponse.setLength(0);

        ConversationLogger.logNovaEvent(
            tracker.getContactId(),
            "CONTENT_START",
            "",
            Map.of("turnId", event.getTurnId())
        );
    }

    private void handleAudioOutput(NovaEvent event) {
        // Queue audio for RTP transmission
        byte[] audioData = event.getAudioData();
        audioQueue.enqueue(audioData);

        LOG.debug("Queued {} bytes of audio", audioData.length);
    }

    private void handleContentEnd(NovaEvent event) {
        String stopReason = event.getStopReason();
        isNovaSpeaking = false;

        LOG.info("Nova stopped speaking. Reason: {}", stopReason);

        if ("INTERRUPTED".equals(stopReason)) {
            // BARGE-IN DETECTED!
            handleBargeIn(event);
        } else if ("END_TURN".equals(stopReason)) {
            // Natural end of response
            tracker.recordNovaResponse(currentResponse.toString());
        }

        ConversationLogger.logNovaEvent(
            tracker.getContactId(),
            "CONTENT_END",
            currentResponse.toString(),
            Map.of(
                "stopReason", stopReason,
                "contentId", event.getContentId(),
                "audioBytesSent", audioQueue.getTotalBytesSent()
            )
        );
    }

    private void handleBargeIn(NovaEvent event) {
        LOG.warn("BARGE-IN: Customer interrupted Nova");

        // 1. Clear audio queue immediately
        int clearedBytes = audioQueue.clear();
        LOG.info("Cleared {} bytes from audio queue", clearedBytes);

        // 2. Stop RTP audio transmission
        rtpSender.stopCurrentPlayback();

        // 3. Log barge-in event
        ConversationLogger.logBargeIn(
            tracker.getContactId(),
            currentResponse.toString()
        );

        // 4. Track in conversation metadata
        tracker.incrementBargeInCount();
        tracker.addEvent("BARGE_IN", Map.of(
            "partialResponse", currentResponse.toString(),
            "timeElapsed", event.getTimestamp()
        ));

        // 5. Update Connect attributes (if applicable)
        tracker.addAttribute("NovaBargeInCount",
            String.valueOf(tracker.getBargeInCount()));
        tracker.addAttribute("NovaCustomerInterrupted", "true");

        // Model automatically switches to listening mode
        // No action needed - just wait for next customer speech
    }

    private void handleTextOutput(NovaEvent event) {
        // ASR transcript - what customer said
        String transcript = event.getText();
        LOG.info("Customer said: {}", transcript);

        tracker.addCustomerUtterance(transcript);

        ConversationLogger.logNovaEvent(
            tracker.getContactId(),
            "ASR_TRANSCRIPT",
            transcript,
            Map.of("isFinal", event.isFinal())
        );
    }

    private void handleToolUse(NovaEvent event) {
        // Nova wants to call a tool (e.g., check account balance)
        LOG.info("Nova requesting tool: {}", event.getToolName());

        // Execute tool and send result back
        ToolExecutor.execute(event.getToolName(), event.getToolInput())
            .thenAccept(result -> {
                novaClient.sendToolResult(event.getToolUseId(), result);
            });
    }
}
```

### Enhanced Conversation Tracking with Events

**Update**: `NovaConversationTracker.java`

```java
public class NovaConversationTracker {
    private int bargeInCount = 0;
    private List<ConversationEvent> events = new ArrayList<>();
    private Map<String, String> connectAttributes = new HashMap<>();

    public void incrementBargeInCount() {
        this.bargeInCount++;
    }

    public int getBargeInCount() {
        return bargeInCount;
    }

    public void addEvent(String eventType, Map<String, Object> data) {
        events.add(new ConversationEvent(
            Instant.now(),
            eventType,
            data
        ));
    }

    @Override
    public Map<String, String> getContactAttributes() {
        Map<String, String> attrs = new HashMap<>();

        attrs.put("NovaConversationSummary", generateSummary());
        attrs.put("NovaIntent", detectIntent());
        attrs.put("NovaSentiment", analyzeSentiment());
        attrs.put("NovaTranscript", getFullTranscript());
        attrs.put("NovaBargeInCount", String.valueOf(bargeInCount));
        attrs.put("NovaConversationEvents", serializeEvents());

        // Barge-in indicates engagement/urgency
        if (bargeInCount > 2) {
            attrs.put("NovaCustomerEngagement", "high");
            attrs.put("NovaRecommendedPriority", "urgent");
        }

        return attrs;
    }

    private String serializeEvents() {
        // JSON array of key events
        return JsonSerializer.toJson(events);
    }
}
```

### Contact Attributes with Event Data

Enhanced attributes sent to Amazon Connect:

```json
{
  "NovaConversationSummary": "Customer inquired about billing...",
  "NovaIntent": "billing_question",
  "NovaSentiment": "frustrated",
  "NovaTranscript": "Full conversation...",

  "NovaBargeInCount": "3",
  "NovaCustomerEngagement": "high",
  "NovaCustomerInterrupted": "true",
  "NovaRecommendedPriority": "urgent",

  "NovaConversationEvents": "[{\"type\":\"BARGE_IN\",\"timestamp\":\"...\"},{\"type\":\"TOOL_USE\",\"tool\":\"check_balance\"}]",

  "NovaStopReasons": "INTERRUPTED,END_TURN,END_TURN",
  "NovaTotalTurns": "4",
  "NovaToolsUsed": "check_balance,get_account_info"
}
```

---

## Monitoring & Observability

### CloudWatch Metrics
- `ConnectTransfers.Count` - Total transfers from Connect
- `NovaConversations.Duration` - Average conversation length
- `ConnectAPI.UpdateAttributes.Success` - Successful API calls
- `ConnectAPI.UpdateAttributes.Failure` - Failed API calls
- `ConnectResumeFlow.Count` - Calls returned to Connect

### CloudWatch Logs
**Log Groups:**
- `/aws/voip-gateway/application` - Application logs (INFO, WARN, ERROR)
- `/aws/voip-gateway/conversations` - Nova conversation transcripts
- `/aws/voip-gateway/sip` - SIP signaling messages
- `/aws/voip-gateway/connect-api` - Connect API requests/responses

**Log Streams:**
- By instance ID for multi-instance deployments
- Structured JSON logging for easy parsing

**Log Content:**
- SIP message logs (Connect headers, INVITE details)
- Nova conversation events (ASR, TTS, barge-in, tool use)
- Nova Sonic event types (contentStart, contentEnd, stopReason)
- Connect API request/response logs
- Error logs with stack traces and context
- Performance metrics (latency, duration)

### Alarms
- High API failure rate (>5%)
- Long conversation duration (>5 min)
- No Connect transfers in last hour (potential outage)
- High barge-in rate (>30% of conversations)
- CloudWatch Logs write failures

---

## Cost Estimation

### Amazon Connect External Voice Transfer
- **Connector**: $3,100/month (flat fee)
- **Per-minute**: $0.005/min per transfer

**Example**: 10,000 calls/month, 2 min average
- Connector: $3,100
- Usage: 10,000 × 2 min × $0.005 = $100
- **Total**: $3,200/month

### Amazon Bedrock (Nova Sonic)
- Existing usage - no additional cost

### EC2 Instance
- Existing t3.micro - no additional cost

### Data Transfer
- Minimal - voice already on AWS network

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Connect API rate limits | API calls fail | Implement retry with exponential backoff |
| Customer hangs up during Nova | Attributes not sent | Send attributes on call disconnect event |
| SIP header parsing fails | No Connect context | Gracefully degrade, continue without context |
| Nova conversation timeout | Call stuck | Implement max duration, force disconnect |
| Network issues Connect↔Gateway | Transfer fails | Monitor success rate, set up alerts |
| Connect pricing changes | Budget impact | Monitor costs, consider per-use vs flat rate |

---

## Success Metrics

### Technical
- 95%+ successful transfers Connect → Nova
- 95%+ successful UpdateContactAttributes calls
- <100ms latency for attribute updates
- <1% call drop rate during transfer

### Business
- Reduced average handle time (AHT) by 20%
- Increased agent satisfaction (context available)
- Improved customer satisfaction scores
- Reduced repeat calls for same issue

---

## Next Steps

1. **Review & Approve Plan** ✅
2. **Provision Amazon Connect Instance**
3. **Begin Phase 1 Implementation**
4. **Set up development/test environment**
5. **Create implementation backlog in project tracker**

---

## Questions for Stakeholder Review

1. **Contact Flow Design**: What IVR logic should precede transfer to Nova?
2. **Intent Categories**: What specific intents should Nova detect?
3. **Routing Logic**: How should calls be routed based on Nova insights?
4. **Compliance**: Any specific PCI/HIPAA requirements for transcripts?
5. **Pricing**: Approve $3,100/month external transfer connector cost?
6. **Timeline**: Target production launch date?
7. **Testing**: Pilot with specific call types or full rollout?

---

## References

- [Amazon Connect External Voice Transfer Documentation](https://docs.aws.amazon.com/connect/latest/adminguide/external-voice-transfer.html)
- [UpdateContactAttributes API Reference](https://docs.aws.amazon.com/connect/latest/APIReference/API_UpdateContactAttributes.html)
- [Transfer to Phone Number Block](https://docs.aws.amazon.com/connect/latest/adminguide/transfer-to-phone-number.html)
- [Resume Flow After Disconnect](https://docs.aws.amazon.com/connect/latest/adminguide/contact-flow-resume.html)
