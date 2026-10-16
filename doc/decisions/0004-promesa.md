# 4. promesa for async

Status: accepted, 2026-10-16

## Context

Plug-ins may be async, for example a config source that makes an HTTP call or a recorder that writes to a database, and many of the operations clients branch on are async too. The library needs one promise API that works on the JVM, in ClojureScript and in nbb, and that accepts native JS promises and `CompletableFuture`s.

## Decision

Use promesa. It wraps the native promise type on each platform, nbb already bundles it, and the API is small. fieldtest only uses functions that exist in both promesa 10.x and 11.x, because the copy nbb bundles may not match the version a JVM project resolves.

## Consequences

promesa is a runtime dependency, but callers don't have to use it themselves, since the public API takes and returns ordinary promises. `branch` passes its result through untouched, promise or not. `test-consumers/nbb` checks that when fieldtest comes in as a dependency, nbb still uses its bundled promesa.
