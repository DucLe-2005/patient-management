.PHONY: help build start stop restart rebuild status logs logs-follow config test reset

TAIL ?= 200

help:
	@echo "Available commands:"
	@echo "  make build        - Build service images"
	@echo "  make start        - Start all services in the background"
	@echo "  make stop         - Stop all services"
	@echo "  make restart      - Stop and start all services"
	@echo "  make rebuild      - Rebuild images and recreate services"
	@echo "  make status       - Show service status"
	@echo "  make logs         - Show the latest 200 log lines"
	@echo "  make logs-follow  - Follow service logs"
	@echo "  make config       - Validate and print the resolved Compose config"
	@echo "  make test         - Run Maven tests"
	@echo "  make reset        - Remove containers and volumes (deletes local data)"
	@echo ""
	@echo ""
	@echo "Options:"
	@echo "  SERVICE=auth-service  Target one service for build, rebuild, status, or logs"
	@echo "  TAIL=50               Change the number of lines shown by make logs"
	@echo ""
	@echo "Examples:"
	@echo "  make build SERVICE=auth-service"
	@echo "  make rebuild SERVICE=patient-service"

build:
	docker compose build $(SERVICE)

start:
	docker compose up -d

stop:
	docker compose down

restart:
	docker compose down
	docker compose up -d

rebuild:
	docker compose up -d --build $(SERVICE)

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
