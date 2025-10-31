package com.example.s2s.voipgateway.nova;

import com.example.s2s.voipgateway.constants.MediaTypes;
import com.example.s2s.voipgateway.constants.SonicAudioConfig;
import com.example.s2s.voipgateway.constants.SonicAudioTypes;
import com.example.s2s.voipgateway.nova.event.*;
import com.example.s2s.voipgateway.nova.tools.DateTimeNovaS2SEventHandler;
import com.example.s2s.voipgateway.NovaMediaConfig;
import com.example.s2s.voipgateway.NovaSonicAudioInput;
import com.example.s2s.voipgateway.NovaSonicAudioOutput;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import org.mjsip.media.AudioStreamer;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.media.StreamerOptions;
import org.mjsip.media.rx.AudioReceiver;
import org.mjsip.media.tx.AudioTransmitter;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * StreamerFactory implementation for Amazon Nova Sonic.
 */
public class NovaStreamerFactory implements StreamerFactory {
    private static final Logger log = LoggerFactory.getLogger(NovaStreamerFactory.class);
    private static final String ROLE_SYSTEM = "SYSTEM";
    private final NovaMediaConfig mediaConfig;
    private String callerPhoneNumber;
    private Runnable hangupCallback;

    public NovaStreamerFactory(NovaMediaConfig mediaConfig) {
        this.mediaConfig = mediaConfig;
    }

    /**
     * Set the caller's phone number for the current call.
     * This should be called before createMediaStreamer().
     */
    public void setCallerPhoneNumber(String phoneNumber) {
        this.callerPhoneNumber = phoneNumber;
        log.info("Set caller phone number: {}", phoneNumber);
    }

    /**
     * Set the hangup callback for the current call.
     * This should be called before createMediaStreamer().
     */
    public void setHangupCallback(Runnable hangupCallback) {
        this.hangupCallback = hangupCallback;
        log.info("Set hangup callback");
    }

    /**
     * Gracefully close the Bedrock stream and then hang up the call.
     * Note: We DON'T call onComplete() here because the audio stream closure will do that.
     * We just trigger the hangup which stops the audio, closes the streams properly, and
     * then the audio stream's close() method will call onComplete() on the observer.
     */
    public void closeStreamAndHangup() {
        log.info("Initiating graceful hangup - will stop audio and close streams");

        // Just execute the hangup callback immediately
        // This will stop the audio streamer, which will properly close the audio output stream,
        // which will send contentEnd and call onComplete() on the observer
        if (hangupCallback != null) {
            log.info("Executing hangup callback to stop audio and close streams");
            hangupCallback.run();
        }
    }

    @Override
    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flowSpec) {
        log.info("Creating Nova streamer ...");
        NettyNioAsyncHttpClient.Builder nettyBuilder = NettyNioAsyncHttpClient.builder()
                .readTimeout(Duration.of(180, ChronoUnit.SECONDS))
                .maxConcurrency(20)
                .protocol(Protocol.HTTP2)
                .protocolNegotiation(ProtocolNegotiation.ALPN);

        BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
                .region(Region.US_EAST_1)
                .httpClientBuilder(nettyBuilder)
                .build();

        String promptName = UUID.randomUUID().toString();

        NovaS2SBedrockInteractClient novaClient = new NovaS2SBedrockInteractClient(client, "amazon.nova-sonic-v1:0");

        // Use the new modular tool system with auto-discovery
        com.example.s2s.voipgateway.nova.tools.ModularNovaS2SEventHandler eventHandler;
        if (callerPhoneNumber != null && !callerPhoneNumber.isEmpty()) {
            log.info("Creating ModularNovaS2SEventHandler with caller phone: {}", callerPhoneNumber);
            eventHandler = new com.example.s2s.voipgateway.nova.tools.ModularNovaS2SEventHandler(callerPhoneNumber);
        } else {
            log.warn("No caller phone number set, using ModularNovaS2SEventHandler with placeholder");
            eventHandler = new com.example.s2s.voipgateway.nova.tools.ModularNovaS2SEventHandler("unknown");
        }

        // Set hangup callback if provided
        if (hangupCallback != null) {
            log.info("Setting hangup callback on event handler");
            eventHandler.setHangupCallback(hangupCallback);
            // Also set the stream close callback which will close the stream gracefully before hangup
            eventHandler.setStreamCloseCallback(this::closeStreamAndHangup);
        }

        log.info("Using system prompt: {}", mediaConfig.getNovaPrompt());

        InteractObserver<NovaSonicEvent> inputObserver = novaClient.interactMultimodal(
                createSessionStartEvent(),
                createPromptStartEvent(promptName, eventHandler),
                createSystemPrompt(promptName, mediaConfig.getNovaPrompt()),
                eventHandler);

        eventHandler.setOutbound(inputObserver);

        AudioTransmitter tx = new NovaSonicAudioInput(eventHandler);
        AudioReceiver rx = new NovaSonicAudioOutput(inputObserver, promptName);

        StreamerOptions options = StreamerOptions.builder()
                .setRandomEarlyDrop(mediaConfig.getRandomEarlyDropRate())
                .setSymmetricRtp(mediaConfig.isSymmetricRtp())
                .build();

        log.debug("Created AudioStreamer");
        return new AudioStreamer(executor, flowSpec, tx, rx, options);
    }

    /**
     * Creates the PromptStart event.
     * @param promptName The prompt name for the session.
     * @param eventHandler The event handler for the session.
     * @return The PromptStartEvent
     */
    private PromptStartEvent createPromptStartEvent(String promptName, NovaS2SEventHandler eventHandler) {
        return new PromptStartEvent(PromptStartEvent.PromptStart.builder()
                .promptName(promptName)
                .textOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.TEXT_PLAIN).build())
                .audioOutputConfiguration(PromptStartEvent.AudioOutputConfiguration.builder()
                        .mediaType(MediaTypes.AUDIO_LPCM)
                        .sampleRateHertz(SonicAudioConfig.SAMPLE_RATE)
                        .sampleSizeBits(SonicAudioConfig.SAMPLE_SIZE)
                        .channelCount(SonicAudioConfig.CHANNEL_COUNT)
                        .voiceId(mediaConfig.getNovaVoiceId())
                        .encoding(SonicAudioConfig.ENCODING_BASE64)
                        .audioType(SonicAudioTypes.SPEECH)
                        .build())
                .toolUseOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.APPLICATION_JSON).build())
                .toolConfiguration(eventHandler.getToolConfiguration())
                .build());
    }

    /**
     * Creates the SessionStart event.
     * @return The SessionStartEvent
     */
    private SessionStartEvent createSessionStartEvent() {
        return new SessionStartEvent(mediaConfig.getNovaMaxTokens(), mediaConfig.getNovaTopP(), mediaConfig.getNovaTemperature());
    }

    /**
     * Creates the system prompt.
     * @param promptName The prompt name for the session.
     * @param systemPrompt The system prompt.
     * @return The system prompt as a TextInputEvent.
     */
    private static TextInputEvent createSystemPrompt(String promptName, String systemPrompt) {
        return new TextInputEvent(TextInputEvent.TextInput.builder()
                .promptName(promptName)
                .contentName(UUID.randomUUID().toString())
                .content(systemPrompt)
                .role(ROLE_SYSTEM)
                .build());
    }
}
