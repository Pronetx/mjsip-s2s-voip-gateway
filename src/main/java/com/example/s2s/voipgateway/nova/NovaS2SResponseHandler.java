package com.example.s2s.voipgateway.nova;

import static software.amazon.awssdk.thirdparty.io.netty.util.internal.ObjectUtil.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.model.BidirectionalOutputPayloadPart;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponseHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous response handler for Amazon Nova Sonic sessions.
 */
public class NovaS2SResponseHandler implements InvokeModelWithBidirectionalStreamResponseHandler {
    private static final Logger log = LoggerFactory.getLogger(NovaS2SResponseHandler.class);
    public static final String TYPE_TOOL = "TOOL";
    private final NovaS2SEventHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String toolUseId;
    private String toolUseContent;
    private String toolName;
    private boolean debugResponses = false;

    public NovaS2SResponseHandler(NovaS2SEventHandler handler) {
        this.handler = checkNotNull(handler, "handler cannot be null");
        debugResponses = System.getenv().getOrDefault("DEBUG_RESPONSES","false").equalsIgnoreCase("true");
    }

    @Override
    public void responseReceived(InvokeModelWithBidirectionalStreamResponse response) {
        log.info("Response received with Bedrock Nova S2S request id: {}", response.responseMetadata().requestId());
    }

    @Override
    public void onEventStream(SdkPublisher<InvokeModelWithBidirectionalStreamOutput> sdkPublisher) {
        log.info("Bedrock Nova S2S event stream received");
        CompletableFuture<Void> completableFuture = sdkPublisher.subscribe((output) -> output.accept(new Visitor() {
            @Override
            public void visitChunk(BidirectionalOutputPayloadPart event) {
                String payloadString =
                        StandardCharsets.UTF_8.decode((event.bytes().asByteBuffer().rewind().duplicate())).toString();
                if (debugResponses) {
                    log.debug("Received chunk: {}", payloadString);
                }
                handleJsonResponse(payloadString);
            }
        }));

        // if any of the chunks fail to parse or be handled ensure to send an error or they will get lost
        completableFuture.exceptionally(t -> {
            // Check if this is the "No open content found" error which indicates stream timeout
            String errorMsg = t.getMessage();
            if (errorMsg != null && errorMsg.contains("No open content found")) {
                log.warn("Bedrock content stream expired - this typically happens after long tool execution delays");
                log.warn("The conversation will be terminated. Consider optimizing tool response times.");
            } else {
                log.error("Event stream error", t);
            }
            handler.onError(new Exception(t));
            return null;
        });

        handler.onStart();
    }

    @Override
    public void exceptionOccurred(Throwable t) {
        log.error("Event stream error, exception occurred", t);
        handler.onError(new Exception(t));
    }

    @Override
    public void complete() {
        log.info("Event stream complete");
        handler.onComplete();
    }

    /**
     * Handles a JSON response from the event stream.
     * @param msg The JSON string to be handled
     */
    private void handleJsonResponse(String msg) {
        try {
            JsonNode rootNode = objectMapper.readTree(msg);
            JsonNode eventNode = rootNode.get("event");
            JsonNode usageEventNode = rootNode.get("usageEvent");

            if (eventNode != null) {
                if (eventNode.has("completionStart")) {
                    log.info("Received completionStart event");
                    handler.handleCompletionStart(eventNode.get("completionStart"));
                } else if (eventNode.has("contentStart")) {
                    log.info("Received contentStart event");
                    handler.handleContentStart(eventNode.get("contentStart"));
                } else if (eventNode.has("textOutput")) {
                    handler.handleTextOutput(eventNode.get("textOutput"));
                } else if (eventNode.has("audioOutput")) {
                    log.info("Received audioOutput event");
                    handler.handleAudioOutput(eventNode.get("audioOutput"));
                } else if (eventNode.has("toolUse")) {
                    toolUseId = eventNode.get("toolUse").get("toolUseId").asText();
                    toolName = eventNode.get("toolUse").get("toolName").asText();
                    toolUseContent = eventNode.get("toolUse").get("content").asText();
                    log.info("Received toolUse event for tool: {}", toolName);
                } else if (eventNode.has("contentEnd")) {
                    if (TYPE_TOOL.equals(eventNode.get("contentEnd").get("type").asText())) {
                        log.info("Received contentEnd event for TOOL type");
                        handler.handleToolUse(eventNode, toolUseId, toolName, toolUseContent);
                    } else {
                        log.info("Received contentEnd event for type: {}", eventNode.get("contentEnd").get("type").asText());
                        handler.handleContentEnd(eventNode.get("contentEnd"));
                    }
                } else if (eventNode.has("completionEnd")) {
                    log.info("Received completionEnd event");
                    handler.handleCompletionEnd(eventNode.get("completionEnd"));
                } else {
                    log.info("Unhandled event: {}", eventNode);
                }
            } else if (usageEventNode != null) {
                // Handle usage events - log at debug level to reduce noise
                if (log.isDebugEnabled()) {
                    JsonNode details = usageEventNode.get("details");
                    JsonNode total = details != null ? details.get("total") : null;
                    if (total != null) {
                        log.debug("Usage metrics - Input tokens: {}, Output tokens: {}, Total: {}",
                                usageEventNode.get("totalInputTokens"),
                                usageEventNode.get("totalOutputTokens"),
                                usageEventNode.get("totalTokens"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
            handler.onError(e);
        }
    }
}