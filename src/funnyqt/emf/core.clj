(ns funnyqt.emf.core
  "Core functions for accessing and manipulating EMF models."
  (:use funnyqt.utils)
  (:use funnyqt.generic)
  (:use funnyqt.generic-protocols)
  (:use ordered.set)
  (:use ordered.map)
  (:require clojure.java.shell)
  (:import
   [org.eclipse.emf.ecore.xmi.impl XMIResourceImpl XMIResourceFactoryImpl]
   [org.eclipse.emf.ecore.util EcoreUtil]
   [org.eclipse.emf.common.util URI EList UniqueEList EMap]
   [org.eclipse.emf.ecore.resource Resource ResourceSet]
   [org.eclipse.emf.ecore.resource.impl ResourceImpl]
   [org.eclipse.emf.ecore EcorePackage EPackage EObject EModelElement EClassifier EClass
    EDataType EEnumLiteral EEnum EFactory ETypedElement EAnnotation EAttribute EReference
    EStructuralFeature]))

(add-long-doc! "TODO")

;;* Code

;;** Metamodel

(def ^org.eclipse.emf.ecore.EPackage$Registry
  epackage-registry org.eclipse.emf.ecore.EPackage$Registry/INSTANCE)

(defn- register-epackages
  "Registeres the given packages at the EPackage$Registry by their nsURI.
  Skips already registered packages."
  [pkgs]
  (doseq [^EPackage p pkgs]
    (when-let [uri (.getNsURI p)]
      ;; Empty URI or already registered -> skip it
      (when (and (seq uri)
                 (nil? (.get epackage-registry uri)))
        (.put epackage-registry (.getNsURI p) p)))
    (let [subs (.getESubpackages p)]
      (when (seq subs)
        (register-epackages subs)))))

(defprotocol EcoreModelBasics
  (load-and-register [this])
  ;; TODO: Implement me.
  (save [this file]))

(deftype EcoreModel [^Resource resource]
  EcoreModelBasics
  (load-and-register [this]
    (.load resource  ;(.getDefaultLoadOptions resource)
           nil)
    (doto (seq (.getContents resource))
      register-epackages)))

(defn load-metamodel
  "Loads the Ecore metamodel from the ecore file `f'.
  Returns as seq of (usually one) root EPackages.
  All EPackages are registered recursively."
  [f]
  (let [uri (URI/createFileURI f)
        res (XMIResourceImpl. uri)]
    (doto (EcoreModel. res)
      load-and-register)))

(def ^:dynamic *ns-uris* nil)
(defmacro with-ns-uris
  "Restricts the EClassifier lookup in the dynamic scope of `body' to those
  contained in EPackages registered with the given URIs at the EPackage
  registry."
  [uris & body]
  `(binding [*ns-uris* ~uris]
     ~@body))

(defn epackages
  "The lazy seq (pkg subpkg...).
  If no package is given, the lazy seq of all registered packages is returned."
  ([]
     (mapcat #(epackages (.getEPackage epackage-registry %))
             (or *ns-uris* (keys epackage-registry))))
  ([^EPackage pkg]
     (when pkg
       (cons pkg (map epackages (.getESubpackages pkg))))))

(defn epackage
  "Returns the EPackage with the given qualified name."
  ([qn]
     (let [ps (clojure.string/split (name qn) #"\.")
           f (first ps)
           r (rest ps)
           tops (seq (filter
                      (fn [^EPackage p]
                        (= (.getName p) f))
                      (mapcat #(epackages (.getEPackage epackage-registry %))
                              (or *ns-uris* (keys epackage-registry)))))]
       (when-not tops
         (error (format "No such root package %s." f)))
       (when (next (next tops))
         (error (format "Multiple root packages named %s: %s\n%s" f tops
                        "Restrict the search space using `with-ns-uris'.")))
       (if (seq r)
         (apply epackage (first tops) r)
         (first tops))))
  ([^EPackage ep & subqns]
     (if (seq subqns)
       (let [f (first subqns)
             subps (filter (fn [^EPackage p] (= (.getName p) f))
                           (.getESubpackages ep))]
         (if (seq subps)
           (recur (the subps) (rest subqns))
           (error (format "No such subpackage %s in %s." f (print-str ep)))))
       ep)))

