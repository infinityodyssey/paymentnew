# payment-backend — authentication and token issuance

Spring Boot 4.0 / Java 25 LTS / Oracle / Redis / Kafka. Covers handshake through payment-token
issuance for the gold-loan repayment journey. Payment initiation, gateway integration
and webhooks are the next phase.

---

## Two risks you are carrying

**The OTP travels in a URL query string.** The SMS gateway takes the message as a GET
parameter, so the code and the mobile number appear in the request line and therefore in
the access logs of every hop — your egress proxy, the provider's load balancer, their web
server. Everything else in this service is built so the OTP never reaches a log; this one
hop defeats that and it cannot be fixed from our side. Ask the provider for a POST
endpoint. Until then this needs a named owner and written confirmation of their access-log
retention. See `SmsOtpSender`.

**A 5-digit code rests entirely on the attempt cap.** Three guesses against 100,000
values is roughly 1 in 33,000 per session, which is fine. Raise the cap, remove the
lockout, or let the counter reset on resend, and it degrades ten times faster than a
6-digit code would. `app.otp.max-attempts`, `app.otp.lockout-seconds` and the counter
reset in `OtpService.issue` are load-bearing. Treat changes to them as security changes.

---

## Running it

```bash
export PAYMENT_TOKEN_HMAC_SECRET="$(openssl rand -base64 48)"
export PAYMENT_OTP_PEPPER="$(openssl rand -base64 48)"
export ACTUATOR_PASSWORD="$(openssl rand -base64 24)"
export ORACLE_URL=jdbc:oracle:thin:@//host:1521/SERVICE
export ORACLE_USER=PAYMENT_APP ORACLE_PASSWORD=... ORACLE_SCHEMA=PAYMENT
export REDIS_HOST=... KAFKA_BOOTSTRAP=...
export GOLDLOAN_TOKEN_URL=... GOLDLOAN_FETCH_URL=...
export SMS_TOKEN_URL=... SMS_SEND_URL=...
export ALLOWED_ORIGINS=https://localhost:4200

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The application will not start without the three secrets, and rejects anything under 32
bytes or containing a placeholder string. The DBA applies `src/main/resources/db/*.sql`;
`ddl-auto` is `validate` and the app never alters the schema.

Serve over TLS even locally. The session cookie is `__Host-` prefixed and `Secure`, so a
browser on plain HTTP discards it and every call after the handshake fails.

---

## What needs your input before it runs

Three places where your existing code was not available, each isolated to one method so
the surrounding logic does not change:

- `LmsTokenProvider.requestToken()` — `getEXApiToken(fiid)`'s request shape was not
  shared. Currently POSTs `{"fiid": n}` and reads the token from
  `app.global.lms-token-json-field`. Replace the body if your endpoint differs; the Redis
  cache, early expiry, interaction recording and the evict-on-401 path are independent of it.
- `SmsTokenProvider.requestToken()` — same for `getSmsAccessToken()`.
- `app.global.sms-message-template` — set to the DLT-registered wording. Do not edit the
  text without re-registering the template or the operator will drop the message.

Also unanswered: whether `success[]` can contain **different mobile numbers** for one
customer. `LmsHttpClient.extract` collects distinct numbers and rejects the lookup with
`AGREEMENT_AMBIGUOUS` if there is more than one, rather than using `success[0]`. Sending
to the first entry when the numbers differ would deliver one borrower's code to a
different person's phone. If your LMS guarantees one number per customer the branch never
fires; if it can fire, you find out in the audit table instead of in a complaint.

---

## Where things are recorded, and why it is split three ways

| | Contents | Purpose |
|---|---|---|
| `PAYMENT_AUDIT_EVENTS` | authentication events, agreement number in full, hashed session and IP | the regulatory record. Synchronous write. |
| `LMS_INTERACTION_LOG` | full upstream request and response | reconciliation and disputes. Async write. |
| Kafka | metadata only, agreement hashed | SIEM, dashboards |
| Logs | metadata only | operations |

The audit write is synchronous and the interaction write is not. Losing an interaction row
costs a debugging session; losing an audit row loses the evidence that an authentication
happened, and an async write can be dropped on pod shutdown.

Kafka is never the system of record. At-least-once delivery, finite retention and consumer
lag mean gaps and duplicates — fine for a dashboard, unacceptable for something a regulator
might ask to see.

Payloads live in Oracle rather than in the log pipeline because an Oracle grant has an
access model and a retention you set. `app.lms.log-payloads` will put them in the logs too,
under a dedicated `LMS_PAYLOAD` logger your platform team can drop before Loki;
`EncryptionProfileGuard` refuses to start if it is enabled outside dev or test.

---

## Rate limiting

There is none in this codebase, by design — the API gateway handles per-IP and per-route
limits.

Two controls stayed, and they are not rate limits in the network sense: the OTP attempt cap
and the per-agreement send budget, both in `OtpService`. The gateway cannot enforce either,
because both key on the agreement number, which is inside an AES-GCM envelope it cannot
read. If they ever move, a proxy pool stays under every per-IP threshold and still walks a
100,000-value code space.

---

## Concurrency

Session writes are compare-and-swap, not blind overwrites. The session is a Redis hash
with `data` and `version`; `SessionService.save` runs a Lua script that writes only if the
version still matches what the caller loaded. Redis executes Lua atomically, so the
compare and the write cannot interleave. A mismatch throws `SessionConflictException`
(409) and is **not** retried — in a payment flow, "someone else already advanced this
session" is a reason to stop.

Optimistic concurrency rather than a distributed lock, deliberately. A lock service adds
stale locks after a pod dies and "lock store unreachable" as a second way to be down, for
a contention pattern that is rare. A version compare costs one round trip and fails loudly
exactly when it matters.

Two other atomic points:

- **OTP consumption.** Two requests carrying the same correct code can both pass the
  attempt cap and both match the hash — the read and compare cannot be made atomic without
  serialising every verification. `DEL` is atomic and returns true to exactly one caller;
  only that caller issues a token. Previously both did, the second `save` overwrote the
  first, and the borrower held a token that had silently stopped working because
  `validate` compares against `session.tokenId`.
- **`claimOnce(sessionId, operation)`.** `SET NX` electing a single winner, used for token
  issuance now and for token consumption and payment initiation next phase.

`SessionConcurrencyIT` covers all three. It needs a live Redis and is `@Disabled` until CI
provisions one — without it the CAS is an assertion in a comment.

## Spring Boot 4 notes

`AntPathRequestMatcher` and `MvcRequestMatcher` are gone in Security 7; everything uses
`PathPatternRequestMatcher`, which also rejects mid-pattern wildcards. Jackson 3 means
`tools.jackson.*` and built-in `java.time` support.

**Java 25 LTS, virtual threads on.** JEP 491 (JDK 24) removed the synchronized-block
pinning that made virtual threads unsafe on 21 — Lettuce and BouncyCastle both
synchronize on paths this service uses constantly, and on 21 that pinned carriers under
load.

**The consequence people miss:** a bounded platform-thread pool was acting as an
accidental bulkhead, capping concurrent LMS calls at the thread count whether anyone
intended it or not. Virtual threads remove that cap. Ten thousand borrowers now produce
ten thousand concurrent LMS calls unless something bounds them, so
`resilience4j.bulkhead` on `LmsHttpClient` and `SmsOtpSender` is now the **only** thing
providing backpressure to the upstreams. Raising those limits, or removing the
annotations, means a month-end spike reaches the LMS unthrottled — which converts your
capacity problem into their outage.

The bulkhead and the circuit breaker are not redundant. The breaker reacts to sustained
failure once it has enough samples; the bulkhead bounds the first thirty seconds of a
degradation, which with a 30-second LMS timeout is exactly when the damage happens.
`max-wait-duration` is zero so a request without a permit fails fast rather than queueing,
which is what keeps `verify-otp` and status polling alive while the LMS is slow.

`AsyncConfig` is now an unbounded virtual-thread executor with no rejection policy. That
is fine for audit-adjacent writes and is precisely why the upstream calls carry explicit
bulkheads instead.

## Not built yet

The two critical findings from the review of the legacy backends, both payment-phase:

1. **Webhook signature verification.** Both legacy backends accepted
   `POST /transactions/{id}/webhook?status=SUCCESS` unauthenticated — one called a
   `verifyGatewaySignature` whose body was `return true;` and ignored the result.
2. **Server-side amount validation.** Both took the amount from the client with only a
   `@DecimalMin("1.00")` check.

Then: single-use token consumption via `SET NX`, transaction ownership checks on receipt
reads, and idempotent initiation with atomic `SET NX` rather than get-then-set.

Nothing here has been compiled, load-tested or penetration-tested.
