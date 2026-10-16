# In-process example

A single JVM process plays both server and client. It runs an A/A experiment, with the same code in `:a` and `:b` and `:b` at 50%, over 10,000 sticky keys, then prints counts and latency per arm from the in-memory recorder.

```sh
clojure -M -m example.main
```

Both arms run the same code, so the split should come out close to 50/50 and the latencies should match. In a real experiment you'd put the new code in `:b` and start it at a low weight.
