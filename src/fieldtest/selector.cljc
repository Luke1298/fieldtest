(ns fieldtest.selector
  "The built-in selector, which splits units between arms by weight.

  A selector is a function of one map and returns an arm keyword (or a
  promise of one):

      {:experiment     \"asset-host\"
       :config         {:revision 3 :weights {:cdn 20} :sticky :hashed}
       :sticky-key     \"user:8f3a\"      ; may be nil
       :supported-arms #{:default :cdn}
       :context        {...}}            ; the decide request's context

  Units fall into a space of 10,000 buckets. Each candidate arm, ordered by
  name, owns a fixed slice of 10,000/n buckets and draws the first
  weight*100 of them. Changing one arm's weight only moves units between that
  arm and `:default`. Adding or removing a candidate changes every slice, so do that
  with a new revision."
  (:require
   [fieldtest.hash :as hash]))

(def buckets
  "Size of the bucket space."
  10000)

(defn hash-bucket
  "The bucket for a sticky key in `hashed` mode."
  [experiment revision sticky-key]
  (mod (hash/fnv1a-32 (str experiment "|" revision "|" sticky-key)) buckets))

(defn random-bucket
  "A uniformly random bucket, for `none` mode."
  []
  (rand-int buckets))

(defn slices
  "Each candidate's slice of the bucket space as `[arm start end]`, where
  `[start end)` are the buckets the arm draws. Candidates are ordered by name;
  a weight is clamped to its slice width."
  [weights]
  (let [arms (sort (keys weights))
        n (count arms)]
    (map-indexed
     (fn [i arm]
       (let [start (quot (* i buckets) n)
             width (- (quot (* (inc i) buckets) n) start)
             drawn (-> (get weights arm) (* 100) (max 0) (min width) long)]
         [arm start (+ start drawn)]))
     arms)))

(defn arm-for-bucket
  "The arm whose drawn buckets contain `bucket`, else `:default`."
  [weights bucket]
  (or (some (fn [[arm start end]]
              (when (and (<= start bucket) (< bucket end)) arm))
            (slices weights))
      :default))

(defn bucket
  "The bucket a request falls into: hashed when the experiment is `hashed` and
  the request has a sticky key, otherwise random."
  [{:keys [experiment config sticky-key]}]
  (if (and (= :hashed (:sticky config)) (some? sticky-key))
    (hash-bucket experiment (:revision config) sticky-key)
    (random-bucket)))

(defn weighted-hash
  "The built-in selector. A unit whose bucket falls in an arm the client does
  not support gets `:default`, never a different candidate."
  [{:keys [config supported-arms] :as request}]
  (let [arm (arm-for-bucket (:weights config) (bucket request))]
    (if (contains? (set supported-arms) arm) arm :default)))
