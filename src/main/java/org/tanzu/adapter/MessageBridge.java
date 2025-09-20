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
 * Message Bridge component that translates between HTTP Streamable transport and STDIO transport.
 *
 * This implementation ensures MCP protocol compliance by:
 * - Validating JSON-RPC message format before forwarding
 * - Handling bidirectional message flow between HTTP clients and STDIO server
 * - Providing health monitoring and error handling
 * - Managing reactive message bridging
 */
@Component
public class MessageBridge {

    private static final Logger logger = LoggerFactory.getLogger(MessageBridge.class);

    @Autowired
    private McpServerProcess mcpServerProcess;


    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        logger.info("MessageBridge initialized for HTTP Streamable transport");
    }

    /**
     * Bridges client messages to the MCP Server with full validation.
     *
     * @param clientMessage JSON-RPC message from HTTP client
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
     * Bridges server messages to HTTP clients via reactive streams.
     * This method creates a reactive stream that forwards messages from the MCP Server
     * to HTTP Streamable clients.
     *
     * @return Flux of String messages for HTTP Streamable transport
     */
    public Flux<String> bridgeServerToClient() {
        return mcpServerProcess.getMessages()
                .doOnNext(message -> logger.debug("Bridging server message to clients: {}", message))
                .onErrorContinue((error, obj) -> {
                    logger.error("Error bridging server message to client: {}", obj, error);
                });
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
     * Manual method to start server-to-client bridging.
     * Can be called when HTTP Streamable clients connect.
     *
     * @return Flux of messages
     */
    public Flux<String> startBridging() {
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
                "bridgeStatus", isHealthy() ? "healthy" : "unhealthy"
        );
    }
}