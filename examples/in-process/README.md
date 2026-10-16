# In-process example

One JVM process that is both server and client. It runs an A/A experiment (identical code in `:a` and `:b`, `:b` at 50%) for 10,000 sticky keys and prints per-arm counts and latency from the in-memory recorder.

```sh
clojure -M -m example.main
```

Expect a split close to 50/50 and matching latencies, because both arms run the same code. A real experiment would put the candidate in `:b` and start it at a low weight.
