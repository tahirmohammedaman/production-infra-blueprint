# Single entrypoint for every workflow in this repository.
# Anything a human or CI needs to do is a target here; there are no undocumented steps.

SHELL         := /bin/bash
.SHELLFLAGS   := -eu -o pipefail -c
.DEFAULT_GOAL := help

SERVICES_DIR := services
GRADLE       := ./gradlew
COMPOSE_FILE := deploy/compose/docker-compose.yml
# Observability is part of the local stack, not an optional extra. Mirrors compose_overlays()
# in scripts/lib.sh, which the scripts use.
COMPOSE_OVERLAY := -f deploy/compose/docker-compose.observability.yml
# Rootless podman on an SELinux-enforcing host needs one extra setting on the gateway and Alloy.
COMPOSE_OVERLAY += $(shell test "$$(getenforce 2>/dev/null)" = "Enforcing" \
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

.PHONY: coverage
coverage: ## Run the tests and print per-service line coverage
	cd $(SERVICES_DIR) && $(TC_ENV) $(GRADLE) test jacocoTestReport
	@for s in $(SERVICES); do \
		csv=$(SERVICES_DIR)/$$s/build/reports/jacoco/test/jacocoTestReport.csv; \
		test -f $$csv && awk -F, -v s=$$s 'NR>1 { m += $$8; c += $$9 } \
			END { printf "  %-8s %d/%d lines  %.1f%%\n", s, c, m + c, (c * 100) / (m + c) }' $$csv; \
	done

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
up: ## Start the local stack in the background, running freshly built images
	@# podman-compose keeps running containers on their old image after --build, and cannot
	@# replace the API alone while the gateway and worker depend on it. See compose_up() in
	@# scripts/lib.sh for the full story; docker compose needs none of this.
	$(COMPOSE) -f $(COMPOSE_FILE) $(COMPOSE_OVERLAY) up -d --build $(if $(findstring podman-compose,$(COMPOSE)),--force-recreate,)

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

# -------------------------------------------------------------- observability

PROMETHEUS_PORT   ?= 9095
ALERTMANAGER_PORT ?= 9093

.PHONY: obs-validate
obs-validate: ## Validate every observability config and run the alert unit tests, as CI does
	scripts/validate-observability.sh

.PHONY: obs-reload
obs-reload: ## Apply edited alert rules and Alertmanager routing without restarting anything
	curl -fsS -X POST http://localhost:$(PROMETHEUS_PORT)/-/reload
	curl -fsS -X POST http://localhost:$(ALERTMANAGER_PORT)/-/reload
	@echo "reloaded; check http://localhost:$(PROMETHEUS_PORT)/rules"

.PHONY: alerts
alerts: ## List the alerts firing right now
	@curl -fsS http://localhost:$(ALERTMANAGER_PORT)/api/v2/alerts?active=true \
		| jq -r '.[] | "\(.labels.severity)\t\(.labels.alertname)\t\(.annotations.summary)"'

# --------------------------------------------------------------- infrastructure

TF_DIR := infra/terraform/envs/prod
ANSIBLE_DIR := infra/ansible

.PHONY: tf-init
tf-init: ## Initialise terraform (needs object storage credentials for the state backend)
	cd $(TF_DIR) && terraform init

.PHONY: tf-plan
tf-plan: ## Show what terraform would change
	cd $(TF_DIR) && terraform plan -out=tfplan

.PHONY: tf-apply
tf-apply: ## Apply a plan produced by tf-plan - never applies without reading one first
	cd $(TF_DIR) && terraform apply tfplan

.PHONY: tf-lint
tf-lint: ## Format check and validate every terraform module
	terraform fmt -recursive -check -diff infra/terraform
	cd $(TF_DIR) && terraform init -backend=false -input=false >/dev/null && terraform validate

.PHONY: provision
provision: ## Run the ansible playbook against the node (BLUEPRINT_NODE_IP from tf output)
	cd $(ANSIBLE_DIR) && ansible-playbook site.yml

.PHONY: provision-check
provision-check: ## Dry-run the playbook and show the diff
	cd $(ANSIBLE_DIR) && ansible-playbook site.yml --check --diff

.PHONY: ansible-lint
ansible-lint: ## Lint the playbook at the production profile, as CI does
	cd $(ANSIBLE_DIR) && ansible-lint --profile production site.yml roles/

# ------------------------------------------------------------------ kubernetes

KUSTOMIZATIONS := \
	deploy/k8s/base \
	deploy/k8s/config/prod \
	deploy/k8s/migrations/prod \
	deploy/k8s/overlays/prod \
	deploy/k8s/overlays/dev \
	deploy/k8s/infrastructure/controllers \
	deploy/k8s/infrastructure/configs \
	deploy/k8s/infrastructure/observability \
	observability

KUSTOMIZE_IMAGE  := registry.k8s.io/kustomize/kustomize@sha256:899fcd3bc898160e62bcaf82932b0cb29ba38d16272353db2e7acbba82129429
KUBECONFORM_IMAGE := ghcr.io/yannh/kubeconform@sha256:5103f6f5e89061728aad4ad5a250627dd0fc9b2a92eb876f3762677a4222f9e0
KUBERNETES_VERSION ?= 1.33.0

.PHONY: k8s-build
k8s-build: ## Render every kustomization to stdout
	@for path in $(KUSTOMIZATIONS); do \
		echo "--- $$path"; \
		$(CONTAINER) run --rm -v "$(PWD)":/k:ro,z $(KUSTOMIZE_IMAGE) build "/k/$$path"; \
	done

.PHONY: k8s-validate
k8s-validate: ## Render every kustomization and check it against the Kubernetes schemas
	@rm -rf .rendered && mkdir -p .rendered
	@for path in $(KUSTOMIZATIONS); do \
		$(CONTAINER) run --rm -v "$(PWD)":/k:ro,z $(KUSTOMIZE_IMAGE) build "/k/$$path" \
			> ".rendered/$$(echo $$path | tr / -).yaml"; \
	done
	@cp clusters/prod/*.yaml .rendered/
	$(CONTAINER) run --rm -v "$(PWD)/.rendered":/w:ro,z $(KUBECONFORM_IMAGE) \
		-strict -summary -kubernetes-version $(KUBERNETES_VERSION) \
		-ignore-missing-schemas -skip Secret /w
	@rm -rf .rendered

.PHONY: secrets-edit
secrets-edit: ## Edit a SOPS-encrypted secret in place (FILE=deploy/k8s/config/prod/app-secrets.enc.yaml)
	@test -n "$(FILE)" || { echo "usage: make secrets-edit FILE=<path to a .enc.yaml>"; exit 1; }
	sops $(FILE)

.PHONY: flux-status
flux-status: ## Show what Flux is reconciling and whether it is healthy
	flux get all --all-namespaces

# ------------------------------------------------------------ supply chain / ci

.PHONY: verify-pins
verify-pins: ## Fail if any GitHub Action is referenced by tag instead of commit SHA
	scripts/check-action-pins.sh

.PHONY: verify-image
verify-image: ## Verify a published image's signature, SBOM and provenance (IMAGE=...)
	@test -n "$(IMAGE)" || { echo "usage: make verify-image IMAGE=ghcr.io/<owner>/blueprint-api:latest"; exit 1; }
	scripts/verify-image.sh $(IMAGE)

.PHONY: lint-ci
lint-ci: ## Lint the workflow files and shell scripts the way CI does
	$(CONTAINER) run --rm -v "$(PWD)":/repo:ro,z --workdir /repo \
		rhysd/actionlint@sha256:887a259a5a534f3c4f36cb02dca341673c6089431057242cdc931e9f133147e9 -color
	scripts/check-action-pins.sh

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