(extend-protocol Abstractness
  EClass
  (abstract? [this]
    (.isAbstract this)))

(defn eclassifiers
  "The lazy seq of EClassifiers."
  []
  (mapcat (fn [^EPackage ep]
            (.getEClassifiers ep))
          (epackages)))

(defn eclassifier
  "Returns the eclassifier with the given `name'.
  `name' may be a simple or qualified name.  Throws an exception, if no such
  classifier could be found, or if the given simple name is ambiguous."
  [name]
  (let [^String n (clojure.core/name name)
        ld (.lastIndexOf n ".")]
    (if (>= ld 0)
      (let [^EPackage ep (epackage (subs n 0 ld))]
        (or (.getEClassifier ep (subs n (inc ld)))
            (error (format "No such EClassifier %s in %s." n (print-str ep)))))
      (let [classifiers (filter (fn [^EClassifier ec]
                                  (= (.getName ec) n))
                                (eclassifiers))]
        (cond
         (empty? classifiers) (error (format "No such EClassifier %s." n))
         (next classifiers)   (error
                               (format "EClassifier %s is ambiguous: %s\n%s"
                                       n (print-str classifiers)
                                       "Restrict the search space using `with-ns-uris'."))
         :else (first classifiers))))))

(defn eenum-literal
  "Returns the EEnumLiteral specified by its `qname'."
  [qname]
  (let [[eenum elit] (split-qname qname)
        ^EEnum enum-cls (eclassifier eenum)]
    (or (.getEEnumLiteral enum-cls ^String elit)
        (error (format "%s has no EEnumLiteral with name %s."
                       (print-str enum-cls) elit)))))

;;** Model

;;*** Qualified Names

(extend-protocol QualifiedName
  EClassifier
  (qname [this]
    (symbol (str (qname (.getEPackage this))
                 "." (.getName this))))

  EPackage
  (qname [this]
    (loop [p (.getESuperPackage this), n (.getName this)]
      (if p
        (recur (.getESuperPackage p) (str (.getName this) "." n))
        (symbol n))))

  EObject
  (qname [o]
    (qname (.eClass o))))

;;*** EMF Model def

(defprotocol EMFModelBasics
  (init-model [this])
  ;; TODO: Maybe varags are supported in newer clojure versions?
  (add-eobject [this eo])
  (add-eobjects [this eos])
  (clone-model [this])
  (save-model [this] [this file]))

(deftype EMFModel [^Resource resource]
  EMFModelBasics
  (init-model [this]
    (.load resource ;(.getDefaultLoadOptions resource)
           nil))
  (add-eobject [this eo]
    (doto (.getContents resource)
      (.add eo))
    eo)
  (add-eobjects [this eos]
    (doto (.getContents resource)
      (.addAll eos))
    eos)
  (clone-model [this]
    (let [nres (ResourceImpl.)
          nconts (.getContents nres)]
      (doseq [o (EcoreUtil/copyAll (.getContents resource))]
        (.add nconts o))
      (EMFModel. nres)))
  (save-model [this]
    (if (.getURI resource)
      (.save resource nil)
      (error (str "You tried to save a non-file-Resource!\n"
                  "Use (save-model m \"foo.xmi\") instead."))))
  (save-model [this file]
    (let [uri (URI/createFileURI file)
          nres (XMIResourceImpl. uri)
          nconts (.getContents nres)]
      (doseq [o (EcoreUtil/copyAll (.getContents resource))]
        (.add nconts o))
      (println "Saving model to " (.toFileString uri))
      (.save nres nil))))

(defn new-model
  "Creates and returns a new, empty EMFModel."
  []
  (EMFModel. (ResourceImpl.)))

(defn load-model
  "Loads an EMF model from the XMI file `f'.
  Returns a seq of the models top-level elements."
  [f]
  (let [uri (URI/createFileURI f)
        res (XMIResourceImpl. uri)]
    (doto (EMFModel. res)
      init-model)))

;;*** Traversal stuff

