package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.connect.ConnectIntegration;
import com.example.s2s.voipgateway.connect.ConnectAttributeManager;
import com.example.s2s.voipgateway.connect.ConnectClientHelper;
import com.example.s2s.voipgateway.nova.NovaStreamerFactory;
import org.mjsip.config.OptionParser;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipKeepAlive;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.*;
import org.mjsip.ua.registration.RegistrationClient;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.LoggerFactory;
import org.zoolu.net.SocketAddress;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * VoIP Gateway/User Agent for Nova Sonic S2S.
 */
public class NovaSonicVoipGateway extends RegisteringMultipleUAS {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NovaSonicVoipGateway.class);
    // Instance variables
    protected final NovaMediaConfig mediaConfig;
    protected final UAConfig uaConfig;
    private StreamerFactory streamerFactory;
    private RegistrationClient _rc;
    private SipKeepAlive keep_alive;
    private Set<String> acceptedNumbers;

    // *************************** Public methods **************************

    /**
     * Creates a new UA.
     */
    public NovaSonicVoipGateway(SipProvider sipProvider, PortPool portPool, ServiceOptions serviceConfig,
                                UAConfig uaConfig, NovaMediaConfig mediaConfig) {
        super(sipProvider, portPool, uaConfig, serviceConfig);
        this.mediaConfig = mediaConfig;
        this.uaConfig = uaConfig;
        streamerFactory = new NovaStreamerFactory(this.mediaConfig);
        this.acceptedNumbers = loadAcceptedNumbers();
        LOG.info("Loaded {} accepted numbers", acceptedNumbers.size());
        registerWithKeepAlive();
    }

    /**
     * Disable RegisteringMultipleUAS.register(), which gets called from the constructor.
     * We need _rc to schedule keep-alives, but it's private in the parent class.
     */
    @Override
    public void register() { }

    /**
     * SIP REGISTER with keep-alive packets sent on a schedule.
     */
    public void registerWithKeepAlive() {
        LOG.info("Registering with {}...", this.uaConfig.getRegistrar());
        if (this.uaConfig.isRegister()) {
            this._rc = new RegistrationClient(this.sip_provider, this.uaConfig, this);
            this._rc.loopRegister(this.uaConfig);
        }
        scheduleKeepAlive(uaConfig.getKeepAliveTime());
    }

    private void scheduleKeepAlive(long keepAliveTime) {
        if (keepAliveTime > 0L) {
            SipURI targetUri = this.sip_provider.hasOutboundProxy() ? this.sip_provider.getOutboundProxy() : _rc.getTargetAOR().getAddress().toSipURI();
            String targetHost = targetUri.getHost();
            int targetPort = targetUri.getPort();
            if (targetPort < 0) {
                targetPort = this.sip_provider.sipConfig().getDefaultPort();
            }

            SocketAddress targetSoAddr = new SocketAddress(targetHost, targetPort);
            if (this.keep_alive != null && this.keep_alive.isRunning()) {
                this.keep_alive.halt();
            }

            this.keep_alive = new SipKeepAlive(this.sip_provider, targetSoAddr, (SipMessage)null, keepAliveTime);
            LOG.info("Keep-alive started");
        }
    }

    @Override
    public void unregister() {
        LOG.info("Unregistering with {}...", this.uaConfig.getRegistrar());
        if (this._rc != null) {
            this._rc.unregister();
            this._rc.halt();
            this._rc = null;
        }
    }

    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        register();
        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller,
                                         MediaDesc[] media_descs) {
                LOG.info("Incoming call from: {} to: {}", caller.getAddress(), callee.getAddress());

                // Parse Amazon Connect integration data from SIP headers
                ConnectIntegration connectIntegration = ConnectIntegration.fromSipMessage(msg);
                ConnectAttributeManager attributeManager = null;

                if (connectIntegration != null && connectIntegration.isConnectCall()) {
                    LOG.info("Amazon Connect call detected - Contact ID: {}", connectIntegration.getContactId());

                    // Initialize CloudWatch log stream with Connect contact ID
                    String logStream = com.example.s2s.voipgateway.logging.CloudWatchLogManager
                            .initializeConnectLogStream(connectIntegration.getContactId());
                    LOG.info("Initialized Connect log stream: {}", logStream);

                    // Create attribute manager for tracking conversation state
                    attributeManager = new ConnectAttributeManager(connectIntegration);

                    // Load initial attributes from UUI data
                    if (connectIntegration.getUuiData() != null && !connectIntegration.getUuiData().isEmpty()) {
                        LOG.info("Loading {} initial attributes from Connect", connectIntegration.getUuiData().size());
                        connectIntegration.getUuiData().forEach(attributeManager::setAttribute);
                    }
                } else {
                    LOG.info("Direct call (non-Connect) - using session-based log stream");
                    String sessionId = java.util.UUID.randomUUID().toString();
                    String logStream = com.example.s2s.voipgateway.logging.CloudWatchLogManager
                            .initializeSessionLogStream(sessionId);
                    LOG.info("Initialized session log stream: {}", logStream);
                }

                // Extract the caller's phone number (From address)
                String callerNumber = extractPhoneNumber(caller.getAddress().toString());
                LOG.info("Extracted caller phone number: {}", callerNumber);

                // Extract the called number (To address)
                String calledNumber = extractPhoneNumber(callee.getAddress().toString());

                // Check if the call is for our accepted number (4432304260, 14432304260, or +14432304260)
                if (isAcceptedNumber(calledNumber)) {
                    LOG.info("Accepting call to: {}", calledNumber);

                    // Set the caller phone number and hangup callback for the tool system
                    if (streamerFactory instanceof NovaStreamerFactory) {
                        NovaStreamerFactory novaFactory = (NovaStreamerFactory) streamerFactory;
                        novaFactory.setCallerPhoneNumber(callerNumber);

                        // Pass attribute manager and streamer factory for tool access
                        final ConnectAttributeManager finalAttributeManager = attributeManager;
                        final NovaStreamerFactory finalStreamerFactory = novaFactory;

                        novaFactory.setHangupCallback(() -> {
                            LOG.info("Hangup callback invoked - terminating call");

                            // Update Connect attributes if this is a Connect call
                            if (finalAttributeManager != null) {
                                // Merge conversation tracker data before finalizing
                                if (finalStreamerFactory.getEventHandler() != null) {
                                    com.example.s2s.voipgateway.nova.conversation.NovaConversationTracker tracker =
                                            finalStreamerFactory.getEventHandler().getConversationTracker();
                                    if (tracker != null) {
                                        LOG.info("Merging conversation tracker data: Intent={}, Sentiment={}, Turns={}",
                                                tracker.getDetectedIntent(), tracker.getDetectedSentiment(), tracker.getTurnCount());
                                        finalAttributeManager.mergeConversationData(tracker.getConversationAttributes());
                                    }
                                }

                                LOG.info("Updating Connect attributes before hangup");
                                java.util.Map<String, String> finalAttributes = finalAttributeManager.getAttributesForUpdate();
                                LOG.info("Final attributes to update: {}", finalAttributes);

                                // Extract instance ID and contact ID for API call
                                String instanceArn = finalAttributeManager.getInstanceArn();
                                String contactId = finalAttributeManager.getContactId();

                                if (instanceArn != null && contactId != null) {
                                    String instanceId = ConnectClientHelper.extractInstanceId(instanceArn);
                                    if (instanceId != null) {
                                        LOG.info("Calling UpdateContactAttributes API for Contact: {} in Instance: {}", contactId, instanceId);
                                        boolean success = ConnectClientHelper.updateContactAttributes(
                                                instanceId,
                                                contactId,
                                                finalAttributes
                                        );
                                        if (success) {
                                            LOG.info("Successfully updated Connect contact attributes");
                                        } else {
                                            LOG.warn("Failed to update Connect contact attributes");
                                        }
                                    } else {
                                        LOG.warn("Could not extract instance ID from ARN: {}", instanceArn);
                                    }
                                } else {
                                    LOG.warn("Missing instanceArn or contactId - cannot update attributes");
                                }
                            }

                            // Clear CloudWatch log stream
                            com.example.s2s.voipgateway.logging.CloudWatchLogManager.clearSessionLogStream();

                            ua.hangup();
                        });
                    }

                    ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory));

                    // After MediaAgent is created, set attributeManager on event handler for tool tracking
                    if (streamerFactory instanceof NovaStreamerFactory) {
                        NovaStreamerFactory novaFactory = (NovaStreamerFactory) streamerFactory;
                        final ConnectAttributeManager finalAttrMgr = attributeManager;
                        // The event handler is created in createMediaStreamer, so we need to wait a moment
                        new Thread(() -> {
                            try {
                                Thread.sleep(500); // Wait for MediaStreamer to be created
                                if (novaFactory.getEventHandler() != null && finalAttrMgr != null) {
                                    novaFactory.getEventHandler().setAttributeManager(finalAttrMgr);
                                    LOG.info("Set attributeManager on event handler for tool tracking");
                                }
                            } catch (Exception e) {
                                LOG.error("Failed to set attributeManager on event handler", e);
                            }
                        }).start();
                    }
                } else {
                    LOG.warn("Rejecting call to unaccepted number: {}", calledNumber);

                    // Clear log stream before hanging up
                    com.example.s2s.voipgateway.logging.CloudWatchLogManager.clearSessionLogStream();

                    ua.hangup();
                }
            }
        };
    }

    /**
     * Extract phone number from SIP URI
     * @param sipUri The SIP URI (e.g., sip:+14432304260@example.com)
     * @return The phone number portion
     */
    private String extractPhoneNumber(String sipUri) {
        if (sipUri == null) return "";

        // Extract the user part from sip:user@domain
        String userPart = sipUri;
        if (userPart.contains("sip:")) {
            userPart = userPart.substring(userPart.indexOf("sip:") + 4);
        }
        if (userPart.contains("@")) {
            userPart = userPart.substring(0, userPart.indexOf("@"));
        }

        // Remove any non-digit characters except + at the start
        if (userPart.startsWith("+")) {
            return "+" + userPart.substring(1).replaceAll("[^0-9]", "");
        } else {
            return userPart.replaceAll("[^0-9]", "");
        }
    }

    /**
     * Load accepted numbers from configuration file
     */
    private Set<String> loadAcceptedNumbers() {
        Set<String> numbers = new HashSet<>();
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("accepted-numbers.properties");
            if (is == null) {
                LOG.warn("accepted-numbers.properties not found, accepting all calls");
                return numbers;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // Normalize the number (remove all non-digits)
                String normalized = line.replaceAll("[^0-9]", "");
                if (!normalized.isEmpty()) {
                    numbers.add(normalized);
                    LOG.info("Added accepted number: {}", normalized);
                }
            }
            reader.close();
        } catch (Exception e) {
            LOG.error("Error loading accepted numbers: {}", e.getMessage(), e);
        }
        return numbers;
    }

    /**
     * Check if the called number matches any accepted number
     * Handles variations: 4432304260, 14432304260, +14432304260
     */
    private boolean isAcceptedNumber(String number) {
        if (number == null || number.isEmpty()) {
            return acceptedNumbers.isEmpty(); // Accept all if no filter configured
        }

        // If no numbers configured, accept all
        if (acceptedNumbers.isEmpty()) {
            return true;
        }

        // Normalize to compare (remove all non-digits)
        String normalized = number.replaceAll("[^0-9]", "");

        // Check exact match
        if (acceptedNumbers.contains(normalized)) {
            return true;
        }

        // Check with country code (1 prefix)
        if (normalized.startsWith("1") && normalized.length() == 11) {
            // Try without the 1 prefix
            if (acceptedNumbers.contains(normalized.substring(1))) {
                return true;
            }
        } else if (normalized.length() == 10) {
            // Try with 1 prefix
            if (acceptedNumbers.contains("1" + normalized)) {
                return true;
            }
        }

        return false;
    }
    /**
     * The main method.
     */
    public static void main(String[] args) {
        // Initialize CloudWatch log stream BEFORE any logging occurs
        String startupLogStream = com.example.s2s.voipgateway.logging.CloudWatchLogManager.initializeCustomLogStream(
                "startup",
                java.time.Instant.now().toString().replace(":", "-")
        );

        println("mjSIP UserAgent " + SipStack.version);

        LOG.info("=== Nova S2S VoIP Gateway Starting ===");
        LOG.info("Version: mjSIP {}", SipStack.version);
        LOG.info("CloudWatch Log Stream: {}", startupLogStream != null ? startupLogStream : "disabled");

        SipConfig sipConfig = new SipConfig();
        UAConfig uaConfig = new UAConfig();
        SchedulerConfig schedulerConfig = new SchedulerConfig();
        PortConfig portConfig = new PortConfig();
        ServiceConfig serviceConfig = new ServiceConfig();
        NovaMediaConfig mediaConfig = new NovaMediaConfig();
        Map<String, String> environ = System.getenv();
        mediaConfig.setNovaVoiceId(environ.getOrDefault("NOVA_VOICE_ID","en_us_matthew"));

        // Load prompt from environment variable
        // Supports both direct prompt text and file paths (ending in .prompt)
        if (isConfigured(environ.get("NOVA_PROMPT"))) {
            String promptValue = environ.get("NOVA_PROMPT");
            if (promptValue.endsWith(".prompt")) {
                // Load from file in prompts/ directory
                String promptContent = NovaMediaConfig.loadPromptFromFile(promptValue);
                if (promptContent != null) {
                    mediaConfig.setNovaPrompt(promptContent);
                    LOG.info("Loaded prompt from file: {}", promptValue);
                } else {
                    LOG.warn("Failed to load prompt file: {}, using default", promptValue);
                }
            } else {
                // Direct prompt text
                mediaConfig.setNovaPrompt(promptValue);
                LOG.info("Using prompt from NOVA_PROMPT environment variable");
            }
        } else {
            LOG.info("Using default prompt from prompts/default.prompt");
        }

        if (isConfigured(environ.get("SIP_SERVER"))) {
            configureFromEnvironment(environ, uaConfig, mediaConfig, portConfig, sipConfig);
        } else {
            OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, mediaConfig, portConfig, serviceConfig);
        }

        sipConfig.normalize();
        uaConfig.normalize(sipConfig);

        SipProvider sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig));
        NovaSonicVoipGateway gateway = new NovaSonicVoipGateway(sipProvider, portConfig.createPool(), serviceConfig, uaConfig, mediaConfig);
    }

    /**
     * Checks if a string is configured.
     * @param str The string
     * @return true if the string is not null and not empty, otherwise false
     */
    private static boolean isConfigured(String str) {
        return str != null && !str.isEmpty();
    }

    private static void configureFromEnvironment(Map<String, String> environ, UAConfig uaConfig,
                                                 NovaMediaConfig mediaConfig, PortConfig portConfig,
                                                 SipConfig sipConfig) {
        uaConfig.setRegistrar(new SipURI(environ.get("SIP_SERVER")));
        uaConfig.setSipUser(environ.get("SIP_USER"));
        uaConfig.setAuthUser(environ.get("AUTH_USER"));
        uaConfig.setAuthPasswd(environ.get("AUTH_PASSWORD"));
        uaConfig.setAuthRealm(environ.get("AUTH_REALM"));
        uaConfig.setDisplayName(environ.get("DISPLAY_NAME"));
        // Support trunk mode (inbound-only, no registration)
        uaConfig.setRegister(environ.getOrDefault("DO_REGISTER","true").equalsIgnoreCase("true"));
        if (isConfigured(environ.get("MEDIA_ADDRESS"))) {
            uaConfig.setMediaAddr(environ.get("MEDIA_ADDRESS"));
        }
        uaConfig.setKeepAliveTime(Long.parseLong(environ.getOrDefault("SIP_KEEPALIVE_TIME","60000")));
        uaConfig.setNoPrompt(true);
        mediaConfig.setMediaDescs(createDefaultMediaDescs());
        if (isConfigured(environ.get("MEDIA_PORT_BASE"))) {
            portConfig.setMediaPort(Integer.parseInt(environ.get("MEDIA_PORT_BASE")));
        }
        if (isConfigured(environ.get("MEDIA_PORT_COUNT"))) {
            portConfig.setPortCount(Integer.parseInt(environ.get("MEDIA_PORT_COUNT")));
        }
        sipConfig.setLogAllPackets(environ.getOrDefault("DEBUG_SIP","true").equalsIgnoreCase("true"));
        if (isConfigured(environ.get("SIP_VIA_ADDR"))) {
            sipConfig.setViaAddrIPv4(environ.get("SIP_VIA_ADDR"));
        }
    }

    /**
     * Prints a message to standard output.
     */
    protected static void println(String str) {
        System.out.println(str);
    }

    /**
     * Creates the default media descriptions.
     * @return
     */
    private static MediaDesc[] createDefaultMediaDescs() {
        return new MediaDesc[]{new MediaDesc("audio",
                4000,
                "RTP/AVP",
                new MediaSpec[]{
                        new MediaSpec(0,
                                "PCMU",
                                8000,
                                1,
                                160)})};
    }

}