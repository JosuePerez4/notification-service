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
- Spring Cloud Netflix Eureka client registers the service as
  `notification-service` using `EUREKA_SERVER_URL`.
- Spring Boot Actuator exposes health and info endpoints for runtime checks.

## Runtime requirements

- Java 21
- Maven
- PostgreSQL database
- RabbitMQ broker reachable by the service
- Eureka server reachable by the service registry client

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
| `EUREKA_SERVER_URL` | Eureka registry URL | Example: `http://localhost:8761/eureka/`. |

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
EUREKA_SERVER_URL=http://localhost:8761/eureka/
```

Do not commit real credentials in `.env` files.

## Run locally

Start PostgreSQL, RabbitMQ, and the Eureka server first, then run:

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

## Service discovery and health checks

The service uses the Spring application name `notification-service`. With
`spring-cloud-starter-netflix-eureka-client` on the classpath, it registers with
the Eureka server configured in `EUREKA_SERVER_URL`. The instance configuration
sets `eureka.instance.prefer-ip-address=true`, so consumers should expect Eureka
to advertise the instance IP address instead of only the hostname.

Actuator is enabled for lightweight operational checks:

```http
GET /actuator/health
GET /actuator/info
```

Only `health` and `info` are exposed over HTTP. Health details are configured
with `management.endpoint.health.show-details=always`, and probe support is
enabled through `management.endpoint.health.probes.enabled=true`.

## Container image

The Dockerfile builds the service with Maven on Java 21 and copies
`target/notification-0.0.1-SNAPSHOT.jar` into a Java 21 JRE image. It runs as a
non-root `app` user and exposes port `8086`.

Build and run locally:

```bash
docker build -t notification-service .
docker run --env-file .env -p 8086:8086 notification-service
```

Keep `NOTIFICATION_SERVICE_PORT=8086` when using the example command, or update
the port mapping to match the configured service port.

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
2. Confirm the Eureka server URL is reachable if the application fails during
   service discovery startup or does not appear in the registry as
   `notification-service`.
3. Confirm RabbitMQ connectivity, virtual host, credentials, and TLS setting.
   Local brokers usually need `RABBITMQ_SSL_ENABLED=false`.
4. Verify the exchange, queue, and routing key values match the publisher. The
   service binds `RABBITMQ_QUEUE_NOTIFICATION` to `RABBITMQ_EXCHANGE` with
   `RABBITMQ_ROUTING_KEY_EVALUATED`.
5. Check that published messages match the `PaperEvaluatedEvent` JSON shape and
   include at least one author. Events without authors are acknowledged but do
   not create rows.
6. Query the REST endpoint with the exact `paperId` from the event.
7. Review `/actuator/health` for database, RabbitMQ, and application health
   details while the service is running.
8. Review application logs for:
   - `Received paper.evaluated event for paperId: ...`
   - `Notification log persisted for author: ...`
   - warnings about null events/data or missing authors.

## Common pitfalls

- `RABBITMQ_SSL_ENABLED` defaults to `true`; set it explicitly to `false` for
  non-TLS local RabbitMQ.
- `NOTIFICATION_SERVICE_PORT` has no default in `application.yml`; define it in
  the environment or `.env`.
- `EUREKA_SERVER_URL` has no default in `application.yml`; define it for local
  runs even when you are only testing the REST endpoint.
- The listener only persists logs after receiving RabbitMQ events. Calling the
  REST endpoint before publishing an event returns an empty list.
- The persisted `sentAt` value is generated by the service at consumption time,
  not copied from the event `occurredAt`.
- The Dockerfile exposes `8086`, but Spring still listens on
  `NOTIFICATION_SERVICE_PORT`; keep the container port mapping aligned with that
  variable.