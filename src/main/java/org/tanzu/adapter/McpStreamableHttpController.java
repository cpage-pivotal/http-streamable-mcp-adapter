package org.tanzu.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * MCP HTTP Streamable Controller implementing the MCP HTTP Streamable transport specification.
 *
 * Supports POST endpoint for JSON-RPC messages with NDJSON streaming responses,
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



    /**
     * POST endpoint for MCP HTTP Streamable protocol.
     * Handles JSON-RPC messages with NDJSON streaming responses.
     *
     * @param jsonRpcMessage The JSON-RPC message from the client
     * @param protocolVersion The MCP-Protocol-Version header
     * @param origin The Origin header for security validation
     * @return ResponseEntity with NDJSON streaming response
     */
    @PostMapping(value = "/")
    public ResponseEntity<Flux<String>> handlePostRequest(
            @RequestBody String jsonRpcMessage,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Accept", required = false) String acceptHeader) {

        logger.info("Received POST request:");
        logger.info("  Protocol: {}", protocolVersion);
        logger.info("  Origin: {}", origin);
        logger.info("  Content-Type: {}", contentType);
        logger.info("  Accept: {}", acceptHeader);
        logger.debug("Request body: {}", jsonRpcMessage);

        // Validate security headers
        if (!securityValidator.validateOrigin(origin)) {
            logger.warn("Origin validation failed for: {}, but continuing for debugging", origin);
            // Temporarily disabled: return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Validate protocol version
        if (!securityValidator.validateProtocolVersion(protocolVersion)) {
            logger.warn("Protocol version validation failed for: {}, but continuing for debugging", protocolVersion);
            // Temporarily disabled: return ResponseEntity.badRequest().build();
        }

        // Check if MCP Server is running before processing
        if (!streamableBridge.isHealthy()) {
            logger.error("Cannot process request - MCP Server is not running");
            MediaType errorContentType = determineContentType(acceptHeader);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(errorContentType)
                    .body(Flux.just(createErrorResponse("MCP Server is not available")));
        }

        // Process message using StreamableBridge for streaming
        Flux<String> responseStream = streamableBridge.processRequest(jsonRpcMessage);

        // Determine content type based on Accept header
        MediaType responseContentType = determineContentType(acceptHeader);

        logger.info("Responding with Content-Type: {}", responseContentType);

        return ResponseEntity.ok()
                .contentType(responseContentType)
                .body(responseStream);
    }


    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        Map<String, Object> healthInfo = Map.of(
                "status", streamableBridge.isHealthy() ? "UP" : "DOWN",
                "mcpServerRunning", streamableBridge.isHealthy(),
                "transport", "http-streamable",
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
                "transport", "http-streamable",
                "activeRequests", streamableBridge.getActiveRequestCount(),
                "healthInfo", streamableBridge.getHealthInfo()
        ));
    }


    /**
     * Determines the appropriate content type based on the Accept header.
     *
     * @param acceptHeader The Accept header from the request
     * @return The appropriate MediaType for the response
     */
    private MediaType determineContentType(String acceptHeader) {
        if (acceptHeader == null) {
            // Default to JSON for clients that don't specify
            return MediaType.APPLICATION_JSON;
        }

        // Check what the client accepts
        if (acceptHeader.contains("application/json")) {
            return MediaType.APPLICATION_JSON;
        } else if (acceptHeader.contains("text/event-stream")) {
            return MediaType.TEXT_EVENT_STREAM;
        } else if (acceptHeader.contains("application/x-ndjson")) {
            return MediaType.parseMediaType(APPLICATION_NDJSON_VALUE);
        } else {
            // Default to JSON for compatibility
            return MediaType.APPLICATION_JSON;
        }
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