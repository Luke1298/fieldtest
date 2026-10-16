# OpenFeature selector example

A fieldtest selector that defers arm choice to an OpenFeature client (the Java SDK, here with its in-memory provider). The flag key is the experiment name, the targeting key is the sticky key, and the flag's string variant names the arm. The decide request's context map becomes OpenFeature evaluation context, along with `fieldtest.experiment` and `fieldtest.revision`.

fieldtest still owns the experiment. The server only asks about experiments in its own config, keeps recording exposures and feedback, and uses a variant only if it is a configured candidate the client supports. Anything else becomes `:a`.

```sh
clojure -M:test
```

The tests run the fieldtest test kit against the selector and route decisions through a server.

```clojure
(def of-client (.getClient (dev.openfeature.sdk.OpenFeatureAPI/getInstance)))
(fieldtest.server/server {:config-source …
                          :selector (example.openfeature-selector/selector of-client)
                          :recorder …})
```
