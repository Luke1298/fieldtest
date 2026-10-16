(ns example.openfeature-selector
  "A fieldtest selector that defers to an OpenFeature client. The flag key is
  the experiment name, the targeting key is the sticky key, and the flag's
  string variant value names the arm.

  fieldtest still owns the experiment: the server only asks this selector
  about experiments in its config, and an arm the flag returns is used only if
  it is a configured candidate the client supports. Anything else is :a."
  (:import
   (dev.openfeature.sdk Client MutableContext)))

(defn- add!
  "Adds one context entry with the matching typed overload. Values of other
  types are skipped."
  [^MutableContext ctx ^String k v]
  (cond
    (string? v) (.add ctx k ^String v)
    (keyword? v) (.add ctx k ^String (subs (str v) 1))
    (boolean? v) (.add ctx k ^Boolean v)
    (integer? v) (.add ctx k (Integer/valueOf (int v)))
    (number? v) (.add ctx k (Double/valueOf (double v)))
    :else ctx))

(defn- evaluation-context
  [{:keys [experiment config sticky-key context]}]
  (let [ctx (if sticky-key (MutableContext. ^String sticky-key) (MutableContext.))]
    (add! ctx "fieldtest.experiment" experiment)
    (add! ctx "fieldtest.revision" (:revision config))
    (doseq [[k v] context]
      (add! ctx (if (keyword? k) (subs (str k) 1) (str k)) v))
    ctx))

(defn selector
  "A selector backed by the OpenFeature `client`. Options: `:flag-key`, a
  function from experiment name to flag key (default: identity)."
  ([client] (selector client {}))
  ([^Client client {:keys [flag-key] :or {flag-key identity}}]
   (fn [{:keys [experiment config supported-arms] :as request}]
     (let [variant (.getStringValue client ^String (flag-key experiment) "a" (evaluation-context request))
           arm (keyword variant)]
       (if (and (contains? (:weights config) arm) (contains? (set supported-arms) arm))
         arm
         :a)))))
