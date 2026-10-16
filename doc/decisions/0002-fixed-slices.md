# 2. Fixed slices per candidate

Status: accepted, 2026-10-16

## Context

Experiments are ramped: `:b` goes from 0% to 5% to 20%. If raising a weight moved units between candidates, a user in `:b` could land in `:c` mid-experiment, mixing their results. The simple cumulative scheme (`:b` takes 0–20, `:c` takes 20–40) moves every `:c` unit when `:b` grows.

## Decision

Candidates, ordered by name, each own a fixed slice of 10,000/n buckets and draw the first `weight × 100` of them. A weight above the slice width is clamped.

## Consequences

Changing a weight only moves units between that candidate and `:a`, and a test.check property checks that. The cost is a cap: with n candidates no candidate can exceed 100/n percent. Adding or removing a candidate changes every slice, so that should come with a new revision. Experiments rarely have more than two or three candidates, so the cap rarely matters.
