# GitHub MCP Protocol Adapter - Deployment Checklist

## 📋 Deployment Artifacts Created

### Core Files
- ✅ **Dockerfile** - Multi-stage build for GitHub MCP Server + Spring Boot adapter
- ✅ **manifest.yml** - Cloud Foundry deployment manifest with 3 environments
- ✅ **.dockerignore** - Optimized Docker build context
- ✅ **build-and-deploy.sh** - Automated build and deployment script

### Documentation  
- ✅ **DEPLOYMENT.md** - Comprehensive deployment guide
- ✅ **DEPLOYMENT_CHECKLIST.md** - This checklist

## 🚀 Quick Start (5 Minutes)

### Option 1: Automated Script

```bash
# Make script executable
chmod +x build-and-deploy.sh

# Build, push, and deploy in one command
./build-and-deploy.sh all \
  --registry your-registry.com \
  --version v1.0.0 \
  --env dev \
  --token ghp_your_github_token_here
```

### Option 2: Manual Steps

```bash
# 1. Build Docker image
docker buildx build --platform linux/amd64 -t your-registry.com/github-mcp-adapter:v1.0.0 .

# 2. Push to registry
docker push your-registry.com/github-mcp-adapter:v1.0.0

# 3. Update manifest.yml with your registry
# 4. Deploy to Cloud Foundry
cf push github-mcp-adapter-dev -f manifest.yml

# 5. Set GitHub token
cf set-env github-mcp-adapter-dev GITHUB_PERSONAL_ACCESS_TOKEN "your_token"
cf restart github-mcp-adapter-dev
```

## 📝 Pre-Deployment Checklist

### ✅ Prerequisites
- [ ] Docker installed and running
- [ ] Cloud Foundry CLI installed (`cf`)
- [ ] Access to container registry (Docker Hub, Harbor, etc.)
- [ ] Cloud Foundry organization and space access
- [ ] GitHub Personal Access Token with required permissions

### ✅ GitHub Token Setup
- [ ] Created GitHub PAT at: https://github.com/settings/personal-access-tokens/new
- [ ] Granted minimum permissions:
  - [ ] `repo` - Repository operations
  - [ ] `read:packages` - Docker image access (if needed)
  - [ ] Additional permissions for your required toolsets
- [ ] Token stored securely (not hardcoded)

### ✅ Configuration Updates
- [ ] Updated `manifest.yml`:
  - [ ] Changed `your-registry` to actual container registry
  - [ ] Updated `apps.yourorg.com` to your CF domain
  - [ ] Adjusted app names if needed to avoid conflicts
- [ ] Updated `build-and-deploy.sh`:
  - [ ] Set `DEFAULT_REGISTRY` to your registry
  - [ ] Verified environment variables

## 🎯 Deployment Targets

The manifest provides 3 deployment configurations:

### Development Environment
```bash
# Deploy to dev
cf push github-mcp-adapter-dev -f manifest.yml

# Configure
cf set-env github-mcp-adapter-dev GITHUB_PERSONAL_ACCESS_TOKEN "token"
cf set-env github-mcp-adapter-dev LOG_LEVEL "DEBUG"
cf restart github-mcp-adapter-dev
```

### Production Environment  
```bash
# Deploy to prod
cf push github-mcp-adapter-prod -f manifest.yml

# Configure with production settings
cf set-env github-mcp-adapter-prod GITHUB_PERSONAL_ACCESS_TOKEN "prod_token"
cf set-env github-mcp-adapter-prod GITHUB_TOOLSETS "all"
cf restart github-mcp-adapter-prod
```

## 🔍 Post-Deployment Verification

### ✅ Health Checks
```bash
# Check application status
cf app github-mcp-adapter-dev

# Test health endpoint
curl https://github-mcp-adapter-dev.apps.your-domain.com/health

# Check GitHub MCP Server process status
curl https://github-mcp-adapter-dev.apps.your-domain.com/debug/process

# View logs
cf logs github-mcp-adapter-dev --recent
```

### ✅ Expected Health Response
```json
{
  "status": "UP",
  "activeSessions": 0,
  "githubMcpServerRunning": true,
  "detailedHealth": {
    "githubMcpServerRunning": true,
    "activeSessionCount": 0,
    "bridgeStatus": "healthy"
  }
}
```

