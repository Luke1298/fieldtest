-- Per-arm count, latency percentiles and failure rate for one experiment
-- revision, by the arm the server assigned (see doc/analysis.md).
select e.arm,
       count(distinct e.exposure_id)                                  as exposures,
       count(f.id)                                                    as feedback,
       percentile_cont(0.50) within group (order by f.value)
         filter (where f.outcome = 'ok')                              as p50,
       percentile_cont(0.75) within group (order by f.value)
         filter (where f.outcome = 'ok')                              as p75,
       percentile_cont(0.95) within group (order by f.value)
         filter (where f.outcome = 'ok')                              as p95,
       avg(case when f.outcome in ('error', 'timeout', 'abandoned') then 1.0 else 0.0 end)
         filter (where f.id is not null)                              as failure_rate,
       count(distinct e.exposure_id) filter (where f.id is null)      as missing_feedback
from exposures e
left join feedback_exposures fe on fe.exposure_id = e.exposure_id
left join feedback f on f.id = fe.feedback_id and f.metric = ?
where e.experiment = ? and e.revision = ?
group by e.arm
order by e.arm;
