# Writing plug-ins

A server is built from three plug-ins and an optional error hook:

```clojure
(server/server {:config-source my-config-source
                :selector      my-selector        ; optional, defaults to weighted-hash
                :recorder      my-recorder
                :error-hook    (fn [{:keys [plugin error experiment]}] ...)})
```

Each plug-in is a plain function. It may return a value or a promise (promesa, a JS promise, or a `CompletableFuture`).

## Config source

```clojure
(fn [] {"asset-host" {:revision 3 :weights {:b 20} :sticky :hashed}})
```

Returns the current configs as a map of experiment name to config. JSON-shaped data with string keys and string arms is fine.

Contract:

- It settles in reasonable time.
- It returns a map of valid experiment configs, or it fails by throwing or rejecting. The server then keeps the last good configs and reports the failure to the error hook.
- Experiments that are invalid are dropped and reported. Others still work.

The server calls the config source on every decide request. Wrap a slow or remote source with `fieldtest.config/cached`, which calls it at most once per TTL and backs off after a failure.

## Selector

```clojure
(fn [{:keys [experiment config sticky-key supported-arms context]}] :b)
```

Chooses an arm for one experiment. `config` is the normalized config, with `:sticky :none` when the request has no sticky key. `context` is the decide request's context map, passed through unchanged.

Contract:

- It never throws or hangs on valid input.
- It returns `:a` or a candidate that is both configured and in `supported-arms`. Anything else is replaced with `:a` and reported.
- In `hashed` mode it returns the same arm every time for the same sticky key, experiment, revision and weights.

If the default selector doesn't fit (say, weights come from a flag service), write one that defers to it. See `examples/openfeature-selector`.

## Recorder

```clojure
(fn [kind record] ...)   ; kind is :exposure or :feedback
```

Receives every exposure and every feedback record. See `fieldtest.records` for their shapes. Join them on the exposure id.

Contract:

- It accepts every record shape without throwing, rejecting or hanging.
- It tolerates concurrent calls.
- Failures are reported to the error hook and never change a response, but a lost record is lost, so make it reliable.

`decide` waits for the recorder before it responds, so a serverless host doesn't freeze before an exposure is written. Keep recording fast, or buffer inside the recorder.

## Running the test kit

Add the test kit to your test classpath. It is its own library in `test-kit/`, so depend on it from a test alias (`io.github.Luke1298/fieldtest-test-kit {:local/root "…/fieldtest/test-kit"}`, or the same with `:git/url` and `:deps/root "test-kit"`). Then call a check from any test runner. Each check returns a promise of `{:pass? :failures}`, where each failure has a `:reason` keyword and a `:message`.

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

On ClojureScript or nbb, wait for the promise with your runner's async support (this repo uses a `defasynctest` macro) instead of `@`.

| Check | Options | Failure reasons |
|---|---|---|
| `check-config-source` | `:timeout-ms`, `:calls` | `:hangs`, `:not-a-map`, `:invalid-experiment` |
| `check-selector` | `:timeout-ms`, `:repeats` | `:throws`, `:hangs`, `:unconfigured-arm`, `:unsupported-arm`, `:unstable` |
| `check-recorder` | `:timeout-ms`, `:concurrency`, `:read-back` | `:throws`, `:hangs`, `:concurrent-failure`, `:missing-records` |
