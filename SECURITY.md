# Security policy

LedgerRail is a synthetic-data portfolio sandbox. It must never process real financial data, credentials, personal data, or production secrets.

## Supported version

Security fixes are applied to the latest commit on `main`.

## Reporting a vulnerability

Please report vulnerabilities privately through the repository's **Security → Advisories → New draft security advisory** flow. Do not include secrets or exploit details in a public issue.

## Security boundaries

- Public transfer routes accept synthetic data only and are protected by burst and persistent daily quotas.
- Operator, recovery, reconciliation, and Prometheus routes require the server-side portfolio key.
- The browser and Android clients do not contain or transmit the operator key.
- Database credentials are supplied only through hosting environment variables.
- Kafka is disabled in the public free-tier deployment and exercised with disposable infrastructure in CI.

Automated tests, dependency review, Dependabot, and CodeQL supplement code review; they do not guarantee the absence of vulnerabilities.

