package com.example.s2s.voipgateway.connect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.connect.ConnectClient;
import software.amazon.awssdk.services.connect.model.UpdateContactAttributesRequest;
import software.amazon.awssdk.services.connect.model.UpdateContactAttributesResponse;

import java.util.Map;

/**
 * Helper for Amazon Connect API operations.
 */
public class ConnectClientHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectClientHelper.class);
    private static ConnectClient connectClient;

    /**
     * Get or create the Connect client (singleton).
     */
    private static synchronized ConnectClient getClient() {
        if (connectClient == null) {
            connectClient = ConnectClient.builder()
                    .region(Region.US_WEST_2)
                    .build();
            LOG.info("Created Amazon Connect client for region: {}", Region.US_WEST_2);
        }
        return connectClient;
    }

    /**
     * Update contact attributes in Amazon Connect.
     *
     * @param instanceId Amazon Connect instance ID (extracted from InstanceARN)
     * @param contactId Contact ID from X-Amzn-ConnectContactId header
     * @param attributes Map of attributes to update
     * @return true if successful, false otherwise
     */
    public static boolean updateContactAttributes(String instanceId, String contactId, Map<String, String> attributes) {
        if (instanceId == null || contactId == null) {
            LOG.warn("Cannot update contact attributes - instanceId or contactId is null");
            return false;
        }

        if (attributes == null || attributes.isEmpty()) {
            LOG.debug("No attributes to update");
            return true;
        }

        try {
            LOG.info("Updating contact attributes for Contact ID: {} in Instance: {}", contactId, instanceId);
            LOG.debug("Attributes to update: {}", attributes);

            UpdateContactAttributesRequest request = UpdateContactAttributesRequest.builder()
                    .instanceId(instanceId)
                    .initialContactId(contactId)
                    .attributes(attributes)
                    .build();

            UpdateContactAttributesResponse response = getClient().updateContactAttributes(request);

            LOG.info("Successfully updated contact attributes. Request ID: {}",
                    response.responseMetadata().requestId());
            return true;

        } catch (Exception e) {
            LOG.error("Failed to update contact attributes for Contact ID: {}", contactId, e);
            return false;
        }
    }

    /**
     * Extract instance ID from instance ARN.
     * ARN format: arn:aws:connect:region:account:instance/instance-id
     *
     * @param instanceArn The full instance ARN
     * @return The instance ID, or null if parsing fails
     */
    public static String extractInstanceId(String instanceArn) {
        if (instanceArn == null || instanceArn.isEmpty()) {
            return null;
        }

        try {
            // Extract instance ID from ARN
            int lastSlash = instanceArn.lastIndexOf('/');
            if (lastSlash > 0 && lastSlash < instanceArn.length() - 1) {
                return instanceArn.substring(lastSlash + 1);
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract instance ID from ARN: {}", instanceArn, e);
        }

        return null;
    }

    /**
     * Close the Connect client.
     */
    public static synchronized void close() {
        if (connectClient != null) {
            connectClient.close();
            connectClient = null;
            LOG.info("Closed Amazon Connect client");
        }
    }
}
