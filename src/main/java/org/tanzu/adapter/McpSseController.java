package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/")
public class McpSseController {

    private static final Logger logger = LoggerFactory.getLogger(McpSseController.class);

    @Autowired
    private SseSessionManager sessionManager;

    @Autowired
    private MessageBridge messageBridge;

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> connectSse(ServerHttpRequest request) {
        String sessionId = sessionManager.createSession();
        logger.info("New SSE connection established with session: {}", sessionId);

        // Check if MCP Server is running
        boolean mcpServerRunning = messageBridge.isHealthy();
        logger.info("MCP Server running status: {}", mcpServerRunning);

        if (!mcpServerRunning) {
            logger.error("MCP Server is not running! SSE connection will likely fail.");
        }

        // Build the correct message endpoint URL from the incoming request
        String messageEndpointUrl = buildMessageEndpointUrl(request);

        return Flux.concat(
                        // Send initial endpoint event with dynamic URL
                        Flux.just(ServerSentEvent.<String>builder()
                                        .event("endpoint")
                                        .data(messageEndpointUrl)
                                        .build())
                                .doOnNext(event -> logger.info("Sent endpoint event to session {}: {}", sessionId, event.data())),

                        // Send session messages
                        sessionManager.getSessionFlux(sessionId)
                                .doOnNext(message -> logger.debug("Sending message to session {}: {}", sessionId, message))
                                .map(message -> ServerSentEvent.<String>builder()
                                        .event("message")
                                        .data(message)
                                        .build())
                )
                .doOnSubscribe(subscription -> {
                    logger.info("SSE stream subscribed for session: {}", sessionId);
                })
                .doOnCancel(() -> {
                    logger.info("SSE connection cancelled for session: {}", sessionId);
                    sessionManager.removeSession(sessionId);
                })
                .doOnTerminate(() -> {
                    logger.info("SSE connection terminated for session: {}", sessionId);
                    sessionManager.removeSession(sessionId);
                })
                .doOnError(error -> {
                    logger.error("SSE connection error for session: " + sessionId, error);
                    sessionManager.removeSession(sessionId);
                })
                .doOnComplete(() -> {
                    logger.info("SSE connection completed for session: {}", sessionId);
                    sessionManager.removeSession(sessionId);
                });
    }

    @PostMapping("/message")
    public Mono<ResponseEntity<Void>> sendMessage(@RequestBody String message) {
        logger.info("Received message: {}", message);

        // Check if MCP Server is running before processing
        if (!messageBridge.isHealthy()) {
            logger.error("Cannot process message - MCP Server is not running");
            return Mono.just(ResponseEntity.status(503).build()); // Service Unavailable
        }

        return messageBridge.bridgeClientToServer(message)
                .doOnSuccess(v -> logger.debug("Successfully bridged message to server"))
                .doOnError(error -> logger.error("Failed to bridge message", error))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()))
                .onErrorReturn(ResponseEntity.badRequest().<Void>build());
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        Map<String, Object> healthInfo = Map.of(
                "status", messageBridge.isHealthy() ? "UP" : "DOWN",
                "activeSessions", sessionManager.getActiveSessionCount(),
                "mcpServerRunning", messageBridge.isHealthy(),
                "detailedHealth", messageBridge.getHealthInfo()
        );

        logger.debug("Health check result: {}", healthInfo);
        return Mono.just(healthInfo);
    }

    // Add debugging endpoint to check MCP Server status
    @GetMapping("/debug/process")
    public Mono<Map<String, Object>> debugProcess() {
        return Mono.just(Map.of(
                "mcpServerRunning", messageBridge.isHealthy(),
                "activeSessionCount", sessionManager.getActiveSessionCount(),
                "healthInfo", messageBridge.getHealthInfo()
        ));
    }

    /**
     * Builds the correct message endpoint URL based on the incoming request.
     * This ensures the URL works properly in Cloud Foundry and other deployment environments.
     *
     * @param request The incoming HTTP request
     * @return The full URL to the /message endpoint
     */
    private String buildMessageEndpointUrl(ServerHttpRequest request) {
        URI requestUri = request.getURI();

        // Get scheme (http/https)
        String scheme = requestUri.getScheme();
        if (scheme == null) {
            // Default to https for Cloud Foundry
            scheme = "https";
        }

        // Get host and port
        String host = requestUri.getHost();
        int port = requestUri.getPort();

        // Build the base URL
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(scheme).append("://").append(host);

        // Only include port if it's not the default port for the scheme
        if (port != -1 &&
                !((scheme.equals("http") && port == 80) ||
                        (scheme.equals("https") && port == 443))) {
            urlBuilder.append(":").append(port);
        }

        // Add the message endpoint path
        urlBuilder.append("/message");

        String messageUrl = urlBuilder.toString();
        logger.info("Built message endpoint URL: {}", messageUrl);

        return messageUrl;
    }
}