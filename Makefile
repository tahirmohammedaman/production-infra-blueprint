# Single entrypoint for every workflow in this repository.
# Anything a human or CI needs to do is a target here; there are no undocumented steps.

SHELL         := /bin/bash
.SHELLFLAGS   := -eu -o pipefail -c
.DEFAULT_GOAL := help

APP_DIR   := app
GRADLE    := ./gradlew
VERSION   ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)

export APP_VERSION := $(VERSION)

.PHONY: help
help: ## Show available targets
	@grep -hE '^[a-zA-Z0-9_.-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- application

.PHONY: build
build: ## Compile and package the service
	cd $(APP_DIR) && $(GRADLE) build -x test

.PHONY: test
test: ## Run the full test suite
	cd $(APP_DIR) && $(GRADLE) test

.PHONY: run
run: ## Run the service locally against a local Postgres (profile: local)
	cd $(APP_DIR) && SPRING_PROFILES_ACTIVE=local $(GRADLE) bootRun

.PHONY: clean
clean: ## Remove build output
	cd $(APP_DIR) && $(GRADLE) clean

.PHONY: deps
deps: ## Print the resolved dependency tree for the runtime classpath
	cd $(APP_DIR) && $(GRADLE) dependencies --configuration runtimeClasspath

# ---------------------------------------------------------------- utilities

.PHONY: version
version: ## Print the version this build would produce
	@echo $(VERSION)
