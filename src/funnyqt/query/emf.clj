(ns funnyqt.query.emf
  "More sophisticated constructs for querying EMF models."
  (:use funnyqt.utils)
  (:use funnyqt.query)
  (:use funnyqt.protocols)
  (:use ordered.set)
  (:use ordered.map)
  (:use funnyqt.emf)
  (:import
   [org.eclipse.emf.ecore EObject EReference EStructuralFeature]))


;;# Adjancencies

(defn- eget-ref ^EReference [^EObject eo ref]
  (if-let [^EStructuralFeature sf (.getEStructuralFeature (.eClass eo) (name ref))]
    (if (instance? EReference sf)
      (.eGet eo sf)
      (error (format "'%s' at %s is no EReference." sf eo)))
    (error (format "No such structural feature '%s' at %s." ref eo))))

(extend-protocol Adjacencies
  EObject
  (adj-internal [this roles]
    (if (seq roles)
      (recur (emf2clj (eget-ref this (first roles)))
             (rest roles))
      this))
  (adjs-internal [this roles]
    ;; TODO
    )
  (adj*-internal [this roles]
    ;; TODO
    )
  (adjs*-internal [this roles]
    ;; TODO
    ))

;;# Regular Path Descriptions

(defn <>--
  "Returns the (direct) contents of EObject`obj` restricted by the type
  specification `ts` (see `eclass-matcher` for details).  `obj` may also be a
  collection of EObjects."
  ([obj]
     (mapcat econtents (to-oset obj)))
  ([obj ts]
     (mapcat #(econtents % ts) (to-oset obj))))

(defn -->
  "Returns the EObjects cross-referenced by `obj` where the references may be
  restricted by `rs`, a reference specification (see `eref-matcher` for
  details).  `obj` may also be a collection of EObjects.  In EMF,
  cross-referenced means referenced by a non-containment EReference."
  ([obj]
     (mapcat ecrossrefs (to-oset obj)))
  ([obj rs]
     (mapcat #(ecrossrefs % rs) (to-oset obj))))

(defn -->>
  "Returns the EObjects referenced by `obj` where the references may be
  restricted by `rs`, a reference specification (see `eref-matcher` for
  details).  `obj` may also be a collection of EObjects.  In contrast to `-->`,
  this function includes both cross-references and containments."
  ([obj]
     (mapcat erefs (to-oset obj)))
  ([obj rs]
     (mapcat #(erefs % rs) (to-oset obj))))

(defn --<>
  "Returns a seq containing `obj`s container.  If there's none,
  returns the empty set."
  [obj]
  (mapcat #(when-let [c (econtainer %)]
             [c])
          (to-oset obj)))

(defn <--
  "Returns all EObjects cross-referencing `obj` with a reference matching the
  reference specification `rs` (see `eref-matcher` for details).  `obj` may also
  be a collection of EObjects, in which case all objects cross-referencing any
  of the objects in `obj` is returned.  In EMF, cross-referenced means
  referenced by a non-containment EReference."
  ([obj]
     (mapcat inv-ecrossrefs (to-oset obj)))
  ([obj rs]
     (mapcat #(inv-ecrossrefs % rs) (to-oset obj)))
  ([obj rs container]
     (mapcat #(inv-ecrossrefs % rs container) (to-oset obj))))

(defn <<--
  "Returns all EObjects referencing `obj` with a reference matching the
  reference specification `rs` (see `eref-matcher` for details).  `obj` may also
  be a collection of EObjects, in which case all objects referencing any of the
  objects in `obj` is returned.  In contrast to `<--', this function includes
  both cross-references and containments."
  ([obj]
     (mapcat inv-erefs (to-oset obj)))
  ([obj rs]
     (mapcat #(inv-erefs % rs) (to-oset obj)))
  ([obj rs container]
     (mapcat #(inv-erefs % rs container) (to-oset obj))))

(defn- p-apply-emf
  [obj p]
  (cond
   ;; funs: -->
   (fn? p) (to-oset (p obj))
   ;; funs with params: [--> :foo], [p-alt :foo :bar]
   (coll? p) (to-oset (apply (first p) obj (rest p)))
   ;; EReference names
   (qname? p) (to-oset (mapcat #(erefs % p) (to-oset obj)))
   :else (error (format "Don't know how to apply %s." p))))

(defn- p-restr-emf
  "EObject restriction concerning `ts` and `pred` on each object in `objs`.
  ts is a type specification (see `eclass-matcher`), `pred` a predicate."
  ([objs ts]
     (p-restr-emf objs ts identity))
  ([objs ts pred]
     (let [objs (to-oset objs)]
       (to-oset
        (if (seq objs)
          (let [tm (eclass-matcher ts)]
            (filter (every-pred tm pred)
                    objs))
          objs)))))

(defn reachables
  "Returns the ordered set of EObjects reachable from `obj` by via the path
  description `p`.  `obj` may be an EObject or a seq of EObjects."
  [obj p]
  (binding [*p-apply* p-apply-emf
            *p-restr* p-restr-emf]
    (*p-apply* obj p)))


;;# Describing EObjects and EClasses

(extend-protocol Describable
  org.eclipse.emf.ecore.EClass
  (describe [this]
    {:name (qname this)
     :abstract (.isAbstract this)
     :interface (.isInterface this)
     :superclasses (seq (.getESuperTypes this))
     :attributes (into {}
                       (map (fn [^org.eclipse.emf.ecore.EAttribute attr]
                              [(keyword (.getName attr)) (.getEType attr)])
                            (seq (.getEAttributes this))))
     :references (into {}
                       (map (fn [^org.eclipse.emf.ecore.EReference ref]
                              [(keyword (.getName ref)) (.getEReferenceType ref)])
                            (seq (.getEReferences this))))})
  org.eclipse.emf.ecore.EObject
  (describe [this]
    {:eclass (qname this)
     :container (econtainer this)
     :attr-slots (into {}
                       (map (fn [^org.eclipse.emf.ecore.EAttribute attr]
                              (let [kw (keyword (.getName attr))]
                                [kw (eget this kw)]))
                            (seq (.getEAllAttributes (.eClass this)))))
     :ref-slots (into {}
                       (map (fn [^org.eclipse.emf.ecore.EReference ref]
                              (let [kw (keyword (.getName ref))]
                                [kw (eget this kw)]))
                            (seq (.getEAllReferences (.eClass this)))))}))