(defn- eclass-matcher-1
  "Returns a matcher for elements Foo, !Foo, Foo!, !Foo!."
  [c]
  (let [v     (type-with-modifiers (name c))
        neg   (v 0)
        qname (v 1)
        exact (v 2)
        ^EClassifier type  (eclassifier qname)]
    (cond
     (and (not neg) (not exact)) (fn [^EClass x] (.isInstance type x))
     (and (not neg) exact)       (fn [^EClass x] (identical? type (.eClass x)))
     (and neg       (not exact)) (fn [^EClass x] (not (.isInstance type x)))
     :default                    (fn [^EClass x] (not (identical? type (.eClass x)))))))

(defn eclass-matcher
  "Returns a matcher for either nil, !Foo!, [Foo Bar! !Baz], [:and 'Foo 'Bar],
  or [:or 'Foo 'Bar].  In a collection spec, the first element may be one of
  the keywords :or (default), :nor, :and, :nand, or :xor with the usual logic
  semantics."
  [ts]
  (cond
   (nil? ts)   identity
   (fn? ts)    ts
   (qname? ts) (eclass-matcher-1 ts)
   (coll? ts)  (if (seq ts)
                  (let [f (first ts)
                        [op r] (case f
                                 :and  [every-pred (next ts)]
                                 :nand [nand-fn    (next ts)]
                                 :or   [some-fn    (next ts)]
                                 :nor  [nor-fn     (next ts)]
                                 :xor  [xor-fn     (next ts)]
                                 [some-fn    ts])]
                    (apply op (map eclass-matcher r)))
                  ;; Empty collection given: (), [], that's also ok
                  identity)
   :else (error (format "Don't know how to create a type matcher for %s" ts))))

(extend-protocol InstanceOf
  EClassifier
  (instance-of? [class object]
    (.isInstance class object))
  EObject
  (type-of? [obj spec]
    ((eclass-matcher spec) obj)))

