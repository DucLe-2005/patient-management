# Patient Management

Patient Management is a Spring Boot microservice system for managing patients, authentication, billing accounts, and patient analytics. The services communicate through HTTP, gRPC, and Kafka and can be run locally as a complete stack with Docker Compose.

## Features

- JWT-based login and authorization
- Patient creation, retrieval, update, and deletion
- Automatic billing-account creation over gRPC
- Asynchronous patient events delivered through Kafka
- Separate PostgreSQL databases for patient and authentication data
- API gateway providing a single public HTTP entry point
- OpenAPI documentation for the patient and authentication APIs

## Architecture

| Component | Port | Responsibility |
| --- | ---: | --- |
| API Gateway | `4004` | Routes public requests and validates JWTs for protected patient endpoints |
| Patient Service | `4000` | Stores and manages patient records |
| Auth Service | `4005` | Authenticates users and issues or validates JWTs |
| Billing Service | `4001`, `9001` | Exposes REST infrastructure and a gRPC billing API |
| Analytics Service | `4002` | Consumes patient events from Kafka |
| Kafka | `9092` | Carries asynchronous patient-created events |
| Patient PostgreSQL | `5432` | Persists patient records |
| Auth PostgreSQL | `5433` | Persists users and credentials |

The gateway is the intended client entry point. Requests under `/auth/**` are forwarded to the Auth Service. Requests under `/api/patients/**` require a bearer token and are forwarded to the Patient Service.

### Infrastructure architecture

![AWS infrastructure architecture](patient-management-architecture.jpg)

### Main request flow

The following diagram shows the main request flow between the client, API gateway, and microservices, including synchronous HTTP and gRPC calls and asynchronous Kafka events.

![Main request flow](patient-management-communication-flow.png)

## Prerequisites

For the recommended Docker workflow:

- Docker Desktop or Docker Engine
- Docker Compose v2
- Make
- `curl` for the command-line API examples

For running or building services outside Docker, install a JDK and Maven compatible with the versions declared in the individual service POM files.

## Setup with Make and Docker Compose

The Makefile provides the primary interface for routine project commands. Run `make help` at any time to list the available targets.

1. Clone the repository and enter it:

   ```bash
   git clone https://github.com/DucLe-2005/patient-management.git
   cd patient-management
   ```

2. Create your local environment file from the committed template:

   ```bash
   cp .env.example .env
   ```

3. Review `.env` and replace the development database passwords and `JWT_SECRET`. The JWT secret must be Base64-encoded and decode to at least 32 bytes. One way to generate it is:

   ```bash
   openssl rand -base64 32
   ```

4. Build the service images:

   ```bash
   make build
   ```

5. Start the stack:

   ```bash
   make start
   ```

6. Confirm that the containers are running:

   ```bash
   make status
   ```

   Kafka and both PostgreSQL containers should report `healthy`. The application containers should report `Up`.

7. Follow logs when needed:

   ```bash
   make logs-follow
   ```

The API gateway is available at `http://localhost:4004` by default. Host ports can be changed in `.env` without changing container-to-container addresses.

To stop the stack while retaining database data:

```bash
make stop
```

To stop it and permanently delete its local database volumes:

```bash
make reset
```

## Seed data

On first database initialization, the Auth Service creates this development account:

| Email | Password | Role |
| --- | --- | --- |
| `testuser@test.com` | `password123` | `ADMIN` |

The Patient Service also inserts several sample patient records. Seed scripts are safe to run repeatedly because they check for existing records before inserting them.

## API examples

All public examples below use the API gateway on port `4004`.

### Log in

```bash
curl -i -X POST http://localhost:4004/auth/login \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "testuser@test.com",
    "password": "password123"
  }'
```

A successful response contains a JWT:

```json
{
  "token": "eyJ..."
}
```

Save the token for subsequent requests. If `jq` is installed:

```bash
TOKEN=$(curl -s -X POST http://localhost:4004/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"testuser@test.com","password":"password123"}' \
  | jq -r '.token')
```

### Validate a token

```bash
curl -i http://localhost:4004/auth/validate \
  -H "Authorization: Bearer $TOKEN"
```

