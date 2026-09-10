.PHONY: help build up down restart logs test clean rebuild

help:
	@echo "Available commands:"
	@echo "  make build    - Build Docker images"
	@echo "  make up       - Start all services"
	@echo "  make down     - Stop all services"
	@echo "  make restart  - Restart all services"
	@echo "  make rebuild  - Rebuild and start everything"
	@echo "  make logs     - Follow logs"
	@echo "  make test     - Run Maven tests"
	@echo "  make clean    - Remove containers and volumes"

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

restart:
	docker compose down
	docker compose up -d

rebuild:
	docker compose down
	docker compose up -d --build

logs:
	docker compose logs -f

test:
	mvn test

clean:
	docker compose down -v