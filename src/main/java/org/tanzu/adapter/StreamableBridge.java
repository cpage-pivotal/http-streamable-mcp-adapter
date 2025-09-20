package org.tanzu.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;

/**
 * Streamable Bridge component that translates between Streamable HTTP transport and STDIO transport.
 *
 * Replaces MessageBridge to support:
 * - Request/response correlation instead of session management
 * - Streaming response aggregation for multiple responses per request
 * - Timeout handling for streaming responses
 * - Stateless request/response pattern
 */
@Component
public class StreamableBridge {

    private static final Logger logger = LoggerFactory.getLogger(StreamableBridge.class);

    @Autowired
    private McpServerProcess mcpServerProcess;

    @Autowired
    private RequestContextManager requestContextManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        // Start bridging server-to-client messages for response correlation
        startServerToClientBridge();
    }

    /**
     * Processes a streamable HTTP request and returns a stream of responses.
     *
     * Core flow as per design document:
     * 1. Validate JSON-RPC message
     * 2. Create correlation context
     * 3. Send to MCP Server via STDIO
     * 4. Stream responses for this request
     *
     * @param jsonRpcMessage The JSON-RPC message from the client
     * @return Flux of JSON response strings
     */
    public Flux<String> processRequest(String jsonRpcMessage) {
        return Mono.fromCallable(() -> {
            // 1. Validate JSON-RPC message
            validateJsonRpcMessage(jsonRpcMessage);

            // 2. Create correlation context
            String correlationId = requestContextManager.createRequestContext(jsonRpcMessage);

            // 3. Send to MCP Server via STDIO
            mcpServerProcess.sendMessage(jsonRpcMessage);

            return correlationId;
        })
        .flatMapMany(correlationId -> {
            // 4. Stream responses for this request
            return streamResponsesForRequest(correlationId);
        })
        .onErrorResume(error -> {
            logger.error("Error processing streamable request: {}", jsonRpcMessage, error);
            return Flux.just(createErrorResponse(error.getMessage(), null));
        });
    }

    /**
     * Streams responses for a specific request correlation ID.
     * Supports both single and multiple responses per request.
     *
     * @param correlationId The correlation ID for the request
     * @return Flux of response messages
     */
    private Flux<String> streamResponsesForRequest(String correlationId) {
        return requestContextManager.getResponseStream(correlationId)
                .doOnNext(response -> logger.debug("Streaming response for {}: {}", correlationId, response))
                .doOnComplete(() -> logger.debug("Response stream completed for: {}", correlationId))
                .doOnError(error -> logger.error("Response stream error for {}", correlationId, error));
    }

    /**
     * Starts the server-to-client bridge for response correlation.
     * Monitors MCP Server output and routes responses to appropriate request contexts.
     */
    private void startServerToClientBridge() {
        mcpServerProcess.getMessages()
                .doOnNext(message -> logger.debug("Received server message: {}", message))
                .doOnNext(message -> {
                    // Route message to appropriate request context
                    requestContextManager.sendResponse(message);
                })
                .onErrorContinue((error, obj) -> {
                    logger.error("Error in server-to-client bridge: {}", obj, error);
                })
                .subscribe(
                        message -> {}, // Messages are handled in sendResponse
                        error -> logger.error("Server-to-client bridge error", error),
                        () -> logger.info("Server-to-client bridge completed")
                );

        logger.info("Started server-to-client message bridge");
    }

    /**
     * Legacy method for backward compatibility with existing MessageBridge usage.
     * Bridges client messages to server but doesn't return response stream.
     *
     * @param clientMessage JSON-RPC message from client
     * @return Mono that completes when message is sent
     */
    public Mono<Void> bridgeClientToServer(String clientMessage) {
        return Mono.fromRunnable(() -> {
            try {
                validateJsonRpcMessage(clientMessage);
                logger.debug("Bridging client message to server: {}", clientMessage);
                mcpServerProcess.sendMessage(clientMessage);
            } catch (Exception e) {
                logger.error("Failed to bridge client message to server: {}", clientMessage, e);
                throw new RuntimeException("Invalid or malformed JSON-RPC message", e);
            }
        });
    }

    /**
     * Validates JSON-RPC message format according to the specification.
     * Reuses validation logic from MessageBridge with enhancements.
     *
     * @param message JSON-RPC message to validate
     * @throws Exception if the message is invalid or malformed
     */
    public void validateJsonRpcMessage(String message) throws Exception {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be null or empty");
        }

        JsonNode messageNode;
        try {
            messageNode = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON format", e);
        }

        // Validate JSON-RPC 2.0 specification requirements
        if (!messageNode.has("jsonrpc")) {
            throw new IllegalArgumentException("Missing required 'jsonrpc' field");
        }

        if (!"2.0".equals(messageNode.get("jsonrpc").asText())) {
            throw new IllegalArgumentException("Invalid JSON-RPC version, expected '2.0'");
        }

        // Must have either 'method' (for requests/notifications) or 'result'/'error' (for responses)
        boolean hasMethod = messageNode.has("method");
        boolean hasResult = messageNode.has("result");
        boolean hasError = messageNode.has("error");
        boolean hasId = messageNode.has("id");

        if (!hasMethod && !hasResult && !hasError) {
            throw new IllegalArgumentException("Invalid JSON-RPC message: missing method, result, or error");
        }

        // Additional validation for different message types
        if (hasMethod) {
            // Request or notification
            String method = messageNode.get("method").asText();
            if (method == null || method.trim().isEmpty()) {
                throw new IllegalArgumentException("Method name cannot be empty");
            }

            // Requests must have an ID, notifications must not
            if (hasId && (hasResult || hasError)) {
                throw new IllegalArgumentException("Request cannot have both method and result/error");
            }
        } else {
            // Response - must have ID
            if (!hasId) {
                throw new IllegalArgumentException("Response message must have an 'id' field");
            }

            // Cannot have both result and error
            if (hasResult && hasError) {
                throw new IllegalArgumentException("Response cannot have both result and error");
            }
        }

        logger.debug("JSON-RPC message validation passed for: {}",
                message.length() > 100 ? message.substring(0, 100) + "..." : message);
    }

    /**
     * Checks if the bridge is healthy.
     *
     * @return true if the underlying MCP Server process is running
     */
    public boolean isHealthy() {
        return mcpServerProcess.isRunning();
    }

    /**
     * Gets the current number of active requests.
     *
     * @return number of active request contexts
     */
    public int getActiveRequestCount() {
        return requestContextManager.getActiveRequestCount();
    }

    /**
     * Gets health information about the streamable bridge.
     *
     * @return Map containing health status information
     */
    public java.util.Map<String, Object> getHealthInfo() {
        return java.util.Map.of(
                "mcpServerRunning", isHealthy(),
                "mcpServerName", mcpServerProcess.getConfig().getName(),
                "activeRequestCount", getActiveRequestCount(),
                "bridgeStatus", isHealthy() ? "healthy" : "unhealthy",
                "transport", "streamable-http"
        );
    }

    /**
     * Creates a JSON-RPC error response.
     *
     * @param errorMessage The error message
     * @param id The request ID (can be null)
     * @return JSON-RPC error response
     */
    private String createErrorResponse(String errorMessage, String id) {
        try {
            String errorJson = String.format(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"%s\"},\"id\":%s}",
                errorMessage.replace("\"", "\\\""),
                id != null ? "\"" + id + "\"" : "null"
            );
            return errorJson;
        } catch (Exception e) {
            logger.error("Failed to create error response", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}";
        }
    }

    /**
     * Creates a JSON-RPC success response.
     *
     * @param result The result object
     * @param id The request ID (can be null)
     * @return JSON-RPC success response
     */
    private String createSuccessResponse(String result, String id) {
        try {
            String successJson = String.format(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"message\":\"%s\"},\"id\":%s}",
                result.replace("\"", "\\\""),
                id != null ? "\"" + id + "\"" : "null"
            );
            return successJson;
        } catch (Exception e) {
            logger.error("Failed to create success response", e);
            return "{\"jsonrpc\":\"2.0\",\"result\":{\"message\":\"Success\"},\"id\":null}";
        }
    }
}