# MCP Protocol Adapter Design & Implementation Plan

## Overview

This document outlines the design and implementation plan for creating a Protocol Adapter that bridges SSE (Server-Sent Events) transport to STDIO transport for the GitHub MCP Server. The solution will be packaged as a Docker image suitable for Cloud Foundry deployment.

## Architecture Overview

```
┌─────────────────┐         SSE          ┌──────────────────┐      STDIO      ┌─────────────────┐
│   MCP Client    │ ◄─────────────────► │Protocol Adapter  │ ◄────────────► │GitHub MCP Server│
│  (SSE Transport)│      HTTP/SSE        │  (Spring Boot)   │    Process     │ (STDIO Transport)│
└─────────────────┘                      └──────────────────┘                └─────────────────┘
```

## Component Design

### 1. Protocol Adapter (Spring Boot Application)

#### 1.1 Core Components

**A. SSE Endpoint Controller**
- Implements the MCP SSE transport specification
- Handles HTTP POST requests for client-to-server messages
- Provides SSE endpoint for server-to-client messages
- Routes: `/sse` (SSE connection) and `/message` (message posting)

**B. STDIO Process Manager**
- Manages the lifecycle of the GitHub MCP Server process
- Handles process creation, monitoring, and cleanup
- Implements buffered I/O for stdin/stdout communication

**C. Message Bridge**
- Translates between SSE transport format and STDIO transport format
- Handles JSON-RPC message serialization/deserialization
- Manages request/response correlation

**D. Session Manager**
- Maintains SSE client connections
- Handles connection lifecycle and cleanup
- Manages message routing to appropriate clients

#### 1.2 Key Classes and Interfaces

```java
// Main application
@SpringBootApplication
public class McpProtocolAdapterApplication

// SSE Controller
@RestController
@RequestMapping("/")
public class McpSseController {
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> connectSse()
    
    @PostMapping("/message")
    public Mono<ResponseEntity<Void>> sendMessage(@RequestBody String message)
}

// Process management
@Component
public class GithubMcpServerProcess {
    public void start()
    public void stop()
    public void sendMessage(String message)
    public Flux<String> getMessages()
}

// Message bridging
@Component
public class MessageBridge {
    public Mono<Void> bridgeClientToServer(String clientMessage)
    public Flux<ServerSentEvent<String>> bridgeServerToClient()
}

// Session management
@Component
public class SseSessionManager {
    public String createSession()
    public void removeSession(String sessionId)
    public void sendToSession(String sessionId, String message)
}
```

### 2. Docker Image Structure

#### 2.1 Two-Stage Docker Architecture

The project now uses a two-stage Docker architecture that separates the generic protocol adapter from specific MCP server implementations:

**Stage 1: Base Adapter Image (`Dockerfile.base`)**
- **Purpose**: Reusable protocol adapter that works with any STDIO MCP server
- **Base**: eclipse-temurin:21-jre-alpine
- **Contents**:
  - Spring Boot application (protocol-adapter.jar)
  - Generic startup script with validation
  - Configuration system for any MCP server
  - Health checks and monitoring
  - Security hardening (non-root user)
- **Image**: `your-registry/mcp-protocol-adapter-base:latest`

**Stage 2: GitHub-Specific Image (`Dockerfile.github`)**
- **Purpose**: Extends base image with GitHub MCP Server implementation
- **Base**: Inherits from base adapter image
- **Contents**:
  - GitHub MCP Server binary (compiled from Go source)
  - GitHub-specific configuration defaults
  - GitHub-specific validation and startup logic
  - Environment variable passthrough for GitHub authentication
- **Image**: `your-registry/github-mcp-adapter:latest`

#### 2.2 Architecture Benefits

```
┌─────────────────────────────────────┐
│           Specific Images           │
│  ┌─────────────┐ ┌─────────────┐   │
│  │   GitHub    │ │   OpenAI    │   │
│  │ MCP Adapter │ │ MCP Adapter │   │
│  └─────────────┘ └─────────────┘   │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│         Base Adapter Image          │
│      (Generic Protocol Adapter)     │
│   Spring Boot + SSE ↔ STDIO Bridge  │
└─────────────────────────────────────┘
```

