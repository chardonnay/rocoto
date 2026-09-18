ROCOTO_WAR := ../../rocoto-war/target/rocoto.war
DEMO_WAR   := ../demo-app/target/demo-app.war
ROCOTO_URL ?= http://localhost:8080/rocoto
KEYCLOAK_URL ?= http://localhost:8081

.PHONY: up down test logs check-artifacts

check-artifacts:
	@test -f $(ROCOTO_WAR) || { echo "missing $(ROCOTO_WAR): run 'mvn install -DskipTests' in the repository root first"; exit 1; }
	@test -f $(DEMO_WAR) || { echo "missing $(DEMO_WAR): run 'mvn -q package' in examples/demo-app first"; exit 1; }

up: check-artifacts
	docker compose up -d
	@echo "waiting for Keycloak and Rocoto ..."
	@for i in $$(seq 1 120); do \
	  kc=$$(curl -s -o /dev/null -w '%{http_code}' $(KEYCLOAK_URL)/realms/rocoto-demo/.well-known/openid-configuration || true); \
	  rc=$$(curl -s -o /dev/null -w '%{http_code}' $(ROCOTO_URL)/api/v1/health/live || true); \
	  if [ "$$kc" = "200" ] && [ "$$rc" = "200" ]; then echo "both are up"; exit 0; fi; sleep 2; \
	done; echo "start-up timed out - see 'make logs'"; exit 1

test:
	ROCOTO_URL=$(ROCOTO_URL) KEYCLOAK_URL=$(KEYCLOAK_URL) ./test.sh

logs:
	docker compose logs --tail=150

down:
	docker compose down -v
