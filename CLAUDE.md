# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot 3.5.4 application that serves as a Protocol Adapter for bridging SSE (Server-Sent Events) transport to STDIO transport for the GitHub MCP Server. The application is designed for Cloud Foundry deployment as a Docker image.

## Architecture

The adapter acts as a bridge between MCP clients using SSE transport and the GitHub MCP Server using STDIO transport:

```
MCP Client (SSE) ↔ Protocol Adapter (Spring Boot) ↔ GitHub MCP Server (STDIO)
```

Key components (fully implemented):
- **SSE Endpoint Controller** (`McpSseController`): Handles HTTP/SSE connections at `/sse` and `/message` endpoints
- **MCP Server Process Manager** (`McpServerProcess`): Manages GitHub MCP Server process lifecycle
- **Message Bridge** (`MessageBridge`): Translates between SSE and STDIO message formats
- **Session Manager** (`SseSessionManager`): Handles SSE client connections and routing
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
  github:
    executable: ./bin/github-mcp-server    # Path to GitHub MCP Server binary
    args: ["stdio"]                        # Arguments for the server
    environment:
      GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN}
      GITHUB_HOST: ${GITHUB_HOST:https://github.com}
      GITHUB_TOOLSETS: ${GITHUB_TOOLSETS:repos,issues,pull_requests,users,code_security,secret_protection,notifications}
  sse:
    max-connections: ${MAX_SSE_CONNECTIONS:100}
    message-buffer-size: ${MESSAGE_BUFFER_SIZE:1000}
  process:
    restart-delay-ms: ${PROCESS_RESTART_DELAY_MS:5000}
```

## Project Structure

```
src/
├── main/java/org/tanzu/adapter/
│   ├── AdapterApplication.java          # Main Spring Boot application
│   ├── McpSseController.java           # SSE endpoints (/sse, /message, /health)
│   ├── McpServerProcess.java           # MCP Server process management
│   ├── MessageBridge.java              # Protocol translation and validation
│   ├── SseSessionManager.java          # SSE session lifecycle management
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
- **`McpSseController`**: REST endpoints for SSE connections, message handling, and health checks
- **`McpServerProcess`**: Complete process lifecycle management with MCP STDIO compliance
- **`MessageBridge`**: Full JSON-RPC validation and bidirectional message translation
- **`SseSessionManager`**: UUID-based session management with reactive streams
- **Configuration Classes**: Centralized configuration management and environment variable processing

### Key Features
- **Reactive Design**: Spring WebFlux with non-blocking I/O
- **MCP STDIO Compliance**: Proper newline escaping and UTF-8 encoding
- **JSON-RPC Validation**: Complete message format validation
- **Process Management**: Automatic restart capabilities
- **Health Monitoring**: Real-time status endpoints
- **Session Management**: UUID-based SSE connection tracking

## API Endpoints

- **`GET /sse`** - Server-Sent Events connection endpoint
- **`POST /message`** - JSON-RPC message submission
- **`GET /health`** - Application health status
- **`GET /debug/process`** - GitHub MCP Server process status

## Environment Requirements

- Java 21 runtime
- GitHub MCP Server binary at `bin/github-mcp-server`
- Environment variables for GitHub authentication:
  - `GITHUB_PERSONAL_ACCESS_TOKEN` (required)
  - `GITHUB_HOST` (optional, defaults to https://github.com)
  - `GITHUB_TOOLSETS` (optional, defaults to common toolsets)
- Cloud Foundry deployment environment

## Testing

The application provides health endpoints for testing:
```bash
# Test health
curl http://localhost:8080/health

# Test SSE connection
curl -N -H "Accept: text/event-stream" http://localhost:8080/sse

# Send JSON-RPC message
curl -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}' \
  http://localhost:8080/message
```

## Deployment

The application is designed for Cloud Foundry deployment using Docker. See `manifest.yml` and deployment documentation for complete deployment instructions.

## Key Files for Development

- **Core Logic**: `src/main/java/org/tanzu/adapter/` - All main components
- **Configuration**: `src/main/resources/application.yml` - Main configuration
- **Design Documentation**: `DESIGN.md` - Detailed architecture and implementation details
- **Deployment**: `manifest.yml`, `Dockerfile` - Cloud Foundry deployment configuration