## 🛠️ Testing the Adapter

### Test SSE Connection
```bash
# Connect to SSE endpoint (should receive endpoint event)
curl -N -H "Accept: text/event-stream" \
  https://github-mcp-adapter-dev.apps.your-domain.com/sse
```

### Test Message Posting
```bash
# Send a JSON-RPC message
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}},"id":1}' \
  https://github-mcp-adapter-dev.apps.your-domain.com/message
```

## 🔧 Troubleshooting Common Issues

### Issue: Image Pull Failed
```bash
# Verify image exists
docker pull your-registry.com/github-mcp-adapter:v1.0.0

# Check CF can access registry  
cf logs github-mcp-adapter-dev | grep "pull"
```

### Issue: GitHub MCP Server Not Starting
```bash
# Check environment variables
cf env github-mcp-adapter-dev | grep GITHUB

# Check logs for GitHub MCP Server errors
cf logs github-mcp-adapter-dev | grep "GithubMcpServerProcess"

# Verify token permissions
curl -H "Authorization: token your_token" https://api.github.com/user
```

### Issue: SSE Connection Fails
```bash
# Check if port is correct
cf app github-mcp-adapter-dev | grep urls

# Check message bridge health
curl https://your-app.com/debug/process

# Check for firewall/security group issues
cf security-groups
```

## 🔄 Update and Rollback Procedures

### Deploy New Version
```bash
# Build new version
./build-and-deploy.sh build --version v1.1.0

# Blue-green deployment
cf push github-mcp-adapter-dev-new -f manifest.yml
# Test new version...
cf map-route github-mcp-adapter-dev-new apps.your-domain.com --hostname github-mcp-adapter-dev
cf unmap-route github-mcp-adapter-dev apps.your-domain.com --hostname github-mcp-adapter-dev
```

### Rollback
```bash
# Quick rollback to previous image
cf set-env github-mcp-adapter-dev DOCKER_IMAGE "your-registry.com/github-mcp-adapter:v1.0.0"
cf restart github-mcp-adapter-dev
```

## 📊 Monitoring and Maintenance

### Key Metrics to Monitor
- Application health: `/health` endpoint
- SSE connection count: `/debug/process`
- GitHub MCP Server process status
- Memory and CPU usage: `cf app github-mcp-adapter-dev`
- Error rates in logs: `cf logs github-mcp-adapter-dev`

### Regular Maintenance
- [ ] Rotate GitHub tokens quarterly
- [ ] Update base images for security patches
- [ ] Monitor resource usage and scale as needed
- [ ] Review and update toolset configurations
- [ ] Test disaster recovery procedures

## 🎉 Success Criteria

Your deployment is successful when:
- [ ] Health endpoint returns `{"status": "UP"}`
- [ ] SSE endpoint accepts connections and sends endpoint event
- [ ] Message endpoint accepts JSON-RPC messages
- [ ] GitHub MCP Server process is running (`githubMcpServerRunning: true`)
- [ ] Application logs show no errors
- [ ] Resource usage is within expected limits

## 📞 Support Resources

- **Application Logs**: `cf logs github-mcp-adapter-dev`
- **Health Endpoint**: `https://your-app.com/health`
- **Debug Endpoint**: `https://your-app.com/debug/process`
- **GitHub MCP Server Docs**: https://github.com/github/github-mcp-server
- **MCP Specification**: https://spec.modelcontextprotocol.io/
- **Spring Boot Docs**: https://spring.io/projects/spring-boot

---

## 📁 File Summary

| File | Purpose | Key Features |
|------|---------|--------------|
| `Dockerfile` | Multi-stage container build | Go + Spring Boot + optimized runtime |
| `manifest.yml` | CF deployment config | 3 environments, health checks |
| `.dockerignore` | Build optimization | Excludes unnecessary files |
| `build-and-deploy.sh` | Automation script | Build, push, deploy in one command |
| `DEPLOYMENT.md` | Detailed guide | Step-by-step instructions |

**Ready to deploy? Start with the Quick Start section above! 🚀**