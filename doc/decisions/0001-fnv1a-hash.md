# 1. 32-bit FNV-1a for hashed buckets

Status: accepted, 2026-10-16

## Context

In `hashed` mode a sticky key must land in the same bucket on the JVM, in browsers and in nbb, and ideally in any other language that wants to check its decisions. Clojure's `hash` differs between Clojure and ClojureScript and isn't specified. Cryptographic hashes are available everywhere only asynchronously (Web Crypto) or need a dependency.

## Decision

The bucket is `fnv1a32(utf8(experiment + "|" + revision + "|" + sticky-key)) mod 10000`. FNV-1a is a few lines, has published reference values, and needs only 32-bit multiplication, which each platform does explicitly (`Math.imul` in JavaScript, masked `long` arithmetic on the JVM). The input is UTF-8 bytes, so non-ASCII keys hash the same everywhere.

## Consequences

FNV-1a isn't cryptographic, and a client could pick keys to land in an arm. Sticky keys come from the client anyway, so this costs nothing. Its distribution over 10,000 buckets is even enough for splits; the A/A conformance test checks that. `doc/selector-vectors.json` pins the behaviour for other implementations.
