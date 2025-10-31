package com.example.s2s.voipgateway;

import org.mjsip.ua.MediaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class NovaMediaConfig extends MediaConfig {
    private static final Logger LOG = LoggerFactory.getLogger(NovaMediaConfig.class);
    private static final String DEFAULT_VOICE_ID = "en_us_matthew";
    private static final String DEFAULT_PROMPT_FILE = "prompts/default.prompt";
    private static final String FALLBACK_PROMPT = "You are a friendly assistant. The user and you will engage in a spoken dialog " +
            "exchanging the transcripts of a natural real-time conversation. Keep your responses short, " +
            "generally two or three sentences for chatty scenarios.";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final float DEFAULT_NOVA_TOP_P = 0.9F;
    private static final float DEFAULT_NOVA_TEMPERATURE = 0.7F;
    private String novaVoiceId = DEFAULT_VOICE_ID;
    private String novaPrompt = loadDefaultPrompt();
    private int novaMaxTokens = DEFAULT_MAX_TOKENS;
    private float novaTopP = DEFAULT_NOVA_TOP_P;
    private float novaTemperature = DEFAULT_NOVA_TEMPERATURE;

    public String getNovaVoiceId() {
        return novaVoiceId;
    }

    public void setNovaVoiceId(String novaVoiceId) {
        this.novaVoiceId = novaVoiceId;
    }

    public String getNovaPrompt() {
        return novaPrompt;
    }

    public void setNovaPrompt(String novaPrompt) {
        this.novaPrompt = novaPrompt;
    }

    public int getNovaMaxTokens() {
        return novaMaxTokens;
    }

    public void setNovaMaxTokens(int novaMaxTokens) {
        this.novaMaxTokens = novaMaxTokens;
    }

    public float getNovaTopP() {
        return novaTopP;
    }

    public void setNovaTopP(float novaTopP) {
        this.novaTopP = novaTopP;
    }

    public float getNovaTemperature() {
        return novaTemperature;
    }

    public void setNovaTemperature(float novaTemperature) {
        this.novaTemperature = novaTemperature;
    }

    /**
     * Load the default prompt from the prompts/default.prompt file in resources.
     * Falls back to FALLBACK_PROMPT if the file cannot be loaded.
     */
    private static String loadDefaultPrompt() {
        try {
            InputStream is = NovaMediaConfig.class.getClassLoader().getResourceAsStream(DEFAULT_PROMPT_FILE);
            if (is == null) {
                LOG.warn("Default prompt file not found: {}, using fallback", DEFAULT_PROMPT_FILE);
                return FALLBACK_PROMPT;
            }

            String prompt = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            LOG.info("Loaded default prompt from: {}", DEFAULT_PROMPT_FILE);
            return prompt;
        } catch (Exception e) {
            LOG.error("Error loading default prompt file: {}", e.getMessage(), e);
            return FALLBACK_PROMPT;
        }
    }

    /**
     * Load a prompt from a file in the prompts/ directory.
     * @param promptFileName The prompt file name (e.g., "custom.prompt")
     * @return The prompt content, or null if not found
     */
    public static String loadPromptFromFile(String promptFileName) {
        try {
            String path = "prompts/" + promptFileName;
            InputStream is = NovaMediaConfig.class.getClassLoader().getResourceAsStream(path);
            if (is == null) {
                LOG.warn("Prompt file not found: {}", path);
                return null;
            }

            String prompt = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            LOG.info("Loaded prompt from: {}", path);
            return prompt;
        } catch (Exception e) {
            LOG.error("Error loading prompt file {}: {}", promptFileName, e.getMessage(), e);
            return null;
        }
    }
}
