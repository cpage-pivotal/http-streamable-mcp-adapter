# GitHub MCP Protocol Adapter

A Spring Boot application that bridges Server-Sent Events (SSE) transport to STDIO transport for the GitHub MCP Server, enabling seamless integration with Model Context Protocol (MCP) clients in Cloud Foundry environments.

## 🚀 Quick Start

This adapter allows MCP clients to communicate with GitHub repositories through a familiar HTTP/SSE interface while internally managing the GitHub MCP Server process over STDIO.

### Prerequisites

- **GitHub Personal Access Token** with appropriate permissions
- **Cloud Foundry environment** or Docker runtime
- **Access to container registry** (Docker Hub, Harbor, etc.)

### 🏃‍♂️ 30-Second Deploy

```bash
# 1. Build the Docker image
docker buildx build --platform linux/amd64 -t your-registry/github-mcp-adapter:latest .

# 2. Push to registry
docker push your-registry/github-mcp-adapter:latest

# 3. Deploy to Cloud Foundry
cf push -f manifest.yml

# 4. Set your GitHub token
cf set-env github-mcp-adapter GITHUB_PERSONAL_ACCESS_TOKEN "your-github-token"
cf restart github-mcp-adapter

# 5. Test the deployment
curl https://your-app.apps.your-domain.com/health
```

## 🏗️ Architecture

```
┌─────────────────┐         SSE          ┌──────────────────┐      STDIO      ┌─────────────────┐
│   MCP Client    │ ◄─────────────────► │Protocol Adapter  │ ◄────────────► │GitHub MCP Server│
│  (SSE Transport)│      HTTP/SSE        │  (Spring Boot)   │    Process     │ (STDIO Transport)│
└─────────────────┘                      └──────────────────┘                └─────────────────┘
```

The adapter serves as a protocol bridge, translating between:
- **Client Side**: HTTP/SSE for web-friendly integration
- **Server Side**: STDIO for direct GitHub MCP Server communication

## 🌟 Key Features

- **🔄 Protocol Translation**: Seamless SSE ↔ STDIO message bridging
- **📡 Real-time Communication**: Server-Sent Events for live updates  
- **🛡️ Process Management**: Automatic GitHub MCP Server lifecycle management
- **✅ Health Monitoring**: Built-in health checks and process status
- **📋 JSON-RPC Validation**: Comprehensive message format validation
- **☁️ Cloud Ready**: Optimized for Cloud Foundry deployment
- **🔐 Secure**: Non-root container execution with proper token handling

## 📊 API Endpoints

### Core Endpoints

- **`GET /sse`** - Server-Sent Events connection endpoint
- **`POST /message`** - JSON-RPC message submission
- **`GET /health`** - Application health status
- **`GET /debug/process`** - GitHub MCP Server process status

### Example Usage

#### Connect to SSE Stream
```bash
curl -N -H "Accept: text/event-stream" \
  https://your-app.com/sse
```

#### Send JSON-RPC Message
```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}},"id":1}' \
  https://your-app.com/message
```

#### Check Health
```bash
curl https://your-app.com/health
# Returns: {"status":"UP","activeSessions":1,"githubMcpServerRunning":true}
```

## ⚙️ Configuration

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `GITHUB_PERSONAL_ACCESS_TOKEN` | GitHub PAT with repo access | `ghp_xxxxxxxxxxxx` |

### Optional Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GITHUB_HOST` | `https://github.com` | GitHub instance URL |
| `GITHUB_TOOLSETS` | `repos,issues,pull_requests,users,code_security,secret_protection,notifications` | Enabled MCP toolsets |
| `LOG_LEVEL` | `INFO` | Logging level |
| `MAX_SSE_CONNECTIONS` | `100` | Maximum concurrent SSE connections |
| `MESSAGE_BUFFER_SIZE` | `1000` | Message queue buffer size |
| `PROCESS_RESTART_DELAY_MS` | `5000` | Delay before restarting failed processes |

### GitHub Token Permissions

Your GitHub Personal Access Token needs these minimum permissions:

- **`repo`** - Repository operations (read/write access to repositories)
- **`read:packages`** - Package read access (if using GitHub packages)
- **Additional permissions** based on your selected toolsets

