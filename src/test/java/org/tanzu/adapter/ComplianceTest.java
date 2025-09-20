package org.tanzu.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compliance tests for MCP Streamable HTTP transport specification.
 * Tests the key requirements outlined in COMPLIANCE.md.
 */
@SpringBootTest
@ActiveProfiles("test")
public class ComplianceTest {

    @Autowired
    private McpStreamableHttpController controller;

    @Test
    void testPostWithNotification_Returns202() {
        String notification = """
            {
                "jsonrpc": "2.0",
                "method": "notifications/message",
                "params": {"text": "Hello"}
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            notification,
            "application/json,text/event-stream",
            "2025-06-18",
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void testPostWithResponse_Returns202() {
        String responseMessage = """
            {
                "jsonrpc": "2.0",
                "result": {"success": true},
                "id": "123"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            responseMessage,
            "application/json",
            "2025-06-18",
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void testGetRequest_ReturnsSSEStream() {
        ResponseEntity<Flux<ServerSentEvent<String>>> response = controller.handleGetRequest(
            "text/event-stream",
            "2025-06-18",
            "session-123",
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst("Mcp-Session-Id")).isNotNull();
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void testGetRequest_WithoutSSEAccept_Returns405() {
        ResponseEntity<?> response = controller.handleGetRequest(
            "application/json",
            "2025-06-18",
            null,
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst("Allow")).contains("POST");
    }

    @Test
    void testOriginValidation_RejectsMaliciousOrigin() {
        ResponseEntity<?> response = controller.handlePostRequest(
            "{}",
            "application/json",
            "2025-06-18",
            null,
            "http://malicious-site.com"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void testOriginValidation_AllowsLocalhost() {
        String validRequest = """
            {
                "jsonrpc": "2.0",
                "method": "initialize",
                "params": {},
                "id": "1"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            validRequest,
            "application/json",
            "2025-06-18",
            null,
            "http://localhost:3000"
        );

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void testOriginValidation_AllowsNullOrigin() {
        String validRequest = """
            {
                "jsonrpc": "2.0",
                "method": "initialize",
                "params": {},
                "id": "1"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            validRequest,
            "application/json",
            "2025-06-18",
            null,
            null // null origin (non-browser client)
        );

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void testProtocolVersionValidation_RejectsUnsupportedVersion() {
        ResponseEntity<?> response = controller.handlePostRequest(
            "{}",
            "application/json",
            "1.0.0", // unsupported version
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testProtocolVersionValidation_AcceptsSupportedVersions() {
        String validRequest = """
            {
                "jsonrpc": "2.0",
                "method": "initialize",
                "params": {},
                "id": "1"
            }
            """;

        // Test 2025-06-18
        ResponseEntity<?> response1 = controller.handlePostRequest(
            validRequest,
            "application/json",
            "2025-06-18",
            null,
            "http://localhost"
        );
        assertThat(response1.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);

        // Test 2025-03-26
        ResponseEntity<?> response2 = controller.handlePostRequest(
            validRequest,
            "application/json",
            "2025-03-26",
            null,
            "http://localhost"
        );
        assertThat(response2.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testProtocolVersionValidation_AllowsNullVersion() {
        String validRequest = """
            {
                "jsonrpc": "2.0",
                "method": "initialize",
                "params": {},
                "id": "1"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            validRequest,
            "application/json",
            null, // null version (default)
            null,
            "http://localhost"
        );

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testContentNegotiation_JSONRequest() {
        String request = """
            {
                "jsonrpc": "2.0",
                "method": "initialize",
                "params": {},
                "id": "1"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            request,
            "application/json", // JSON only
            "2025-06-18",
            null,
            "http://localhost"
        );

        // Should return JSON response (not SSE)
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.ACCEPTED);
        if (response.getHeaders().getContentType() != null) {
            assertThat(response.getHeaders().getContentType()).isNotEqualTo(MediaType.TEXT_EVENT_STREAM);
        }
    }

    @Test
    void testContentNegotiation_SSERequest() {
        String request = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "params": {},
                "id": "1"
            }
            """;

        ResponseEntity<?> response = controller.handlePostRequest(
            request,
            "text/event-stream", // SSE preferred
            "2025-06-18",
            null,
            "http://localhost"
        );

        // Should return SSE response or accept for MCP server not running
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);
        if (response.getStatusCode() == HttpStatus.OK) {
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        }
    }
}