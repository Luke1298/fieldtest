# 3. Plug-ins are functions, not protocols

Status: accepted, 2026-10-16

## Context

The server needs three host-supplied behaviours: read configs, choose an arm, record a record. They could be protocols, multimethods or plain functions.

## Decision

Each plug-in is a function that returns a value or a promise. The selector takes one map, so it can gain keys without breaking implementations, and the recorder takes `(kind record)`.

## Consequences

Plug-ins are easy to write inline, wrap (`fieldtest.config/cached`), compose and stub, and they work unchanged in nbb, where protocol support is narrower. Nothing in the type system says what a plug-in must do, so the test kit checks behaviour instead. That's a stronger guarantee than a protocol signature anyway.
