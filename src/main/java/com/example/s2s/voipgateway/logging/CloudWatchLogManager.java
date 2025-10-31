package com.example.s2s.voipgateway.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Manages CloudWatch log stream naming for unique per-session logging.
 * Each call session gets its own log stream to avoid mixing logs from multiple concurrent calls.
 */
public class CloudWatchLogManager {
    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchLogManager.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // MDC key for log stream name
    private static final String LOG_STREAM_KEY = "awsLogStreamName";

    // Track if CloudWatch logging is enabled
    private static final boolean CLOUDWATCH_ENABLED =
            "true".equalsIgnoreCase(System.getenv().getOrDefault("CLOUDWATCH_LOGGING_ENABLED", "false"));

    // Cache EC2 instance ID
    private static String ec2InstanceId = null;

    /**
     * Get EC2 instance ID from metadata service.
     * @return EC2 instance ID or "unknown" if not available
     */
    private static String getEc2InstanceId() {
        if (ec2InstanceId != null) {
            return ec2InstanceId;
        }

        try {
            // Get token for IMDSv2
            URL tokenUrl = new URL("http://169.254.169.254/latest/api/token");
            HttpURLConnection tokenConn = (HttpURLConnection) tokenUrl.openConnection();
            tokenConn.setRequestMethod("PUT");
            tokenConn.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "21600");
            tokenConn.setConnectTimeout(1000);
            tokenConn.setReadTimeout(1000);

            BufferedReader tokenReader = new BufferedReader(new InputStreamReader(tokenConn.getInputStream()));
            String token = tokenReader.readLine();
            tokenReader.close();

            // Get instance ID using token
            URL metadataUrl = new URL("http://169.254.169.254/latest/meta-data/instance-id");
            HttpURLConnection metadataConn = (HttpURLConnection) metadataUrl.openConnection();
            metadataConn.setRequestProperty("X-aws-ec2-metadata-token", token);
            metadataConn.setConnectTimeout(1000);
            metadataConn.setReadTimeout(1000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(metadataConn.getInputStream()));
            ec2InstanceId = reader.readLine();
            reader.close();

            LOG.info("Detected EC2 instance ID: {}", ec2InstanceId);
            return ec2InstanceId;
        } catch (Exception e) {
            LOG.debug("Could not retrieve EC2 instance ID: {}", e.getMessage());
            ec2InstanceId = "unknown";
            return ec2InstanceId;
        }
    }

    /**
     * Initialize a new log stream for a call session.
     * Stream name format: ec2-instance-id/yyyy-MM-dd/session-uuid
     *
     * @param sessionId Optional session ID (uses UUID if null)
     * @return The log stream name created
     */
    public static String initializeSessionLogStream(String sessionId) {
        if (!CLOUDWATCH_ENABLED) {
            LOG.debug("CloudWatch logging disabled, skipping log stream initialization");
            return null;
        }

        // Generate session ID if not provided
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // Create log stream name: instance-id/date/session-id
        String instanceId = getEc2InstanceId();
        String date = LocalDate.now().format(DATE_FORMATTER);
        String logStreamName = instanceId + "/" + date + "/" + sessionId;

        // Set in MDC for logback to use
        MDC.put(LOG_STREAM_KEY, logStreamName);

        LOG.info("Initialized CloudWatch log stream: {}", logStreamName);
        return logStreamName;
    }

    /**
     * Initialize log stream from Amazon Connect contact ID.
     * Stream name format: ec2-instance-id/yyyy-MM-dd/connect-{contactId}
     */
    public static String initializeConnectLogStream(String contactId) {
        if (!CLOUDWATCH_ENABLED) {
            return null;
        }

        String instanceId = getEc2InstanceId();
        String date = LocalDate.now().format(DATE_FORMATTER);
        String logStreamName = instanceId + "/" + date + "/connect-" + contactId;

        MDC.put(LOG_STREAM_KEY, logStreamName);

        LOG.info("Initialized CloudWatch log stream for Connect call: {}", logStreamName);
        return logStreamName;
    }

    /**
     * Initialize log stream with custom prefix and session ID.
     * Stream name format: ec2-instance-id/yyyy-MM-dd/prefix-{sessionId}
     */
    public static String initializeCustomLogStream(String prefix, String sessionId) {
        if (!CLOUDWATCH_ENABLED) {
            return null;
        }

        String instanceId = getEc2InstanceId();
        String date = LocalDate.now().format(DATE_FORMATTER);
        String logStreamName = instanceId + "/" + date + "/" + prefix + "-" + sessionId;

        MDC.put(LOG_STREAM_KEY, logStreamName);

        LOG.info("Initialized CloudWatch log stream: {}", logStreamName);
        return logStreamName;
    }

    /**
     * Clear the log stream from MDC (call at end of session).
     */
    public static void clearSessionLogStream() {
        String logStreamName = MDC.get(LOG_STREAM_KEY);
        if (logStreamName != null) {
            LOG.debug("Clearing CloudWatch log stream: {}", logStreamName);
            MDC.remove(LOG_STREAM_KEY);
        }
    }

    /**
     * Get the current log stream name.
     */
    public static String getCurrentLogStream() {
        return MDC.get(LOG_STREAM_KEY);
    }

    /**
     * Check if CloudWatch logging is enabled.
     */
    public static boolean isCloudWatchEnabled() {
        return CLOUDWATCH_ENABLED;
    }
}
