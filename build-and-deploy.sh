#!/bin/bash

# GitHub MCP Protocol Adapter - Build and Deploy Script
# This script automates the build and deployment process for Cloud Foundry

set -e  # Exit on any error

# Configuration
DEFAULT_REGISTRY="us-docker.pkg.dev/cf-mcp/gcr.io"
DEFAULT_IMAGE_NAME="github-mcp-adapter"
DEFAULT_VERSION="latest"
DEFAULT_ENVIRONMENT="dev"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS] COMMAND

Commands:
    build       Build the Docker image
    push        Push the Docker image to registry
    deploy      Deploy to Cloud Foundry
    all         Build, push, and deploy (default)

Options:
    -r, --registry REGISTRY     Container registry (default: ${DEFAULT_REGISTRY})
    -i, --image IMAGE          Image name (default: ${DEFAULT_IMAGE_NAME})
    -v, --version VERSION      Image version (default: ${DEFAULT_VERSION})
    -e, --env ENVIRONMENT      Environment (dev|staging|prod, default: ${DEFAULT_ENVIRONMENT})
    -t, --token TOKEN          GitHub Personal Access Token
    -n, --no-cache             Build without Docker cache
    -h, --help                 Show this help message

Examples:
    $0 build -v v1.0.0
    $0 deploy -e prod -t ghp_xxxxxxxxxxxx
    $0 all -r myregistry.com -v v1.2.3 -e staging

Environment Variables:
    DOCKER_REGISTRY           Container registry URL
    GITHUB_PAT               GitHub Personal Access Token
    CF_ORG                   Cloud Foundry organization
    CF_SPACE                 Cloud Foundry space

EOF
}

# Parse command line arguments
REGISTRY="${DOCKER_REGISTRY:-${DEFAULT_REGISTRY}}"
IMAGE_NAME="$DEFAULT_IMAGE_NAME"
VERSION="$DEFAULT_VERSION"
ENVIRONMENT="$DEFAULT_ENVIRONMENT"
GITHUB_TOKEN="${GITHUB_PAT:-}"
NO_CACHE=""
COMMAND=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -r|--registry)
            REGISTRY="$2"
            shift 2
            ;;
        -i|--image)
            IMAGE_NAME="$2"
            shift 2
            ;;
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -e|--env)
            ENVIRONMENT="$2"
            shift 2
            ;;
        -t|--token)
            GITHUB_TOKEN="$2"
            shift 2
            ;;
        -n|--no-cache)
            NO_CACHE="--no-cache"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        build|push|deploy|all)
            COMMAND="$1"
            shift
            ;;
        *)
            print_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Default command
if [ -z "$COMMAND" ]; then
    COMMAND="all"
fi

# Validate environment
if [[ ! "$ENVIRONMENT" =~ ^(dev|staging|prod)$ ]]; then
    print_error "Invalid environment: $ENVIRONMENT. Must be dev, staging, or prod."
    exit 1
fi

# Construct full image name
FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${VERSION}"

print_status "Configuration:"
print_status "  Registry: $REGISTRY"
print_status "  Image: $IMAGE_NAME"
print_status "  Version: $VERSION"
print_status "  Environment: $ENVIRONMENT"
print_status "  Full Image: $FULL_IMAGE_NAME"
print_status "  Command: $COMMAND"

# Function to check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."

    # Check Docker
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed or not in PATH"
        exit 1
    fi

    # Check if Docker is running
    if ! docker info &> /dev/null; then
        print_error "Docker is not running"
        exit 1
    fi

    # Check Cloud Foundry CLI for deploy commands
    if [[ "$COMMAND" == "deploy" || "$COMMAND" == "all" ]]; then
        if ! command -v cf &> /dev/null; then
            print_error "Cloud Foundry CLI is not installed or not in PATH"
            exit 1
        fi

        # Check if logged into CF
        if ! cf target &> /dev/null; then
            print_error "Not logged into Cloud Foundry. Run 'cf login' first."
            exit 1
        fi
    fi

    print_success "Prerequisites check passed"
}

