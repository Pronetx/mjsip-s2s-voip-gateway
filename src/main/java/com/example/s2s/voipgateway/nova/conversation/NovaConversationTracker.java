package com.example.s2s.voipgateway.nova.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tracks conversation data during a Nova Sonic session.
 * Collects transcript, detects intent, and analyzes sentiment.
 */
public class NovaConversationTracker {
    private static final Logger LOG = LoggerFactory.getLogger(NovaConversationTracker.class);

    private final List<ConversationTurn> turns;
    private final Instant conversationStartTime;
    private String detectedIntent;
    private String detectedSentiment;
    private final Map<String, String> extractedEntities;

    public NovaConversationTracker() {
        this.turns = new ArrayList<>();
        this.conversationStartTime = Instant.now();
        this.extractedEntities = new HashMap<>();
        this.detectedIntent = "unknown";
        this.detectedSentiment = "neutral";
    }

    /**
     * Add user speech (from ASR) to the conversation.
     */
    public void addUserTurn(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        ConversationTurn turn = new ConversationTurn("user", text.trim(), Instant.now());
        turns.add(turn);
        LOG.debug("Added user turn: {}", text);

        // Analyze the user's message for intent and sentiment
        analyzeUserMessage(text);
    }

    /**
     * Add Nova's response to the conversation.
     */
    public void addNovaTurn(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        ConversationTurn turn = new ConversationTurn("assistant", text.trim(), Instant.now());
        turns.add(turn);
        LOG.debug("Added Nova turn: {}", text);
    }

    /**
     * Analyze user message for intent and sentiment.
     */
    private void analyzeUserMessage(String text) {
        // Update intent based on keywords
        String intent = detectIntent(text);
        if (!"unknown".equals(intent)) {
            this.detectedIntent = intent;
            LOG.info("Detected intent: {}", intent);
        }

        // Update sentiment
        String sentiment = detectSentiment(text);
        if (!"neutral".equals(sentiment)) {
            this.detectedSentiment = sentiment;
            LOG.info("Detected sentiment: {}", sentiment);
        }

        // Extract entities
        extractEntities(text);
    }

    /**
     * Simple keyword-based intent detection.
     */
    private String detectIntent(String text) {
        String lowerText = text.toLowerCase();

        // Billing/Payment intents
        if (Pattern.compile("\\b(bill|billing|payment|pay|invoice|charge|fee)\\b").matcher(lowerText).find()) {
            return "billing_inquiry";
        }

        // Account management
        if (Pattern.compile("\\b(account|password|username|login|reset|update|change)\\b").matcher(lowerText).find()) {
            return "account_management";
        }

        // Technical support
        if (Pattern.compile("\\b(problem|issue|error|not work|broken|fix|help|support)\\b").matcher(lowerText).find()) {
            return "technical_support";
        }

        // Order/shipping
        if (Pattern.compile("\\b(order|shipping|delivery|track|package|receive)\\b").matcher(lowerText).find()) {
            return "order_status";
        }

        // Cancellation
        if (Pattern.compile("\\b(cancel|refund|return|money back)\\b").matcher(lowerText).find()) {
            return "cancellation_request";
        }

        // General inquiry
        if (Pattern.compile("\\b(what|how|when|where|why|information|tell me|explain)\\b").matcher(lowerText).find()) {
            return "general_inquiry";
        }

        // Greeting
        if (Pattern.compile("\\b(hello|hi|hey|good morning|good afternoon|good evening)\\b").matcher(lowerText).find()) {
            return "greeting";
        }

        // Farewell
        if (Pattern.compile("\\b(bye|goodbye|thank|thanks|have a good)\\b").matcher(lowerText).find()) {
            return "farewell";
        }

        return "unknown";
    }

    /**
     * Simple keyword-based sentiment analysis.
     */
    private String detectSentiment(String text) {
        String lowerText = text.toLowerCase();

        // Negative sentiment indicators
        int negativeCount = countMatches(lowerText,
                "angry", "frustrated", "upset", "disappointed", "terrible", "horrible",
                "awful", "bad", "worst", "hate", "annoying", "unacceptable", "useless"
        );

        // Positive sentiment indicators
        int positiveCount = countMatches(lowerText,
                "happy", "great", "excellent", "wonderful", "amazing", "love", "perfect",
                "good", "best", "thank", "thanks", "appreciate", "helpful"
        );

        if (negativeCount > positiveCount && negativeCount >= 1) {
            return "negative";
        } else if (positiveCount > negativeCount && positiveCount >= 1) {
            return "positive";
        }

        return "neutral";
    }

