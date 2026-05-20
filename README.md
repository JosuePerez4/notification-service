# notification-service

Spring Boot service that records notification logs when papers are evaluated. It
consumes `paper.evaluated` events from RabbitMQ, persists one `NotificationLog`
per author in PostgreSQL, and exposes a REST endpoint to query the logs for a
paper.

> Current behavior: this service stores notification records with status `SENT`.
> It does not send emails or call an external notification provider.

## Architecture

```text
paper-service
    |
    | paper.evaluated JSON event
    v
RabbitMQ topic exchange
    |
    | queue: RABBITMQ_QUEUE_NOTIFICATION
    v
PaperEventListener
    |
    | one NotificationLog per author
    v
PostgreSQL
    |
    v
GET /notifications/paper/{paperId}
```

Key codepaths:

- `RabbitMQConfig` declares the topic exchange, queue, binding, and JSON message
  converter from environment variables.
- `PaperEventListener` consumes `PaperEvaluatedEvent` messages from
  `RABBITMQ_QUEUE_NOTIFICATION`, builds a subject/content string, and saves logs.
- `NotificationLogRepository` stores and queries `NotificationLog` JPA entities.
- `NotificationController` exposes `GET /notifications/paper/{paperId}`.

## Runtime requirements

- Java 21
- Maven
- PostgreSQL database
- RabbitMQ broker reachable by the service

The application imports configuration from `.env` files at the repository root
or `./notification-service/.env`.

Required variables:

| Variable | Purpose | Notes |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | Include SSL options if required by the database provider. |
| `SPRING_DATASOURCE_USERNAME` | Database user | Required at startup. |
| `SPRING_DATASOURCE_PASSWORD` | Database password | Required at startup. |
| `RABBITMQ_HOST` | RabbitMQ host | Required at startup. |
| `RABBITMQ_PORT` | RabbitMQ port | Use `5671` for TLS brokers or `5672` for local non-TLS brokers. |
| `RABBITMQ_USERNAME` | RabbitMQ user | Required at startup. |
| `RABBITMQ_PASSWORD` | RabbitMQ password | Required at startup. |
| `RABBITMQ_VHOST` | RabbitMQ virtual host | Use `/` for a default local broker. |
| `RABBITMQ_SSL_ENABLED` | Enables RabbitMQ TLS | Defaults to `true`; set `false` for local RabbitMQ without TLS. |
| `RABBITMQ_EXCHANGE` | Topic exchange name | Declared by the service. |
| `RABBITMQ_ROUTING_KEY_EVALUATED` | Binding/routing key | Expected value for evaluated-paper events, for example `paper.evaluated`. |
| `RABBITMQ_QUEUE_NOTIFICATION` | Queue consumed by the listener | Declared and consumed by the service. |
| `NOTIFICATION_SERVICE_PORT` | HTTP port | Example: `8086`. |

Example local `.env`:

```properties
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/notification
SPRING_DATASOURCE_USERNAME=notification
SPRING_DATASOURCE_PASSWORD=notification

RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_VHOST=/
RABBITMQ_SSL_ENABLED=false

RABBITMQ_EXCHANGE=paper.events
RABBITMQ_ROUTING_KEY_EVALUATED=paper.evaluated
RABBITMQ_QUEUE_NOTIFICATION=notification.paper.evaluated

NOTIFICATION_SERVICE_PORT=8086
```

Do not commit real credentials in `.env` files.

## Run locally

Start PostgreSQL and RabbitMQ first, then run:

```bash
mvn spring-boot:run
```

Build without starting the service:

```bash
mvn clean package
```

Hibernate is configured with `spring.jpa.hibernate.ddl-auto=update`, so the
database schema is updated from the JPA entity model when the app starts. Review
schema changes before pointing the service at shared or production databases.

### Manual smoke test

After the service is running, publish one evaluated-paper event and query the
created logs. If the RabbitMQ management API is enabled locally, this publishes
to the default `/` virtual host and the `paper.events` exchange from the example
environment above:

```bash
curl -u guest:guest \
  -H "content-type: application/json" \
  -X POST http://localhost:15672/api/exchanges/%2F/paper.events/publish \
  -d '{
    "properties": {},
    "routing_key": "paper.evaluated",
    "payload_encoding": "string",
    "payload": "{\"eventType\":\"paper.evaluated\",\"eventVersion\":\"1.0\",\"eventId\":\"4e527764-69a2-4e83-a7fb-843d3198c29e\",\"occurredAt\":\"2026-05-10T00:00:00Z\",\"source\":\"paper-service\",\"data\":{\"paperId\":\"ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89\",\"conferenceId\":\"fd59fd63-1a36-44e5-92b1-00953a1d72be\",\"title\":\"Reliable Event-Driven Systems\",\"topic\":\"software-architecture\",\"status\":\"ACCEPTED\",\"evaluationObservations\":\"Strong contribution and clear methodology.\",\"evaluatedBy\":{\"userId\":\"29573324-a2fd-4916-b7d7-8933ff16d81e\",\"role\":\"CHAIR\"},\"authors\":[{\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}]}}"
  }'
```

Then verify that the notification log is visible through the service API:

