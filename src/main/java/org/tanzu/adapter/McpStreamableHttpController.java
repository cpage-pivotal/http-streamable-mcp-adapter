package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/")
public class McpStreamableHttpController {

    private static final Logger logger = LoggerFactory.getLogger(McpStreamableHttpController.class);

    // Define NDJSON media type for streamable responses
    public static final String APPLICATION_NDJSON_VALUE = "application/x-ndjson";

    @Autowired
    private StreamableBridge streamableBridge;

    /**
     * Main streamable HTTP endpoint for MCP protocol.
     * Accepts JSON-RPC messages and returns streaming NDJSON responses.
     *
     * @param jsonRpcMessage The JSON-RPC message from the client
     * @param request The HTTP request object
     * @param response The HTTP response object for setting headers
     * @return Flux of newline-delimited JSON responses
     */
    @PostMapping(value = "/",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = APPLICATION_NDJSON_VALUE)
    public Flux<String> handleStreamableRequest(
            @RequestBody String jsonRpcMessage,
            ServerHttpRequest request,
            ServerHttpResponse response) {

        logger.info("Received streamable HTTP request: {}", jsonRpcMessage);

        // Configure response headers for streaming
        configureStreamingHeaders(response);

        // Check if MCP Server is running before processing
        if (!streamableBridge.isHealthy()) {
            logger.error("Cannot process request - MCP Server is not running");
            return Flux.just(createErrorResponse("MCP Server is not available"));
        }

        // Process the request and return streaming response
        return processStreamableRequest(jsonRpcMessage)
                .doOnNext(responseMessage -> logger.debug("Streaming response: {}", responseMessage))
                .doOnError(error -> logger.error("Error processing streamable request", error))
                .onErrorResume(error -> Flux.just(createErrorResponse(error.getMessage())));
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
                "healthInfo", streamableBridge.getHealthInfo()
        ));
    }

    /**
     * Configures HTTP response headers for streaming NDJSON content.
     *
     * @param response The HTTP response object
     */
    private void configureStreamingHeaders(ServerHttpResponse response) {
        // Set content type for NDJSON streaming
        response.getHeaders().set("Content-Type", APPLICATION_NDJSON_VALUE);

        // Disable caching for streaming responses
        response.getHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        response.getHeaders().set("Pragma", "no-cache");
        response.getHeaders().set("Expires", "0");

        // Enable chunked transfer encoding implicitly by not setting Content-Length
        // Spring WebFlux will automatically use chunked encoding for Flux responses

        // Set connection header for HTTP/1.1 compatibility
        response.getHeaders().set("Connection", "keep-alive");

        logger.debug("Configured streaming headers for NDJSON response");
    }

    /**
     * Processes the incoming JSON-RPC message and returns a stream of responses.
     * Uses the new StreamableBridge for proper request correlation and streaming.
     *
     * @param jsonRpcMessage The JSON-RPC message to process
     * @return Flux of JSON response strings
     */
    private Flux<String> processStreamableRequest(String jsonRpcMessage) {
        // Use StreamableBridge for full request/response correlation and streaming
        return streamableBridge.processRequest(jsonRpcMessage);
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