# MCP Protocol Adapter - Deployment Guide

This guide explains how to build and deploy the MCP Protocol Adapter to Cloud Foundry. The adapter bridges SSE (Server-Sent Events) transport to STDIO transport for any MCP server, enabling web-friendly integration with MCP servers that use STDIO communication.

## Prerequisites

- Docker installed and running
- Cloud Foundry CLI installed (`cf` command)
- Access to a container registry (Docker Hub, Harbor, etc.)
- Cloud Foundry space with appropriate permissions
- MCP server binary (e.g., GitHub MCP Server, other STDIO MCP servers)
- Required authentication tokens/credentials for your MCP server

## Quick Deployment

### 1. Build and Push Docker Image

```bash
# Build the Docker image (with correct architecture targeting)
docker buildx build --platform linux/amd64 -t your-registry/mcp-protocol-adapter:latest .

# Push to your container registry
docker push your-registry/mcp-protocol-adapter:latest
```

### 2. Set Environment Variables

```bash
# Set required authentication credentials (adapt to your MCP server)
cf set-env mcp-protocol-adapter GITHUB_PERSONAL_ACCESS_TOKEN "your-token-here"  # For GitHub MCP Server
# cf set-env mcp-protocol-adapter API_KEY "your-api-key"                        # For other MCP servers
# cf set-env mcp-protocol-adapter DATABASE_URL "your-db-url"                    # For database MCP servers

# Configure MCP server settings
cf set-env mcp-protocol-adapter MCP_SERVER_EXECUTABLE "./bin/your-mcp-server"
cf set-env mcp-protocol-adapter MCP_SERVER_ARGS '["stdio"]'
cf set-env mcp-protocol-adapter LOG_LEVEL "INFO"
```

### 3. Deploy to Cloud Foundry

```bash
# Update the image reference in manifest.yml first!
# Edit manifest.yml and replace 'your-registry' with your actual registry

# Deploy the application
cf push -f manifest.yml

# Check deployment status
cf apps
cf logs mcp-protocol-adapter --recent
```

## Adapter Configuration for Different MCP Servers

The adapter can work with any MCP server that supports STDIO transport. Configure it for your specific server:

### Configuration Locations

1. **Environment Variables** (recommended for deployment):
   ```bash
   cf set-env mcp-protocol-adapter MCP_SERVER_EXECUTABLE "./bin/your-server"
   cf set-env mcp-protocol-adapter MCP_SERVER_ARGS '["stdio", "--option"]'
   ```

2. **Application Configuration** (`application.yml`):
   ```yaml
   mcp:
     server:  # Note: This replaces the github-specific config
       executable: "./bin/your-mcp-server"
       args: ["stdio"]
       environment:
         API_KEY: ${API_KEY}
         DATABASE_URL: ${DATABASE_URL}
   ```

3. **Docker Build** - Update Dockerfile to include your MCP server binary

### Common MCP Server Examples

**GitHub MCP Server:**
- Executable: `./bin/github-mcp-server`
- Args: `["stdio"]` 
- Environment: `GITHUB_PERSONAL_ACCESS_TOKEN`, `GITHUB_HOST`, `GITHUB_TOOLSETS`

**Database MCP Server:**
- Executable: `./bin/database-mcp-server`
- Args: `["stdio", "--connection-pool", "10"]`
- Environment: `DATABASE_URL`, `DB_TIMEOUT`

**Custom MCP Server:**
- Executable: `./bin/custom-mcp-server`
- Args: `["stdio", "--config", "/app/config.json"]`
- Environment: Custom variables as needed

### Dockerfile Customization

Update the Dockerfile to include your MCP server:

```dockerfile
# Replace the GitHub MCP Server build stage with your server
FROM golang:1.21-alpine AS mcp-server-build
WORKDIR /build
# Copy your MCP server source or download binary
COPY your-mcp-server-source/ .
RUN go build -o mcp-server ./cmd/server

# In the final stage, copy your server
COPY --from=mcp-server-build /build/mcp-server /app/bin/your-mcp-server
```

