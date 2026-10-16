(ns fieldtest.hash
  "32-bit FNV-1a over the UTF-8 bytes of a string. The JVM, ClojureScript and
  nbb implementations have to agree, and the selector test vectors check
  that they do.")

(def ^:private offset-basis 2166136261)
(def ^:private prime 16777619)

(defn- utf8-bytes [^String s]
  #?(:clj (map #(bit-and % 0xff) (.getBytes s java.nio.charset.StandardCharsets/UTF_8))
     :cljs (seq (js/Array.from (.encode (js/TextEncoder.) s)))))

(defn fnv1a-32
  "The 32-bit FNV-1a hash of `s`'s UTF-8 bytes, as a non-negative integer
  below 2^32."
  [s]
  (reduce (fn [h b]
            #?(:clj (bit-and (* (bit-xor h b) prime) 0xffffffff)
               :cljs (unsigned-bit-shift-right (js/Math.imul (bit-xor h b) prime) 0)))
          offset-basis
          (utf8-bytes s)))