- **Reusability**: Base image can support any MCP server with STDIO transport
- **Maintainability**: Protocol adapter updates benefit all server implementations
- **Flexibility**: Easy to create new MCP server adapters by extending the base
- **Security**: Consistent security hardening across all implementations

#### 2.3 Directory Structure in Container

**Base Image (`/app/`):**
```
/app/
├── protocol-adapter.jar       # Spring Boot application
├── application.yml            # Base configuration
├── start.sh                  # Generic startup script
├── bin/                      # Directory for MCP server binaries
└── (owned by appuser:appuser)
```

**GitHub Image (Additional Contents):**
```
/app/
├── bin/github-mcp-server     # GitHub MCP Server binary
├── github-config.yml         # GitHub-specific configuration
├── start-github.sh           # GitHub startup script
└── (all files owned by appuser:appuser)
```

## Implementation Details

### 1. SSE Transport Implementation

#### 1.1 Connection Flow

1. Client connects to `/sse` endpoint
2. Protocol Adapter establishes SSE connection
3. Sends initial 'endpoint' event with message posting URL
4. Maintains connection for server-to-client messages

#### 1.2 Message Flow

**Client to Server:**
1. Client POSTs JSON-RPC message to `/message`
2. Protocol Adapter validates and forwards to STDIO process
3. Returns HTTP 204 No Content

**Server to Client:**
1. GitHub MCP Server writes to stdout
2. Protocol Adapter reads and parses message
3. Sends as SSE 'message' event to connected clients

### 2. STDIO Process Management

#### 2.1 Process Lifecycle

1. **Startup:**
   - Launch github-mcp-server with 'stdio' argument
   - Configure process I/O streams
   - Set up error handling and monitoring

2. **Runtime:**
   - Monitor process health
   - Handle process crashes with restart logic
   - Implement backpressure for message queuing

3. **Shutdown:**
   - Graceful shutdown on SIGTERM
   - Clean up resources and connections

#### 2.2 I/O Handling

- Use buffered readers/writers for efficiency
- Implement proper line-based protocol handling
- Handle partial reads and message boundaries

### 3. Error Handling and Resilience

#### 3.1 Connection Resilience
- Automatic reconnection for dropped SSE connections
- Message buffering during connection interruptions
- Timeout handling for unresponsive clients

#### 3.2 Process Resilience
- Automatic restart of crashed GitHub MCP Server
- Circuit breaker pattern for repeated failures
- Health check endpoints for monitoring

### 4. Cloud Foundry Considerations

#### 4.1 Port Configuration
- Use `PORT` environment variable
- Default to 8080 if not set
- Configure Spring Boot server.port dynamically

#### 4.2 Memory and Resource Limits
- Configure JVM memory settings
- Set appropriate container memory limits
- Implement resource monitoring

#### 4.3 Logging
- Use Cloud Foundry-compatible logging
- JSON-structured logs for better parsing
- Appropriate log levels for production

## Configuration

### 1. Environment Variables

```bash
# Required
GITHUB_PERSONAL_ACCESS_TOKEN=<token>

# Optional
PORT=8080                           # HTTP port (CF will set this)
GITHUB_HOST=https://github.com      # GitHub host
GITHUB_TOOLSETS=all                 # Enabled toolsets
LOG_LEVEL=INFO                      # Logging level
MAX_SSE_CONNECTIONS=100             # Max concurrent SSE connections
MESSAGE_BUFFER_SIZE=1000            # Message queue size
PROCESS_RESTART_DELAY_MS=5000       # Delay before restarting crashed process
```

### 2. Spring Boot Configuration (application.yml)

```yaml
server:
  port: ${PORT:8080}
  
spring:
  webflux:
    base-path: /
    
logging:
  level:
    root: ${LOG_LEVEL:INFO}
    
mcp:
  github:
    executable: ./github-mcp-server
    args: 
      - stdio
    environment:
      GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN}
      GITHUB_HOST: ${GITHUB_HOST:https://github.com}
      GITHUB_TOOLSETS: ${GITHUB_TOOLSETS:all}
  sse:
    max-connections: ${MAX_SSE_CONNECTIONS:100}
    message-buffer-size: ${MESSAGE_BUFFER_SIZE:1000}
  process:
    restart-delay-ms: ${PROCESS_RESTART_DELAY_MS:5000}
```

