package org.tanzu.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Message processor for MCP Streamable HTTP transport.
 * Handles different JSON-RPC message types and decides on appropriate response format.
 */
@Service
public class MessageProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MessageProcessor.class);

    @Autowired
    private StreamableBridge streamableBridge;

    @Autowired
    private SessionManager sessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Processes a JSON-RPC message and returns the appropriate HTTP response.
     *
     * @param jsonRpcMessage The JSON-RPC message
     * @param acceptHeader The Accept header value
     * @param sessionId The session ID (for SSE)
     * @return ResponseEntity with appropriate status and body
     */
    public ResponseEntity<?> processMessage(String jsonRpcMessage, String acceptHeader, String sessionId) {
        try {
            JsonRpcMessage message = parseJsonRpcMessage(jsonRpcMessage);

            if (message.isNotification()) {
                // Process notification and return 202 Accepted
                return processNotification(message, sessionId);
            }

            if (message.isResponse()) {
                // Process response and return 202 Accepted
                return processResponse(message, sessionId);
            }

            if (message.isRequest()) {
                // Decide between JSON or SSE based on Accept header and request complexity
                if (shouldUseSSE(acceptHeader, message)) {
                    return createSseResponse(message, sessionId);
                } else {
                    return createJsonResponse(message);
                }
            }

            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Invalid JSON-RPC message type", null));

        } catch (Exception e) {
            logger.error("Error processing message: {}", jsonRpcMessage, e);
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage(), null));
        }
    }

    /**
     * Processes a JSON-RPC notification.
     *
     * @param message The notification message
     * @param sessionId The session ID (optional)
     * @return 202 Accepted response
     */
    private ResponseEntity<?> processNotification(JsonRpcMessage message, String sessionId) {
        logger.info("Processing notification: {}", message.getMethod());

        // Forward notification to MCP server
        try {
            streamableBridge.bridgeClientToServer(message.getRawMessage()).subscribe();
            logger.debug("Forwarded notification to MCP server: {}", message.getMethod());
        } catch (Exception e) {
            logger.error("Failed to forward notification to MCP server", e);
        }

        // Return 202 Accepted for notifications per spec
        return ResponseEntity.accepted().build();
    }

    /**
     * Processes a JSON-RPC response.
     *
     * @param message The response message
     * @param sessionId The session ID (optional)
     * @return 202 Accepted response
     */
    private ResponseEntity<?> processResponse(JsonRpcMessage message, String sessionId) {
        logger.info("Processing response for request ID: {}", message.getId());

        // Forward response to MCP server
        try {
            streamableBridge.bridgeClientToServer(message.getRawMessage()).subscribe();
            logger.debug("Forwarded response to MCP server for ID: {}", message.getId());
        } catch (Exception e) {
            logger.error("Failed to forward response to MCP server", e);
        }

        // Return 202 Accepted for responses per spec
        return ResponseEntity.accepted().build();
    }

    /**
     * Creates an SSE streaming response for a request.
     *
     * @param message The request message
     * @param sessionId The session ID
     * @return ResponseEntity with SSE stream
     */
    private ResponseEntity<Flux<ServerSentEvent<String>>> createSseResponse(JsonRpcMessage message, String sessionId) {
        logger.info("Creating SSE response for request: {} (session: {})", message.getMethod(), sessionId);

        // Create or validate session
        if (sessionId == null || !sessionManager.isValidSession(sessionId)) {
            sessionId = sessionManager.createSession();
        }

        // Register session
        sessionManager.registerSession(sessionId);

        // Process request in background and stream responses via SSE
        processRequestInBackground(message, sessionId);

        // Create SSE stream
        Flux<ServerSentEvent<String>> sseStream = sessionManager.getMessageStream(sessionId)
                .map(responseMessage -> ServerSentEvent.<String>builder()
                        .id("mcp-" + System.currentTimeMillis())
                        .event("message")
                        .data(responseMessage)
                        .build());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Mcp-Session-Id", sessionId)
                .body(sseStream);
    }

    /**
     * Creates a JSON response for a request (blocking/synchronous).
     *
     * @param message The request message
     * @return ResponseEntity with JSON response
     */
    private ResponseEntity<?> createJsonResponse(JsonRpcMessage message) {
        logger.info("Creating JSON response for request: {}", message.getMethod());

        try {
            // Process request synchronously and collect first response
            String firstResponse = streamableBridge.processRequest(message.getRawMessage())
                    .blockFirst(); // Block for first response

            if (firstResponse != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(firstResponse);
            } else {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createSuccessResponse("Request processed", message.getId()));
            }

        } catch (Exception e) {
            logger.error("Error creating JSON response for request: {}", message.getMethod(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse(e.getMessage(), message.getId()));
        }
    }

    /**
     * Processes a request in the background and streams responses via SSE.
     *
     * @param message The request message
     * @param sessionId The session ID
     */
    private void processRequestInBackground(JsonRpcMessage message, String sessionId) {
        streamableBridge.processRequest(message.getRawMessage())
                .subscribe(
                        response -> sessionManager.sendMessage(sessionId, response),
                        error -> {
                            logger.error("Error processing request in background", error);
                            String errorResponse = createErrorResponse(error.getMessage(), message.getId());
                            sessionManager.sendMessage(sessionId, errorResponse);
                            sessionManager.terminateSession(sessionId);
                        },
                        () -> {
                            logger.debug("Background request processing completed for session: {}", sessionId);
                            // Keep session open for potential additional requests
                        }
                );
    }

    /**
     * Determines whether to use SSE based on Accept header and request characteristics.
     *
     * @param acceptHeader The Accept header value
     * @param message The request message
     * @return true if SSE should be used
     */
    private boolean shouldUseSSE(String acceptHeader, JsonRpcMessage message) {
        if (acceptHeader == null) {
            return false;
        }

        // Use SSE if explicitly requested
        if (acceptHeader.contains("text/event-stream")) {
            return true;
        }

        // Use SSE for requests that may generate multiple responses
        return expectsMultipleResponses(message);
    }

    /**
     * Checks if a request expects multiple responses.
     *
     * @param message The request message
     * @return true if multiple responses are expected
     */
    private boolean expectsMultipleResponses(JsonRpcMessage message) {
        // List of methods that typically generate multiple responses
        String method = message.getMethod();
        return method != null && (
                method.startsWith("sampling/") ||
                method.startsWith("resources/") ||
                method.equals("tools/list") ||
                method.equals("prompts/list")
        );
    }

    /**
     * Parses a JSON-RPC message string into a structured object.
     *
     * @param jsonRpcMessage The JSON-RPC message string
     * @return Parsed JsonRpcMessage
     * @throws Exception if parsing fails
     */
    private JsonRpcMessage parseJsonRpcMessage(String jsonRpcMessage) throws Exception {
        JsonNode node = objectMapper.readTree(jsonRpcMessage);
        return new JsonRpcMessage(node, jsonRpcMessage);
    }

    /**
     * Creates a JSON-RPC success response.
     *
     * @param result The result message
     * @param id The request ID
     * @return JSON-RPC success response
     */
    private String createSuccessResponse(String result, String id) {
        try {
            return String.format(
                    "{\"jsonrpc\":\"2.0\",\"result\":{\"message\":\"%s\"},\"id\":%s}",
                    result.replace("\"", "\\\""),
                    id != null ? "\"" + id + "\"" : "null"
            );
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"result\":{\"message\":\"Success\"},\"id\":null}";
        }
    }

    /**
     * Creates a JSON-RPC error response.
     *
     * @param errorMessage The error message
     * @param id The request ID
     * @return JSON-RPC error response
     */
    private String createErrorResponse(String errorMessage, String id) {
        try {
            return String.format(
                    "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"%s\"},\"id\":%s}",
                    errorMessage.replace("\"", "\\\""),
                    id != null ? "\"" + id + "\"" : "null"
            );
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}";
        }
    }

    /**
     * Helper class to represent a parsed JSON-RPC message.
     */
    public static class JsonRpcMessage {
        private final JsonNode node;
        private final String rawMessage;

        public JsonRpcMessage(JsonNode node, String rawMessage) {
            this.node = node;
            this.rawMessage = rawMessage;
        }

        public boolean isRequest() {
            return node.has("method") && node.has("id");
        }

        public boolean isNotification() {
            return node.has("method") && !node.has("id");
        }

        public boolean isResponse() {
            return (node.has("result") || node.has("error")) && node.has("id");
        }

        public String getMethod() {
            return node.has("method") ? node.get("method").asText() : null;
        }

        public String getId() {
            return node.has("id") ? node.get("id").asText() : null;
        }

        public String getRawMessage() {
            return rawMessage;
        }

        public JsonNode getNode() {
            return node;
        }
    }
}