# In-process example

A single JVM process plays both server and client. It runs an A/A experiment over 10,000 sticky keys, where the `:candidate` arm runs the same code as `:default` and gets 50% of keys, then prints counts and latency per arm from the in-memory recorder.

```sh
clojure -M -m example.main
```

Both arms run the same code, so the split should come out close to 50/50 and the latencies should match. In a real experiment you'd give the candidate arm the new code and start it at a low weight.
