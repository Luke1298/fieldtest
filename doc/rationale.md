# Rationale

## The server picks the arm

Weights live on the server so that changing them doesn't require a deploy. Keeping the assignment logic in one place also means a JVM service, a browser page and an nbb Lambda all split traffic the same way, instead of each having its own implementation that can get out of sync. Clients only need to know which arms they can run. They ask at the start of a request or job and use the answer for the rest of it.

"Server" is a slightly misleading name. It's a map you build with `fieldtest.server/server` and pass to `decide`. If you want it on the network you mount the HTTP handlers in front of it; if you don't, you call it in-process. Both go through the same code.

## Each call runs one arm

The operations this was written for have side effects, things like uploads and database writes. If fieldtest ran both versions so it could compare their results, every upload would happen twice. Retrying a failed `:b` on `:a` has the same problem. So `branch` calls exactly one arm, and if that arm throws, you get the exception back as it was thrown.

You can still fall back by hand: catch the error inside your `:b` arm, call the old code, and report the outcome as `fallback` so the analysis counts it.

GitHub's Scientist and the libraries based on it run both versions on every call. That works for pure code, but not for the kind of operations fieldtest is meant for.

## Feedback points at exposures

When the server makes a decision it writes an exposure record holding the arm, the revision, the sticky key and the client's context, under a random id. The client gets the id back with the decision. When it later reports a timing, the feedback carries that id along with the measurement, and to see timings per arm you join feedback to exposures on it.

This way only the server records the arm. If clients reported it themselves, a client bug could file a `:b` timing under `:a` and the numbers would look fine. It also makes exposures that never receive feedback easy to find, and those are often the first sign that an arm is failing (see [analysis](analysis.md)).

Exposure ids are 128 random bits and don't encode the sticky key or the arm, so they're safe to log.

## Sticky keys

The client decides what to send. For a page-load experiment a user id is a reasonable choice; for an upload experiment the upload id might be better. The server hashes the string and doesn't use it for anything else. fieldtest has no built-in idea of a user, because the right unit depends on the experiment and picking the wrong one biases the results without any error showing up.

## Plug-ins are functions

The config source, selector and recorder are plain functions that return a value or a promise. You can write one inline in a test, or wrap one in another function; `fieldtest.config/cached` is just a function that takes a config source and returns a caching one. They also work in nbb, which supports protocols less completely than ClojureScript does.

The cost is that a function signature says nothing about how the function should behave. The test kit is there to cover that: it calls your plug-in with a range of inputs and checks the results against what the server relies on.

## When tracking fails

If the config source throws, the server keeps using the last configs it read, and if the selector throws, that experiment gets `:a`. A recorder that throws loses the record but the response still goes out. On the client, a decider that errors or runs past its timeout leaves `branch` without a decision, so it runs `:a` and reports nothing. The table in [concepts](concepts.md) lists every case.

## Infrastructure

fieldtest does no I/O of its own beyond the optional HTTP transport. Reading environment variables, files, AWS or a database is up to the host's plug-ins, and `bb lint:no-infra` fails if code like that shows up under `src/`.

## Compatibility

Every wire document has a `v` field. Later versions may add fields but won't remove or rename them, and readers ignore fields they don't recognise. The Clojure API follows the same rule: functions can gain options, but what they already do won't change. That's also why selectors receive a single map; adding a key later won't break the selectors people have already written.
