# Kafka adapter and delivery model

## Deployment position

LedgerRail's Kafka integration is broker-neutral. It is not connected to a managed broker in the public Render deployment because a short-lived credit offer is not a dependable portfolio dependency and Kafka should not be squeezed into Render's 512 MB application container.

`KAFKA_ENABLED=false` and `KAFKA_CONSUMER_ENABLED=false` are therefore intentional in Render. The complete path is still executable and verified: GitHub Actions runs `mvn verify`, Testcontainers starts disposable PostgreSQL and Apache Kafka instances, and the integration test proves that a committed transfer reaches Kafka, becomes `PUBLISHED`, and reconciles against authoritative state.

This keeps the public claims precise:

- The live demo proves the API, idempotency, atomic ledger, PostgreSQL outbox, security, and operational endpoints.
- CI proves the Kafka producer, headers, acknowledgement handling, final delivery state, consumer de-duplication, and reconciliation.
- A managed broker can be attached later without changing domain logic.

## Delivery model

Neon PostgreSQL remains the system of record for transfers, ledger entries, and event delivery state. The transfer, both ledger entries, and the outbox row commit in one PostgreSQL transaction. When Kafka is enabled, the scheduled publisher:

1. Recovers claims whose five-minute lease expired.
2. Claims a batch with `FOR UPDATE SKIP LOCKED` so multiple application instances do not normally publish the same row concurrently.
3. Sends JSON to `transfer.completed.v1` using the transfer ID as the Kafka key.
4. Adds `event_id` and `event_type` record headers.
5. Waits for the producer acknowledgement before marking the row `PUBLISHED`.
6. Returns a failed send to `PENDING` with capped exponential backoff.
7. Marks the row `FAILED` after eight unsuccessful claims.

This is at-least-once delivery. A process can stop after Kafka accepts a record but before PostgreSQL stores `PUBLISHED`, causing a later retry to produce a duplicate. Every consumer must therefore store and de-duplicate the `event_id` header or `eventId` payload field.

The reconciliation consumer implements that contract. Its database claim uses `event_id` as a primary key, so concurrent or later duplicate deliveries perform no second reconciliation. The first delivery compares the payload with the transfer, exactly one debit, exactly one credit, matching totals and currencies, and the transactional outbox row. It records either `MATCHED` or a durable `MISMATCH` with an operator-readable reason.

## Verification in CI or locally

The public GitHub repository runs the following command on every push and pull request:

```bash
mvn verify
```

Docker is required when running it locally. `OutboxPublisherIntegrationTest` automatically:

1. Starts PostgreSQL 17 and Apache Kafka containers.
2. Creates a synthetic transfer through the API.
3. Publishes its committed outbox event.
4. Consumes the Kafka record and verifies its key, topic, payload, and headers.
5. Confirms that PostgreSQL records `PUBLISHED`, one attempt, a publication timestamp, and no error.
6. Confirms that reconciliation records `MATCHED`.
7. Publishes the same event again and proves event-ID de-duplication.

No long-running local Kafka installation and no cloud credentials are required.

## Optional managed-broker contract

Do not enable this in Render unless a real broker is available. Keep `KAFKA_ENABLED=false` until all required values have been saved.

| Name | Purpose |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker bootstrap address in `HOST:PORT` form |
| `KAFKA_TOPIC` | Topic name; defaults to `transfer.completed.v1` |
| `KAFKA_SECURITY_PROTOCOL` | Broker protocol, such as `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | SASL mechanism required by the provider |
| `KAFKA_SASL_JAAS_CONFIG` | Provider-specific JAAS login string |
| `KAFKA_SSL_TRUSTSTORE_TYPE` | Truststore type when a custom CA is required |
| `KAFKA_SSL_TRUSTSTORE_LOCATION` | Runtime path to the truststore or PEM file |
| `KAFKA_ENABLED` | Starts scheduled publication only when set to `true` |
| `KAFKA_CONSUMER_ENABLED` | Starts reconciliation consumption only when set to `true` |
| `KAFKA_CONSUMER_GROUP` | Consumer group; defaults to `ledgerrail-reconciliation-v1` |

The bootstrap value is only `HOST:PORT`. Do not add `kafka://`, `https://`, `jdbc:`, a username, or a password. Keep credentials in the hosting provider's secret manager, never in GitHub.

When a future broker is connected, verify a new transfer in three places:

1. Consume the JSON record from `transfer.completed.v1`.
2. Confirm that the matching `outbox_events` row is `PUBLISHED` with a non-null `published_at`.
3. Find `ledgerrail_outbox_events_total{outcome="published"}` in `/actuator/prometheus`.

If the broker is unavailable, transfer creation still commits to PostgreSQL. The event remains `PENDING` between retries or becomes `FAILED` after the retry limit, making delivery failure visible without losing the financial record.

An operator can list failed events at `GET /api/v1/operations/outbox/failed` and requeue one with `POST /api/v1/operations/outbox/{eventId}/replay`. Both endpoints require `X-Portfolio-Key`. Replay resets delivery attempts, records an audit count and timestamp, and leaves the original transfer and ledger untouched.
