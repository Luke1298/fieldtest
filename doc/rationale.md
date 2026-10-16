# Rationale

fieldtest exists to answer one question safely: is the new implementation of this operation better than the old one, measured on real traffic? Each choice below follows from that.

## The server decides, the client executes

Weights, stickiness and arm choice live on a server so they can change without a deploy and so every runtime (JVM, browser, nbb) splits traffic the same way. The client only knows which arms it can run. It asks once per scope, then runs whatever it was told.

The server is a value, not a process. A host can call it in-process, put it behind HTTP, or both, and the decision is identical either way.

## One arm, no retry

An operation may have side effects: an upload, a write, a charge. Running two arms to compare them, or retrying a failed candidate on the baseline, could do the side effect twice. So `branch` runs exactly one arm, and an error from that arm reaches the caller unchanged. Whether to fall back is the caller's decision, and when they do, they can report it as `fallback` feedback.

This is the main difference from Scientist-style libraries, which run both versions and so need code without side effects.

## Feedback carries no arm

Feedback names exposure ids, never the arm, revision or sticky key. The arm is known only from the server's exposure record, and analysis joins on the id. That keeps clients from mislabelling results, keeps the arm in one place, and means a client that ran the wrong arm can't hide it. An exposure without feedback is a signal too (see [analysis](analysis.md)).

Exposure ids are random, so they reveal nothing about the arm, key or experiment.

## The sticky key is opaque

The client chooses what the sticky key identifies: a user, a device, a session, an upload. The server only hashes it. There is no built-in notion of a user, because what makes a fair unit differs per experiment and the library shouldn't guess.

## Plug-ins are functions

A config source, selector and recorder are plain functions that return a value or a promise. There are no protocols to implement, no records to construct and no lifecycle. That makes them trivial to write, wrap (as `fieldtest.config/cached` does) and stub in tests, and it works the same in Clojure, ClojureScript and nbb. The [test kit](writing-plugins.md) checks behaviour, which a protocol couldn't do anyway.

## Failures never hurt the operation

Tracking is optional and the operation is not. A failing config source keeps the last good configs, a failing selector gives `:a`, a failing recorder or reporter loses a record, and a decider that fails or is slow gives no decisions, so the client runs `:a` untracked. None of these change what the operation returns.

## No infrastructure

The library reads no environment, stores nothing durable and calls no cloud service. Those are a host's decisions, made in plug-ins. `bb lint:no-infra` keeps it that way.

## Accrete-only compatibility

The wire format carries a version, and new versions only add fields. Readers ignore fields they don't know. Public functions gain options rather than changing meaning. Plug-ins take a single map so new keys don't break them.
