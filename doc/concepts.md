# Concepts

## Terms

An **experiment** is a named choice between implementations of one operation. Each implementation is an **arm**: `:a` is the current implementation, and `:b`, `:c` and so on are candidates.

Every experiment has a **revision**. Changing it reassigns every sticky key from scratch, and numbers from different revisions shouldn't be compared.

A candidate's **weight** is the percentage of units it gets. `:a` has no weight; it gets everyone who isn't drawn into a candidate.

The **stickiness mode** is either `hashed` or `none`. With `hashed`, the same sticky key gets the same arm as long as the experiment, revision and weights stay the same. With `none`, every request is a fresh draw. A request that doesn't send a sticky key is treated as `none` either way.

The **server** decides arms and accepts feedback. It's a value you build from plug-ins and call like any other function. The **client** is the code that has arms to choose between. It gets decisions from a **decider** and sends feedback through a **reporter**, and both can be in-process or over HTTP.

The **sticky key** is a string the client sends so it keeps getting the same arm.

A **decision** is one experiment's revision, arm and exposure id, as returned by the server. The server also writes an **exposure** for every decision it makes, and feedback refers back to it by exposure id.

**Feedback** is a timing or count reported against one or more exposure ids, with an outcome of `ok`, `error`, `timeout`, `abandoned`, `fallback` or `unsupported-arm`.

A **plug-in** is one of the functions the server is built from: the config source, the selector or the recorder. The **test kit** checks that a plug-in does what the server expects of it.

## Configuring an experiment

```clojure
{"asset-host" {:revision 3 :weights {:b 20} :sticky :hashed}}
```

The config source returns a map like this, or the equivalent JSON. If an experiment is missing a field, uses an unknown stickiness mode, gives `:a` a weight, or has a negative or non-numeric weight, it's ignored and everyone gets `:a`.

## How units are split

The default selector hashes `experiment|revision|sticky-key` with 32-bit FNV-1a over the UTF-8 bytes, and takes the result mod 10,000 to get a bucket. The candidates are sorted by name, and each owns a fixed block of 10,000/n buckets. A candidate at weight w takes the first w × 100 buckets of its block. If w is bigger than the block, it's capped.

Because the blocks don't move, raising `:b` from 10 to 20 only pulls new units in from `:a`. Nobody who was in `:b` leaves, and `:c` doesn't change at all. Adding or removing a candidate does move the blocks, so bump the revision when you do that.

## Running an experiment

Start by shipping the branch with the candidate at weight 0. Everyone runs `:a`, but every call still produces an exposure, so you can check that decisions and feedback are flowing.

Before trying the real candidate, run an A/A test: put the same code in `:a` and `:b` and give `:b` some weight. The two arms should look identical. If they don't, the problem is in your measurement, and you want to find that out before it gets mixed up with a real difference. See [analysis](analysis.md).

Then put the candidate in `:b` and turn it up in config. Units already in `:b` stay there as the weight grows.

When you've picked a winner, delete the losing arms and the `branch` call, and remove the experiment from config.

## When something fails

| Situation | Decision | Exposure | Feedback |
|---|---|---|---|
| Experiment absent or revision mismatch | `:a` | yes | normal |
| Selector throws or returns an unsupported arm | `:a` | yes | normal |
| Config source fails | last good configs, else `:a` | yes | normal |
| Decider fails or times out | none, client runs `:a` | no | none |
| Decision names an arm the client lacks | client runs `:a` | already made | `unsupported-arm` |
| Recorder or reporter fails | unchanged | lost | lost; the operation is unaffected |
