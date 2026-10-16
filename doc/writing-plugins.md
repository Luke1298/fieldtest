# Writing plug-ins

A server takes three plug-ins and, optionally, an error hook:

```clojure
(server/server {:config-source my-config-source
                :selector      my-selector        ; defaults to the built-in weighted-hash selector
                :recorder      my-recorder
                :error-hook    (fn [{:keys [plugin error experiment]}] ...)})
```

Each one is an ordinary function. It can return a plain value or a promise, and any of promesa promises, JS promises and `CompletableFuture`s will do.

## Config source

```clojure
(fn [] {"asset-host" {:revision 3 :weights {:cdn 20} :sticky :hashed}})
```

Returns the current configs, keyed by experiment name. String keys and string arm names, as you'd get from parsing JSON, are fine.

The server calls it on every decide request. It should answer reasonably quickly with a map of valid experiments, or throw (or reject) when it can't. When it fails, the server keeps using the last configs it read successfully and passes the error to the error hook. If a single experiment in the map is invalid, that one is dropped and reported, and the rest still work.

If reading configs is slow or goes over the network, wrap the source in `fieldtest.config/cached`. That calls it at most once per TTL and waits a little before retrying after a failure.

## Selector

```clojure
(fn [{:keys [experiment config sticky-key supported-arms context]}] :cdn)
```

Picks an arm for one experiment. `config` has already been normalized, and if the request had no sticky key, its `:sticky` is set to `:none`. `context` is whatever map the client put in its decide request.

A selector shouldn't throw or hang on valid input. It should return `:default` or a candidate that is both in the config and in `supported-arms`; the server replaces anything else with `:default` and reports it. In `hashed` mode, the same sticky key, experiment, revision and weights should always give the same arm.

If the weights really live in a flag service, write a selector that asks it. `examples/openfeature-selector` does this for OpenFeature.

## Recorder

```clojure
(fn [kind record] ...)   ; kind is :exposure or :feedback
```

Gets every exposure and every piece of feedback. The record shapes are documented in `fieldtest.records`, and the two are joined on exposure id.

It has to accept every record shape and cope with being called concurrently. If it throws, the server reports the error and carries on, but that record is gone, so it's worth making the recorder dependable.

`decide` waits for the recorder before it responds. That way a serverless host can't freeze the process before the exposure is written, but it also means a slow recorder makes decisions slow. Keep writes quick, or buffer inside the recorder.

## The test kit

The test kit is a separate library in `test-kit/`. Add it to a test alias, either as `io.github.luke1298/fieldtest-test-kit {:local/root "…/fieldtest/test-kit"}` or with `:git/url` and `:deps/root "test-kit"`, and call its checks from whatever test runner you use. Each check returns a promise of `{:pass? :failures}`, and every failure has a `:reason` keyword and a `:message`.

```clojure
(ns my.recorder-test
  (:require [clojure.test :refer [deftest is]]
            [fieldtest.test-kit :as test-kit]
            [my.recorder :as recorder]))

(deftest recorder-meets-the-contract
  (let [r (recorder/make test-db)
        {:keys [pass? failures]} @(test-kit/check-recorder
                                   r {:read-back #(recorder/all-records test-db)})]
    (is pass? (pr-str failures))))
```

In ClojureScript or nbb you can't deref, so wait on the promise with your runner's async support. This repo's tests use a `defasynctest` macro for that.

| Check | Options | Failure reasons |
|---|---|---|
| `check-config-source` | `:timeout-ms`, `:calls` | `:hangs`, `:not-a-map`, `:invalid-experiment` |
| `check-selector` | `:timeout-ms`, `:repeats` | `:throws`, `:hangs`, `:unconfigured-arm`, `:unsupported-arm`, `:unstable` |
| `check-recorder` | `:timeout-ms`, `:concurrency`, `:read-back` | `:throws`, `:hangs`, `:concurrent-failure`, `:missing-records` |