### List patients

```bash
curl -i http://localhost:4004/api/patients \
  -H "Authorization: Bearer $TOKEN"
```

### Create a patient

Creating a patient also calls the Billing Service over gRPC and publishes a Kafka event for the Analytics Service.

```bash
curl -i -X POST http://localhost:4004/api/patients \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Jordan Lee",
    "email": "jordan.lee@example.com",
    "address": "123 Main Street",
    "dateOfBirth": "1990-06-15",
    "registeredDate": "2026-09-09"
  }'
```

### Update a patient

`registeredDate` is required when creating a patient but is not required when updating one.

```bash
curl -i -X PUT http://localhost:4004/api/patients/PATIENT_UUID \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Jordan Lee",
    "email": "jordan.lee@example.com",
    "address": "456 Oak Avenue",
    "dateOfBirth": "1990-06-15"
  }'
```

### Delete a patient

```bash
curl -i -X DELETE http://localhost:4004/api/patients/PATIENT_UUID \
  -H "Authorization: Bearer $TOKEN"
```

### Call the Billing Service with gRPC

With a gRPC client that supports the repository's `.http` syntax, use [`grpc-requests/billing-service/create-billing-account.http`](grpc-requests/billing-service/create-billing-account.http). With `grpcurl` and server reflection:

```bash
grpcurl -plaintext \
  -d '{"patientId":"12333","name":"Jordan Lee","email":"jordan.lee@example.com"}' \
  localhost:9001 BillingService/CreateBillingAccount
```

Additional ready-to-run HTTP request files are available under [`api-requests`](api-requests).

## API documentation

After starting the stack, the gateway exposes the OpenAPI specifications at:

- Patient Service: `http://localhost:4004/api-docs/patients`
- Auth Service: `http://localhost:4004/api-docs/auth`

The services also expose their own Springdoc endpoints directly on ports `4000` and `4005`.

## Building with Maven

The root POM is a multi-module aggregator for the five microservices:

```bash
mvn clean package
```

Build one service and any reactor dependencies with:

```bash
mvn -pl patient-service -am clean package
```

The `infrastructure` and `integration-tests` projects have independent POM files. With the Docker stack running, execute the integration tests using:

```bash
mvn -f integration-tests/pom.xml test
```

## Troubleshooting

### Login returns `500 Internal Server Error`

First check the gateway and Auth Service:

```bash
make status
make logs SERVICE="api-gateway auth-service auth-db"
```

If `auth-service` is restarting and PostgreSQL reports `password authentication failed`, the database volume was initialized using older credentials. PostgreSQL only applies `POSTGRES_USER` and `POSTGRES_PASSWORD` when creating a new data directory; editing `.env` does not update credentials in an existing volume.

For disposable local data, recreate both databases using the current `.env` values:

```bash
make reset
make rebuild
```

This deletes local database contents and reloads the seed data. To preserve existing data, restore the passwords used when the volumes were first created or change the PostgreSQL roles manually.

### Kafka is unhealthy

Inspect its health status and logs:

```bash
make status SERVICE=kafka
make logs SERVICE=kafka
```

The Apache Kafka image used by this project exposes its topic utility at `/opt/kafka/bin/kafka-topics.sh`. If the image is changed, confirm that the Compose health-check command exists in the replacement image.

### A port is already in use

Change the matching `*_HOST_PORT` value in `.env`, then recreate the affected containers:

```bash
make restart
```

Internal service ports should remain unchanged because other containers use them for service discovery.

### A service cannot connect to another container

Containers must use Compose service names such as `auth-service`, `patient-db`, `billing-service`, and `kafka`—not `localhost`. Verify the resolved configuration and inspect logs:

```bash
make config
make logs SERVICE=SERVICE_NAME
```

### Environment changes are not taking effect

Confirm that the root `.env` exists and recreate the containers:

```bash
make config
make restart
```

The `.env` file is intentionally ignored by Git. Commit `.env.example` when introducing new required variables, but never commit real passwords or signing secrets.

### Start from a clean local stack

```bash
make reset
make rebuild
```

This removes all Compose-managed local database data, so use it only when that data is disposable.
