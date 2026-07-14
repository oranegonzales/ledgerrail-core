# Aiven Kafka integration

## What Aiven does in LedgerRail

Neon PostgreSQL remains the system of record for transfers, ledger entries, and event delivery state. Aiven hosts Apache Kafka as the event transport. It receives facts that have already committed successfully, beginning with `transfer.completed.v1`.

This separation allows later services to react independently:

- A reconciliation worker can compare transfer and ledger totals.
- A notification worker can simulate receipts without delaying the transfer request.
- Audit and analytics consumers can build their own read models.
- New consumers can be added without changing the transfer transaction.

Aiven is not a replacement for PostgreSQL, and a Kafka acknowledgement does not decide whether a transfer completed.

## Delivery model

The transfer, both ledger entries, and the outbox row commit in one PostgreSQL transaction. The scheduled publisher then:

1. Recovers claims whose five-minute lease expired.
2. Claims a batch with `FOR UPDATE SKIP LOCKED` so multiple application instances do not normally publish the same row concurrently.
3. Sends JSON to `transfer.completed.v1` using the transfer ID as the Kafka key.
4. Waits for the producer acknowledgement before marking the row `PUBLISHED`.
5. Returns a failed send to `PENDING` with capped exponential backoff.
6. Marks the row `FAILED` after eight unsuccessful claims.

This is at-least-once delivery. A process can stop after Kafka accepts a record but before PostgreSQL stores `PUBLISHED`, causing a later retry to produce a duplicate. Every consumer must therefore store and de-duplicate the `event_id` header or `eventId` payload field.

## Free-plan fit

Aiven Free is appropriate for this synthetic portfolio workload. The plan currently provides up to 250 kB/s in, 250 kB/s out, three-day retention, Schema Registry, REST proxy, and encryption on disk and over the network. It is a development service, not the production infrastructure for real financial traffic.

## Create the Aiven service

1. Create an Aiven for Apache Kafka service on the Free plan.
2. Choose a region near the Render service when the free plan offers one.
3. Enable SASL authentication if it is not already enabled.
4. In Connection information, select SASL and record the host, SASL port, username, and password.
5. Download `ca.pem`.
6. Create a topic named `transfer.completed.v1`.

The bootstrap value is only `HOST:SASL_PORT`. Do not include `kafka://`, `https://`, `jdbc:`, a username, or a password.

## Configure Render

Keep `KAFKA_ENABLED=false` until every value below is saved.

In the Render service, open Environment and add a Secret File:

| Filename | Contents |
| --- | --- |
| `aiven-ca.pem` | The complete contents of the downloaded `ca.pem` file |

Add these environment variables:

| Name | Value |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Aiven SASL host and port, such as `example.aivencloud.com:12345` |
| `KAFKA_TOPIC` | `transfer.completed.v1` |
| `KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-256` |
| `KAFKA_SASL_JAAS_CONFIG` | The one-line value shown below with the Aiven username and password |
| `KAFKA_SSL_TRUSTSTORE_TYPE` | `PEM` |
| `KAFKA_SSL_TRUSTSTORE_LOCATION` | `/etc/secrets/aiven-ca.pem` |

Use this shape for the JAAS value, including the final semicolon:

```text
org.apache.kafka.common.security.scram.ScramLoginModule required username="YOUR_AIVEN_USERNAME" password="YOUR_AIVEN_PASSWORD";
```

After saving the secret file and variables, set `KAFKA_ENABLED=true` and deploy. Do not commit the password, JAAS value, or downloaded certificate to GitHub.

## Verify the connection

1. Create one synthetic transfer from the dashboard API lab.
2. Open the Aiven topic viewer for `transfer.completed.v1` and confirm one JSON event appears.
3. In Neon, confirm the matching `outbox_events` row has status `PUBLISHED`, `attempts` equal to `1`, and a non-null `published_at`.
4. Open `/actuator/prometheus` and find `ledgerrail_outbox_events_total{outcome="published"}`.

If Aiven is unavailable, transfer creation still commits to PostgreSQL. The event stays `PENDING` between retries or becomes `FAILED` after the retry limit, making the failure visible without losing the financial record.

## Local and CI verification

The default local application keeps Kafka disabled. `mvn verify` starts disposable PostgreSQL and Kafka containers and proves the full path from an API transfer through the outbox to a consumed Kafka record. Docker is required for the integration tests.
