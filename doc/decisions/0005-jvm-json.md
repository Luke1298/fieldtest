# 5. cheshire for JSON on the JVM, js/JSON elsewhere

Status: accepted, 2026-10-16

## Context

The HTTP transport and decision handoff need JSON on every runtime. ClojureScript and nbb have `js/JSON` built in. The JVM has nothing in the standard library. The options were an optional `clojure.data.json` resolved at runtime, encode/decode functions passed in by the host, or a real dependency.

## Decision

fieldtest depends on cheshire on the JVM. `fieldtest.json` requires it only inside a `#?(:clj ...)` branch, and uses `js/JSON` in the `:cljs` branch.

Keywords encode as their full name (`:a/b` becomes `"a/b"`), insts encode as ISO-8601 with milliseconds in UTC on every runtime, and decoding keywordizes object keys.

## Guarding nbb and ClojureScript

cheshire must never be loaded by nbb or a ClojureScript build. Two checks enforce that:

- `bb lint:no-infra` reads every file under `src/` with the `:cljs` and nbb reader features and fails if any cheshire symbol survives.
- `test-consumers/nbb` is a downstream nbb project that depends on fieldtest through `:local/root`, so cheshire's and promesa's jars are on its classpath. It checks that cheshire isn't loaded, that nbb's built-in promesa is used rather than the jar's source, and that decide, branch and handoff work. `bb test` runs it.

## Consequences

JVM hosts get cheshire and Jackson transitively. Hosts that already use another JSON library carry both. Hosts can still exclude cheshire with `:exclusions` if they never use `fieldtest.http` or `fieldtest.json`, since nothing else requires it.
