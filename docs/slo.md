# Service level objectives

Three objectives, each over a rolling 30 days. They are the only thing in this repository
allowed to page someone for user-visible impact; everything else that pages covers a hole the
objectives cannot see.

| Objective | Target | Good event | Measured at | Budget per 30 days |
| --- | ---: | --- | --- | ---: |
| `api-availability` | 99.5% | a request routed to the API that does not end in a 5xx | the gateway | 0.5% of requests — 3 h 36 min of total outage |
| `api-latency` | 99% | a request routed to the API that completes within 500 ms | the gateway | 1% of requests |
| `worker-freshness` | 99% | an event applied to the read model within 10 s of its transaction committing | the worker | 1% of events |

The executable half is `observability/prometheus/rules/slo.yml`, with unit tests in
`observability/prometheus/tests/slo.test.yml`. The dashboard is **Blueprint / SLOs**.

## Why these numbers

**99.5%, not 99.9%.** Everything runs on one node in one location. A kernel update that needs
a reboot takes every replica down at once for a few minutes; a 99.9% objective has 43 minutes
of budget a month and would spend a quarter of it on routine patching. More to the point, a
single server cannot promise more availability than its host, and promising it anyway is how
an SLO stops meaning anything. 99.5% leaves room for host maintenance and for our own
mistakes, which is what a budget is for. Going higher is an architecture change — a second
node — not a configuration change, and the cost of that is in `docs/cost-analysis.md`.

**500 ms for 99% of requests.** Every endpoint is one indexed query or one cache read. Half a
second is where a human notices, and it is far enough above normal latency that crossing it
means something is wrong — pool exhaustion, a slow query, a GC storm — rather than that the
day was busy. Measured through the gateway on the local stack with `make load` (60 requests a
second, a mix of reads and writes), p99 is 37 ms: the threshold sits an order of magnitude
above normal, on purpose.

**10 seconds for 99% of events.** The relay polls every second and the consumer applies an
event in milliseconds, so normal freshness is one to two seconds. Ten is the point at which a
user who just wrote an item and reloaded the summary would see a stale number and reasonably
call it a bug.

## What the SLIs deliberately do and do not count

- **The API is measured at the gateway, not in the API.** When no replica is ready the gateway
  answers 503 itself, and when one dies mid-request it answers 502 or 504. None of those reach
  the application's metrics, so an SLI computed from them reports 100% through a total outage.
  This was found by stopping the API and watching which series moved: only the gateway's router
  metrics counted the failures.
- **4xx is not an error.** A 404 or a 409 is the caller being wrong. Counting it would page
  someone because a client shipped a bug, and it is exactly the class of defect the error
  handler fix in S2 was about: a 500 for an unmapped path would have burned budget on typos.
- **Freshness is measured from the commit, not from the publish.** Relay delay counts against
  it. The alternative — timing from when the record reached Kafka — hides the outbox entirely,
  which is the part most likely to be slow.
- **Events that never arrive are not in the freshness SLI.** A freshness measurement is taken
  when an event is applied; a dead worker applies nothing and the SLI goes quiet rather than
  bad. `WorkerDown` and `OutboxRelayStalled` in `alerts.yml` exist to close that gap, and they
  page for the same reason the objective would.

## Alerting

Multi-window, multi-burn-rate, from the Google SRE Workbook (chapter 5). The burn rate is how
many times faster than sustainable the budget is being spent: at 1 it lasts exactly 30 days.

| Alert | Severity | Burn rate | Long window | Short window | Budget spent when it fires |
| --- | --- | ---: | ---: | ---: | ---: |
| `ErrorBudgetBurnFast` | page | 14.4 | 1 h | 5 min | 2% |
| `ErrorBudgetBurnFast` | page | 6 | 6 h | 30 min | 5% |
| `ErrorBudgetBurnSlow` | ticket | 3 | 1 d | 2 h | 10% |
| `ErrorBudgetBurnSlow` | ticket | 1 | 3 d | 6 h | 10% |

The long window decides whether the problem is significant; the short one decides whether it
is still happening. Together they fire within minutes of a real problem and clear within
minutes of the fix. A single-window alert has to choose between the two. The unit tests
include the clearing behaviour: after a burst, the 14.4x pair lets go within minutes, but the
6x pair holds until its 30-minute window has also moved past it.

**Low traffic** is the known weakness of ratio-based alerting. With a handful of requests an
hour, one failure is a large ratio. The `for:` clauses absorb single blips, and the
availability objective is not meaningful below a few requests a minute — at that volume the
honest signal is a synthetic probe, not a budget.

## Error budget policy

The budget exists to be spent on change. What happens as it runs down:

| Budget remaining | What changes |
| --- | --- |
| above 25% | Ship normally. |
| 0–25% | Changes that touch the request path or the async path need a rollback plan written down before they merge. |
| below 0 | Feature changes stop. Merges to `main` are fixes, reverts and reliability work until the 30-day window recovers above zero. |

Any single incident that spends more than 20% of a budget gets a written review — what
happened, why the alerts fired when they did, and what stops it recurring. The review is
blameless and the output is a change, not a document.

## Reviewing the objectives

Once a quarter, against the 30-day history on the SLO dashboard:

- **Budget never touched?** The objective is probably too loose to protect anyone, or the
  system is being run too conservatively. Either tighten it or ship faster.
- **Budget regularly exhausted?** Either the objective promises more than this architecture can
  deliver — fix the architecture or lower the number, honestly — or the delivery process is
  breaking things, and the policy above should already be biting.
- **A page that did not correspond to user pain, or user pain with no page?** The SLI is
  measuring the wrong thing. That is a bug in `slo.yml` and it gets a test like any other.
