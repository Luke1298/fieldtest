# Concepts

## Glossary

- **experiment**: a named choice between implementations of one operation.
- **arm**: one implementation. `:a` is the hardened baseline; `:b`, `:c`, … are candidates.
- **revision**: the experiment's version. Changing it reassigns every sticky key independently of the previous revision, and results are never compared across revisions.
- **weight**: the percentage of units a candidate draws. `:a` is never weighted; it receives everything else.
- **stickiness mode**: `hashed` gives the same arm for the same sticky key, experiment, revision and weights. `none` chooses afresh on every request. A request without a sticky key is always decided as `none`.
- **server**: decides arms and receives feedback. It is a value built from plug-ins, not a process.
- **client**: code with arms to choose between. It gets decisions from a **decider** and reports through a **reporter**, each either in-process or over HTTP.
- **sticky key**: an opaque string the client sends to keep getting the same arm.
- **decision**: one experiment's revision, arm and exposure id, returned by the server.
- **exposure**: the server's record that a decision was made. Its id is the only link to later feedback.
- **feedback**: a timing or outcome reported against exposure ids. The outcome is one of `ok`, `error`, `timeout`, `abandoned`, `fallback` or `unsupported-arm`.
- **plug-in**: a host-supplied function: config source, selector or recorder.
- **test kit**: contract checks anyone can run against their own plug-in.

## An experiment's config

```clojure
{"asset-host" {:revision 3 :weights {:b 20} :sticky :hashed}}
```

The config source returns a map like this, or JSON with the same shape. An experiment that is missing a field, names an unknown stickiness mode, weights `:a` or has a negative or non-numeric weight is treated as absent, so everyone gets `:a`.

## How units are split

The default selector hashes `experiment|revision|sticky-key` (32-bit FNV-1a over UTF-8) into one of 10,000 buckets. Candidates, ordered by name, each own a fixed slice of 10,000/n buckets and draw the first `weight × 100` of them. A weight above the slice width is clamped to it.

Because slices are fixed, raising `:b` only moves units from `:a` into `:b`. No one leaves `:b` and `:c` is untouched. Adding or removing a candidate changes every slice, so do that with a new revision.

## Lifecycle

1. **Ship at 0%.** Add the experiment to config with the candidate at weight 0 and deploy the branch. Everyone runs `:a`, and every call is still an exposure, so you can check the plumbing.
2. **Run an A/A first.** Point `:b` at the same code as `:a` and give it a weight. The arms should be indistinguishable. If they aren't, the measurement is broken, not the candidate (see [analysis](analysis.md)).
3. **Ramp.** Put the real candidate in `:b` and raise its weight in config. Sticky keys already in `:b` stay there.
4. **Decide.** Compare per-arm latency and failure rates for one revision.
5. **Remove the branch.** Make the winner the only code path, delete the arms you don't need, and remove the experiment from config. A branch left in place forever is a fork in your code.

## When things fail

| Situation | Decision | Exposure | Feedback |
|---|---|---|---|
| Experiment absent or revision mismatch | `:a` | yes | normal |
| Selector throws or returns an unsupported arm | `:a` | yes | normal |
| Config source fails | last good configs, else `:a` | yes | normal |
| Decider fails or times out | none, client runs `:a` | no | none (untracked) |
| Decision names an arm the client lacks | client runs `:a` | already made | `unsupported-arm` |
| Recorder or reporter fails | unchanged | lost | lost; operation unaffected |
