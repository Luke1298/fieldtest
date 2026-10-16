# Analysis

fieldtest records; it doesn't analyse. This page describes the analysis the records are designed for. Everything below is per experiment and per revision. Never compare across revisions.

## Join exposures to feedback

Every exposure has an id, and feedback lists the exposure ids it measured. Join on that id to label each piece of feedback with the arm the server assigned:

```sql
select e.arm, f.metric, f.value, f.outcome
from exposures e
join feedback_exposures fe on fe.exposure_id = e.exposure_id
join feedback f on f.id = fe.feedback_id
where e.experiment = 'asset-host' and e.revision = 3;
```

## Per-arm summary

For each arm and metric, report:

- **count**: exposures, and feedback rows.
- **latency**: p50, p75 and p95 of `value` where `outcome = 'ok'`. Means hide tails, and tails are usually what a new implementation changes.
- **failure rate**: the share of feedback whose outcome is `error`, `timeout` or `abandoned`.
- **fallback rate**: the share with outcome `fallback`, if callers fall back by hand.

## Analyse by assigned arm

Group by the arm in the exposure, the one the server assigned, not by what the client says it ran. A client that couldn't run `:b` ran `:a` and reported `unsupported-arm`. Those units still count as `:b`. Dropping them would bias `:b` toward the clients that can run it. Report `unsupported-arm` separately: a lot of it means clients are out of date.

## Check sample-ratio mismatch

With `:b` at 20%, about 20% of exposures should be `:b`. Test the observed split against the configured weights (a chi-squared test is fine). A mismatch means something upstream is broken, for example caching, a filter, or clients that crash before asking. Results from a mismatched experiment can't be trusted, however good they look.

## Check missing feedback

Count exposures with no feedback, per arm. Some are expected (the user left, the process was frozen). If one arm has noticeably more, that arm may be failing so badly it never reports, and its failure rate is understated.

## Split by release

Exposures carry the client's context map. If clients send a release or build id there, split results by it. A change in either arm between releases shows up as a step, and you can drop the release that mixed two versions of an arm.

## A/A first

Before trusting an experiment, run it with identical code in `:a` and `:b`. The arms should show no sample-ratio mismatch, matching latency distributions and matching failure rates. Any difference is measurement error, and it is also the smallest effect you can hope to detect. This library's own test suite runs an A/A over 20,000 simulated keys for the same reason.
