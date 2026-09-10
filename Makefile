.PHONY: help build start stop restart rebuild status logs logs-follow config test reset

TAIL ?= 200

help:
	@echo "Available commands:"
	@echo "  make build        - Build all service images"
	@echo "  make start        - Start all services in the background"
	@echo "  make stop         - Stop all services"
	@echo "  make restart      - Stop and start all services"
	@echo "  make rebuild      - Rebuild images and recreate all services"
	@echo "  make status       - Show service status"
	@echo "  make logs         - Show the latest 200 log lines"
	@echo "  make logs-follow  - Follow service logs"
	@echo "  make config       - Validate and print the resolved Compose config"
	@echo "  make test         - Run Maven tests"
	@echo "  make reset        - Remove containers and volumes (deletes local data)"
	@echo ""
	@echo "Optional: SERVICE=auth-service and TAIL=50 narrow status or logs output."

build:
	docker compose build

start:
	docker compose up -d

stop:
	docker compose down

restart:
	docker compose down
	docker compose up -d

rebuild:
	docker compose up -d --build

status:
	docker compose ps $(SERVICE)

logs:
	docker compose logs --tail=$(TAIL) $(SERVICE)

logs-follow:
	docker compose logs -f $(SERVICE)

config:
	docker compose config

test:
	mvn test

reset:
	docker compose down -v
