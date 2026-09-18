ROCOTO_WAR := ../../rocoto-war/target/rocoto.war
DEMO_WAR   := ../demo-app/target/demo-app.war
ROCOTO_URL ?= http://localhost:8080/rocoto

.PHONY: up down test logs check-artifacts

check-artifacts:
	@test -f $(ROCOTO_WAR) || { echo "missing $(ROCOTO_WAR): run 'mvn install -DskipTests' in the repository root first"; exit 1; }
	@test -f $(DEMO_WAR) || { echo "missing $(DEMO_WAR): run 'mvn -q package' in examples/demo-app first"; exit 1; }

up: check-artifacts
	docker compose up -d --build
	@echo "waiting for $(ROCOTO_URL) ..."
	@for i in $$(seq 1 90); do \
	  code=$$(curl -s -o /dev/null -w '%{http_code}' $(ROCOTO_URL)/api/v1/health/live || true); \
	  if [ "$$code" = "200" ]; then echo "Rocoto is up"; exit 0; fi; sleep 2; \
	done; echo "Rocoto did not start - see 'make logs'"; exit 1

test:
	ROCOTO_URL=$(ROCOTO_URL) ./test.sh

logs:
	docker compose logs --tail=150 wildfly

down:
	docker compose down -v