## Detailed Instructions

### Step 1: Prepare MCP Server Credentials

**For GitHub MCP Server:**
1. Create a GitHub Personal Access Token at: https://github.com/settings/personal-access-tokens/new
2. Grant required permissions:
   - `repo` - Repository operations
   - `read:packages` - For Docker image access (if using GitHub packages)
   - Additional permissions based on your MCP toolset requirements

**For Other MCP Servers:**
1. Gather required authentication credentials (API keys, database URLs, service tokens)
2. Ensure your MCP server binary supports STDIO transport
3. Verify the server executable path and required arguments

**Security Note:** Store all credentials securely - you'll need them for deployment

### Step 2: Build Docker Image

The Docker image uses a multi-stage build that:
- Includes your MCP server binary (adapt Dockerfile as needed)
- Builds the Protocol Adapter (Spring Boot JAR)
- Creates an optimized runtime image

```bash
# Clone or navigate to your project directory
cd your-project-directory

# Build the image with a version tag (correct architecture)
docker buildx build --platform linux/amd64 -t your-registry/mcp-protocol-adapter:v1.0.0 .

# Also tag as latest
docker tag your-registry/mcp-protocol-adapter:v1.0.0 your-registry/mcp-protocol-adapter:latest

# Push both tags
docker push your-registry/mcp-protocol-adapter:v1.0.0
docker push your-registry/mcp-protocol-adapter:latest
```

### Step 3: Configure Cloud Foundry Manifest

Edit `manifest.yml` and update the following:

1. **Container Registry**: Replace `your-registry` with your actual registry
2. **Domain**: Replace `apps.yourorg.com` with your Cloud Foundry domain
3. **Application Name**: Adjust if needed to avoid conflicts

```yaml
applications:
- name: mcp-protocol-adapter
  docker:
    image: my-registry.com/mcp-protocol-adapter:v1.0.0  # Update this
  routes:
  - route: mcp-protocol-adapter.apps.my-cf-domain.com    # Update this
```

### Step 4: Set Environment Variables (Secure Method)

Use Cloud Foundry's built-in environment variable management:

```bash
# Target your Cloud Foundry space
cf target -o your-org -s your-space

# Configure MCP server (adapt to your server type)
# For GitHub MCP Server:
cf set-env mcp-protocol-adapter GITHUB_PERSONAL_ACCESS_TOKEN "ghp_xxxxxxxxxxxxxxxxxxxx"
cf set-env mcp-protocol-adapter GITHUB_HOST "https://github.com"
cf set-env mcp-protocol-adapter GITHUB_TOOLSETS "repos,issues,pull_requests,users"

# For other MCP servers, set appropriate credentials:
# cf set-env mcp-protocol-adapter API_KEY "your-api-key"
# cf set-env mcp-protocol-adapter DATABASE_URL "postgresql://..."
# cf set-env mcp-protocol-adapter SERVICE_ENDPOINT "https://api.service.com"

# Configure the adapter
cf set-env mcp-protocol-adapter MCP_SERVER_EXECUTABLE "./bin/your-mcp-server"
cf set-env mcp-protocol-adapter MCP_SERVER_ARGS '["stdio"]'
cf set-env mcp-protocol-adapter LOG_LEVEL "INFO"
cf set-env mcp-protocol-adapter MAX_SSE_CONNECTIONS "100"
```

### Step 5: Deploy Application

```bash
# Deploy using the manifest
cf push -f manifest.yml

# Monitor the deployment
cf logs github-mcp-adapter
```

### Step 6: Verify Deployment

```bash
# Check application status
cf app mcp-protocol-adapter

# Test health endpoint
curl https://your-app-route.apps.your-domain.com/health

# View logs
cf logs mcp-protocol-adapter --recent
```

## Environment Configurations

### Development Environment

