# 1. 32-bit FNV-1a for hashed buckets

Status: accepted, 2026-10-16

## Context

In `hashed` mode a sticky key has to land in the same bucket on the JVM, in browsers and in nbb, and ideally in other languages too, so people can check decisions outside Clojure. Clojure's `hash` gives different answers in Clojure and ClojureScript and isn't specified anywhere. A cryptographic hash would need either a dependency or Web Crypto, which is async.

## Decision

The bucket is `fnv1a32(utf8(experiment + "|" + revision + "|" + sticky-key)) mod 10000`. FNV-1a takes a few lines to write, has published reference values, and only needs 32-bit multiplication, which each platform does explicitly: `Math.imul` in JavaScript, masked `long` arithmetic on the JVM. Hashing UTF-8 bytes keeps non-ASCII keys consistent everywhere.

## Consequences

FNV-1a isn't cryptographic, so a client could choose keys that land in a particular arm. That doesn't matter here, since clients already choose their own sticky keys. It spreads keys evenly enough across 10,000 buckets, and the A/A conformance test checks that. `doc/selector-vectors.json` pins the exact behaviour for other implementations.
