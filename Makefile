.PHONY: test unit it run up down logs token demo

unit:            ## fast unit tests (no Docker)
	mvn -B test

test:            ## unit + integration/e2e tests (needs Docker for Testcontainers)
	mvn -B verify

up:              ## full stack: app, Postgres, Redis, Kafka, Prometheus, Grafana, Kafka UI, webhook sink
	docker compose up --build -d

down:
	docker compose down -v

logs:
	docker compose logs -f app webhook-sink

run:             ## run the app on the host against `docker compose up postgres redis kafka`
	SPRING_PROFILES_ACTIVE=seed,local mvn spring-boot:run

demo:            ## scripted end-to-end walk-through with curl (stack must be up)
	./scripts/demo.sh
