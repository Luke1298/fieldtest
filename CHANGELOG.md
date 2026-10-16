# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-16

### Added

- Experiment server built from plug-ins (config source, selector, recorder) with failure isolation.
- Weighted-hash selector over 32-bit FNV-1a with fixed per-candidate slices, and published test vectors.
- Client: `branch` (every branch declares a `:default` arm, which runs whenever there's no usable decision), deciders with timeout fallback, fire-and-forget reporters, `timed`, and JSON decision handoff.
- HTTP transport: wire format v1, framework-free handlers, HTTP decider and reporter.
- In-memory plug-ins, a cached config source wrapper, and a plug-in test kit.
- Docs and examples (in-process, Ring server, browser client, nbb Lambda, Postgres recorder, OpenFeature selector).

[Unreleased]: https://github.com/Luke1298/fieldtest/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Luke1298/fieldtest/releases/tag/v0.1.0