    /**
     * Count how many of the given keywords appear in the text.
     */
    private int countMatches(String text, String... keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Extract entities like phone numbers, email addresses, account numbers.
     */
    private void extractEntities(String text) {
        // Extract phone numbers (simple pattern)
        Pattern phonePattern = Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b");
        java.util.regex.Matcher phoneMatcher = phonePattern.matcher(text);
        if (phoneMatcher.find()) {
            extractedEntities.put("phone_number", phoneMatcher.group());
            LOG.debug("Extracted phone number: {}", phoneMatcher.group());
        }

        // Extract email addresses
        Pattern emailPattern = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
        java.util.regex.Matcher emailMatcher = emailPattern.matcher(text);
        if (emailMatcher.find()) {
            extractedEntities.put("email", emailMatcher.group());
            LOG.debug("Extracted email: {}", emailMatcher.group());
        }

        // Extract account numbers (pattern: 6-12 digits)
        Pattern accountPattern = Pattern.compile("\\baccount\\s*(?:number|#)?\\s*:?\\s*(\\d{6,12})\\b", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher accountMatcher = accountPattern.matcher(text);
        if (accountMatcher.find()) {
            extractedEntities.put("account_number", accountMatcher.group(1));
            LOG.debug("Extracted account number: {}", accountMatcher.group(1));
        }

        // Extract order numbers (pattern: alphanumeric 6-15 chars)
        Pattern orderPattern = Pattern.compile("\\border\\s*(?:number|#)?\\s*:?\\s*([A-Z0-9]{6,15})\\b", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher orderMatcher = orderPattern.matcher(text);
        if (orderMatcher.find()) {
            extractedEntities.put("order_number", orderMatcher.group(1));
            LOG.debug("Extracted order number: {}", orderMatcher.group(1));
        }
    }

    /**
     * Get the full conversation transcript.
     */
    public String getTranscript() {
        StringBuilder transcript = new StringBuilder();
        for (ConversationTurn turn : turns) {
            String speaker = "user".equals(turn.speaker) ? "Customer" : "Assistant";
            transcript.append(speaker).append(": ").append(turn.text).append("\n");
        }
        return transcript.toString();
    }

    /**
     * Get a summary of the conversation (first few turns).
     */
    public String getSummary(int maxTurns) {
        StringBuilder summary = new StringBuilder();
        int turnsToInclude = Math.min(maxTurns, turns.size());

        for (int i = 0; i < turnsToInclude; i++) {
            ConversationTurn turn = turns.get(i);
            String speaker = "user".equals(turn.speaker) ? "Customer" : "Assistant";
            summary.append(speaker).append(": ").append(turn.text).append("\n");
        }

        if (turns.size() > maxTurns) {
            summary.append("... (").append(turns.size() - maxTurns).append(" more turns)\n");
        }

        return summary.toString();
    }

    /**
     * Get conversation metadata for Connect attributes.
     */
    public Map<String, String> getConversationAttributes() {
        Map<String, String> attributes = new HashMap<>();

        // Basic metadata
        attributes.put("Nova_ConversationStartTime", conversationStartTime.toString());
        attributes.put("Nova_ConversationDuration", String.valueOf(
                java.time.Duration.between(conversationStartTime, Instant.now()).getSeconds()
        ));
        attributes.put("Nova_TurnCount", String.valueOf(turns.size()));

        // Intent and sentiment
        attributes.put("Nova_Intent", detectedIntent);
        attributes.put("Nova_Sentiment", detectedSentiment);

        // Entities
        if (!extractedEntities.isEmpty()) {
            for (Map.Entry<String, String> entry : extractedEntities.entrySet()) {
                attributes.put("Nova_Entity_" + entry.getKey(), entry.getValue());
            }
        }

        // Summary (limited to 256 chars for Connect attribute limits)
        String summary = getSummary(3);
        if (summary.length() > 256) {
            summary = summary.substring(0, 253) + "...";
        }
        attributes.put("Nova_ConversationSummary", summary);

        // Full transcript (may be too long for Connect attributes - consider CloudWatch instead)
        String transcript = getTranscript();
        if (transcript.length() <= 1024) {
            attributes.put("Nova_Transcript", transcript);
        } else {
            attributes.put("Nova_Transcript", "See CloudWatch Logs for full transcript");
            LOG.info("Transcript too long for Connect attributes ({} chars), logged to CloudWatch", transcript.length());
        }

        return attributes;
    }

    /**
     * Get the detected intent.
     */
    public String getDetectedIntent() {
        return detectedIntent;
    }

    /**
     * Get the detected sentiment.
     */
    public String getDetectedSentiment() {
        return detectedSentiment;
    }

    /**
     * Get extracted entities.
     */
    public Map<String, String> getExtractedEntities() {
        return new HashMap<>(extractedEntities);
    }

    /**
     * Get conversation start time.
     */
    public Instant getConversationStartTime() {
        return conversationStartTime;
    }

    /**
     * Get number of conversation turns.
     */
    public int getTurnCount() {
        return turns.size();
    }

    /**
     * Represents a single turn in the conversation.
     */
    private static class ConversationTurn {
        final String speaker; // "user" or "assistant"
        final String text;
        final Instant timestamp;

        ConversationTurn(String speaker, String text, Instant timestamp) {
            this.speaker = speaker;
            this.text = text;
            this.timestamp = timestamp;
        }
    }
}
