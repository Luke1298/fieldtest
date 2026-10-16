# 3. Plug-ins are functions, not protocols

Status: accepted, 2026-10-16

## Context

The server needs the host to supply configs, arm selection and record storage. These could be protocols, multimethods or plain functions.

## Decision

Each plug-in is a function that returns a value or a promise. The selector takes a single map, so new keys can be added later without breaking existing selectors. The recorder takes `kind` and `record`.

## Consequences

Plug-ins can be written inline, wrapped (like `fieldtest.config/cached`) and stubbed in tests, and they work the same in nbb, where protocol support is more limited. The downside is that nothing in the signature says what a plug-in has to do. The test kit fills that gap by checking behaviour, which a protocol wouldn't have specified either.
