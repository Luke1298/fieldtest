# Browser client example

A shadow-cljs page that asks the [ring-server](../ring-server) example for a decision on `image-format`, loads a PNG (`:a`) or an SVG (`:b`) through `branch`, and reports the fetch time with `timed`.

Start the server, then the page:

```sh
(cd ../ring-server && clojure -M -m example.server)   # :8723
npm install
npx shadow-cljs watch app                              # page on :8724
```

Open http://localhost:8724. The status line shows the arm and exposure id, and `../ring-server/records.jsonl` gets an exposure and an `image.fetch` feedback line. The sticky key lives in `localStorage`, so reloading keeps the same arm. Clear it to draw again.

If the server is down, the decider times out after 500 ms, the page loads the PNG (`:a`), and nothing is reported.
