package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for formatting Server-Sent Events (SSE) messages according to the SSE specification.
 * Used by the MCP Streamable HTTP transport for SSE format responses.
 */
public class SseMessageFormatter {

    private static final Logger logger = LoggerFactory.getLogger(SseMessageFormatter.class);

    /**
     * Formats a Server-Sent Event with optional event type and ID.
     *
     * @param eventType The event type (optional)
     * @param data The event data
     * @param id The event ID (optional)
     * @return Properly formatted SSE string
     */
    public static String formatSseEvent(String eventType, String data, String id) {
        StringBuilder sb = new StringBuilder();

        // Add ID field if provided
        if (id != null && !id.trim().isEmpty()) {
            sb.append("id: ").append(id).append("\n");
        }

        // Add event type field if provided
        if (eventType != null && !eventType.trim().isEmpty()) {
            sb.append("event: ").append(eventType).append("\n");
        }

        // Add data field (required)
        // Handle multi-line data by prefixing each line with "data: "
        if (data != null) {
            String[] lines = data.split("\n");
            for (String line : lines) {
                sb.append("data: ").append(line).append("\n");
            }
        } else {
            sb.append("data: \n");
        }

        // End with double newline
        sb.append("\n");

        String result = sb.toString();
        logger.debug("Formatted SSE event: {}", result.replace("\n", "\\n"));
        return result;
    }

    /**
     * Formats a JSON-RPC message as an SSE event.
     *
     * @param jsonRpcMessage The JSON-RPC message
     * @param eventId The event ID (optional)
     * @return SSE-formatted JSON-RPC message
     */
    public static String formatJsonRpcSseEvent(String jsonRpcMessage, String eventId) {
        return formatSseEvent("message", jsonRpcMessage, eventId);
    }

    /**
     * Formats a JSON-RPC message as an SSE event with automatic ID generation.
     *
     * @param jsonRpcMessage The JSON-RPC message
     * @return SSE-formatted JSON-RPC message with generated ID
     */
    public static String formatJsonRpcSseEvent(String jsonRpcMessage) {
        String eventId = "mcp-" + System.currentTimeMillis();
        return formatJsonRpcSseEvent(jsonRpcMessage, eventId);
    }

    /**
     * Creates an SSE heartbeat/keepalive event.
     *
     * @return SSE heartbeat event
     */
    public static String createHeartbeatEvent() {
        return formatSseEvent("heartbeat", "{\"type\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}", null);
    }

    /**
     * Creates an SSE connection established event.
     *
     * @param sessionId The session ID
     * @return SSE connection event
     */
    public static String createConnectionEvent(String sessionId) {
        String data = String.format("{\"type\":\"connection\",\"sessionId\":\"%s\",\"timestamp\":%d}",
                sessionId, System.currentTimeMillis());
        return formatSseEvent("connection", data, "connection-" + sessionId);
    }

    /**
     * Creates an SSE error event.
     *
     * @param errorMessage The error message
     * @param errorCode The error code (optional)
     * @return SSE error event
     */
    public static String createErrorEvent(String errorMessage, Integer errorCode) {
        String data = String.format("{\"type\":\"error\",\"message\":\"%s\"",
                errorMessage.replace("\"", "\\\""));

        if (errorCode != null) {
            data += ",\"code\":" + errorCode;
        }

        data += ",\"timestamp\":" + System.currentTimeMillis() + "}";

        return formatSseEvent("error", data, "error-" + System.currentTimeMillis());
    }
}