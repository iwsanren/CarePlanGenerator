# CarePlan Generator Backend

This is a Java/Spring Boot learning project that builds a care plan generation backend step by step. The project starts from a small MVP and gradually introduces database modeling, async processing, queues, workers, polling, validation, tests, adapter patterns, monitoring, and AWS deployment concepts.

The goal is not to jump straight to a perfect architecture. Each stage intentionally exposes a small limitation, so the next topic feels necessary instead of arbitrary.

## Current Status

This backend is no longer the early Day 2-3 synchronous MVP. The current codebase includes local async processing, multi-source intake APIs, monitoring support, and AWS Lambda/SQS practice code.

Implemented pieces:

- Spring Boot REST API
- PostgreSQL persistence for `Patient`, `Provider`, `Order`, and `CarePlan`
- Redis-backed queue for care plan generation jobs
- Scheduled worker that consumes queued jobs and calls an LLM provider
- Polling API for checking care plan generation status
- Request validation, warning handling, and unified error responses
- Adapter Pattern for ingesting orders from different external sources
- Unit and integration tests
- Prometheus and Grafana local monitoring setup
- AWS Lambda / SQS handler and packaging practice code

## Tech Stack

- Java 17
- Spring Boot 4.0.2
- Spring Web MVC
- Spring Data JPA
- PostgreSQL 15
- Redis 7
- Spring Retry
- Micrometer + Prometheus
- JUnit / Spring Boot Test
- Docker / Docker Compose
- LLM providers: OpenAI, Claude, or local mock

## Project Structure

```text
src/main/java/com/page24/backend
├── controller/      # REST controllers: HTTP request/response boundary
├── service/         # Business logic, queueing, workers, LLM calls
├── repository/      # Spring Data JPA repositories
├── entity/          # JPA entities
├── dto/             # Request/response DTOs and mappers
├── exception/       # Unified API error handling
├── intake/          # Multi-source intake adapters
├── aws/lambda/      # AWS Lambda / SQS practice code
└── config/          # Web and Redis configuration
```

Important entry points:

- Application entry: `src/main/java/com/page24/backend/BackendApplication.java`
- Main order API: `src/main/java/com/page24/backend/controller/OrderController.java`
- Intake API: `src/main/java/com/page24/backend/controller/IntakeController.java`
- Order business logic: `src/main/java/com/page24/backend/service/OrderService.java`
- Background worker: `src/main/java/com/page24/backend/service/CarePlanWorker.java`
- Care plan generation: `src/main/java/com/page24/backend/service/CarePlanGenerationService.java`

## Quick Start

### 1. Create an environment file

Copy `.env.example` to `.env`.

Windows:

```powershell
copy .env.example .env
```

Mac/Linux:

```bash
cp .env.example .env
```

For local development, the project can use a mock LLM provider. This does not require a real API key:

```env
LLM_MOCK_ENABLED=true
```

To use real OpenAI calls:

```env
LLM_MOCK_ENABLED=false
LLM_PROVIDER=openai
LLM_API_KEY=your-openai-api-key
LLM_OPENAI_MODEL=gpt-3.5-turbo
```

To use Claude:

```env
LLM_MOCK_ENABLED=false
LLM_PROVIDER=claude
CLAUDE_API_KEY=your-claude-api-key
CLAUDE_MODEL=claude-3-5-sonnet-latest
```

### 2. Start the services

```bash
docker-compose up --build
```

Available URLs:

- Frontend page: http://localhost:8080
- Backend API: http://localhost:8080/api/orders
- Actuator health: http://localhost:8080/actuator/health
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

On first startup, the application inserts mock data automatically. If the database already has data, initialization is skipped.

### 3. Stop the services

Keep database data:

```bash
docker-compose down
```

Remove database volumes and start fresh next time:

```bash
docker-compose down -v
```

## Local Database

PostgreSQL connection details from `docker-compose.yml`:

- Host: `localhost`
- Port: `5432`
- Database: `careplan`
- User: `careplan_user`
- Password: `careplan_password`

Open a `psql` shell:

```bash
docker exec -it careplan-postgres psql -U careplan_user -d careplan
```

Useful queries:

```sql
SELECT * FROM patients;
SELECT * FROM providers;
SELECT * FROM orders;
SELECT * FROM care_plans;
```

## Core Flow

The current local flow is asynchronous:

```text
Frontend / API client
  -> POST /api/orders
  -> Spring Boot creates Patient / Provider / Order / CarePlan
  -> CarePlan starts as PENDING
  -> carePlanId is pushed into Redis
  -> CarePlanWorker consumes one job every 5 seconds
  -> CarePlanGenerationService calls the selected LLM provider
  -> Success updates the CarePlan to COMPLETED
  -> Retries are attempted on failure; exhausted retries mark it as FAILED
  -> The frontend polls GET /api/orders/{id}/status
```

This maps to Days 4-6 in the course: introduce a queue, add a worker, then let the frontend observe task progress.

## API

### Create an order

```http
POST /api/orders
Content-Type: application/json
```

Example request:

