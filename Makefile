# TransferGuard developer commands. Requires Docker and JDK 21.
COMPOSE := docker compose
MVN     := ./mvnw -B
ADMIN_TOKEN = $$(curl -s -d grant_type=client_credentials -d client_id=local-admin -d client_secret=x \
                 http://localhost:8090/transferguard/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

.PHONY: help bootstrap doctor smoke postman build test it image up up-core down clean demo seed scenarios sim load chaos logs token mutation

help:          ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

bootstrap:     ## Install Docker, the Compose plugin, JDK 21, make, curl and jq (asks for sudo)
	./scripts/bootstrap.sh

doctor:        ## Check that everything needed to run locally is installed
	./scripts/bootstrap.sh --check

build:         ## Compile and run unit tests
	$(MVN) verify -DskipITs

test: build    ## Alias for build

it:            ## Unit + Testcontainers integration tests (needs Docker)
	$(MVN) verify

mutation:      ## PIT mutation testing on the rules
	$(MVN) -pl transferguard-service -Pmutation verify -DskipITs

image:         ## Build both images into the local Docker daemon with Jib
	$(MVN) -DskipTests package jib:dockerBuild

up: .env image ## Build and start everything, with Prometheus, Grafana and Tempo
	TRACING_EXPORT_ENABLED=true $(COMPOSE) --profile observability up -d
	@echo "Swagger UI: http://localhost:8080/swagger-ui.html   Grafana: http://localhost:3000"

up-core: .env image ## Start only the API and its dependencies
	$(COMPOSE) up -d

down:          ## Stop everything
	$(COMPOSE) --profile observability --profile sim --profile load down

clean:         ## Stop everything and delete the volumes
	$(COMPOSE) --profile observability --profile sim --profile load down -v

demo: seed scenarios ## Seed 30 days of history, then run the five scenarios

seed:          ## Seed 30 days of history (idempotent)
	$(COMPOSE) --profile sim run --rm --no-deps simulator seed

scenarios:     ## Run the five labelled scenarios once
	$(COMPOSE) --profile sim run --rm --no-deps simulator scenarios

sim:           ## Labelled traffic for 5 minutes at 10 rps, then precision and recall
	$(COMPOSE) --profile sim run --rm --no-deps simulator run 5 10

load:          ## k6: 200 requests per second for 5 minutes
	mkdir -p load-test/results
	$(COMPOSE) --profile load run --rm --no-deps k6

smoke:         ## End-to-end smoke test of all 19 operations against the running stack
	./scripts/smoke-test.sh

postman:       ## Run the Postman collection with newman (in Docker) against the running stack
	docker run --rm --network host -v $(CURDIR)/postman:/etc/newman postman/newman:6-alpine \
	  run TransferGuard.postman_collection.json -e TransferGuard-local.postman_environment.json

chaos:         ## Toxiproxy chaos experiment in all three fail modes (about 20 minutes)
	./chaos/run-chaos.sh

logs:          ## Follow the API logs
	$(COMPOSE) logs -f transferguard

token:         ## Print an admin access token for curl
	@echo $(ADMIN_TOKEN)

.env:
	cp .env.example .env
	@echo "Created .env from .env.example; change the passwords if this machine is shared."
