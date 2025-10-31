package com.example.s2s.voipgateway.connect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages conversation attributes for Amazon Connect integration.
 * Tracks conversation metadata, Nova events, barge-ins, and tool usage.
 */
public class ConnectAttributeManager {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectAttributeManager.class);

    private final ConnectIntegration connectIntegration;
    private final Map<String, String> attributes;
    private final AtomicInteger bargeInCount;
    private final StringBuilder conversationTranscript;

    private Instant conversationStartTime;
    private Instant conversationEndTime;
    private String intent;
    private String sentiment;
    private int toolInvocationCount;

    public ConnectAttributeManager(ConnectIntegration connectIntegration) {
        this.connectIntegration = connectIntegration;
        this.attributes = new HashMap<>();
        this.bargeInCount = new AtomicInteger(0);
        this.conversationTranscript = new StringBuilder();
        this.conversationStartTime = Instant.now();
        this.toolInvocationCount = 0;

        // Initialize with Connect metadata
        if (connectIntegration != null && connectIntegration.isConnectCall()) {
            attributes.putAll(connectIntegration.getAllAttributes());
        }

        LOG.info("ConnectAttributeManager initialized for {}",
                connectIntegration != null ? connectIntegration.getContactId() : "non-Connect call");
    }

    /**
     * Record when Nova starts speaking.
     */
    public void recordNovaOutputStart() {
        setAttribute("Nova_LastOutputTimestamp", Instant.now().toString());
    }

    /**
     * Record barge-in event (when user interrupts Nova).
     */
    public void recordBargeIn() {
        int count = bargeInCount.incrementAndGet();
        setAttribute("Nova_BargeInCount", String.valueOf(count));
        setAttribute("Nova_LastBargeInTimestamp", Instant.now().toString());
        LOG.info("Barge-in recorded - Total count: {}", count);
    }

    /**
     * Add user speech to transcript.
     */
    public void addUserTranscript(String text) {
        if (text != null && !text.isEmpty()) {
            conversationTranscript.append("[User]: ").append(text).append("\n");
            updateAttribute("Nova_TranscriptLength", conversationTranscript.length());
        }
    }

    /**
     * Add Nova response to transcript.
     */
    public void addNovaTranscript(String text) {
        if (text != null && !text.isEmpty()) {
            conversationTranscript.append("[Nova]: ").append(text).append("\n");
            updateAttribute("Nova_TranscriptLength", conversationTranscript.length());
        }
    }

    /**
     * Record tool invocation.
     */
    public void recordToolInvocation(String toolName) {
        toolInvocationCount++;
        setAttribute("Nova_ToolInvocationCount", String.valueOf(toolInvocationCount));
        setAttribute("Nova_LastToolInvoked", toolName);
        setAttribute("Nova_LastToolTimestamp", Instant.now().toString());
        LOG.info("Tool invocation recorded: {} (Total: {})", toolName, toolInvocationCount);
    }

    /**
     * Set conversation intent (e.g., "billing_inquiry", "technical_support").
     */
    public void setIntent(String intent) {
        this.intent = intent;
        setAttribute("Nova_Intent", intent);
        LOG.info("Intent set: {}", intent);
    }

    /**
     * Set conversation sentiment (e.g., "positive", "neutral", "frustrated").
     */
    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
        setAttribute("Nova_Sentiment", sentiment);
        LOG.info("Sentiment set: {}", sentiment);
    }

    /**
     * Add or update a custom attribute.
     */
    public void setAttribute(String key, String value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * Helper to update numeric attributes.
     */
    private void updateAttribute(String key, int value) {
        attributes.put(key, String.valueOf(value));
    }

    /**
     * Mark conversation as complete and finalize timestamps.
     */
    public void completeConversation() {
        conversationEndTime = Instant.now();
        setAttribute("Nova_ConversationEndTime", conversationEndTime.toString());

        // Calculate duration
        long durationSeconds = java.time.Duration.between(conversationStartTime, conversationEndTime).getSeconds();
        setAttribute("Nova_ConversationDurationSeconds", String.valueOf(durationSeconds));

        // Set full transcript
        setAttribute("Nova_Transcript", conversationTranscript.toString());

        LOG.info("Conversation completed - Duration: {}s, Transcript length: {} chars, Barge-ins: {}, Tools used: {}",
                durationSeconds, conversationTranscript.length(), bargeInCount.get(), toolInvocationCount);
    }

    /**
     * Get all attributes ready for UpdateContactAttributes API call.
     */
    public Map<String, String> getAttributesForUpdate() {
        Map<String, String> result = new HashMap<>(attributes);

        // Add conversation start time if not already set
        result.putIfAbsent("Nova_ConversationStartTime", conversationStartTime.toString());

        // Add summary statistics
        result.put("Nova_ConversationCompleted", "true");

        return result;
    }

    /**
     * Check if this is an Amazon Connect call.
     */
    public boolean isConnectCall() {
        return connectIntegration != null && connectIntegration.isConnectCall();
    }

    /**
     * Get the Contact ID for UpdateContactAttributes API call.
     */
    public String getContactId() {
        return connectIntegration != null ? connectIntegration.getContactId() : null;
    }

    /**
     * Get the Instance ARN for UpdateContactAttributes API call.
     */
    public String getInstanceArn() {
        return connectIntegration != null ? connectIntegration.getInstanceArn() : null;
    }

    /**
     * Get conversation summary for logging/debugging.
     */
    public String getSummary() {
        return String.format("ConnectAttributes{contactId=%s, intent=%s, sentiment=%s, bargeIns=%d, tools=%d}",
                getContactId(), intent, sentiment, bargeInCount.get(), toolInvocationCount);
    }
}
