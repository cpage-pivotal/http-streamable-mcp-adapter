# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot 3.5.4 application that serves as a Protocol Adapter for bridging HTTP Streamable transport to STDIO transport for MCP Servers. The application is designed for Cloud Foundry deployment as a Docker image.

## Architecture

The adapter acts as a bridge between MCP clients using HTTP Streamable transport and MCP Servers using STDIO transport:

```
MCP Client (HTTP Streamable) ↔ Protocol Adapter (Spring Boot) ↔ MCP Server (STDIO)
```

Key components (fully implemented):
- **HTTP Streamable Controller** (`McpStreamableHttpController`): Handles POST endpoint with NDJSON streaming responses
- **MCP Server Process Manager** (`McpServerProcess`): Manages MCP Server process lifecycle
- **Streamable Bridge** (`StreamableBridge`): Translates between HTTP Streamable and STDIO message formats
- **Request Context Manager** (`RequestContextManager`): Handles request correlation and context management
- **Configuration** (`config/McpServerConfig`, `config/EnvironmentVariableProcessor`): Server configuration and environment processing

## Development Commands

### Build and Run
```bash
# Build the project
./mvnw clean compile

# Run tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=MessageBridgeTest

# Run the application
./mvnw spring-boot:run

# Package as JAR (includes compilation and tests)
./mvnw clean package

# Run packaged JAR
java -jar target/adapter-0.0.1-SNAPSHOT.jar
```

### Maven Wrapper
- Use `./mvnw` (Unix/macOS) or `mvnw.cmd` (Windows) instead of `mvn` commands
- Maven wrapper is included and configured

### Docker Commands
```bash
# Build Docker image (for Cloud Foundry deployment)
docker buildx build --platform linux/amd64 -t your-registry/github-mcp-adapter:latest .

# Run locally with Docker
docker run -d -p 8080:8080 -e GITHUB_PERSONAL_ACCESS_TOKEN="your-token" your-registry/github-mcp-adapter:latest
```

## Technology Stack

- **Java 21** (configured in pom.xml)
- **Spring Boot 3.5.4** with WebFlux for reactive programming
- **Jackson** for JSON processing
- **Reactor** for reactive streams

## Key Dependencies

The project uses Spring WebFlux for reactive web programming and Jackson for JSON processing. Originally designed for MCP (Model Context Protocol) integration with GitHub services.

## Configuration

- Main application properties in `src/main/resources/application.properties`
- Main configuration in `src/main/resources/application.yml`
- Application name: `adapter`
- Environment variables for GitHub authentication and process configuration

### Key Configuration Properties
```yaml
mcp:
  server:
    name: ${MCP_SERVER_NAME}
    description: ${MCP_SERVER_DESCRIPTION}
    executable: ${MCP_SERVER_EXECUTABLE}
    args: ${MCP_SERVER_ARGS}
    working-directory: ${MCP_SERVER_WORKDIR:.}
    process:
      restart-enabled: ${MCP_PROCESS_RESTART_ENABLED:true}
      restart-delay-ms: ${MCP_PROCESS_RESTART_DELAY_MS:5000}
      health-check-enabled: ${MCP_PROCESS_HEALTH_CHECK_ENABLED:true}
      health-check-interval-ms: ${MCP_PROCESS_HEALTH_CHECK_INTERVAL_MS:30000}
      startup-timeout-ms: ${MCP_PROCESS_STARTUP_TIMEOUT_MS:60000}
    validation:
      required-env-vars: []

  streamable:
    endpoint: "/"
    protocol-version: "2025-06-18"
    session-timeout: ${STREAMABLE_SESSION_TIMEOUT:300}s
    max-concurrent-sessions: ${STREAMABLE_MAX_SESSIONS:100}
    response-timeout: ${STREAMABLE_RESPONSE_TIMEOUT:30}s
    max-responses-per-request: ${STREAMABLE_MAX_RESPONSES:100}
    security:
      validate-origin: ${STREAMABLE_VALIDATE_ORIGIN:true}
      allowed-origins: ["localhost", "127.0.0.1"]
      require-protocol-version: ${STREAMABLE_REQUIRE_PROTOCOL:false}
```

