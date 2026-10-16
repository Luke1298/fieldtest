(ns fieldtest.memory
  "In-memory plug-ins for tests and local development. Nothing here is
  durable.")

(defn config-source
  "A config source that returns `configs`, or the current value of `configs`
  when it is an atom (so tests can change it)."
  [configs]
  (fn []
    (if #?(:clj (instance? clojure.lang.IDeref configs) :cljs (satisfies? IDeref configs))
      @configs
      configs)))

(defn recorder
  "A recorder that appends `{:kind :exposure|:feedback :record {...}}` to the
  vector in the atom `store`."
  [store]
  (fn [kind record]
    (swap! store conj {:kind kind :record record})
    nil))

(defn exposures
  "The exposure records in a store's value, in order."
  [entries]
  (into [] (comp (filter #(= :exposure (:kind %))) (map :record)) entries))

(defn feedback
  "The feedback records in a store's value, in order."
  [entries]
  (into [] (comp (filter #(= :feedback (:kind %))) (map :record)) entries))
