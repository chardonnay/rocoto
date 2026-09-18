ROCOTO_WAR := ../../rocoto-war/target/rocoto.war
ROCOTO_URL ?= https://localhost:8443/rocoto

.PHONY: certs up down test logs check-artifacts

check-artifacts:
	@test -f $(ROCOTO_WAR) || { echo "missing $(ROCOTO_WAR): run 'mvn install -DskipTests' in the repository root first"; exit 1; }

certs:
	./gen-certs.sh

up: check-artifacts
	@test -f certs/ca.p12 || ./gen-certs.sh
	docker compose up -d
	@echo "waiting for $(ROCOTO_URL) ..."
	@for i in $$(seq 1 60); do \
	  code=$$(curl -s -o /dev/null -w '%{http_code}' --cacert certs/ca.crt $(ROCOTO_URL)/api/v1/server || true); \
	  if [ "$$code" = "401" ]; then echo "Rocoto is up"; exit 0; fi; sleep 2; \
	done; echo "Rocoto did not start - see 'make logs'"; exit 1

test:
	ROCOTO_URL=$(ROCOTO_URL) ./test.sh

logs:
	docker compose logs --tail=100

down:
	docker compose down -v
