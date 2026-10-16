# Ring server example

fieldtest's two handlers mounted in http-kit. Configs come from `experiments.edn`, cached for two seconds, and records are appended to `records.jsonl` as JSON lines.

```sh
clojure -M -m example.server        # listens on :8723
```

Ask for a decision:

```sh
curl -s localhost:8723/decide -d '{"v":1,"sticky-key":"user:42","experiments":[{"experiment":"asset-host","revision":1,"arms":["a","b"]}]}'
# {"decisions":[{"experiment":"asset-host","revision":1,"arm":"b","exposure-id":"…"}],"v":1}
```

Then send feedback for the exposure id you got back:

```sh
curl -s localhost:8723/feedback -d '{"v":1,"feedback":[{"exposure-ids":["…"],"metric":"asset.load","value":120,"value-unit":"ms","outcome":"ok"}]}'
# {"accepted":1,"v":1}
```

`records.jsonl` should now have an exposure line and a feedback line. Edit `experiments.edn` while the server is running to change the weights.

CORS is open to any origin so the [browser-client](../browser-client) example can call this server from another port. A real deployment should restrict origins and add authentication.
