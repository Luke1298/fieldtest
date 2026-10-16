# nbb Lambda example

An nbb handler shaped like a Lambda. It sorts a list, branching on fieldtest decisions, and reports how long the sort took.

There are two sample events. `event-handoff.json` already contains decisions, made elsewhere (say, by the service that queued the job) and serialized with `client/decisions->json`, so the handler branches on them without contacting the server. `event-ask.json` has none, so the handler asks the HTTP decider and waits at most 300 ms.

Both send feedback to the [ring-server](../ring-server) example, so start that first:

```sh
(cd ../ring-server && clojure -M -m example.server)   # :8723
npm install
npx nbb -m example.handler event-handoff.json
npx nbb -m example.handler event-ask.json
```

Each run prints the handler's response and appends feedback to `../ring-server/records.jsonl`; `event-ask.json` adds an exposure as well. If the server is down, the sort still runs, on `:default`, and nothing is reported.
