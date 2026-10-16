# Browser client example

A shadow-cljs page that asks the [ring-server](../ring-server) example for a decision on `image-format`, then uses `branch` to load either a PNG (`:a`) or an SVG (`:b`) and `timed` to report how long the fetch took.

Start the server first, then the page:

```sh
(cd ../ring-server && clojure -M -m example.server)   # :8723
npm install
npx shadow-cljs watch app                              # page on :8724
```

Open http://localhost:8724. The page shows which arm it got and the exposure id, and `../ring-server/records.jsonl` gets an exposure and an `image.fetch` feedback line. The sticky key is kept in `localStorage`, so reloading gives you the same arm. Clear it if you want a new draw.

If the server isn't running, the decider gives up after 500 ms, the page loads the PNG, and nothing is reported.
