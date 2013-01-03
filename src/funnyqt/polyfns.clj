(ns funnyqt.polyfns
  "Polymorphic functions dispatching on types of model elements."
  (:use [funnyqt.protocols   :only [has-type?]])
  (:use [funnyqt.utils       :only [errorf]])
  (:use [clojure.tools.macro :only [name-with-attributes]])
  (:require clojure.pprint))

;; {name {TypeSpec fn}}
(def *polyfn-dispatch-table* (atom {}))

(defmacro declare-polyfn
  "Decares a polymorphic function dispatching on a model element type.
  `name` is the name of the new polyfn, an optional `doc-string` may be
  provided.  The argument list's first element must be the model element on
  whose type the dispatch is done.  Polymorphic functions for several metamodel
  types are provided later using `defpolyfn`.  If an optional `body` is
  provided, this is executed if no implementation for `model-elem`s type was
  added using `defpoly`.  The default behavior in that case (i.e., `body`
  omitted) is to throw an exception.

  Example:

    ;; Declare a polyfn
    (declare-polyfn elem2str
      \"Returns a string representation of elem\".
      [elem]
      ;; Optional default behavior
      (str \"Don't know how to handle \" elem))

    ;; Define implementations for several types
    (defpolyfn elem2str TypeA [elem] ...)
    (defpolyfn elem2str TypeB [elem] ...) "
  {:arglists '([name doc-string? [model-elem & more] & body])}
  [name & more]
  (let [[name more] (name-with-attributes name more)
        argvec      (first more)
        body        (next more)]
    `(do
       (defn ~name ~(meta name)
         ~argvec
         (loop [dispatch-map# (@*polyfn-dispatch-table* #'~name)]
           (if (seq dispatch-map#)
             (let [[ts# f#] (first dispatch-map#)]
               (if (has-type? ~(first argvec) ts#)
                 (f# ~@argvec)
                 (recur (rest dispatch-map#))))
             ~(if (seq body)
                `(do ~@body)
                `(errorf "No polyfn for handling '%s'." ~(first argvec))))))
       (when-not (find @*polyfn-dispatch-table* #'~name)
         (swap! *polyfn-dispatch-table* assoc #'~name {}))
       #'~name)))

(defmacro defpolyfn
  "Defines an implementation of the polyfn `name` for a given type.
  The polyfn has to be declared using `declare-polyfn`, and `name` is its name.
  `typespec` is a type specification that is used to check if the polyfn
  implementation is matching the type of `model-elem` (see
  `funnyqt.emf/eclass-matcher` and `funnyqt.tg/type-matcher`).  The arity of
  the argument vector has to match the one of the corresponding
  `declare-polyfn`."
  {:arglists '([name typespec [model-elem & more] & body])}
  [name & more]
  (let [[name more]     (name-with-attributes name more)
        [typespec more] [(first more) (next more)]
        [argvec body]   [(first more) (next more)]]
    `(do
       (when-not (find @*polyfn-dispatch-table* #'~name)
         (errorf "%s is not declared as a polyfn." #'~name))
       (swap! *polyfn-dispatch-table* update-in [#'~name] assoc
              '~typespec
              (fn ~argvec ~@body)))))

(defn print-polyfn-dispatch-table
  "Prints the *polyfn-dispatch-table* for debugging purposes."
  []
  (clojure.pprint/pprint @*polyfn-dispatch-table*))