# Function to build Docker image
build_image() {
    print_status "Building Docker image: $FULL_IMAGE_NAME"

    # Build the image
    docker build $NO_CACHE \
        --tag "$FULL_IMAGE_NAME" \
        --tag "${REGISTRY}/${IMAGE_NAME}:latest" \
        --build-arg VERSION="$VERSION" \
        .

    print_success "Docker image built successfully"

    # Show image info
    docker images "${REGISTRY}/${IMAGE_NAME}" | head -2
}

# Function to push Docker image
push_image() {
    print_status "Pushing Docker image: $FULL_IMAGE_NAME"

    # Push versioned image
    docker push "$FULL_IMAGE_NAME"

    # Push latest tag
    if [ "$VERSION" != "latest" ]; then
        docker push "${REGISTRY}/${IMAGE_NAME}:latest"
    fi

    print_success "Docker image pushed successfully"
}

# Function to deploy to Cloud Foundry
deploy_application() {
    print_status "Deploying to Cloud Foundry environment: $ENVIRONMENT"

    # Determine app name based on environment
    case $ENVIRONMENT in
        dev)
            APP_NAME="github-mcp-adapter-dev"
            MANIFEST_TARGET="github-mcp-adapter-dev"
            ;;
        staging)
            APP_NAME="github-mcp-adapter-staging"
            MANIFEST_TARGET="github-mcp-adapter-prod"  # Use prod config as template
            ;;
        prod)
            APP_NAME="github-mcp-adapter-prod"
            MANIFEST_TARGET="github-mcp-adapter-prod"
            ;;
    esac

    # Check if GitHub token is provided for deployment
    if [ -z "$GITHUB_TOKEN" ]; then
        print_warning "No GitHub token provided. You'll need to set it manually:"
        print_warning "cf set-env $APP_NAME GITHUB_PERSONAL_ACCESS_TOKEN 'your-token-here'"
    fi

    # Create temporary manifest with updated image
    TEMP_MANIFEST=$(mktemp)
    sed "s|your-registry/github-mcp-adapter:latest|${FULL_IMAGE_NAME}|g" manifest.yml > "$TEMP_MANIFEST"

    print_status "Deploying $APP_NAME with image $FULL_IMAGE_NAME"

    # Deploy the application
    cf push "$APP_NAME" -f "$TEMP_MANIFEST"

    # Set GitHub token if provided
    if [ -n "$GITHUB_TOKEN" ]; then
        print_status "Setting GitHub Personal Access Token"
        cf set-env "$APP_NAME" GITHUB_PERSONAL_ACCESS_TOKEN "$GITHUB_TOKEN"

        print_status "Restarting application to load new environment variables"
        cf restart "$APP_NAME"
    fi

    # Clean up temp manifest
    rm "$TEMP_MANIFEST"

    # Show deployment status
    print_status "Checking deployment status..."
    cf app "$APP_NAME"

    # Get app URL
    APP_URL=$(cf app "$APP_NAME" | grep -oP 'urls:\s+\K\S+' | head -1)
    if [ -n "$APP_URL" ]; then
        print_success "Application deployed successfully!"
        print_success "Health check: https://$APP_URL/health"
        print_success "Debug endpoint: https://$APP_URL/debug/process"

        # Test health endpoint
        print_status "Testing health endpoint..."
        if curl -f -s "https://$APP_URL/health" > /dev/null; then
            print_success "Health check passed"
        else
            print_warning "Health check failed - check application logs"
            print_warning "Run: cf logs $APP_NAME --recent"
        fi
    fi
}

# Main execution
main() {
    check_prerequisites

    case $COMMAND in
        build)
            build_image
            ;;
        push)
            push_image
            ;;
        deploy)
            deploy_application
            ;;
        all)
            build_image
            push_image
            deploy_application
            ;;
        *)
            print_error "Unknown command: $COMMAND"
            usage
            exit 1
            ;;
    esac

    print_success "Operation completed successfully!"
}

# Run main function
main