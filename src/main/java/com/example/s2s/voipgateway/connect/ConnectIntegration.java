package com.example.s2s.voipgateway.connect;

import com.example.s2s.voipgateway.UUIParser;
import org.mjsip.sip.message.SipMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles Amazon Connect integration metadata from SIP headers.
 * Extracts Connect-specific headers and UUI data from SIP INVITE messages.
 */
public class ConnectIntegration {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectIntegration.class);

    private String contactId;
    private String initialContactId;
    private String customerPhoneNumber;
    private String instanceArn;
    private Map<String, String> uuiData;
    private Map<String, String> allAttributes;

    public ConnectIntegration() {
        this.uuiData = new HashMap<>();
        this.allAttributes = new HashMap<>();
    }

    /**
     * Parse Amazon Connect metadata from SIP INVITE message.
     * Extracts X-Connect-* headers and UUI header.
     *
     * @param msg The SIP INVITE message
     * @return ConnectIntegration instance with parsed data, or null if not a Connect call
     */
    public static ConnectIntegration fromSipMessage(SipMessage msg) {
        ConnectIntegration integration = new ConnectIntegration();

        // Extract X-Connect-* headers
        integration.contactId = extractHeader(msg, "X-Connect-ContactId");
        integration.initialContactId = extractHeader(msg, "X-Connect-InitialContactId");
        integration.customerPhoneNumber = extractHeader(msg, "X-Connect-CustomerPhoneNumber");
        integration.instanceArn = extractHeader(msg, "X-Connect-InstanceARN");

        // Parse UUI header (hex-encoded JSON)
        String uuiHeader = extractHeader(msg, "User-to-User");
        if (uuiHeader != null && !uuiHeader.isEmpty()) {
            try {
                integration.uuiData = UUIParser.parseUUI(uuiHeader);
                LOG.info("Parsed UUI data: {} attributes", integration.uuiData.size());
            } catch (Exception e) {
                LOG.warn("Failed to parse UUI header: {}", e.getMessage());
            }
        }

        // Build combined attributes map
        integration.buildAttributesMap();

        // Return null if no Connect headers found (not a Connect call)
        if (integration.contactId == null && integration.initialContactId == null) {
            LOG.debug("No Amazon Connect headers found in SIP message");
            return null;
        }

        LOG.info("Amazon Connect call detected - ContactId: {}, InitialContactId: {}",
                integration.contactId, integration.initialContactId);

        return integration;
    }

    /**
     * Extract header value from SIP message, handling case-insensitive lookup.
     */
    private static String extractHeader(SipMessage msg, String headerName) {
        try {
            // SipMessage.getHeader() returns a Header object, need to get its value
            Object header = msg.getHeader(headerName);
            if (header != null) {
                return header.toString().trim();
            }
        } catch (Exception e) {
            LOG.debug("Header {} not found: {}", headerName, e.getMessage());
        }
        return null;
    }

    /**
     * Build a combined map of all attributes (Connect headers + UUI data).
     */
    private void buildAttributesMap() {
        allAttributes.clear();

        if (contactId != null) {
            allAttributes.put("Connect_ContactId", contactId);
        }
        if (initialContactId != null) {
            allAttributes.put("Connect_InitialContactId", initialContactId);
        }
        if (customerPhoneNumber != null) {
            allAttributes.put("Connect_CustomerPhoneNumber", customerPhoneNumber);
        }
        if (instanceArn != null) {
            allAttributes.put("Connect_InstanceARN", instanceArn);
        }

        // Add UUI data with "UUI_" prefix
        for (Map.Entry<String, String> entry : uuiData.entrySet()) {
            allAttributes.put("UUI_" + entry.getKey(), entry.getValue());
        }
    }

    /**
     * Check if this is an Amazon Connect call.
     */
    public boolean isConnectCall() {
        return contactId != null || initialContactId != null;
    }

    // Getters
    public String getContactId() {
        return contactId;
    }

    public String getInitialContactId() {
        return initialContactId;
    }

    public String getCustomerPhoneNumber() {
        return customerPhoneNumber;
    }

    public String getInstanceArn() {
        return instanceArn;
    }

    public Map<String, String> getUuiData() {
        return new HashMap<>(uuiData);
    }

    public Map<String, String> getAllAttributes() {
        return new HashMap<>(allAttributes);
    }

    @Override
    public String toString() {
        return "ConnectIntegration{" +
                "contactId='" + contactId + '\'' +
                ", initialContactId='" + initialContactId + '\'' +
                ", customerPhone='" + customerPhoneNumber + '\'' +
                ", uuiAttributes=" + uuiData.size() +
                '}';
    }
}
