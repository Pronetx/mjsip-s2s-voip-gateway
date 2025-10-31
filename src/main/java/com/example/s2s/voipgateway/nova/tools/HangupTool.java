package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool for hanging up the phone call.
 */
public class HangupTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(HangupTool.class);
    private Runnable hangupCallback;
    private Runnable streamCloseCallback;
    private volatile boolean hangupRequested = false;

    public HangupTool() {
    }

    /**
     * Check if hangup was requested (tool was invoked).
     */
    public boolean isHangupRequested() {
        return hangupRequested;
    }

    /**
     * Sets the callback to be invoked when hanging up.
     * @param hangupCallback The callback runnable
     */
    public void setHangupCallback(Runnable hangupCallback) {
        this.hangupCallback = hangupCallback;
    }

    /**
     * Sets the callback to gracefully close the stream before hanging up.
     * @param streamCloseCallback The callback that closes the stream and then hangs up
     */
    public void setStreamCloseCallback(Runnable streamCloseCallback) {
        this.streamCloseCallback = streamCloseCallback;
    }

    @Override
    public String getName() {
        return "hangupTool";
    }

    @Override
    public String getDescription() {
        return "End the phone call when the conversation is complete or the caller requests to hang up";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    /**
     * Execute the hangup - called after Nova finishes speaking.
     */
    public void executeHangup() {
        if (!hangupRequested) {
            log.warn("executeHangup called but hangup was not requested");
            return;
        }

        hangupRequested = false; // Clear flag to prevent duplicate execution

        // Prefer stream close callback (which closes stream then hangs up), fallback to direct hangup
        Runnable callbackToUse = streamCloseCallback != null ? streamCloseCallback : hangupCallback;

        if (callbackToUse != null) {
            log.info("Executing {} callback to end call after Nova finished speaking",
                    streamCloseCallback != null ? "stream-close-and-hangup" : "hangup");
            callbackToUse.run();
        } else {
            log.warn("No hangup callback configured - cannot terminate call");
        }
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        log.info("Hangup tool invoked - will hang up after Nova finishes speaking (END_TURN)");
        hangupRequested = true;
        output.put("status", "acknowledged");
        output.put("message", "Please say goodbye to the caller.");
        // Wait for END_TURN event to trigger executeHangup()
    }
}
