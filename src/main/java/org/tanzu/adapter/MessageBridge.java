package org.tanzu.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;

/**
 * Message Bridge component that translates between SSE transport and STDIO transport.
 *
 * This implementation ensures MCP protocol compliance by:
 * - Validating JSON-RPC message format before forwarding
 * - Handling bidirectional message flow between SSE clients and STDIO server
 * - Providing health monitoring and error handling
 * - Managing connection-aware message bridging
 */
@Component
public class MessageBridge {

    private static final Logger logger = LoggerFactory.getLogger(MessageBridge.class);

    @Autowired
    private McpServerProcess mcpServerProcess;

    @Autowired
    private SseSessionManager sseSessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        // Start bridging server-to-client messages only when there are active sessions
        // This is more efficient than auto-subscribing regardless of connections
        bridgeServerToClientWhenNeeded();
    }

    /**
     * Bridges client messages to the MCP Server with full validation.
     *
     * @param clientMessage JSON-RPC message from SSE client
     * @return Mono that completes when message is successfully sent
     */
    public Mono<Void> bridgeClientToServer(String clientMessage) {
        return Mono.fromRunnable(() -> {
            try {
                // Validate JSON-RPC message format before forwarding
                validateJsonRpcMessage(clientMessage);

                logger.debug("Bridging validated client message to server: {}", clientMessage);

                // Forward to MCP Server via STDIO
                // The McpServerProcess handles MCP STDIO compliance (newline escaping, etc.)
                mcpServerProcess.sendMessage(clientMessage);

            } catch (Exception e) {
                logger.error("Failed to bridge client message to server: {}", clientMessage, e);
                throw new RuntimeException("Invalid or malformed JSON-RPC message", e);
            }
        });
    }

    /**
     * Bridges server messages to SSE clients.
     * This method creates a reactive stream that forwards messages from the MCP Server
     * to all connected SSE sessions.
     *
     * @return Flux of ServerSentEvent objects for SSE transport
     */
    public Flux<ServerSentEvent<String>> bridgeServerToClient() {
        return mcpServerProcess.getMessages()
                .doOnNext(message -> logger.debug("Bridging server message to clients: {}", message))
                .doOnNext(message -> {
                    // Send to all active SSE sessions
                    sseSessionManager.sendToAllSessions(message);
                })
                .map(message -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(message)
                        .build())
                .onErrorContinue((error, obj) -> {
                    logger.error("Error bridging server message to client: {}", obj, error);
                });
    }

    /**
     * Connection-aware server-to-client bridging.
     * Only maintains the bridge when there are active SSE sessions.
     */
    private void bridgeServerToClientWhenNeeded() {
        // Monitor session count and manage bridge lifecycle
        Flux.interval(java.time.Duration.ofSeconds(5))
                .filter(tick -> sseSessionManager.getActiveSessionCount() > 0)
                .take(1) // Take only the first tick when sessions become active
                .flatMap(tick -> bridgeServerToClient())
                .repeat() // Restart monitoring when bridge completes
                .subscribe(
                        event -> {}, // Events are handled in bridgeServerToClient
                        error -> logger.error("Error in connection-aware bridging", error),
                        () -> logger.debug("Server-to-client bridge completed")
                );
    }

    /**
     * Checks if the message bridge is healthy.
     *
     * @return true if the underlying MCP Server process is running
     */
    public boolean isHealthy() {
        return mcpServerProcess.isRunning();
    }

    /**
     * Validates JSON-RPC message format according to the specification.
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
     * Gets the current number of active SSE sessions.
     * Useful for monitoring and health checks.
     *
     * @return number of active sessions
     */
    public int getActiveSessionCount() {
        return sseSessionManager.getActiveSessionCount();
    }

    /**
     * Manual method to start server-to-client bridging.
     * Can be called when the first SSE client connects.
     *
     * @return Flux of server-sent events
     */
    public Flux<ServerSentEvent<String>> startBridging() {
        logger.info("Starting manual server-to-client message bridging");
        return bridgeServerToClient();
    }

    /**
     * Gets health information about the message bridge.
     *
     * @return Map containing health status information
     */
    public java.util.Map<String, Object> getHealthInfo() {
        return java.util.Map.of(
                "mcpServerRunning", isHealthy(),
                "mcpServerName", mcpServerProcess.getConfig().getName(),
                "activeSessionCount", getActiveSessionCount(),
                "bridgeStatus", isHealthy() ? "healthy" : "unhealthy"
        );
    }
}