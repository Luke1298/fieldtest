# 2. Fixed slices per candidate

Status: accepted, 2026-10-16

## Context

Experiments get ramped, with `:b` going from 0% to 5% to 20%. The obvious layout puts candidates end to end (`:b` takes buckets 0–20, `:c` takes 20–40), but then growing `:b` shifts every `:c` unit, and someone who was in `:c` can end up in `:b` halfway through the experiment.

## Decision

Candidates are sorted by name and each owns a fixed block of 10,000/n buckets. A candidate takes the first `weight × 100` buckets of its own block, capped at the block size.

## Consequences

Changing a weight only moves units between that candidate and `:a`, and a test.check property checks this. In exchange, with n candidates none of them can go above 100/n percent. Adding or removing a candidate moves every block, so it should come with a new revision. Most experiments should have one or two candidates, where the cap is 100% or 50%.