```bash
curl http://localhost:8086/notifications/paper/ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89
```

## Persistence model

`NotificationLog` is the only persisted entity. Each saved row contains:

| Field | Source |
| --- | --- |
| `id` | Database-generated identity value. |
| `paperId` | `data.paperId` from the event. |
| `conferenceId` | `data.conferenceId` from the event. |
| `recipientEmail` | `data.authors[].email` from each author entry. |
| `subject` | Built as `Evaluation Result for your Paper: {title}`. |
| `content` | Built from `title`, `paperId`, `status`, and `evaluationObservations`. |
| `sentAt` | `LocalDateTime.now()` when the listener handles the event. |
| `status` | Always stored as `SENT` by the current listener. |

The REST API returns the JPA entity directly, so these field names are also the
JSON response shape. There are no explicit uniqueness constraints or
deduplication checks in the current code; replaying the same event creates
additional rows for each author.

## Event contract

The listener expects JSON matching `PaperEvaluatedEvent`. The important fields
for notification log creation are:

- `data.paperId`
- `data.conferenceId`
- `data.title`
- `data.status`
- `data.evaluationObservations`
- `data.authors[].email`

Example message:

```json
{
  "eventType": "paper.evaluated",
  "eventVersion": "1.0",
  "eventId": "4e527764-69a2-4e83-a7fb-843d3198c29e",
  "occurredAt": "2026-05-10T00:00:00Z",
  "source": "paper-service",
  "data": {
    "paperId": "ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89",
    "conferenceId": "fd59fd63-1a36-44e5-92b1-00953a1d72be",
    "title": "Reliable Event-Driven Systems",
    "topic": "software-architecture",
    "status": "ACCEPTED",
    "evaluationObservations": "Strong contribution and clear methodology.",
    "evaluatedBy": {
      "userId": "29573324-a2fd-4916-b7d7-8933ff16d81e",
      "role": "CHAIR"
    },
    "authors": [
      {
        "name": "Ada Lovelace",
        "email": "ada@example.com"
      }
    ]
  }
}
```

If the event or `data` is null, the listener logs a warning and does not persist
anything. If `authors` is null or empty, it logs a warning and no notification
records are created.

Processing constraints:

- `eventId`, `eventVersion`, `occurredAt`, `source`, `topic`, `evaluatedBy`, and
  author `name` are accepted by the DTO but are not persisted.
- The listener uses `title`, `status`, `evaluationObservations`, and
  `authors[].email` as received. It does not validate email syntax or reject
  missing optional-looking text fields.
- There is no idempotency key. Duplicate RabbitMQ deliveries or manual replays
  create duplicate notification log rows.

## REST API

### List notification logs for a paper

```http
GET /notifications/paper/{paperId}
```

Path parameters:

- `paperId` - UUID of the evaluated paper.

Example:

```bash
curl http://localhost:8086/notifications/paper/ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89
```

Example response:

```json
[
  {
    "id": 1,
    "paperId": "ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89",
    "conferenceId": "fd59fd63-1a36-44e5-92b1-00953a1d72be",
    "recipientEmail": "ada@example.com",
    "subject": "Evaluation Result for your Paper: Reliable Event-Driven Systems",
    "content": "Your paper titled 'Reliable Event-Driven Systems' (ID ad1f0d72-7aa8-4c83-8ef2-dfa645bf3a89) has been ACCEPTED. Observations: Strong contribution and clear methodology.",
    "sentAt": "2026-05-10T00:00:00",
    "status": "SENT"
  }
]
```

The service also includes Springdoc OpenAPI UI dependencies; when the app is
running with default Springdoc settings, API docs are available at
`/v3/api-docs` and `/swagger-ui.html`.

## Operational runbook

Use this checklist when the service starts but does not create logs:

1. Confirm all required environment variables are set. Missing values fail
   startup because `application.yml` and `RabbitMQConfig` resolve them directly.
2. Confirm RabbitMQ connectivity, virtual host, credentials, and TLS setting.
   Local brokers usually need `RABBITMQ_SSL_ENABLED=false`.
3. Verify the exchange, queue, and routing key values match the publisher. The
   service binds `RABBITMQ_QUEUE_NOTIFICATION` to `RABBITMQ_EXCHANGE` with
   `RABBITMQ_ROUTING_KEY_EVALUATED`.
4. Check that published messages match the `PaperEvaluatedEvent` JSON shape and
   include at least one author. Events without authors are acknowledged but do
   not create rows.
5. Query the REST endpoint with the exact `paperId` from the event.
6. Review application logs for:
   - `Received paper.evaluated event for paperId: ...`
   - `Notification log persisted for author: ...`
   - warnings about null events/data or missing authors.

## Common pitfalls

- `RABBITMQ_SSL_ENABLED` defaults to `true`; set it explicitly to `false` for
  non-TLS local RabbitMQ.
- `NOTIFICATION_SERVICE_PORT` has no default in `application.yml`; define it in
  the environment or `.env`.
- The listener only persists logs after receiving RabbitMQ events. Calling the
  REST endpoint before publishing an event returns an empty list.
- The persisted `sentAt` value is generated by the service at consumption time,
  not copied from the event `occurredAt`.