## Security Considerations

1. **Authentication:**
   - Pass through GitHub PAT securely
   - Consider adding adapter-level authentication
   - Implement rate limiting

2. **Input Validation:**
   - Validate JSON-RPC message format
   - Sanitize message content
   - Prevent injection attacks

3. **Resource Protection:**
   - Limit concurrent connections
   - Implement timeout policies
   - Monitor resource usage

## Testing Strategy

### 1. Unit Tests
- Test message parsing and validation
- Test session management logic
- Test error handling scenarios

### 2. Integration Tests
- Test full message flow (SSE → STDIO → SSE)
- Test process management (start/stop/restart)
- Test connection handling

### 3. End-to-End Tests
- Test with actual MCP clients
- Test Cloud Foundry deployment
- Test performance under load

## Deployment Plan

### 1. Build Process

The build process now uses a two-stage approach with separate Dockerfiles:

#### 1.1 Base Image Build
```bash
# Build the reusable base adapter image
docker buildx build --platform linux/amd64 \
  -f Dockerfile.base \
  -t your-registry/mcp-protocol-adapter-base:latest \
  .
```

#### 1.2 GitHub-Specific Image Build
```bash
# Build the GitHub MCP adapter (extends base image)
docker buildx build --platform linux/amd64 \
  -f Dockerfile.github \
  --build-arg BASE_IMAGE_TAG=latest \
  -t your-registry/github-mcp-adapter:latest \
  .
```

#### 1.3 Automated Build Script
```bash
# Build both images using the provided script
./build-multi-stage.sh all

# Or build individually
./build-multi-stage.sh base
./build-multi-stage.sh github
```

#### 1.4 Push to Registry
```bash
# Push both images
./build-multi-stage.sh push -r your-registry.com

# Or push manually
docker push your-registry/mcp-protocol-adapter-base:latest
docker push your-registry/github-mcp-adapter:latest
```

### 2. Cloud Foundry Deployment

The deployment configuration remains the same, but now uses the GitHub-specific image:

```yaml
applications:
- name: github-mcp-adapter
  memory: 1G
  disk_quota: 512M
  instances: 1
  docker:
    image: your-registry/github-mcp-adapter:latest  # Uses GitHub-specific image
  health-check-type: http
  health-check-http-endpoint: /health
  timeout: 180
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ((github-token))
    GITHUB_HOST: https://github.com
    GITHUB_TOOLSETS: repos,issues,pull_requests,users,code_security,secret_protection,notifications
    LOG_LEVEL: INFO
    MAX_SSE_CONNECTIONS: 100
    MESSAGE_BUFFER_SIZE: 1000
    PROCESS_RESTART_DELAY_MS: 5000
    SPRING_PROFILES_ACTIVE: cloud
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,info,metrics
```

#### 2.1 Deployment Benefits

- **Backward Compatibility**: Existing deployment configurations work unchanged
- **Reduced Image Size**: Only the GitHub-specific functionality is included
- **Security**: GitHub MCP Server binary is compiled from trusted source during image build
- **Flexibility**: Base image can be reused for other MCP server implementations

### 3. Monitoring and Observability

Both base and GitHub-specific images inherit the same monitoring capabilities:

- Health check endpoint: `/health`
- Debug endpoint: `/debug/process` 
- Custom metrics for:
  - Active SSE connections
  - Message throughput
  - Process restarts
  - Error rates

#### 3.1 Health Check Examples
```bash
# Test deployment health
curl http://localhost:8080/health

# Check GitHub MCP Server process status
curl http://localhost:8080/debug/process

# Test SSE connection
curl -N -H "Accept: text/event-stream" http://localhost:8080/sse
```

## Implementation Status

### ✅ Completed

