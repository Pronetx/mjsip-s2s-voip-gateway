package com.example.s2s.voipgateway;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for SIP User-to-User Information (UUI) header.
 *
 * Amazon Connect can pass custom data in the UUI header as hex-encoded JSON.
 * This class decodes the hex string and parses the JSON into a key-value map.
 *
 * Example UUI header:
 *   User-to-User: 7B226B6579223A2276616C7565222C20226B657931223A2276616C756531227D
 *
 * Decodes to:
 *   {"key":"value", "key1":"value1"}
 */
public class UUIParser {
    private static final Logger LOG = LoggerFactory.getLogger(UUIParser.class);

    /**
     * Parse hex-encoded JSON from UUI header
     *
     * @param hexEncodedJson Hex-encoded JSON string from UUI header
     * @return Map of key-value pairs from JSON
     */
    public static Map<String, String> parseUUI(String hexEncodedJson) {
        Map<String, String> result = new HashMap<>();

        if (hexEncodedJson == null || hexEncodedJson.trim().isEmpty()) {
            LOG.debug("UUI header is empty or null");
            return result;
        }

        try {
            // Step 1: Decode hex to string
            String jsonString = hexToString(hexEncodedJson.trim());
            LOG.debug("Decoded UUI hex to JSON: {}", jsonString);

            // Step 2: Parse JSON
            JSONObject jsonObject = new JSONObject(jsonString);

            // Step 3: Convert to Map
            for (String key : jsonObject.keySet()) {
                Object value = jsonObject.get(key);
                result.put(key, value != null ? value.toString() : null);
            }

            LOG.info("Parsed UUI data: {} key-value pairs", result.size());
            return result;

        } catch (IllegalArgumentException e) {
            LOG.error("Failed to decode hex string from UUI: {}", hexEncodedJson, e);
            return result;
        } catch (JSONException e) {
            LOG.error("Failed to parse JSON from UUI: {}", hexEncodedJson, e);
            return result;
        } catch (Exception e) {
            LOG.error("Unexpected error parsing UUI: {}", hexEncodedJson, e);
            return result;
        }
    }

    /**
     * Convert hex string to UTF-8 string
     *
     * @param hexString Hex-encoded string (e.g., "48656C6C6F" for "Hello")
     * @return Decoded UTF-8 string
     * @throws IllegalArgumentException if hex string is invalid
     */
    public static String hexToString(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return "";
        }

        // Remove any whitespace or non-hex characters
        hexString = hexString.replaceAll("[^0-9A-Fa-f]", "");

        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length: " + hexString);
        }

        byte[] bytes = new byte[hexString.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            int value = Integer.parseInt(hexString.substring(index, index + 2), 16);
            bytes[i] = (byte) value;
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Convert string to hex encoding (for testing/debugging)
     *
     * @param input UTF-8 string
     * @return Hex-encoded string
     */
    public static String stringToHex(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder hexString = new StringBuilder();
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);

        for (byte b : bytes) {
            hexString.append(String.format("%02X", b));
        }

        return hexString.toString();
    }

    /**
     * Extract UUI data from SIP message and merge with existing context
     *
     * @param uuiHeader Raw UUI header value from SIP INVITE
     * @param existingContext Existing context map to merge into
     * @return Updated context map with UUI data
     */
    public static Map<String, String> extractAndMergeUUI(String uuiHeader, Map<String, String> existingContext) {
        Map<String, String> mergedContext = new HashMap<>(existingContext);

        if (uuiHeader == null || uuiHeader.trim().isEmpty()) {
            return mergedContext;
        }

        // Parse UUI
        Map<String, String> uuiData = parseUUI(uuiHeader);

        // Merge UUI data into context with "UUI_" prefix to avoid conflicts
        for (Map.Entry<String, String> entry : uuiData.entrySet()) {
            String prefixedKey = "UUI_" + entry.getKey();
            mergedContext.put(prefixedKey, entry.getValue());
            LOG.debug("Added UUI data to context: {} = {}", prefixedKey, entry.getValue());
        }

        return mergedContext;
    }

    /**
     * Validate that a hex string can be decoded to valid JSON
     *
     * @param hexEncodedJson Hex-encoded JSON string
     * @return true if valid, false otherwise
     */
    public static boolean isValidUUI(String hexEncodedJson) {
        try {
            String jsonString = hexToString(hexEncodedJson.trim());
            new JSONObject(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
