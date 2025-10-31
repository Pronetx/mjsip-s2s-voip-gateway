package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.conversation.NovaConversationTracker;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Modular S2S Event Handler with auto-discovery of tools.
 * Tools are automatically discovered from the tools package - just add a new Tool implementation
 * and it will be available. Tools can be filtered via PromptConfiguration files.
 */
public class ModularNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ModularNovaS2SEventHandler.class);
    private final ToolRegistry toolRegistry;
    private final NovaConversationTracker conversationTracker;
    private volatile boolean audioEndTurnReceived = false;
    private volatile boolean textEndTurnReceived = false;
    private com.example.s2s.voipgateway.connect.ConnectAttributeManager attributeManager;

    /**
     * Creates a handler with all auto-discovered tools enabled.
     * @param phoneNumber The caller's phone number for SMS
     */
    public ModularNovaS2SEventHandler(String phoneNumber) {
        this.toolRegistry = new ToolRegistry();
        this.conversationTracker = new NovaConversationTracker();
        initializeAllTools(phoneNumber);
    }

    /**
     * Creates a handler with tools specified in PromptConfiguration.
     * @param phoneNumber The caller's phone number for SMS
     * @param promptConfig The prompt configuration specifying which tools to enable
     */
    public ModularNovaS2SEventHandler(String phoneNumber, PromptConfiguration promptConfig) {
        this.toolRegistry = new ToolRegistry();
        this.conversationTracker = new NovaConversationTracker();
        initializeToolsFromConfig(phoneNumber, promptConfig);
    }

    /**
     * Creates a handler with a custom tool registry.
     * @param toolRegistry The pre-configured tool registry
     */
    public ModularNovaS2SEventHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.conversationTracker = new NovaConversationTracker();
    }

    /**
     * Initializes all available tools using auto-discovery.
     * @param phoneNumber The caller's phone number
     */
    private void initializeAllTools(String phoneNumber) {
        log.info("Auto-discovering and initializing ALL tools for phone number: {}", phoneNumber);

        ToolFactory factory = new ToolFactory(phoneNumber);
        int count = 0;
        for (Tool tool : factory.createAllTools()) {
            toolRegistry.register(tool);
            count++;
        }

        log.info("Registered {} auto-discovered tools", count);
    }

    /**
     * Initializes only the tools specified in the PromptConfiguration using auto-discovery.
     * @param phoneNumber The caller's phone number
     * @param promptConfig The prompt configuration
     */
    private void initializeToolsFromConfig(String phoneNumber, PromptConfiguration promptConfig) {
        log.info("Auto-discovering and initializing tools from config for phone number: {}", phoneNumber);
        log.info("Tools to enable: {}", promptConfig.getToolNames());

        ToolFactory factory = new ToolFactory(phoneNumber);
        int count = 0;
        for (Tool tool : factory.createToolsByName(promptConfig.getToolNames())) {
            toolRegistry.register(tool);
            count++;
        }

        log.info("Registered {} configured tools", count);
    }

    /**
     * Gets the tool registry for adding/removing tools.
     * @return The tool registry
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Sets the hangup callback on the hangup tool.
     * @param hangupCallback The callback to invoke
     */
    public void setHangupCallback(Runnable hangupCallback) {
        Tool hangupTool = toolRegistry.getTool("hangupTool");
        if (hangupTool instanceof HangupTool) {
            ((HangupTool) hangupTool).setHangupCallback(hangupCallback);
        } else {
            log.warn("HangupTool not found in registry, cannot set callback");
        }
    }

    /**
     * Sets the stream close callback on the hangup tool.
     * This callback should gracefully close the Bedrock stream before hanging up.
     * @param streamCloseCallback The callback to invoke
     */
    public void setStreamCloseCallback(Runnable streamCloseCallback) {
        Tool hangupTool = toolRegistry.getTool("hangupTool");
        if (hangupTool instanceof HangupTool) {
            ((HangupTool) hangupTool).setStreamCloseCallback(streamCloseCallback);
        } else {
            log.warn("HangupTool not found in registry, cannot set stream close callback");
        }
    }

    @Override
    protected void handleToolInvocation(String toolUseId, String toolName, String content, Map<String, Object> output) {
        if (toolName == null) {
            log.warn("Received null toolName");
            return;
        }

        log.info("Handling tool invocation: {} with content: {}", toolName, content);

        // Record tool invocation to Connect attributes
        if (attributeManager != null) {
            attributeManager.recordToolInvocation(toolName, content);
        }

        boolean handled = toolRegistry.handle(toolName, toolUseId, content, output);
        if (!handled) {
            output.put("status", "error");
            output.put("message", "Unknown tool: " + toolName);
        }
    }

    @Override
    public void handleContentEnd(JsonNode node) {
        super.handleContentEnd(node);

        // Check if this is an END_TURN and hangup was requested
        String stopReason = node.has("stopReason") ? node.get("stopReason").asText() : "";
        String contentType = node.has("type") ? node.get("type").asText() : "";

        if ("END_TURN".equals(stopReason)) {
            Tool hangupTool = toolRegistry.getTool("hangupTool");
            if (hangupTool instanceof HangupTool) {
                HangupTool ht = (HangupTool) hangupTool;
                if (ht.isHangupRequested()) {
                    // Track which END_TURN we received
                    if ("AUDIO".equals(contentType)) {
                        log.info("AUDIO END_TURN received");
                        audioEndTurnReceived = true;
                    } else if ("TEXT".equals(contentType)) {
                        log.info("TEXT END_TURN received");
                        textEndTurnReceived = true;
                    }

                    // Only hang up after we've received BOTH END_TURNs
                    if (audioEndTurnReceived && textEndTurnReceived) {
                        log.info("Both AUDIO and TEXT END_TURN received - waiting 3 seconds then hanging up");
                        // Reset flags for next call
                        audioEndTurnReceived = false;
                        textEndTurnReceived = false;

                        // Wait 3 seconds then hang up
                        new Thread(() -> {
                            try {
                                Thread.sleep(3000);
                                log.info("3-second buffer complete - executing hangup now");
                                ht.executeHangup();
                            } catch (Exception e) {
                                log.error("Failed to execute hangup after buffer", e);
                            }
                        }).start();
                    }
                }
            }
        }
    }

    @Override
    public void handleTextOutput(JsonNode node) {
        super.handleTextOutput(node);

        // Capture text for transcript
        if (node.has("content") && node.has("role")) {
            String content = node.get("content").asText();
            String role = node.get("role").asText();

            if ("user".equalsIgnoreCase(role)) {
                conversationTracker.addUserTurn(content);
            } else if ("assistant".equalsIgnoreCase(role)) {
                conversationTracker.addNovaTurn(content);
            }
        }
    }

    /**
     * Get the conversation tracker for accessing conversation data.
     * @return The conversation tracker
     */
    public NovaConversationTracker getConversationTracker() {
        return conversationTracker;
    }

    /**
     * Set the ConnectAttributeManager for recording tool invocations.
     * @param attributeManager The attribute manager
     */
    public void setAttributeManager(com.example.s2s.voipgateway.connect.ConnectAttributeManager attributeManager) {
        this.attributeManager = attributeManager;
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return toolRegistry.getToolConfiguration();
    }

    public void close() {
        // ToolFactory manages PinpointClient lifecycle
        // No cleanup needed here
    }
}
