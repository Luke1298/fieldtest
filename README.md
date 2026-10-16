# fieldtest

Run experiments on real traffic from Clojure, ClojureScript and nbb: a server decides which arm each client runs, and clients report how it went.

You have an operation with a hardened implementation (`:a`) and one or more candidates (`:b`, `:c`, …). Ship the candidates behind a branch at 0%, ramp them up from config, and compare timings and failure rates per arm. Each call runs exactly one arm, so candidates may have side effects. Nothing runs twice and nothing is retried.

fieldtest has no opinion about infrastructure. Where weights come from, how arms are chosen and where records go are all plug-ins: plain functions that return a value or a promise.

## 60 seconds, in one process

```clojure
(require '[fieldtest.client :as client]
         '[fieldtest.memory :as memory]
         '[fieldtest.server :as server])

;; A server is a value built from plug-ins. These are the in-memory ones.
(def store (atom []))
(def srv
  (server/server
   {:config-source (memory/config-source
                    {"greeting" {:revision 1 :weights {:b 50} :sticky :hashed}})
    :recorder (memory/recorder store)}))

;; The client describes the experiment with the arms it can run.
(def greeting {:experiment "greeting" :revision 1 :arms [:a :b]})

;; Ask once per scope (a request, a page, a job) and reuse the decisions.
(def decisions
  @(client/decide (client/in-process-decider srv) [greeting]
                  {:sticky-key "user:42"}))

(def c {:decisions decisions
        :reporter (client/in-process-reporter srv)})

;; Run the decided arm, timing it against the decision's exposure id.
(def result
  (client/timed c {:exposure-id (:exposure-id (client/decision c greeting))
                   :metric "greeting.render"}
                #(:result (client/branch c greeting
                                         {:a (fn [who] (str "Hello, " who))
                                          :b (fn [who] (str "Hi, " who))}
                                         "Ada"))))

result
;; => "Hello, Ada" or "Hi, Ada", the same every time for "user:42"

(memory/exposures @store) ;; one exposure: which arm "user:42" got
(memory/feedback @store)  ;; one timing, joined to it by exposure id
```

On ClojureScript and nbb the same code works with promises instead of `@`.

## Install

fieldtest isn't on Clojars yet. Depend on it locally or from git:

```clojure
;; deps.edn
{:deps {io.github.Luke1298/fieldtest {:local/root "../fieldtest"}}}
;; or
{:deps {io.github.Luke1298/fieldtest {:git/url "https://github.com/Luke1298/fieldtest" :git/sha "…"}}}
```

nbb projects use the same coordinates in `nbb.edn`. shadow-cljs builds pick it up through `deps.edn` or `:source-paths`.

Runtime dependencies are promesa and, on the JVM only, cheshire. cheshire is never loaded by ClojureScript or nbb (see [decision 5](doc/decisions/0005-jvm-json.md)).

## Concepts at a glance

- **experiment**: a named choice between implementations of one operation, with a **revision**. A new revision reshuffles everyone, and results never cross revisions.
- **arm**: one implementation. `:a` is the baseline and gets every unit not drawn into a candidate.
- **server**: decides arms and receives feedback, through three plug-ins: a **config source**, a **selector** and a **recorder**. Run it in-process, behind HTTP, or both.
- **client**: runs exactly the decided arm with `branch`, and reports **feedback** (timings, outcomes) with `timed` and `report!`.
- **sticky key**: an opaque string the client sends to keep getting the same arm. The client decides what it identifies.
- **exposure**: the server's record of a decision. Its random id is the only link between a decision and later feedback.

When anything fails, the operation still runs. A missing decision runs `:a` untracked, a failing plug-in gives `:a`, and a failing recorder or reporter loses the record but never changes the result. See [concepts](doc/concepts.md).

## Over HTTP

`fieldtest.http` provides framework-free handlers (`{:body …}` in, `{:status :headers :body}` out) and an HTTP decider and reporter that use `js/fetch` or `java.net.http`:

```clojure
(def decide-handler (http/decide-handler srv))     ; mount at POST /decide
(def feedback-handler (http/feedback-handler srv)) ; mount at POST /feedback

;; in the browser, nbb or another JVM
(client/decide (http/decider "https://example.test/decide") [greeting]
               {:sticky-key "user:42" :timeout-ms 300})
```

Decisions also serialize with `client/decisions->json`, so a server-rendered page or a Lambda payload can hand them to another runtime that branches without asking again.

## How it compares

- **Touchstone** is a Clojure split-testing library for web apps that stores its tests in Redis and chooses arms with a bandit. fieldtest leaves storage and selection to plug-ins, runs on the JVM, in the browser and in nbb, and is aimed at measuring operations (latency, failure rate) rather than conversions.
- **Scientist-style libraries** run both the old and new code and compare their results, so the code must be free of side effects. fieldtest runs exactly one arm per call, which makes it safe for operations with side effects, and compares arms statistically across calls instead of call by call.
- **OpenFeature** is a vendor-neutral API for evaluating feature flags. fieldtest can sit on top of a flag system: a selector can defer to an OpenFeature client (see `examples/openfeature-selector`). fieldtest adds the exposure records, feedback and the single-arm, never-retry branch that experiments need.

## Plug-ins and the test kit

Write your own config source, selector or recorder as a function, then check it against the server's contracts with the test kit (its own library in `test-kit/`; add it to your test deps):

```clojure
(let [{:keys [pass? failures]} @(test-kit/check-recorder my-recorder)]
  (is pass? (pr-str failures)))
```

See [writing plug-ins](doc/writing-plugins.md).

## Docs

- [Rationale](doc/rationale.md)
- [Concepts](doc/concepts.md)
- [Writing plug-ins](doc/writing-plugins.md)
- [Analysis](doc/analysis.md)
- [Decisions](doc/decisions/)
- [Selector test vectors](doc/selector-vectors.json) for implementations in other languages

## Development

`bb ci` lints and runs the JVM, shadow-cljs (node) and nbb suites. `bb tasks` lists the rest.

## License

Copyright © 2026 the fieldtest authors. Distributed under the [Eclipse Public License 2.0](LICENSE).
