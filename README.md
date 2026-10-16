# fieldtest

fieldtest is a library for A/B testing code paths in production. It runs on the JVM, in the browser through shadow-cljs, and in nbb.

It's meant for rewrites of code that already runs in production, like the way a page fetches its assets or the way uploads are split into chunks. You send some real traffic to the new version and check whether it's faster or fails less before switching everyone over.

The old code stays as arm `:a` and the new code becomes `:b`. A server, which can just be a value in your own process, tells each client which arm to run. The client runs that arm once and reports how long it took and whether it worked. You move traffic toward `:b` by editing config.

Only one arm runs for any given call, so the new code is allowed to write to a database or call an API. A failed `:b` isn't retried on `:a`; if you want a fallback, write it into your arm and report it as one.

You decide where the config lives, how arms are picked and where the records end up. Each of those is a function you pass in. In-memory versions are included for tests and local work.

## Trying it in a REPL

```clojure
(require '[fieldtest.client :as client]
         '[fieldtest.memory :as memory]
         '[fieldtest.server :as server])

(def store (atom []))
(def srv
  (server/server
   {:config-source (memory/config-source
                    {"greeting" {:revision 1 :weights {:b 50} :sticky :hashed}})
    :recorder (memory/recorder store)}))

;; The client lists the arms it knows how to run.
(def greeting {:experiment "greeting" :revision 1 :arms [:a :b]})

;; Ask once per request (or page, or job) and reuse the answer.
(def decisions
  @(client/decide (client/in-process-decider srv) [greeting]
                  {:sticky-key "user:42"}))

(def c {:decisions decisions
        :reporter (client/in-process-reporter srv)})

(def result
  (client/timed c {:exposure-id (:exposure-id (client/decision c greeting))
                   :metric "greeting.render"}
                #(:result (client/branch c greeting
                                         {:a (fn [who] (str "Hello, " who))
                                          :b (fn [who] (str "Hi, " who))}
                                         "Ada"))))

result
;; => "Hello, Ada" or "Hi, Ada"

(memory/exposures @store)
(memory/feedback @store)
```

Whichever greeting `user:42` gets, they keep getting it. The store ends up with one exposure, saying which arm `user:42` was given, and one timing that points back to it by exposure id.

In ClojureScript and nbb, use the promises directly instead of `@`.

## Installing

fieldtest isn't on Clojars yet, so depend on a local checkout or on git:

```clojure
;; deps.edn
{:deps {io.github.Luke1298/fieldtest {:local/root "../fieldtest"}}}
;; or
{:deps {io.github.Luke1298/fieldtest {:git/url "https://github.com/Luke1298/fieldtest" :git/sha "…"}}}
```

nbb takes the same coordinates in `nbb.edn`, and shadow-cljs picks it up from `deps.edn`.

On the JVM it depends on promesa and cheshire. ClojureScript and nbb only need promesa, and cheshire is never loaded there. [Decision 5](doc/decisions/0005-jvm-json.md) explains how that is enforced.

## How it fits together

An experiment has a name and a revision. Bumping the revision reshuffles who gets which arm, so don't compare numbers across revisions.

The server takes three plug-ins: a config source that returns the weights, a selector that picks an arm, and a recorder that stores exposures and feedback. The default selector hashes the sticky key, so a given key keeps getting the same arm.

The sticky key can be any string. Use whatever you want assignments to stay consistent for, such as a user id or a job id.

Each decision is recorded as an exposure with a random id, and the client includes that id when it reports a timing. To see timings per arm, join feedback to exposures on the id.

Your code keeps working when fieldtest has problems. Without a decision the client runs `:a` and reports nothing, a failing config source or selector also leads to `:a`, and a failing recorder or reporter just loses that record. [Concepts](doc/concepts.md) has the full table.

## Over HTTP

`fieldtest.http` has a decide handler and a feedback handler that take `{:body …}` and return `{:status :headers :body}`, so you can mount them in whatever server you already run. On the client side there's an HTTP decider and reporter, built on `js/fetch` in ClojureScript and `java.net.http` on the JVM.

```clojure
(def decide-handler (http/decide-handler srv))     ; POST /decide
(def feedback-handler (http/feedback-handler srv)) ; POST /feedback

;; in the browser, in nbb, or on another JVM
(client/decide (http/decider "https://example.test/decide") [greeting]
               {:sticky-key "user:42" :timeout-ms 300})
```

Decisions can also be passed along with `client/decisions->json`. A server-rendered page can embed them, or a Lambda can receive them in its event, and branch on them without asking the server again.

## Other libraries

Touchstone is aimed at conversion testing in web apps, keeps its state in Redis, and picks arms with a bandit. fieldtest is aimed at latency and error rates and has no storage of its own.

GitHub's Scientist and its ports run the old and new code side by side and compare outputs, which is fine for pure code but not for uploads or database writes. fieldtest runs one arm per call and compares the arms across many calls.

OpenFeature evaluates feature flags but doesn't record exposures or collect timings. The two can be combined: `examples/openfeature-selector` is a selector that asks an OpenFeature client which variant to use.

## Writing your own plug-ins

To check that a plug-in behaves the way the server expects, run it through the test kit. It lives in `test-kit/` and can be added to your test dependencies:

```clojure
(let [{:keys [pass? failures]} @(test-kit/check-recorder my-recorder)]
  (is pass? (pr-str failures)))
```

[Writing plug-ins](doc/writing-plugins.md) covers what each check looks for.

## Docs

- [Rationale](doc/rationale.md)
- [Concepts](doc/concepts.md)
- [Writing plug-ins](doc/writing-plugins.md)
- [Analysis](doc/analysis.md)
- [Decisions](doc/decisions/)
- [Selector test vectors](doc/selector-vectors.json), for anyone writing a compatible selector in another language

## Development

`bb ci` runs the linters and the test suites on the JVM, under shadow-cljs and in nbb. `bb tasks` lists everything else.

## License

Copyright © 2026 the fieldtest authors. Distributed under the [Eclipse Public License 2.0](LICENSE).
