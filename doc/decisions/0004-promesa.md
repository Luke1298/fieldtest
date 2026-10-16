# 4. promesa for async

Status: accepted, 2026-10-16

## Context

Plug-ins may be async (an HTTP config source, a database recorder), and the client branches on operations that are often async. The library needs one promise abstraction that works on the JVM, in ClojureScript and in nbb, and that accepts native JS promises and `CompletableFuture`s.

## Decision

Use promesa. It wraps native promises on each platform, nbb bundles it, and its API is small. The library only uses functions that promesa 10.x and 11.x share, since nbb's bundled version may differ from the one a JVM host resolves.

## Consequences

promesa is a runtime dependency. The public API takes and returns plain promises, so callers don't need to use promesa themselves. A `branch` result is passed through as the same object, promise or not. `test-consumers/nbb` checks that nbb's built-in promesa is the one used when fieldtest arrives as a dependency.
