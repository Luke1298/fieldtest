# Analysis

fieldtest doesn't include any analysis. This page covers what to do with the records, always looking at one experiment at one revision.

## Joining exposures and feedback

Each exposure has an id, and each piece of feedback lists the exposure ids it measured. Joining on that id tells you which arm the server assigned to each measurement:

```sql
select e.arm, f.metric, f.value, f.outcome
from exposures e
join feedback_exposures fe on fe.exposure_id = e.exposure_id
join feedback f on f.id = fe.feedback_id
where e.experiment = 'asset-host' and e.revision = 3;
```

The Postgres example has the schema behind this query and a fuller per-arm version of it.

## What to report per arm

For each arm and metric, look at how many exposures and how many feedback rows there are, the p50, p75 and p95 of `value` for `ok` outcomes, and the share of feedback that came back as `error`, `timeout` or `abandoned`. If your code falls back by hand, look at the `fallback` share too.

Prefer percentiles to means. Changes often show up in the tail, and a mean hides them.

## Group by the assigned arm

Group by the arm on the exposure. That is the arm the server assigned, and it can differ from what the client actually ran. Suppose a client couldn't run `:b`. It ran `:a` instead and reported `unsupported-arm`, but that unit still belongs to `:b`. If you drop those units, `:b` looks better than it is because only the clients that could run it are left. Count `unsupported-arm` separately. If there's a lot of it, some clients are probably out of date.

## Sample-ratio mismatch

With `:b` at 20%, roughly 20% of exposures should be `:b`. Check the actual split against the configured weights; a chi-squared test is enough. A mismatch means something upstream is wrong, for example a cache serving stale decisions or clients crashing before they ask, and the experiment's results can't be trusted.

## Missing feedback

Count exposures that never got feedback, per arm. Some of that is normal, because users leave and processes get frozen. But if one arm has noticeably more, it may be failing so badly that it never reports, and its error rate is lower than reality.

## Releases

Exposures carry the client's context map. If clients put a release or build id in it, you can split the results by release. When either arm changes between releases you'll see a step, and you can drop the release where an arm was half one version and half another.

## Run an A/A first

Before trusting any experiment, run it with the same code in `:a` and `:b`. You should see no sample-ratio mismatch and matching latency and failure rates. Any difference you see is noise, and it gives you a rough idea of how small an effect you'll be able to detect. fieldtest's own test suite runs an A/A over 20,000 simulated keys for this reason.
