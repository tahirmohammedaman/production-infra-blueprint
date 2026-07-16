# Single entrypoint for every workflow in this repository.
# Anything a human or CI needs to do is a target here; there are no undocumented steps.

SHELL         := /bin/bash
.SHELLFLAGS   := -eu -o pipefail -c
.DEFAULT_GOAL := help

SERVICES_DIR := services
GRADLE       := ./gradlew
COMPOSE_FILE := deploy/compose/docker-compose.yml
# Rootless podman on an SELinux-enforcing host needs one extra setting on the gateway.
COMPOSE_OVERLAY := $(shell test "$$(getenforce 2>/dev/null)" = "Enforcing" \
                     && ! command -v docker >/dev/null 2>&1 \
                     && echo "-f deploy/compose/docker-compose.podman.yml")
SERVICES     := api worker
VERSION      ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
GATEWAY_PORT ?= 8080
MGMT_PORT    ?= 9090
WORKER_MGMT_PORT ?= 9091

# docker and podman are both first-class here: rootless podman is the default on Fedora
# and friends, and a repository that only works with docker is not reproducible.
COMPOSE   := $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" \
               || (command -v podman-compose >/dev/null 2>&1 && echo podman-compose) \
               || echo "docker-compose")
CONTAINER := $(shell command -v docker >/dev/null 2>&1 && echo docker || echo podman)

# Traefik discovers services through the runtime socket, which lives elsewhere under
# rootless podman. Compose reads this variable.
CONTAINER_SOCKET ?= $(shell test -S /var/run/docker.sock && echo /var/run/docker.sock \
                      || echo $${XDG_RUNTIME_DIR:-/run/user/$$(id -u)}/podman/podman.sock)

# Testcontainers needs the runtime socket too, and rootless podman does not run Ryuk.
TC_ENV := DOCKER_HOST=unix://$(CONTAINER_SOCKET) $(if $(findstring podman,$(CONTAINER_SOCKET)),TESTCONTAINERS_RYUK_DISABLED=true,)

export APP_VERSION := $(VERSION)
export CONTAINER_SOCKET

.PHONY: help
help: ## Show available targets
	@grep -hE '^[a-zA-Z0-9_.-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- application

.PHONY: build
build: ## Compile and package every service
	cd $(SERVICES_DIR) && $(GRADLE) build -x test

.PHONY: test
test: ## Run the full test suite (unit, slice, and Testcontainers integration)
	cd $(SERVICES_DIR) && $(TC_ENV) $(GRADLE) test

.PHONY: check
check: ## Run tests plus the formatting gate, exactly as CI does
	cd $(SERVICES_DIR) && $(TC_ENV) $(GRADLE) check

.PHONY: fmt
fmt: ## Reformat sources in place
	cd $(SERVICES_DIR) && $(GRADLE) spotlessApply

.PHONY: run-api
run-api: ## Run the API on the host against local backing stores (profile: local)
	cd $(SERVICES_DIR) && SPRING_PROFILES_ACTIVE=local $(GRADLE) :api:bootRun

.PHONY: run-worker
run-worker: ## Run the worker on the host against local backing stores
	cd $(SERVICES_DIR) && SPRING_PROFILES_ACTIVE=local $(GRADLE) :worker:bootRun

.PHONY: clean
clean: ## Remove build output
	cd $(SERVICES_DIR) && $(GRADLE) clean

.PHONY: deps
deps: ## Print the resolved runtime dependency tree for the API
	cd $(SERVICES_DIR) && $(GRADLE) :api:dependencies --configuration runtimeClasspath

.PHONY: modules
modules: ## Report the JDK modules jdeps can see (a review aid for the jlink list)
	cd $(SERVICES_DIR) && $(GRADLE) :api:printJdepsModules

# ------------------------------------------------------------------ container

.PHONY: images
images: $(addprefix image-,$(SERVICES)) ## Build the hardened image for every service

.PHONY: image-%
image-%: ## Build the hardened image for one service (e.g. make image-worker)
	$(CONTAINER) build \
		--target final \
		--build-arg SERVICE=$* \
		--build-arg APP_VERSION=$(VERSION) \
		--build-arg VCS_REF=$$(git rev-parse --short HEAD 2>/dev/null || echo unknown) \
		--build-arg BUILD_DATE=$$(date -u +%Y-%m-%dT%H:%M:%SZ) \
		-t blueprint-$*:$(VERSION) -t blueprint-$*:latest \
		$(SERVICES_DIR)

.PHONY: image-cds
image-cds: ## Build the API image with a class-data-sharing archive (faster start, larger image)
	$(CONTAINER) build \
		--target cds \
		--build-arg SERVICE=api \
		--build-arg APP_VERSION=$(VERSION) \
		-t blueprint-api:$(VERSION)-cds \
		$(SERVICES_DIR)

.PHONY: image-size
image-size: ## Report the size of the built images
	@$(CONTAINER) images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep '^blueprint-' || \
		echo "no images built yet - run 'make images'"

# ---------------------------------------------------------------- local stack

.PHONY: bootstrap
bootstrap: ## Bring up the whole local stack from nothing and verify it works
	scripts/bootstrap.sh

.PHONY: up
up: ## Start the local stack in the background
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) up -d --build

.PHONY: down
down: ## Stop the local stack and delete its volumes
	scripts/teardown.sh

.PHONY: logs
logs: ## Follow logs from every service
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) logs -f

.PHONY: logs-%
logs-%: ## Follow one service's logs (e.g. make logs-worker)
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) logs -f $*

.PHONY: ps
ps: ## Show the state of the local stack
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) ps

.PHONY: smoke
smoke: ## Run the smoke tests against a running stack
	scripts/smoke-test.sh http://localhost:$(GATEWAY_PORT) http://localhost:$(MGMT_PORT) http://localhost:$(WORKER_MGMT_PORT)

.PHONY: dlq
dlq: ## Print anything sitting in the dead-letter topic
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) exec kafka \
		/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
		--topic item-events.DLT --from-beginning --timeout-ms 5000

.PHONY: lag
lag: ## Show consumer group lag for the worker
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) exec kafka \
		/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
		--describe --group blueprint-worker

.PHONY: topics
topics: ## List Kafka topics and their partition counts
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) exec kafka \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

.PHONY: redis-cli
redis-cli: ## Open a redis-cli against the local cache
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) exec redis redis-cli

.PHONY: psql
psql: ## Open a psql shell against the local database
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) exec postgres psql -U $${DB_USER:-blueprint} -d $${DB_NAME:-blueprint}

# ---------------------------------------------------------------- diagnostics

.PHONY: version
version: ## Print the version this build would produce
	@echo $(VERSION)

.PHONY: doctor
doctor: ## Report which tools the workflows found on this machine
	@echo "compose:    $(COMPOSE)"
	@echo "container:  $(CONTAINER)"
	@echo "socket:     $(CONTAINER_SOCKET)"
	@echo "version:    $(VERSION)"
	@printf 'java:       '; java -version 2>&1 | head -1 || echo "not installed"
	@printf 'curl:       '; curl --version 2>/dev/null | head -1 || echo "not installed"
