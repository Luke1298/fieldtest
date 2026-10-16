# 5. cheshire for JSON on the JVM, js/JSON elsewhere

Status: accepted, 2026-10-16

## Context

The HTTP transport and decision handoff both need JSON. ClojureScript and nbb have `js/JSON`, but the JVM has no JSON support in the standard library. The options were to resolve `clojure.data.json` at runtime if it happened to be there, to make the host pass in encode and decode functions, or to take a real dependency.

## Decision

fieldtest depends on cheshire on the JVM. `fieldtest.json` only requires it inside a `#?(:clj ...)` branch and uses `js/JSON` in the `:cljs` branch.

On every runtime, keywords encode with their namespace (`:a/b` becomes `"a/b"`), insts encode as UTC ISO-8601 with milliseconds, and object keys decode to keywords.

## Keeping cheshire out of nbb and ClojureScript

nbb and ClojureScript builds must never load cheshire. Two checks make sure of that:

- `bb lint:no-infra` reads every file under `src/` with the `:cljs` and nbb reader features and fails if any cheshire symbol is still there.
- `test-consumers/nbb` is a small nbb project that depends on fieldtest through `:local/root`, which puts the cheshire and promesa jars on its classpath. It checks that cheshire never loads, that nbb uses its own promesa instead of the jar's source, and that decide, branch and handoff all work. It runs as part of `bb test`.

## Consequences

JVM projects pull in cheshire and Jackson. If a project already uses another JSON library, it ends up with both. A project that never touches `fieldtest.http` or `fieldtest.json` can exclude cheshire with `:exclusions`, because nothing else requires it.