```json
{
  "patientFirstName": "John",
  "patientLastName": "Doe",
  "patientMrn": "123456",
  "patientDateOfBirth": "1980-01-01",
  "providerName": "Dr. Smith",
  "providerNpi": "1234567890",
  "medicationName": "IVIG",
  "primaryDiagnosis": "G70.00",
  "additionalDiagnosis": "I10, K21.9",
  "medicationHistory": "Pyridostigmine 60mg q6h PRN",
  "patientRecords": "Progressive muscle weakness over 2 weeks."
}
```

A successful request usually returns `201 Created`. The care plan status starts as `PENDING`, then moves through `PROCESSING`, `COMPLETED`, or `FAILED`.

### Query orders and status

```http
GET /api/orders
GET /api/orders/{id}
GET /api/orders/{id}/status
GET /api/orders/search?patientName=John
GET /api/orders/search?mrn=123456
```

### Download a completed care plan

```http
GET /api/orders/{id}/download
```

Only `COMPLETED` care plans can be downloaded. Other statuses return a business error.

### Multi-source intake APIs

These endpoints demonstrate the Adapter Pattern. External payloads may have different shapes, but each adapter converts its source format into the internal order request model.

```http
POST /api/intake/clinic-b
Content-Type: application/json
```

```http
POST /api/intake/pharma-corp
Content-Type: application/xml
```

```http
POST /api/intake/hospital-d
Content-Type: text/csv
```

If a duplicate medication order from a different day triggers a warning, the first request asks for confirmation. Submit again with:

```http
POST /api/intake/clinic-b?confirm=true
```

## Validation and Business Rules

The backend currently enforces:

- MRN must be exactly 6 digits
- NPI must be exactly 10 digits
- `primaryDiagnosis` must be a valid ICD-10-style code
- `additionalDiagnosis` supports a comma-separated ICD-10-style list
- Same NPI with a different provider name is an error
- Same patient + same medication + same day is an error
- Same patient + same medication + different day is a warning and requires `confirm=true`
- LLM failures are retried; exhausted retries mark the care plan as `FAILED`
- API errors use a unified JSON format and do not expose stack traces

Interview note: a useful explanation for the warning behavior is that same-day duplicates are likely accidental, so they should be blocked. Cross-day duplicates may represent refills or another therapy cycle, so the user is asked to confirm.

## Tests

Run tests locally:

```bash
./mvnw test
```

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

Run tests inside Docker:

```bash
docker-compose run --rm backend-test
```

Tests use an H2 in-memory database and `LLM_MOCK_ENABLED=true`, so they do not require a real LLM API.

## Monitoring

Spring Boot Actuator exposes Prometheus metrics at:

```text
http://localhost:8080/actuator/prometheus
```

Docker Compose also starts:

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

Prometheus configuration:

```text
notes/Day11_Monitoring/prometheus.yml
```

## AWS Practice Code

The repository includes AWS Lambda/SQS practice classes:

- `CreateOrderHandler`
- `GetOrderHandler`
- `SqsCarePlanQueue`
- `LambdaSpringContext`
- `LambdaApplication`

Packaging config:

```text
src/assembly/aws-lambda.xml
```

Build package:

```bash
./mvnw package
```

AWS deployment is not required for local development. In the course plan, AWS appears in Days 12-15: first using the AWS Console manually, then connecting API Gateway, Lambda, SQS, and RDS, and finally automating infrastructure with Terraform.

## Course Map

| Day | Topic | Java/Spring Boot implementation |
|---|---|---|
| 1 | Requirements + design doc | `docs/` |
| 2 | Synchronous MVP | Spring Controller + Service + LLM |
| 3 | Database design | JPA Entity + Repository + PostgreSQL |
| 4 | Message queue | Redis queue |
| 5 | Worker | Scheduled worker + Spring Retry |
| 6 | Frontend status updates | Polling API |
| 7 | Code refactor | Controller-Service-Repository layering |
| 8 | Errors, warnings, tests | Validation, unified errors, JUnit |
| 9-10 | Adapter Pattern | Multi-source adapters under `intake/` |
| 11 | Monitoring | Actuator + Prometheus + Grafana |
| 12-15 | AWS / SQS / Terraform | Lambda/SQS code and later infra work |
| 16 | RESTful API practice | Independent CRUD practice |

## Common Questions

### Why is the care plan not completed immediately after creating an order?

The current version uses async processing. `POST /api/orders` creates the order and queues a job. A background worker generates the care plan later. Clients should poll `GET /api/orders/{id}/status`.

### Why use a mock LLM during development?

The mock provider avoids API keys, API costs, and network instability while you are still validating the core workflow. Once the business flow works, you can switch to OpenAI or Claude.

### Why not use WebSocket yet?

Polling is intentionally introduced first because it is simpler and easier to reason about. WebSocket adds connection management, reconnect behavior, and scaling concerns. Those are valuable topics, but they make more sense after the polling version is working.

## Good Files to Read Next

- `docs/project-instructions.md`: original project requirements and learning goals
- `notes/`: day-by-day notes and stage explanations
- `src/test/java/com/page24/backend`: tests that describe expected behavior
