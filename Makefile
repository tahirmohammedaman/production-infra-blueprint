# Single entrypoint for every workflow in this repository.
# Anything a human or CI needs to do is a target here; there are no undocumented steps.

SHELL         := /bin/bash
.SHELLFLAGS   := -eu -o pipefail -c
.DEFAULT_GOAL := help

APP_DIR      := app
GRADLE       := ./gradlew
COMPOSE_FILE := deploy/compose/docker-compose.yml
IMAGE        := blueprint-api
VERSION      ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
API_PORT     ?= 8080
MGMT_PORT    ?= 9090

# docker and podman are both first-class here: rootless podman is the default on Fedora
# and friends, and a repository that only works with docker is not reproducible.
COMPOSE   := $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" \
               || (command -v podman-compose >/dev/null 2>&1 && echo podman-compose) \
               || echo "docker-compose")
CONTAINER := $(shell command -v docker >/dev/null 2>&1 && echo docker || echo podman)

export APP_VERSION := $(VERSION)

.PHONY: help
help: ## Show available targets
	@grep -hE '^[a-zA-Z0-9_.-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- application

.PHONY: build
build: ## Compile and package the service
	cd $(APP_DIR) && $(GRADLE) build -x test

.PHONY: test
test: ## Run the full test suite (unit, slice, and Testcontainers integration)
	cd $(APP_DIR) && $(GRADLE) test

.PHONY: check
check: ## Run tests plus the formatting gate, exactly as CI does
	cd $(APP_DIR) && $(GRADLE) check

.PHONY: fmt
fmt: ## Reformat sources in place
	cd $(APP_DIR) && $(GRADLE) spotlessApply

.PHONY: run
run: ## Run the service on the host against a local Postgres (profile: local)
	cd $(APP_DIR) && SPRING_PROFILES_ACTIVE=local $(GRADLE) bootRun

.PHONY: clean
clean: ## Remove build output
	cd $(APP_DIR) && $(GRADLE) clean

.PHONY: deps
deps: ## Print the resolved runtime dependency tree
	cd $(APP_DIR) && $(GRADLE) dependencies --configuration runtimeClasspath

.PHONY: modules
modules: ## Report the JDK modules jdeps can see (a review aid for the jlink list)
	cd $(APP_DIR) && $(GRADLE) printJdepsModules

# ------------------------------------------------------------------ container

.PHONY: image
image: ## Build the hardened container image
	$(CONTAINER) build \
		--target final \
		--build-arg APP_VERSION=$(VERSION) \
		--build-arg VCS_REF=$$(git rev-parse --short HEAD 2>/dev/null || echo unknown) \
		--build-arg BUILD_DATE=$$(date -u +%Y-%m-%dT%H:%M:%SZ) \
		-t $(IMAGE):$(VERSION) -t $(IMAGE):latest \
		$(APP_DIR)

.PHONY: image-cds
image-cds: ## Build the image with a class-data-sharing archive (faster start, larger image)
	$(CONTAINER) build \
		--target cds \
		--build-arg APP_VERSION=$(VERSION) \
		-t $(IMAGE):$(VERSION)-cds \
		$(APP_DIR)

.PHONY: image-size
image-size: ## Report the size of the built image and its layers
	@$(CONTAINER) images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep '^$(IMAGE)' || \
		echo "no image built yet - run 'make image'"

# ---------------------------------------------------------------- local stack

.PHONY: bootstrap
bootstrap: ## Bring up the whole local stack from nothing and verify it works
	scripts/bootstrap.sh

.PHONY: up
up: ## Start the local stack in the background
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build

.PHONY: down
down: ## Stop the local stack and delete its volumes
	scripts/teardown.sh

.PHONY: logs
logs: ## Follow the API logs
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f api

.PHONY: ps
ps: ## Show the state of the local stack
	$(COMPOSE) -f $(COMPOSE_FILE) ps

.PHONY: smoke
smoke: ## Run the smoke tests against a running stack
	scripts/smoke-test.sh http://localhost:$(API_PORT) http://localhost:$(MGMT_PORT)

.PHONY: psql
psql: ## Open a psql shell against the local database
	$(COMPOSE) -f $(COMPOSE_FILE) exec postgres psql -U $${DB_USER:-blueprint} -d $${DB_NAME:-blueprint}

# ---------------------------------------------------------------- diagnostics

.PHONY: version
version: ## Print the version this build would produce
	@echo $(VERSION)

.PHONY: doctor
doctor: ## Report which tools the workflows found on this machine
	@echo "compose:    $(COMPOSE)"
	@echo "container:  $(CONTAINER)"
	@echo "version:    $(VERSION)"
	@printf 'java:       '; java -version 2>&1 | head -1 || echo "not installed"
	@printf 'curl:       '; curl --version 2>/dev/null | head -1 || echo "not installed"