(defprotocol EContents
  (eallcontents-internal [this tm]
    "Returns a seq of all directly and indirectly contained EObjects whose type
  matches the eclass-matcher `tm'.")
  (econtents-internal [this tm]
    "Returns a seq of all directly contained EObjects whose type matches the
  eclass-matcher `tm'.")
  (econtainer [this]
    "Returns the EObject containing this.")
  (eallobjects [this] [this ts]
    "Returns a seq of all objects matching the type specification `ts' (see
  `eclass-matcher') that are contained in this EMFModel."))

(extend-protocol EContents
  EObject
  (econtents-internal [this tm]
    (filter tm (seq (.eContents this))))
  (eallcontents-internal [this tm]
    (filter tm (iterator-seq (.eAllContents this))))
  (econtainer [this]
    (.eContainer this))

  EMFModel
  (econtents-internal [this tm]
    (filter tm (seq (.getContents ^Resource (.resource this)))))
  (eallcontents-internal [this tm]
    (filter tm (iterator-seq (.getAllContents ^Resource (.resource this)))))
  (eallobjects
    ([this]
       (eallcontents-internal this identity))
    ([this ts]
       (eallcontents-internal this (eclass-matcher ts))))

  clojure.lang.IPersistentCollection
  (econtents-internal [this tm]
    (mapcat #(econtents-internal % tm) this))
  (eallcontents-internal [this tm]
    (mapcat #(eallcontents-internal % tm) this)))

(defn eallcontents
  "Returns a seq of `x's direct and indirect contents matching the type spec
`ts'."
  ([x]
     (eallcontents-internal x identity))
  ([x ts]
     (eallcontents-internal x (eclass-matcher ts))))

(defn econtents
  "Returns a seq of `x's direct contents matching the type spec `ts'."
  ([x]
     (econtents-internal x identity))
  ([x ts]
     (econtents-internal x (eclass-matcher ts))))

(defn eref-matcher
  "Returns a reference matcher for the reference spec `rs'.
  A reference matcher is a function of arity one that gets an EReference and
  returns logical true if that ref should be accepted, false otherwise.

  Semantics depend on `rs':

    nil           => accept all references
    someERef      => accept only this EReference
    :foo          => accept only references named foo
    [:foo :bar]   => accept both foo and bar refs
    (fn [r] ...)  => simply use that"
  [rs]
  (cond
   (nil? rs)    identity
   (fn? rs)     rs
   (qname? rs)  (let [n (name rs)]
                  (fn [^EReference ref]
                    (= n (.getName ref))))
   (instance? EReference rs) (fn [r] (= rs r))
   (coll? rs)  (if (seq rs)
                 (apply some-fn (map eref-matcher rs))
                  ;; Empty collection given: (), [], that's also ok
                  identity)
   :else (RuntimeException.
          (format "Don't know how to create a reference matcher for %s" rs))))

(defprotocol EReferences
  (ecrossrefs-internal [this rm]
    "Returns a seq of cross-referenced EObjects accepted by reference-matcher
  `rm'.  Cross-referenced objects are those that are referenced by a
  non-containment relationship.")
  (erefs-internal [this rm]
    "Returns a seq of referenced EObjects accepted by reference-matcher `rm'.
  In contrast to ecrossrefs-internal, containment refs are not excluded.")
  (inv-ecrossrefs-internal [this rm container]
    "Returns a seq of EObjects that cross-reference `this' with a ref matching
  `rm'.  Cross-referenced objects are those that are referenced by a
  non-containment relationship.  If `container' is nil, check only opposites of
  this object's ref, else do a search over the nconts of `container', which
  may be an EMFModel or a collection of EObjects.")
  (inv-erefs-internal [this rm container]
    "Returns a seq of EObjects that reference `this' with a ref matching `rm'.
  If `container' is nil, check only opposites of this object's ref, else do a
  search over the nconts of `container', which may be an EMFModel or a
  collection of EObjects."))

(defn- eopposite-refs
  "Returns the seq of `eo's EClass' references whose opposites match `here-rm'.

  Example: [Foo] f --- b [Bar]
              f \\
                 `---- c [Car]

  Given a Foo object and a eref-matcher matching f, returns a seq of the
  EReferences b and c, because those are the opposites of the matched f.  Of
  course, if `here-rm' matches only one specific EReference, i.e., it was
  constructed by (eref-matcher fERef) and not (eref-matcher :f)."
  [^EObject eo here-rm]
  (seq (remove nil? (map (fn [^EReference r]
                           (when-let [o (.getEOpposite r)]
                             (when (here-rm o) r)))
                         (seq (-> eo .eClass .getEAllReferences))))))

(defn- search-ereferencers
  "Returns the seq of objects referencing `refed' by a reference matching `rm'
  that are contained in `container'.  `reffn' is either erefs-internal or
  ecrossrefs-internal."
  [refed reffn rm container]
  (filter (fn [o] (member? refed (reffn o rm)))
          (cond
           (instance? EMFModel container) (eallobjects container)
           (coll? container)              container
           :else (error (format "container is neither an EMFModel nor a collection: %s"
                                container)))))

(extend-protocol EReferences
  EObject
  (ecrossrefs-internal [this rm]
    (let [^org.eclipse.emf.ecore.util.EContentsEList$FeatureIterator it
          (-> this .eCrossReferences .iterator)]
      (loop [r []]
        (if (.hasNext it)
          (let [eo (.next it)]
            (recur (if (rm (.feature it))
                     (conj r eo)
                     r)))
          r))))
  (erefs-internal [this rm]
    (loop [r [], refs (seq (-> this .eClass .getEAllReferences))]
      (if (seq refs)
        (let [^EReference ref (first refs)]
          (recur (if (rm ref)
                   (if-let [x (.eGet this ref)]
                     (if (.isMany ref)
                       (into r x)
                       (conj r x))
                     r)
                   r)
                 (rest refs)))
        r)))
  (inv-erefs-internal [this rm container]
    (if container
      (search-ereferencers this erefs-internal rm container)
      (if-let [opposites (eopposite-refs this rm)]
        (erefs-internal this (eref-matcher opposites))
        (error "No opposite EReferences found."))))
  (inv-ecrossrefs-internal [this rm container]
    (if container
      (search-ereferencers this ecrossrefs-internal rm container)
      (if-let [opposites (eopposite-refs this rm)]
        (ecrossrefs-internal this (eref-matcher opposites))
        (error "No opposite EReferences found."))))

  clojure.lang.IPersistentCollection
  (ecrossrefs-internal [this rm]
    (mapcat #(ecrossrefs-internal % rm) this))
  (erefs-internal [this rm]
    (mapcat #(erefs-internal % rm) this))
  (inv-erefs-internal [this rm container]
    (mapcat #(inv-erefs-internal % rm container) this))
  (inv-ecrossrefs-internal [this rm container]
    (mapcat #(inv-ecrossrefs-internal % rm container) this)))

(defn ecrossrefs
  "Returns a seq of EObjects cross-referenced by `eo', possibly restricted by
  the reference spec `rs'.  `eo' may be an EObject or a collection of EObjects.
  For the syntax and semantics of `rs', see `eref-matcher'.  In EMF, crossrefs
  are all non-containment refs."
  ([eo]
     (ecrossrefs-internal eo identity))
  ([eo rs]
     (ecrossrefs-internal eo (eref-matcher rs))))

(defn erefs
  "Returns a seq of EObjects referenced by `eo', possibly restricted by the
  reference spec `rs'.  `eo' may be an EObject or a collection of EObjects.
  For the syntax and semantics of `rs', see `eref-matcher'.  In contrast to
  `ecrossrefs', this function doesn't ignore containment refs."
  ([eo]
     (erefs-internal eo identity))
  ([eo rs]
     (erefs-internal eo (eref-matcher rs))))

(defn inv-erefs
  "Returns the seq of EOjects that reference `eo' with an EReference matching
  `rs' (see `eref-matcher').  `eo' may also be a collection of eobjects.  If no
  `container' is given, then only check the opposite refs of `eo'.  Else, all
  objects in `container' are tested if they reference `eo'.  `container' may be
  either an EMFModel or a collection of EObjects."
  ([eo]
     (inv-erefs-internal eo identity nil))
  ([eo rs]
     (inv-erefs-internal eo (eref-matcher rs) nil))
  ([eo rs container]
     (inv-erefs-internal eo (eref-matcher rs) container)))

(defn inv-ecrossrefs
  "Returns the seq of EOjects that cross-reference `eo' with an EReference
  matching `rs' (see `eref-matcher').  `eo' may also be a collection of
  eobjects.  If no `container' is given, then only check the opposite refs of
  `eo'.  Else, all objects in `container' are tested if they cross-reference
  `eo'. `container' may be either an EMFModel or a collection of EObjects. "
  ([eo]
     (inv-ecrossrefs-internal eo identity nil))
  ([eo rs]
     (inv-ecrossrefs-internal eo (eref-matcher rs) nil))
  ([eo rs container]
     (inv-ecrossrefs-internal eo (eref-matcher rs) container)))

(defprotocol EmfToClj
  (emf2clj [this]
    "Converts an EMF thingy to a clojure thingy.

  EMF Type     | Clojure Type
  -------------+-------------
  UniqueEList  | ordered-set
  EMap         | ordered-map
  EList        | seq

  All other objects are kept as-is."))

(extend-protocol EmfToClj
  UniqueEList
  (emf2clj [this] (into (ordered-set) (seq this)))
  EMap
  (emf2clj [this] (into (ordered-map) (seq this)))
  EList
  (emf2clj [this] (seq this))
  EObject
  (emf2clj [this] this)
  Number
  (emf2clj [this] this)
  String
  (emf2clj [this] this)
  nil
  (emf2clj [_] nil))

(defprotocol CljToEmf
  (clj2emf [this]
    "Converts a Clojure thingy to an EMF thingy.

  Clojure Type | EMF Type
  -------------+-------------
  ordered-set  | UniqueEList
  set          | UniqueEList
  map          | EMap
  seq          | EList

  All other objects are kept as-is."))

(extend-protocol CljToEmf
  ordered.set.OrderedSet
  (clj2emf [this]
    (let [ul (org.eclipse.emf.common.util.UniqueEList. (count this))]
      (doseq [item this]
        (.add ul (clj2emf item)))
      ul))
  clojure.lang.IPersistentSet
  (clj2emf [this]
    (let [ul (org.eclipse.emf.common.util.UniqueEList. (count this))]
      (doseq [item this]
        (.add ul (clj2emf item)))
      ul))
  clojure.lang.IPersistentMap
  (clj2emf [this]
    (let [em (org.eclipse.emf.common.util.BasicEMap. (count this))]
      (doseq [[k v] this]
        (.put em (clj2emf k) (clj2emf v)))
      em))
  clojure.lang.ISeq
  (clj2emf [this]
    (let [el (org.eclipse.emf.common.util.BasicEList. (count this))]
      (doseq [item this]
        (.add el (clj2emf item)))
      el))
  java.lang.Object
  (clj2emf [this] this)
  nil
  (clj2emf [this] this))

(defn eget-raw
  "Returns the value of `eo's structural feature `sf'.
  Throws an exception, if there's no EStructuralFeature `sf'.

  The value is kept as-is, i.e., not converted to some immutable clojure data
  structure as `eget' does.  So if you eget-raw an EList, you can mutate it
  in-place.  That's totally not stylish, but it might be a last resort when
  optimizing for performance.  You've been warned!"
  [^EObject eo sf]
  (if-let [sfeat (if (instance? EStructuralFeature sf)
                   sf
                   (.getEStructuralFeature (.eClass eo) (name sf)))]
    (.eGet eo sfeat)
    (error (format "No such structural feature %s for %s." sf (print-str eo)))))

(defn eget
  "Returns the value of `eo's structural feature `sf'.
  The value is converted to some clojure type (see EmfToClj protocol).
  Throws an exception, if there's no EStructuralFeature `sf'."
  [^EObject eo sf]
  (emf2clj (eget-raw eo sf)))

(defn eset!
  "Sets `eo's structural feature `sf' to `value' and returns `value'.
  The value is converted to some EMF type (see CljToEmf protocol).
  Throws an exception, if there's no EStructuralFeature `sf'."
  [^EObject eo sf value]
  (if-let [sfeat (.getEStructuralFeature (.eClass eo) (name sf))]
    (do (.eSet eo sfeat (clj2emf value)) value)
    (error (format "No such structural feature %s for %s." sf (print-str eo)))))

;;*** Visualization

(defn- dot-id [eo]
  (str "O" (Integer/toString (hash eo) (Character/MAX_RADIX))))

(defn- dot-attributes [^EObject eo]
  (reduce str
          (for [^EAttribute attr (.getEAllAttributes (.eClass eo))
                :let [n (.getName attr)]]
            (str n " = \\\"" (eget eo n) "\\\"\\l"))))

(defn- dot-eobject [eo]
  (let [h (dot-id eo)]
    (str "  " h
         " [label=\"{{" (qname eo) "}|"
         (dot-attributes eo)
         "}\", shape=record, fontname=Sans, fontsize=14];\n")))

(defn- dot-content-refs [^EObject eo]
  (let [h (dot-id eo)]
    (reduce str
            (for [^EReference ref (.getEAllContainments (.eClass eo))
                  :let [oref (.getEOpposite ref)
                        n (.getName ref)]
                  t (eget eo ref)]
              (str "  " h " -> " (dot-id t)
                   " [dir="
                   (if oref "none" "forward")
                   ", arrowtail=diamondnormal, fontname=Sans, "
                   "headlabel=\"" n "\""
                   (when oref
                     (str ", taillabel=\"" (.getName oref)))
                   "\"];\n")))))

(def ^{:private true, :dynamic true}
  *done-refs*)

(defn- dot-cross-refs [^EObject eo]
  (let [h (dot-id eo)]
    (reduce str
            (for [^EReference ref (.getEAllReferences (.eClass eo))
                  :when (not (or (.isContainment ref)
                                 (.isContainer ref)))
                  :let [n (.getName ref)
                        oref (.getEOpposite ref)
                        on (when oref (.getName oref))
                        x (eget eo ref)]
                  :when x
                  t (if (coll? x) x [x])
                  :let [h2 (dot-id t)
                        s #{n on h h2}]
                  :when (not (member? s @*done-refs*))]
              (do
                ;(println @done)
                (swap! *done-refs* conj s)
                (str "  " h " -> " h2
                     " [dir="
                     (if oref "none" "forward")
                     ", fontname=Sans, "
                     "headlabel=\"" n "\""
                     (when oref
                       (str ", taillabel=\"" (.getName oref)))
                     "\"];\n"))))))

(defn- dot-ereferences [eo]
  (str (dot-content-refs eo)
       (dot-cross-refs eo)))

(defn- dot-model [m]
  (str "digraph EMFModel {"
       "  ranksep = \"1.2\";\n\n"
       (reduce str
               (map dot-eobject
                    (eallobjects m)))
       (binding [*done-refs* (atom #{})]
         (reduce str
                 (map dot-ereferences
                      (eallobjects m))))
       "}"))

(defn pdf-print-model [m f]
  (let [ds (dot-model m)
        r (clojure.java.shell/sh "dot" "-Tpdf" "-o" f
                                 :in ds)]
    (when-not (zero? (:exit r))
      (error (format "Dotting failed: %s" (:err r))))))

;;*** EObject Creation

(defn ecreate
  "Creates an EObject of EClass `ecls'.
  `ecls' may be either an EClass or just an EClass name given as symbol,
  string, or keyword.  If a `model' is provided, then add the new EObject to
  it."
  ([ecls]
     (EcoreUtil/create (if (instance? EClass ecls)
                         ecls
                         (eclassifier ecls))))
  ([model ecls]
     (add-eobject model (ecreate ecls))))

;;*** EObject Deletion

(extend-protocol Deletable
  EObject
  (delete!
    ([this]
       (EcoreUtil/delete this true)
       this)
    ([this recursive]
       ;; Gotta provide a real boolean, not just a truthy thingy
       (EcoreUtil/delete this (if recursive true false))
       this)))

;;** Printing

;;*** Normal toString() output

;; TODO: We don't handle EFactories, ETypedElements, and EAnnotations yet.

(defn- feature-str
  "Returns a description of enabled features `fs'.
  fs => [test-val desc-str]*"
  ([fs]
     (feature-str [] fs))
  ([s fs]
     (if (seq fs)
       (let [[f n] (first fs)]
         (recur (if f (conj s n) s)
                (rest fs)))
       (when-let [r (seq s)]
         (str " " r)))))

(defmethod print-method EClass
  [^EClass ec ^java.io.Writer out]
  (.write out
          (str "#<EClass "
               (qname ec)
               (feature-str
                [[(.isAbstract ec)  :abstract]
                 [(.isInterface ec) :interface]])
               (let [m (into {}
                             (map (fn [^EAttribute attr]
                                    [(keyword (.getName attr)) (.getEType attr)])
                                  (seq (.getEAttributes ec))))]
                 (when (seq m)
                   (str " " m)))
               ">")))

(defmethod print-method EEnum
  [^EEnum en ^java.io.Writer out]
  (.write out
          (str "#<EEnum " (qname en)
               #_(feature-str
                  [[(.isSerializable en) :serializable]])
               ">")))

(defmethod print-method EDataType
  [^EDataType edt ^java.io.Writer out]
  (.write out
          (str "#<EDataType " (qname edt)
               #_(feature-str
                  [[(.isSerializable edt) :serializable]])
               ">")))

(defmethod print-method EPackage
  [^EPackage ep ^java.io.Writer out]
  (.write out
          (str "#<"
               (if (instance? EcorePackage ep)
                 "EcorePackage "
                 "EPackage ")
               (qname ep)
               (let [m (into {}
                             (map (fn [[v k]]
                                    (when v
                                      [k v]))
                                  [[(.getNsPrefix ep) :nsPrefix]
                                   [(.getNsURI ep)    :nsURI]]))]
                 (when (seq m)
                   (str " " m)))
               ">")))

(defmethod print-method EEnumLiteral
  [^EEnumLiteral el ^java.io.Writer out]
  (.write out
          (str "#<EEnumLiteral"
               (-> el .getEEnum .getName)
               "/"
               (.getLiteral el)
               ">")))

(defmethod print-method EObject
  [^EObject eo ^java.io.Writer out]
  (.write out
          (if (or (instance? EFactory      eo)
                  (instance? ETypedElement eo)
                  (instance? EAnnotation   eo))
            ;; Usual toString() for those
            (str eo)
            ;; Custom toString() for the others
            (str "#<"
                 (qname eo)
                 ">"))))