```bash
cf push mcp-protocol-adapter-dev -f manifest.yml --var environment=dev
```

### Production Environment

```bash
cf push mcp-protocol-adapter-prod -f manifest.yml --var environment=prod
```

## Troubleshooting

### Common Issues

1. **Image Pull Errors**
   ```bash
   # Verify image exists
   docker pull your-registry/mcp-protocol-adapter:latest
   
   # Check Cloud Foundry can access your registry
   cf set-env mcp-protocol-adapter DOCKER_REGISTRY_URL "your-registry-url"
   ```

2. **Authentication Issues**
   ```bash
   # Verify credentials are set (adapt to your MCP server)
   cf env mcp-protocol-adapter | grep GITHUB_PERSONAL_ACCESS_TOKEN  # For GitHub
   cf env mcp-protocol-adapter | grep API_KEY                       # For API-based servers
   
   # Test credentials (example for GitHub)
   curl -H "Authorization: token your-token" https://api.github.com/user
   
   # Test database connection (example for database servers)
   # psql $DATABASE_URL -c "SELECT 1;"
   ```

3. **Application Startup Issues**
   ```bash
   # Check detailed logs
   cf logs mcp-protocol-adapter
   
   # Check health endpoint
   cf curl /v2/apps/$(cf app mcp-protocol-adapter --guid)/instances
   ```

### Health Monitoring

The application provides several endpoints for monitoring:

- `/health` - Basic health check
- `/debug/process` - MCP Server process status
- `/actuator/metrics` - Application metrics (if enabled)

### Log Analysis

```bash
# Stream live logs
cf logs mcp-protocol-adapter

# Get recent logs
cf logs mcp-protocol-adapter --recent

# Filter logs by component
cf logs mcp-protocol-adapter | grep "McpServerProcess"
cf logs mcp-protocol-adapter | grep "MessageBridge"
```

## Security Best Practices

1. **Token Management**
   - Use Cloud Foundry environment variables, not hardcoded values
   - Rotate tokens regularly
   - Use separate tokens for different environments

2. **Network Security**
   - Configure appropriate Cloud Foundry security groups
   - Use HTTPS for all external communication
   - Consider VPN access for sensitive environments

3. **Resource Limits**
   - Set appropriate memory and disk quotas
   - Monitor resource usage
   - Implement alerting for resource exhaustion

## Scaling

### Horizontal Scaling

```bash
# Scale to multiple instances
cf scale mcp-protocol-adapter -i 3

# Auto-scaling (if supported)
cf enable-autoscaling mcp-protocol-adapter
```

### Vertical Scaling

```bash
# Increase memory
cf scale mcp-protocol-adapter -m 2G

# Increase disk
cf scale mcp-protocol-adapter -k 1G
```

## Updates and Rollbacks

### Blue-Green Deployment

```bash
# Deploy new version
cf push mcp-protocol-adapter-green -f manifest.yml

# Test new version
curl https://mcp-protocol-adapter-green.apps.your-domain.com/health

# Switch traffic
cf map-route mcp-protocol-adapter-green apps.your-domain.com -n mcp-protocol-adapter
cf unmap-route mcp-protocol-adapter apps.your-domain.com -n mcp-protocol-adapter

# Clean up old version
cf delete mcp-protocol-adapter -f
cf rename mcp-protocol-adapter-green mcp-protocol-adapter
```

### Rolling Back

```bash
# Restart with previous image
cf set-env mcp-protocol-adapter DOCKER_IMAGE "your-registry/mcp-protocol-adapter:v0.9.0"
cf restart mcp-protocol-adapter
```

## Support

For issues with:
- **Cloud Foundry deployment**: Check CF documentation and logs
- **Docker build**: Verify Docker configuration and dependencies  
- **MCP Server**: Check your specific MCP server documentation
- **Protocol Adapter**: Review application logs and health endpoints

Remember to check the `DESIGN.md` file for architectural details and the `CLAUDE.md` file for development guidance.