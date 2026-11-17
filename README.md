# Makao Hub staff website

Invite-only, server-rendered staff workspace built with Play Framework 3.0.12, Java 17 and sbt. It connects to the existing Spring Boot staff API and exposes only the queue assigned to the authenticated staff role.

## Local run

Start the backend on port 8080, then run:

```bash
cd /home/hp/Desktop/makao-hub-web
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
sbt run
```

Open `http://localhost:9000/staff/sign-in`. IntelliJ should import `build.sbt` with Project SDK 17.

Authentication, staff-invitation and private-file requests allow 60 seconds for password
checks, SMTP/storage transfer and database commit; other API requests retain their 12-second limit. You can override this with
`MAKAO_AUTH_REQUEST_TIMEOUT`, for example `60s`. This changes only the HTTP waiting
time, not the OTP expiry or sending limits. A timeout does not trigger an automatic
retry because the backend may already have sent a code or completed the operation.

## Production-required configuration

- `MAKAO_API_BASE_URL=https://...` — TLS backend endpoint.
- `MAKAO_SESSION_SECURE=true` — Secure staff session cookie.
- `PLAY_SECRET=...` — unique secret held in the deployment secret manager.

The application refuses to start in production when the API uses cleartext HTTP, the cookie is not Secure, or `PLAY_SECRET` is left at its development value.

## Test

```bash
sbt clean test
```

See [implementation notes](docs/implementation-notes.md) for the route, class and security walkthrough. See [design QA](design-qa.md) for the visual comparison report.

## Deliberate boundaries

- Payment and booking records are viewable by Staff leads and Payment reviewers. Either role can approve an eligible TEST-only booking release; this updates the simulated ledger and never initiates a Paystack transfer. Refund and live-payout controls remain unavailable.
- JWT access and refresh tokens never enter browser storage; the browser receives only a random opaque session ID.
- The in-memory token store is for one-node developer testing. `RECOMMENDED`: choose a protected distributed session store before multi-node deployment.
- Deployment provider, KMS, regulated custody arrangement, refund rules and staff invitation delivery channel remain `TBC`.
# MakaoHub_Admin
# MakaoHub_Admin