## Project Structure

```
src/
├── main/java/org/tanzu/adapter/
│   ├── AdapterApplication.java          # Main Spring Boot application
│   ├── McpStreamableHttpController.java # HTTP Streamable endpoints (POST /, /health)
│   ├── McpServerProcess.java           # MCP Server process management
│   ├── StreamableBridge.java           # HTTP Streamable to STDIO protocol translation
│   ├── RequestContextManager.java      # Request correlation and context management
│   ├── SecurityValidator.java          # Origin and protocol version validation
│   └── config/
│       ├── EnvironmentVariableProcessor.java # Environment variable processing
│       └── McpServerConfig.java        # MCP server configuration
├── main/resources/
│   ├── application.properties           # Basic app configuration
│   └── application.yml                  # Main configuration with MCP settings
└── test/java/org/tanzu/adapter/
    ├── McpServerProcessTest.java        # Process management tests
    └── MessageBridgeTest.java           # Message bridge tests
```

## Implementation Status

✅ **FULLY IMPLEMENTED** - The application is complete and production-ready with:

### Core Components (Implemented)
- **`McpStreamableHttpController`**: REST endpoint for HTTP Streamable transport with NDJSON streaming
- **`McpServerProcess`**: Complete process lifecycle management with MCP STDIO compliance
- **`StreamableBridge`**: Full JSON-RPC validation and bidirectional message translation
- **`RequestContextManager`**: Request correlation and context management for streaming responses
- **`SecurityValidator`**: Origin validation and MCP protocol version checking
- **Configuration Classes**: Centralized configuration management and environment variable processing

### Key Features
- **Reactive Design**: Spring WebFlux with non-blocking I/O
- **MCP STDIO Compliance**: Proper newline escaping and UTF-8 encoding
- **JSON-RPC Validation**: Complete message format validation
- **Process Management**: Automatic restart capabilities
- **Health Monitoring**: Real-time status endpoints
- **HTTP Streamable Protocol**: NDJSON streaming responses with request correlation

## API Endpoints

- **`POST /`** - HTTP Streamable endpoint for JSON-RPC messages with NDJSON streaming responses
- **`GET /health`** - Application health status
- **`GET /debug/process`** - MCP Server process status

## Environment Requirements

- Java 21 runtime
- MCP Server binary (configured via `MCP_SERVER_EXECUTABLE`)
- Environment variables for MCP server configuration:
  - `MCP_SERVER_NAME` - Name of the MCP server
  - `MCP_SERVER_DESCRIPTION` - Description of the MCP server
  - `MCP_SERVER_EXECUTABLE` - Path to the MCP server binary
  - `MCP_SERVER_ARGS` - Arguments for the MCP server (comma-separated)
  - Additional environment variables specific to the MCP server being used
- Cloud Foundry deployment environment

## Testing

The application provides endpoints for testing:
```bash
# Test health
curl http://localhost:8080/health

# Test HTTP Streamable endpoint with NDJSON streaming
curl -X POST -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-06-18" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}' \
  http://localhost:8080/

# Test debug endpoint
curl http://localhost:8080/debug/process
```

## Deployment

The application is designed for Cloud Foundry deployment using Docker. See `manifest.yml` and deployment documentation for complete deployment instructions.

## Key Files for Development

- **Core Logic**: `src/main/java/org/tanzu/adapter/` - All main components
- **Configuration**: `src/main/resources/application.yml` - Main configuration
- **Design Documentation**: `DESIGN.md` - Detailed architecture and implementation details
- **Deployment**: `manifest.yml`, `Dockerfile` - Cloud Foundry deployment configuration