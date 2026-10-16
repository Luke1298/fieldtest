(ns fieldtest.ids
  "Random exposure ids. They don't encode the arm, sticky key or experiment,
  so they can be logged freely.")

#?(:clj (def ^:private ^java.security.SecureRandom rng (java.security.SecureRandom.)))

(defn- random-bytes [n]
  #?(:clj (let [bs (byte-array n)]
            (.nextBytes rng bs)
            (map #(bit-and % 0xff) bs))
     :cljs (let [bs (js/Uint8Array. n)]
             (if (and (exists? js/crypto) (exists? js/crypto.getRandomValues))
               (js/crypto.getRandomValues bs)
               (dotimes [i n] (aset bs i (rand-int 256))))
             (seq (js/Array.from bs)))))

(defn- hex2 [b]
  (let [s #?(:clj (Integer/toString (int b) 16) :cljs (.toString b 16))]
    (if (< b 16) (str "0" s) s)))

(defn exposure-id
  "A new random exposure id: 32 lowercase hex characters (128 bits)."
  []
  (apply str (map hex2 (random-bytes 16))))
