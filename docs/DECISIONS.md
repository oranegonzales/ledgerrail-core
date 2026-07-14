# Engineering decisions

## PostgreSQL is the system of record

Transfers and ledger entries are committed in PostgreSQL. Kafka will distribute events but will not replace the authoritative ledger. This keeps financial state changes inside database transactions with explicit constraints.

## Every transfer produces two ledger entries

A pay-in debits the platform clearing account and credits the sandbox user account. A pay-out debits the sandbox user account and credits the platform clearing account. Both entries use the same transfer identifier, amount, currency, and commit boundary.

Version 0.1 does not enforce account balances. Balance reservations and insufficient-funds handling belong to the asynchronous workflow milestone.

## Money uses decimal storage

The API accepts at most two fractional digits and PostgreSQL stores amounts as `NUMERIC(19,2)`. Binary floating-point types are not used for money.

## Idempotency is scoped to the account

The database has a unique constraint on `account_id` and `idempotency_key`. Repeating an identical request returns the original result. Reusing the same key for a different request within the same account returns HTTP 409.

## Events begin in a transactional outbox

The transfer, two ledger entries, and outbox event commit in the same transaction. A later publisher will send pending outbox records to Kafka and mark them published only after broker acknowledgement.

## Free hosting is separated by responsibility

Render runs the API, Neon stores PostgreSQL data, and Aiven will provide Kafka. This arrangement fits free-tier memory limits and avoids running a database or broker on temporary container storage.

## The public deployment is a sandbox

Every `/api/` endpoint requires a portfolio API key. The key is a basic abuse-control mechanism for a synthetic-data demo, not a replacement for user authentication or authorization.
