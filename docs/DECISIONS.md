# Engineering decisions

## PostgreSQL is the system of record

Transfers and ledger entries are committed in PostgreSQL. Kafka can distribute events but does not replace the authoritative ledger. This keeps financial state changes inside database transactions with explicit constraints.

## Every transfer produces two ledger entries

A pay-in debits the platform clearing account and credits the sandbox user account. A pay-out debits the sandbox user account and credits the platform clearing account. Both entries use the same transfer identifier, amount, currency, and commit boundary.

The current sandbox does not enforce account balances. Balance reservations and insufficient-funds handling remain outside this synthetic reliability demo.

## Money uses decimal storage

The API accepts at most two fractional digits and PostgreSQL stores amounts as `NUMERIC(19,2)`. Binary floating-point types are not used for money.

## Idempotency is scoped to the account

The database has a unique constraint on `account_id` and `idempotency_key`. Repeating an identical request returns the original result. Reusing the same key for a different request within the same account returns HTTP 409.

## Events begin in a transactional outbox

The transfer, two ledger entries, and outbox event commit in the same transaction. When Kafka is enabled, the publisher claims pending records, sends them, and marks them published only after broker acknowledgement.

## Free hosting is separated by responsibility

Render runs the API and Neon stores PostgreSQL data. The public deployment deliberately has no managed Kafka dependency because the available broker offer was time-limited and running a broker in Render's small, temporary container would be unreliable. GitHub Actions verifies the broker-neutral Kafka adapter against an ephemeral Testcontainer instead.

## The public deployment is a sandbox

Synthetic transfer endpoints are anonymous so a recruiter can use the website or Android client immediately. Public requests pass through a per-client minute limit, while each anonymous POST atomically consumes a PostgreSQL-backed daily quota that survives application restarts. Supplying a valid portfolio key bypasses those demo quotas; recovery and reconciliation operator endpoints always require it. This is deliberate sandbox access control, not end-user authentication.

The Render deployment explicitly trusts its proxy-appended `X-Forwarded-For` value and reads the rightmost address so a client-supplied prefix cannot bypass the minute limit. Direct and local deployments ignore forwarded addresses by default. Prometheus, reconciliation, and recovery routes share the operator-key boundary.

Account-history reads and request bodies are bounded, JSON rejects unknown or trailing values, response locations are relative rather than derived from proxy headers, and correlation IDs accept only a small log-safe character set. Actuator endpoints are private by default, with only health probes and basic application info explicitly public.

## Reconciliation treats PostgreSQL as authoritative

The Kafka consumer stores each `event_id` before comparing the event payload with the transfer, its exact debit and credit, and its originating outbox row. A duplicate event is acknowledged without repeating reconciliation. Differences are persisted for operator inspection and counted in Prometheus instead of silently changing financial state.
