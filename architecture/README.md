# Architecture document set — LMS Repayment Platform

Companions to *Proposed Architecture — LMS Repayment Platform*. All target design.
None of it describes a current deployment, and none of the performance figures are
measured.

| Document | Answers | Audience |
|---|---|---|
| `NETWORK_ARCHITECTURE.md` | what can reach what, and how traffic is isolated | security review, infra |
| `DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` | how it is built, shipped, scaled and recovered | platform, SRE |
| `CAPACITY_AND_PERFORMANCE.md` | where the ceiling is and how much it handles | CTO, capacity planning |

## The three things a reviewer should take away

**1. The ceiling is the LMS, not the application.** On Java 25 with virtual threads the
platform no longer starves itself on a slow upstream — but the thread pool that used to
cap concurrent LMS calls is gone, and with it the backpressure that protected the LMS.
An explicit bulkhead (50 concurrent LMS calls, 30 SMS) is now the only thing bounding
that, and `maxReplicas × 50` must stay inside whatever concurrency the LMS agrees to
accept. See `CAPACITY_AND_PERFORMANCE.md` §2.

**2. Three inbound paths, not one.** Borrower traffic, vendor callbacks and the
monitoring plane are three trust populations with three authentication mechanisms.
Vendor callbacks get their own hostname, their own certificate, their own WAF policy and
an IP allowlist — never the borrower listener. See `NETWORK_ARCHITECTURE.md` §4.

**3. Redis availability equals service availability, deliberately.** Session state,
nonces, OTP hashes and concurrency versions live only in Redis with no local fallback,
because degrading to per-node state silently disables replay protection and attempt
counting across a cluster. That is the correct trade for a payment flow, and whoever owns
the SLA needs to have agreed to it in advance rather than discovered it during an
incident.

## Contradiction in the parent document that needs resolving

"Scale stateless app instances horizontally" and the WebSocket push channel cannot both
be true without a broker relay or Redis pub/sub fan-out. A WebSocket connection is pinned
to one pod; the Kafka consumer that receives the status event will usually be on a
different one. Options and a recommendation are in
`DEPLOYMENT_AND_CONTAINER_ARCHITECTURE.md` §6. The recommendation is to ship polling
first.

## Blocking questions

Carried from the individual documents, consolidated:

1. Real borrower volumes and month-end spike shape — without these, every capacity figure
   is a placeholder.
2. LMS committed p95/p99 and the concurrent-request ceiling they will accept — this sets
   the bulkhead limit and `maxReplicas` together, and neither can be chosen without it.
3. Vendor mTLS support and callback source IP ranges.
4. SMS provider: is a POST endpoint available? The current GET puts the OTP in a query
   string, which lands in access logs on hops outside your control.
5. WebSocket — required for launch, or deferred?
6. Distroless or UBI9 base image.
7. Secrets manager, and whether workload identity is available.
8. Confirmed log retention period and storage location.
