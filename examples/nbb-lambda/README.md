# nbb Lambda example

A Lambda-shaped nbb handler that branches a sort on fieldtest decisions and reports its duration.

- `event-handoff.json` carries decisions made elsewhere (for example by the server that enqueued the job), serialized with `client/decisions->json`. The handler branches on them without asking again.
- `event-ask.json` has no decisions, so the handler asks the HTTP decider, with a 300 ms timeout.

Both report through the [ring-server](../ring-server) example, so start it first:

```sh
(cd ../ring-server && clojure -M -m example.server)   # :8723
npm install
npx nbb -m example.handler event-handoff.json
npx nbb -m example.handler event-ask.json
```

Each run prints the handler's response and adds feedback (and, for `event-ask.json`, an exposure) to `../ring-server/records.jsonl`. With the server down, the handler still sorts: it runs `:a` untracked.