Create a token at: [GitHub Settings → Personal Access Tokens](https://github.com/settings/personal-access-tokens/new)

## 🚀 Deployment Options

### Option 1: Cloud Foundry (Recommended)

```bash
# Update manifest.yml with your registry and domain
cf push -f manifest.yml
cf set-env github-mcp-adapter GITHUB_PERSONAL_ACCESS_TOKEN "your-token"
cf restart github-mcp-adapter
```

### Option 2: Docker

```bash
docker run -d \
  -p 8080:8080 \
  -e GITHUB_PERSONAL_ACCESS_TOKEN="your-token" \
  -e GITHUB_TOOLSETS="repos,issues,pull_requests" \
  your-registry/github-mcp-adapter:latest
```

### Option 3: Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: github-mcp-adapter
spec:
  replicas: 1
  selector:
    matchLabels:
      app: github-mcp-adapter
  template:
    metadata:
      labels:
        app: github-mcp-adapter
    spec:
      containers:
      - name: adapter
        image: your-registry/github-mcp-adapter:latest
        ports:
        - containerPort: 8080
        env:
        - name: GITHUB_PERSONAL_ACCESS_TOKEN
          valueFrom:
            secretKeyRef:
              name: github-token
              key: token
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
```

## 🔍 Monitoring & Troubleshooting

### Health Checks

```bash
# Basic health
curl https://your-app.com/health

# Detailed process status  
curl https://your-app.com/debug/process

# Example healthy response:
{
  "status": "UP",
  "activeSessions": 2,
  "githubMcpServerRunning": true,
  "detailedHealth": {
    "githubMcpServerRunning": true,
    "activeSessionCount": 2,
    "bridgeStatus": "healthy"
  }
}
```

### Common Issues

#### ❌ GitHub MCP Server Not Starting
```bash
# Check token permissions
curl -H "Authorization: token YOUR_TOKEN" https://api.github.com/user

# Verify environment variable is set
cf env github-mcp-adapter | grep GITHUB_PERSONAL_ACCESS_TOKEN

# Check process logs
cf logs github-mcp-adapter | grep "GithubMcpServerProcess"
```

#### ❌ SSE Connections Failing
```bash
# Test SSE endpoint directly
curl -N -H "Accept: text/event-stream" https://your-app.com/sse

# Check bridge status
curl https://your-app.com/debug/process

# Verify message endpoint URL in SSE stream
```

#### ❌ Docker Build Issues
```bash
# Use correct architecture targeting
docker buildx build --platform linux/amd64 -t your-registry/github-mcp-adapter:latest .

# Verify binary architecture in container
docker run --rm your-registry/github-mcp-adapter:latest file /app/bin/github-mcp-server
```

### Log Analysis

```bash
# Cloud Foundry logs
cf logs github-mcp-adapter --recent
cf logs github-mcp-adapter | grep -E "(ERROR|WARN)"

# Docker logs
docker logs <container-id>

# Key log patterns to look for:
# - "GitHub MCP Server started successfully"
# - "SSE connection established" 
# - "Bridging validated client message"
# - "MessageBridge" component logs
```

## 🛡️ Security Best Practices

1. **Token Security**
   - Never hardcode tokens in configuration files
   - Use Cloud Foundry environment variables or Kubernetes secrets
   - Rotate tokens regularly (quarterly recommended)
   - Use separate tokens for dev/staging/prod environments

2. **Network Security**
   - Enable HTTPS for all external communication
   - Configure appropriate Cloud Foundry security groups
   - Consider VPN access for sensitive environments
   - Monitor and log all API access

3. **Resource Security**
   - Run as non-root user (enforced in container)
   - Set appropriate memory and CPU limits
   - Monitor resource usage and set up alerting
   - Regularly update base images for security patches

## 📈 Scaling

### Cloud Foundry Scaling
```bash
# Horizontal scaling
cf scale github-mcp-adapter -i 3

# Vertical scaling  
cf scale github-mcp-adapter -m 2G -k 1G

# Auto-scaling (if supported)
cf enable-autoscaling github-mcp-adapter
```

### Performance Tuning
- **SSE Connections**: Adjust `MAX_SSE_CONNECTIONS` based on load
- **Memory**: Start with 1GB, monitor and adjust based on usage
- **Process Restart**: Tune `PROCESS_RESTART_DELAY_MS` for your environment
- **Buffer Size**: Increase `MESSAGE_BUFFER_SIZE` for high-throughput scenarios

## 📚 Documentation

- **[DESIGN.md](DESIGN.md)** - Detailed architecture and implementation design
- **[deployment_guide.md](deployment_guide.md)** - Step-by-step deployment instructions
- **[deployment_checklist.md](deployment_checklist.md)** - Quick deployment checklist
- **[CLAUDE.md](CLAUDE.md)** - Development guidance for Claude Code

## 🤝 Contributing

1. **Development Setup**: See [CLAUDE.md](CLAUDE.md) for development environment setup
2. **Architecture**: Review [DESIGN.md](DESIGN.md) for system architecture details
3. **Testing**: Use the provided health endpoints for integration testing
4. **Deployment**: Follow [deployment_guide.md](deployment_guide.md) for deployment procedures

## 📄 License

This project bridges the GitHub MCP Server with SSE transport. Please refer to the GitHub MCP Server project for its licensing terms.

## 🆘 Support

- **Application Issues**: Check application logs via `cf logs` or `docker logs`
- **Health Status**: Monitor `/health` and `/debug/process` endpoints
- **GitHub MCP Server**: Refer to [GitHub MCP Server Documentation](https://github.com/github/github-mcp-server)
- **MCP Protocol**: See [MCP Specification](https://spec.modelcontextprotocol.io/)
- **Spring Boot**: Check [Spring Boot Documentation](https://spring.io/projects/spring-boot)

---

**Ready to deploy?** Start with the [Quick Start](#-quick-start) section above! 🚀