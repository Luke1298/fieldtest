# Ring server example

fieldtest's decide and feedback handlers mounted in http-kit, with an EDN-file config source (`experiments.edn`, cached for 2 seconds) and a recorder that appends JSON lines to `records.jsonl`.

```sh
clojure -M -m example.server        # listens on :8723
```

Ask for a decision:

```sh
curl -s localhost:8723/decide -d '{"v":1,"sticky-key":"user:42","experiments":[{"experiment":"asset-host","revision":1,"arms":["a","b"]}]}'
# {"decisions":[{"experiment":"asset-host","revision":1,"arm":"b","exposure-id":"…"}],"v":1}
```

Report feedback against the exposure id it returned:

```sh
curl -s localhost:8723/feedback -d '{"v":1,"feedback":[{"exposure-ids":["…"],"metric":"asset.load","value":120,"value-unit":"ms","outcome":"ok"}]}'
# {"accepted":1,"v":1}
```

`records.jsonl` now has one exposure line and one feedback line. Edit `experiments.edn` while the server runs to change weights.

The server allows any origin so the [browser-client](../browser-client) example can call it from another port. A real host would restrict origins and add authentication; the handlers leave both to the host.