**✅ Phase 1: Core Functionality (COMPLETE)**
   - ✅ Set up Spring Boot project with WebFlux
     - Added `spring-boot-starter-webflux` dependency
     - Created application.yml with MCP configuration
   - ✅ Implement basic SSE endpoints
     - `McpSseController` with `/sse`, `/message`, and `/health` endpoints
     - `SseSessionManager` for connection lifecycle management
     - UUID-based session tracking with automatic cleanup
     - Initial endpoint event with MCP protocol capabilities
   - ✅ Implement STDIO process management
     - `GithubMcpServerProcess` for process lifecycle management
     - Automatic process startup/shutdown with environment variables
     - Buffered I/O for stdin/stdout communication
     - Reactive output streaming with automatic restart capability
   - ✅ Basic message bridging
     - `MessageBridge` for protocol translation between SSE and STDIO
     - JSON-RPC message validation
     - Bidirectional message flow (Client ↔ SSE ↔ STDIO ↔ Server)
     - Error handling and logging

**Implemented Classes:**
```java
// SSE Controller - handles HTTP/SSE endpoints
@RestController
@RequestMapping("/")
public class McpSseController {
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> connectSse()
    
    @PostMapping("/message")  
    public Mono<ResponseEntity<Void>> sendMessage(@RequestBody String message)
    
    @GetMapping("/health")
    public Mono<Map<String, Object>> health()
}

// Session Management - handles SSE connection lifecycle
@Component
public class SseSessionManager {
    public String createSession()
    public void removeSession(String sessionId)  
    public void sendToSession(String sessionId, String message)
    public void sendToAllSessions(String message)
    public Flux<String> getSessionFlux(String sessionId)
    public int getActiveSessionCount()
}

// STDIO Process Management - manages GitHub MCP Server process
@Component
public class GithubMcpServerProcess {
    public void start()
    public void stop() 
    public void sendMessage(String message)
    public Flux<String> getMessages()
    public boolean isRunning()
}

// Message Bridge - translates between SSE and STDIO protocols
@Component  
public class MessageBridge {
    public Mono<Void> bridgeClientToServer(String clientMessage)
    public Flux<ServerSentEvent<String>> bridgeServerToClient()
    public boolean isHealthy()
    public void validateJsonRpcMessage(String message)
}
```

**Architecture Flow:**
```
Client SSE Connection → McpSseController → MessageBridge → GithubMcpServerProcess
                                                                      ↓
Client SSE Events    ← SseSessionManager ← MessageBridge ← GitHub MCP Server (STDIO)
```

**Key Features Implemented:**
- **Reactive Design**: Spring WebFlux with Reactor for non-blocking I/O
- **Session Management**: UUID-based SSE session tracking with automatic cleanup
- **Process Management**: GitHub MCP Server lifecycle management with environment variables
- **Error Handling**: JSON-RPC validation and process restart capabilities
- **Health Monitoring**: Real-time status of SSE connections and STDIO process
- **Message Validation**: Comprehensive JSON-RPC format validation
- **Configuration**: YAML-based configuration with environment variable support

**Dependencies Added:**
- `spring-boot-starter-webflux` - Reactive web framework
- `jackson-databind` - JSON processing
- Configuration path updated to use `bin/github-mcp-server`

### 🚧 Next Steps

1. **Implementation Phase 2: Resilience & Error Handling**
   - Add connection management
   - Implement process monitoring and restart
   - Add comprehensive error handling

2. **Implementation Phase 3: Production Readiness**
   - Add authentication/authorization
   - Implement monitoring and metrics
   - Performance optimization
   - Documentation

3. **Implementation Phase 4: Testing & Deployment**
   - Comprehensive test suite
   - Docker image optimization
   - Cloud Foundry deployment testing
   - Load testing and tuning

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Process crashes | Service unavailability | Automatic restart with exponential backoff |
| Memory leaks | Service degradation | Resource monitoring and automatic recycling |
| Message loss | Data integrity issues | Message acknowledgment and buffering |
| Concurrent connection overload | Performance degradation | Connection limiting and queueing |
| Security vulnerabilities | Data breach | Regular security updates and input validation |

## Conclusion

This design provides a robust bridge between SSE and STDIO transports for the GitHub MCP Server, suitable for Cloud Foundry deployment. The Spring Boot-based Protocol Adapter handles the complexity of protocol translation while providing production-ready features like monitoring, error handling, and scalability.