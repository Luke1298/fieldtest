# OpenFeature selector example

A selector that lets an OpenFeature client choose the arm. It uses the Java SDK with its in-memory provider. The flag key is the experiment name, the targeting key is the sticky key, and the flag's string variant is the arm. The decide request's context map is passed in as evaluation context, along with `fieldtest.experiment` and `fieldtest.revision`.

OpenFeature only chooses the variant. The server still records exposures and feedback, only consults OpenFeature for experiments in its own config, and replaces any variant that isn't a configured candidate the client supports with `:a`.

```sh
clojure -M:test
```

The tests run fieldtest's test kit against the selector and send decisions through a server.

```clojure
(def of-client (.getClient (dev.openfeature.sdk.OpenFeatureAPI/getInstance)))
(fieldtest.server/server {:config-source …
                          :selector (example.openfeature-selector/selector of-client)
                          :recorder …})
```
