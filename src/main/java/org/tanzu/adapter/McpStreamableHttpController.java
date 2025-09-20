package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * MCP Streamable HTTP Controller implementing the MCP Streamable HTTP transport specification.
 *
 * Supports both GET (for SSE) and POST (for JSON-RPC messages) with proper content negotiation,
 * security validation, and protocol compliance.
 */
@RestController
@RequestMapping("/")
public class McpStreamableHttpController {

    private static final Logger logger = LoggerFactory.getLogger(McpStreamableHttpController.class);

    // Define NDJSON media type for streamable responses
    public static final String APPLICATION_NDJSON_VALUE = "application/x-ndjson";

    @Autowired
    private StreamableBridge streamableBridge;

    @Autowired
    private SecurityValidator securityValidator;

    @Autowired
    private MessageProcessor messageProcessor;

    @Autowired
    private SessionManager sessionManager;

    /**
     * POST endpoint for MCP Streamable HTTP protocol.
     * Handles JSON-RPC messages with content negotiation (JSON vs SSE).
     *
     * @param jsonRpcMessage The JSON-RPC message from the client
     * @param acceptHeader The Accept header for content negotiation
     * @param protocolVersion The MCP-Protocol-Version header
     * @param sessionId The Mcp-Session-Id header for SSE sessions
     * @param origin The Origin header for security validation
     * @return ResponseEntity with appropriate content type and status
     */
    @PostMapping(value = "/")
    public ResponseEntity<?> handlePostRequest(
            @RequestBody String jsonRpcMessage,
            @RequestHeader(value = "Accept", defaultValue = "application/json") String acceptHeader,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "Origin", required = false) String origin) {

        logger.info("Received POST request with Accept: {}, Protocol: {}, Session: {}",
                    acceptHeader, protocolVersion, sessionId);

        // Validate security headers
        if (!securityValidator.validateOrigin(origin)) {
            logger.warn("Origin validation failed for: {}", origin);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Validate protocol version
        if (!securityValidator.validateProtocolVersion(protocolVersion)) {
            logger.warn("Protocol version validation failed for: {}", protocolVersion);
            return ResponseEntity.badRequest().build();
        }

        // Check if MCP Server is running before processing
        if (!streamableBridge.isHealthy()) {
            logger.error("Cannot process request - MCP Server is not running");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorResponse("MCP Server is not available"));
        }

        // Process message using MessageProcessor with content negotiation
        return messageProcessor.processMessage(jsonRpcMessage, acceptHeader, sessionId);
    }

    /**
     * GET endpoint for Server-Sent Events (SSE) connections.
     * Used for server-to-client communication in MCP protocol.
     *
     * @param acceptHeader The Accept header (must include text/event-stream)
     * @param protocolVersion The MCP-Protocol-Version header
     * @param sessionId The Mcp-Session-Id header (optional, will create if not provided)
     * @param lastEventId The Last-Event-ID header for SSE resumption
     * @param origin The Origin header for security validation
     * @return Flux of ServerSentEvent for streaming
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> handleGetRequest(
            @RequestHeader(value = "Accept", defaultValue = "text/event-stream") String acceptHeader,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestHeader(value = "Origin", required = false) String origin) {

        logger.info("Received GET request for SSE connection with Session: {}, Last-Event-ID: {}",
                    sessionId, lastEventId);

        // Validate security headers
        if (!securityValidator.validateOrigin(origin)) {
            logger.warn("Origin validation failed for: {}", origin);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Validate protocol version
        if (!securityValidator.validateProtocolVersion(protocolVersion)) {
            logger.warn("Protocol version validation failed for: {}", protocolVersion);
            return ResponseEntity.badRequest().build();
        }

        // Validate Accept header for SSE
        if (!acceptHeader.contains("text/event-stream")) {
            logger.warn("Invalid Accept header for GET request: {}", acceptHeader);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header("Allow", "POST")
                    .build();
        }

        // Check if MCP Server is running
        if (!streamableBridge.isHealthy()) {
            logger.error("Cannot establish SSE connection - MCP Server is not running");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // Create or validate session
        if (sessionId == null || !sessionManager.isValidSession(sessionId)) {
            sessionId = sessionManager.createSession();
            logger.info("Created new SSE session: {}", sessionId);
        }

        // Create SSE stream
        Flux<ServerSentEvent<String>> sseStream = createServerToClientSseStream(sessionId, lastEventId);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .header("Mcp-Session-Id", sessionId)
                .body(sseStream);
    }

    /**
     * Health check endpoint (unchanged from SSE version)
     */
    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        Map<String, Object> healthInfo = Map.of(
                "status", streamableBridge.isHealthy() ? "UP" : "DOWN",
                "mcpServerRunning", streamableBridge.isHealthy(),
                "transport", "streamable-http",
                "activeSessions", sessionManager.getActiveSessionCount(),
                "detailedHealth", streamableBridge.getHealthInfo()
        );

        logger.debug("Health check result: {}", healthInfo);
        return Mono.just(healthInfo);
    }

    /**
     * Debug endpoint for MCP Server process status
     */
    @GetMapping("/debug/process")
    public Mono<Map<String, Object>> debugProcess() {
        return Mono.just(Map.of(
                "mcpServerRunning", streamableBridge.isHealthy(),
                "transport", "streamable-http",
                "activeSessions", sessionManager.getActiveSessionCount(),
                "activeRequests", streamableBridge.getActiveRequestCount(),
                "healthInfo", streamableBridge.getHealthInfo()
        ));
    }

    /**
     * Debug endpoint for session information
     */
    @GetMapping("/debug/sessions")
    public Mono<Map<String, Object>> debugSessions() {
        return Mono.just(Map.of(
                "activeSessionCount", sessionManager.getActiveSessionCount(),
                "cleanupExpiredSessions", sessionManager.cleanupExpiredSessions()
        ));
    }

    /**
     * Creates an SSE stream for server-to-client communication.
     *
     * @param sessionId The session ID
     * @param lastEventId The last event ID for resumption (optional)
     * @return Flux of ServerSentEvent for streaming
     */
    private Flux<ServerSentEvent<String>> createServerToClientSseStream(String sessionId, String lastEventId) {
        logger.info("Creating SSE stream for session: {}", sessionId);

        // Register session with the session manager
        sessionManager.registerSession(sessionId);

        // Create initial connection event
        ServerSentEvent<String> connectionEvent = ServerSentEvent.<String>builder()
                .id("connection-" + sessionId)
                .event("connection")
                .data(String.format("{\"type\":\"connection\",\"sessionId\":\"%s\",\"timestamp\":%d}",
                        sessionId, System.currentTimeMillis()))
                .build();

        // Get message stream for this session
        Flux<ServerSentEvent<String>> messageStream = sessionManager.getMessageStream(sessionId)
                .map(message -> ServerSentEvent.<String>builder()
                        .id("mcp-" + System.currentTimeMillis())
                        .event("message")
                        .data(message)
                        .build());

        // Combine connection event with message stream
        return Flux.concat(
                Flux.just(connectionEvent),
                messageStream
        ).doOnCancel(() -> {
            logger.info("SSE stream cancelled for session: {}", sessionId);
            sessionManager.terminateSession(sessionId);
        }).doOnComplete(() -> {
            logger.info("SSE stream completed for session: {}", sessionId);
            sessionManager.terminateSession(sessionId);
        }).doOnError(error -> {
            logger.error("SSE stream error for session: {}", sessionId, error);
            sessionManager.terminateSession(sessionId);
        });
    }

    /**
     * Creates a success response in JSON-RPC format.
     *
     * @param message The success message
     * @return JSON-RPC success response
     */
    private String createSuccessResponse(String message) {
        return String.format("{\"jsonrpc\":\"2.0\",\"result\":{\"message\":\"%s\"},\"id\":null}", message);
    }

    /**
     * Creates an error response in JSON-RPC format.
     *
     * @param errorMessage The error message
     * @return JSON-RPC error response
     */
    private String createErrorResponse(String errorMessage) {
        return String.format("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"%s\"},\"id\":null}",
                            errorMessage.replace("\"", "\\\""));
    }
}