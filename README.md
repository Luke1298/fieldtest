# fieldtest

fieldtest is a Clojure library for trying a new implementation of something on part of your production traffic and measuring whether it does better. It works on the JVM, in ClojureScript through shadow-cljs, and in nbb.

Say your app serves images from its own servers and you want to know whether a CDN would be faster for your users. You wrap the code that builds image URLs in a `branch` with two arms: `:default`, which is the code you have now, and `:cdn`, the new version. A server tells each client which arm to use, based on weights you set in config. The client runs that arm, times it, and reports back. If the CDN turns out to be faster, you raise its weight until everyone is on it, and then delete the branch.

`:default` has one special job. It's what runs whenever fieldtest can't give an answer, for example because the server is down, the experiment isn't in the config, or the client was told to use an arm it doesn't have. Every branch must declare it. The other arms can have any name you like.

Each call runs exactly one arm. The code being tested usually has side effects, like uploading a file or writing a row, and running both versions would do that twice. If the new arm throws, fieldtest doesn't retry with `:default`; the exception reaches your code the same way it would without fieldtest.

You supply the functions that load the config, choose the arm and store the measurements. In-memory versions are included for tests and local development.

## Trying it in a REPL

```clojure
(require '[fieldtest.client :as client]
         '[fieldtest.memory :as memory]
         '[fieldtest.server :as server])

(def store (atom []))
(def srv
  (server/server
   {:config-source (memory/config-source
                    {"asset-host" {:revision 1 :weights {:cdn 50} :sticky :hashed}})
    :recorder (memory/recorder store)}))

;; The client lists the arms it knows how to run.
(def asset-host {:experiment "asset-host" :revision 1 :arms [:default :cdn]})

;; Ask once per request (or page, or job) and reuse the answer.
(def decisions
  @(client/decide (client/in-process-decider srv) [asset-host]
                  {:sticky-key "user:42"}))

(def c {:decisions decisions
        :reporter (client/in-process-reporter srv)})

(def result
  (client/timed c {:exposure-id (:exposure-id (client/decision c asset-host))
                   :metric "asset-url"}
                #(:result (client/branch c asset-host
                                         {:default (fn [path] (str "https://app.example.com" path))
                                          :cdn (fn [path] (str "https://cdn.example.com" path))}
                                         "/logo.png"))))

result
;; => "https://app.example.com/logo.png" or "https://cdn.example.com/logo.png"

(memory/exposures @store)
(memory/feedback @store)
```

`user:42` gets the same URL every time, because the sticky key is hashed. Half of all keys land on `:cdn`, since that's its weight.

The store now holds two records. When the server makes a decision it records it as an *exposure*, which says that `user:42` was given `:default` or `:cdn` and has a random id. The timing the client reported is *feedback*, and it refers to the exposure by that id. Joining the two gets you timings per arm.

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

An experiment has a name and a revision number. Changing the revision reshuffles which keys get which arm, so numbers from different revisions shouldn't be compared.

The server is built from three functions: a config source that returns each experiment's weights, a selector that picks an arm for a request, and a recorder that stores exposures and feedback. The built-in selector hashes the sticky key, so a given key keeps getting the same arm until you change the weights or the revision.

The sticky key can be any string. Use whatever the assignment should stay consistent for, such as a user id or a job id.

Your code keeps working when fieldtest has problems. Without a decision, `branch` runs `:default` and reports nothing. A config source or selector that throws also leads to `:default`, and a recorder or reporter that fails only loses that record. [Concepts](doc/concepts.md) has the full table.

## Over HTTP

`fieldtest.http` has a decide handler and a feedback handler that take `{:body …}` and return `{:status :headers :body}`, so you can mount them in whatever web server you already run. On the client side there's an HTTP decider and reporter, built on `js/fetch` in ClojureScript and `java.net.http` on the JVM.

```clojure
(def decide-handler (http/decide-handler srv))     ; POST /decide
(def feedback-handler (http/feedback-handler srv)) ; POST /feedback

;; in the browser, in nbb, or on another JVM
(client/decide (http/decider "https://example.test/decide") [asset-host]
               {:sticky-key "user:42" :timeout-ms 300})
```

Decisions can also be passed along with `client/decisions->json`. A server-rendered page can embed them, or a Lambda can receive them in its event, and branch on them without asking the server again.

## How it differs from existing libraries

Touchstone is aimed at conversion testing in web apps, keeps its state in Redis, and picks arms with a bandit. fieldtest is aimed at latency and error rates and has no storage of its own.

GitHub's Scientist and its ports run the old and new code side by side and compare outputs, which is fine for pure code but not for uploads or database writes. fieldtest runs one arm per call and compares the arms across many calls.

OpenFeature evaluates feature flags but doesn't record exposures or collect timings. The two can be combined: `examples/openfeature-selector` is a selector that asks an OpenFeature client which variant to use.

## Writing your own plug-ins

To check that your own config source, selector or recorder behaves the way the server expects, run it through the test kit. It lives in `test-kit/` and can be added to your test dependencies:

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
