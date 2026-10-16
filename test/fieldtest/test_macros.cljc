(ns fieldtest.test-macros
  "Async tests that work the same on the JVM, in shadow-cljs and in nbb.

  `defasynctest` defines a test whose body returns a promise (or a value).
  The test finishes only when the promise settles, and a rejection is
  reported as an error of that test, so assertions never land on whichever
  test happens to be running later."
  #?(:cljs (:require-macros [fieldtest.test-macros]))
  (:require
   #?(:clj [clojure.test])
   [promesa.core]))

#?(:clj
   (defn run-blocking
     "Runs `thunk`, which returns a promise or value, and blocks until it settles.
     Promise callbacks run on other threads, which don't see clojure.test's
     dynamic bindings, so their assertions would go uncounted. While the test
     runs, the bindings' root values are set to this thread's values. JVM test
     runners run tests one at a time, so this is safe."
     [thunk]
     (let [vars [#'clojure.test/*report-counters*
                 #'clojure.test/*testing-vars*
                 #'clojure.test/*testing-contexts*
                 #'clojure.test/*test-out*]
           roots (mapv #(.getRawRoot ^clojure.lang.Var %) vars)]
       (try
         (doseq [v vars]
           (let [current (deref v)]
             (alter-var-root v (constantly current))))
         (deref (promesa.core/promise (thunk)))
         (catch java.util.concurrent.ExecutionException e
           (throw (or (.getCause e) e)))
         (finally
           (doseq [[v root] (map vector vars roots)]
             (alter-var-root v (constantly root))))))))

(defmacro defasynctest
  "Defines a test named `test-name` whose `body` returns a promise. Use
  `p/with-redefs` rather than `with-redefs` inside it, so redefinitions last
  until the promise settles."
  [test-name & body]
  (let [cljs? #?(:clj (boolean (:ns &env)) :cljs true)
        {:keys [line column]} (meta &form)
        ;; shadow-cljs has no *file* at macro time; the namespace will do.
        file (or (some-> &env :ns :name str)
                 ;; nbb binds *file* in ClojureScript too.
                 #_{:clj-kondo/ignore [:unresolved-symbol]} *file*)]
    (if cljs?
      `(cljs.test/deftest ~test-name
         (cljs.test/async
          finish#
          (-> (promesa.core/do ~@body)
              (promesa.core/catch
               (fn [error#]
                 (cljs.test/report {:type :error
                                    :message (str "Uncaught rejection: " (ex-message error#))
                                    :file ~file
                                    :line ~line
                                    :column ~column
                                    :expected nil
                                    :actual error#})))
              (promesa.core/finally (fn [_# _#] (finish#))))))
      `(clojure.test/deftest ~test-name
         (run-blocking (fn [] ~@body))))))
