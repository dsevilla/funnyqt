(ns funnyqt.utils.xmltg
  "Convert XML to DOM-like TGraphs.

The DOM-like schema looks like this:

  https://raw.github.com/jgralab/funnyqt/master/resources/xml-schema.png

To transform an XML document to a DOM-like TGraph, use

  (xml2xml-graph \"example.xml\")

If the XML file has a DTD which describes what attributes are IDs, IDREFs, and
IDREFS, all references will be represented as References edges in the Graph.

If the XML file has no DTD, you can influence the resolution by providing an
`attr-type-fn` and/or an `text-type-fn` as documented in `xml2xml-graph`."
  (:use funnyqt.tg)
  (:use funnyqt.protocols)
  (:use funnyqt.query.tg)
  (:use funnyqt.query)
  (:use [funnyqt.utils :only [errorf]])
  (:require [clojure.string :as str])
  (:require [clojure.java.io :as io])
  (:import
   (javax.xml.stream XMLInputFactory XMLEventReader XMLStreamConstants)
   (javax.xml.stream.events StartDocument EndDocument StartElement EndElement
                            Attribute Characters XMLEvent)
   (javax.xml.namespace QName)
   (de.uni_koblenz.jgralab Graph Vertex Edge)
   (de.uni_koblenz.jgralab.schema Schema)))

;;# XML Graph Utils

(defn ^:private ns-get [what e]
  (if-let [n (value e what)]
    n
    (when-let [p (adj e (if (has-type? e 'Element)
                          :parent :element))]
      (recur what p))))

(def ^{:doc "Returns the namespace prefix of the given Element or Attribute.
  If that doesn't declare a namespace itself, returns the namespace of its
  container (recursively if needed)."
       :arglists '([e])}
  ns-prefix (partial ns-get :nsPrefix))

(def ^{:doc "Returns the namespace URI of the given Element or Attribute.
  Lookup is done recursively."
       :arglists '([e])}
  ns-uri (partial ns-get :nsURI))

(defn expanded-name
  "Returns the expanded name of the give Element, i.e., \"nsprefix:name\"."
  [e]
  (if-let [n (ns-prefix e)]
    (str n ":" (value e :name))
    (value e :name)))

(defn declared-name
  "Returns the name of the given Element or Attribute, possibly prefixed with its namespace.
  If it doesn't declare a namespace itself, the local name is returned."
  [e]
  (if-let [n (value e :nsPrefix)]
    (str n ":" (value e :name))
    (value e :name)))

(defn qualified-name
  "Returns the qualified name of Element `e` in the form \"{nsURI}localName\",
  or just \"localName\" if it's not namespaced."
  [e]
  (if-let [u (ns-uri e)]
    (str "{" u "}" (value e :name))
    (value e :name)))

(defn ^:private filter-by-name
  ;; n = {nsURI}bar => check qualified name
  ;; n = foo:bar    => check declared and expanded name
  ;; n = bar        => only check local name
  [n coll]
  (let [n (name n)
        name-matches (cond
                      (re-matches #"\{.*\}.*" "{http://foo/1.0}bar") #(= n (qualified-name %))
                      (re-matches #".*:.*" n) #(or (= n (declared-name %))
                                                   (= n (expanded-name %)))
                      :else #(= n (value % :name)))]
    (filter name-matches coll)))

(defn children
  "Returns the children Element vertices of Element vertex `e`.
  May be restricted to elements of type `qn`, a qualified name (see
  `qualified-name`), an expanded (see `expanded-name`) or declared name (see
  `declared-name`), or a local name."
  ([e]
     (adjs e :children))
  ([e qn]
     (filter-by-name qn (adjs e :children))))

(defn siblings
  "Returns the sibling Element vertices of Element or Text `e`.
  May be restricted to elements of the given type `qn`, , a qualified name (see
  `qualified-name`), an expanded (see `expanded-name`) or declared name (see
  `declared-name`), or a local name.

  The result is a vector of two component seqs:

    [left-siblings right-siblings]

  left-siblings is a seq of siblings that occur left of (or above) `e`, and
  right-siblings is a seq of siblings that occur right of (or below) `e`.  `e`
  itself is not included."
  ([e]
     (siblings e nil))
  ([e qn]
     (let [all (if qn
                 (filter-by-name qn (adjs (adj e :parent) :contents))
                 (adjs (adj e :parent) :contents))
           right (atom false)
           [l r] (partition-by (fn [s]
                                 (or @right
                                     (when (= s e)
                                       (swap! right (fn [& _] true)))))
                               all)]
       [l (next r)])))

(defn attribute-value
  "Returns the value of `elem`s xml attribute `attr-name`.
  First the `attr-name' is compared to the declared name.  If no attribute
  matches, the local names are compared, too."
  [elem attr-name]
  (if-let [attr (first (filter #(= (declared-name %) (name attr-name))
                               (adjs elem :attributes)))]
    (value attr :value)
    (if-let [attrs (seq (filter #(= (value % :name) (name attr-name))
                                (adjs elem :attributes)))]
      (value (the attrs) :value)
      (errorf "No such attribute %s at element %s." elem attr-name))))

(defn describe-element
  "Returns a map describing the given xml Element vertex `e`."
  ([e]
     (describe-element e true))
  ([e with-children]
     {:expanded-name (expanded-name e)
      :attrs (apply hash-map
                    (mapcat (fn [a]
                              [(keyword (expanded-name a)) (value a :value)])
                            (adjs e :attributes)))
      :children (if with-children
                  (map #(describe-element % false)
                       (adjs e :children))
                  :skipped)}))

;;# Parsing XML -> XMLGraph

;;## Internal vars

(def ^:dynamic ^:private *graph*)
(def ^:dynamic ^:private *stack*)
(def ^:dynamic ^:private *current*)
(def ^:dynamic ^:private *attr-type-fn*)
(def ^:dynamic ^:private *text-type-fn*)
(def ^:dynamic ^:private *id2elem*) ;; map from ID to Element vertex

;; map from Referent vertex to a vector of referenced element IDs (an attr or
;; Text can reference multiple elements in terms of a IDREFS attr name)
(def ^:dynamic ^:private *referent2refed-ids*)
;; set of Attribute vertices whose value is an EMFFragmentPath expression that
;; has to be resolved after the graph has successfully been created
(def ^:dynamic ^:private *emf-fragment-path-attrs*)

(defonce ^:private xml-stream-constants-map
  (apply hash-map
         (mapcat (fn [^java.lang.reflect.Field f]
                   [(.get f nil) (.getName f)])
                 (seq (.getDeclaredFields XMLStreamConstants)))))

;;## Internal functions

(defn ^:private resolve-refs
  "Create References edges for ID/IDREF[S]s collected while parsing."
  []
  (loop [a2rs *referent2refed-ids*]
    (when (seq a2rs)
      (let [[attr refs] (first a2rs)]
        (loop [ref refs]
          (when (seq ref)
            (create-edge! (graph attr)
                          'References attr
                          (or (*id2elem* (first ref))
                              (errorf "No element for id %s." (first ref))))
            (recur (rest ref)))))
      (recur (rest a2rs)))))

(defn ^:private eval-emf-fragment-1 [start exps]
  (if (seq exps)
    (let [^String f (first exps)]
      (recur
       (cond
        ;; @members
        (.startsWith f "@") (children start (symbol (subs f 1)))
        ;; @members.17
        (re-matches #"[0-9]+" f) (nth (if (vertex? start)
                                        (adjs start :children)
                                        start)
                                      (Long/valueOf f))
        ;; @members[firstName='Hugo'] where firstName is an attribute set as
        ;; eKeys feature for the members reference
        (re-matches #".*=.*" f)
        (let [m (apply hash-map
                       (mapcat (fn [kv]
                                 (let [[k v] (str/split kv #"=")]
                                   [k (subs v 1 (dec (count v)))]))
                               (str/split f #",[ ]*")))]
          (the (fn [e]
                 (every? (fn [[a v]]
                           (= (attribute-value e a) v))
                         m))
               start))
        ;; Oh, we don't know that...
        :else (errorf "Don't know how to handle EMF fragment '%s'." f))
       (rest exps)))
    start))

(defn ^:private eval-emf-fragment [g frag]
  (let [r (if (graph? g)
            (the (vseq g 'RootElement))
            g)]
    (eval-emf-fragment-1 r (next (str/split frag #"[/.\[\]]+")))))

(defn ^:private resolve-emf-fragment-paths
  ""
  []
  (let [r (the (vseq *graph* 'RootElement))]
    (doseq [a *emf-fragment-path-attrs*
            :let [fe (value a :value)]]
      (doseq [exp (str/split fe #" ")]
        (if-let [t (eval-emf-fragment r exp)]
          (create-edge! *graph* 'References a t)
          (binding [*out* *err*]
            (println (format "Couldn't resolve EMF Fragment Path '%s'." exp))))))))

(defn ^:private handle-start-document [^StartDocument ev]
  nil)

(defn ^:private handle-end-document [^EndDocument ev]
  (resolve-refs)
  (resolve-emf-fragment-paths))

(defn ^:private handle-type-semantics [t container-vertex referent-vertex val]
  (condp = t
    "EMFFragmentPath"  (set! *emf-fragment-path-attrs*
                             (conj *emf-fragment-path-attrs* referent-vertex))
    "ID"     (set! *id2elem* (assoc *id2elem* val container-vertex))
    "IDREF"  (set! *referent2refed-ids*
                   (update-in *referent2refed-ids* [referent-vertex]
                              #(conj (vec %) val)))
    "IDREFS" (set! *referent2refed-ids*
                   (update-in *referent2refed-ids* [referent-vertex]
                              #(into (vec %) (clojure.string/split val #"\s+"))))
    nil))

(defn ^:private handle-attribute [elem ^Attribute a]
  (let [qn (.getName a)
        val (.getValue a)
        t (if *attr-type-fn*
            (*attr-type-fn* (qualified-name elem) (str qn) val)
            (.getDTDType a))
        av (create-vertex! *graph* 'Attribute)]
    (set-value! av :value val)
    (set-value! av :name (.getLocalPart qn))
    (let [p (.getPrefix qn)
          u (.getNamespaceURI qn)]
      (when (seq p)
        (set-value! av :nsPrefix p))
      (when (seq u)
        (set-value! av :nsURI u)))
    (create-edge! *graph* 'HasAttribute elem av)
    (handle-type-semantics t elem av val)))

(defn ^:private handle-start-element [^StartElement ev]
  (let [type (if (seq *stack*) 'Element 'RootElement)
        e (create-vertex! *graph* type)
        qn (.getName ev)]
    (set-value! e :name (.getLocalPart qn))
    (let [p (.getPrefix qn)
          u (.getNamespaceURI qn)]
      (when (seq p)
        (set-value! e :nsPrefix p))
      (when (seq u)
        (set-value! e :nsURI u)))
    (doseq [a (iterator-seq (.getAttributes ev))]
      (handle-attribute e a))
    (when *current*
      (create-edge! (graph e) 'HasChild *current* e))
    (set! *stack* (conj *stack* *current*))
    (set! *current* e)))

(defn ^:private handle-end-element [^EndElement ev]
  (set! *current* (peek *stack*))
  (set! *stack* (pop *stack*)))

(defn ^:private handle-characters [^Characters ev]
  (when-not (or (.isIgnorableWhiteSpace ev)
                (.isWhiteSpace ev))
    (let [txt (create-vertex! *graph* 'Text)
          data (.getData ev)
          parent (adj *current* :parent)
          t (if *text-type-fn*
              (*text-type-fn* (qualified-name parent) (qualified-name *current*) data)
              "CDATA")]
      (set-value! txt :content data)
      (create-edge! *graph* 'HasText *current* txt)
      (when *text-type-fn*
        (handle-type-semantics t parent *current* data)))))

(defn ^:private parse [f]
  (let [xer (.createXMLEventReader
             (XMLInputFactory/newFactory)
             (io/input-stream f))
        conts (iterator-seq xer)]
    (doseq [^XMLEvent ev conts]
      (condp = (.getEventType ev)
        XMLStreamConstants/START_DOCUMENT (handle-start-document ev)
        XMLStreamConstants/END_DOCUMENT   (handle-end-document ev)
        XMLStreamConstants/START_ELEMENT  (handle-start-element ev)
        XMLStreamConstants/END_ELEMENT    (handle-end-element ev)
        XMLStreamConstants/CHARACTERS     (handle-characters ev)
        (binding [*out* *err*]
          (println "Unhandled XMLEvent of type"
                   (xml-stream-constants-map (.getEventType ev))))))))

;;## The user API

(defn xml2xml-graph
  "Parse the XML file `f` into a TGraph conforming the generic XML schema.
  IDREF resolving, which is needed for creating References edges, works
  automatically only for XML files containing a DTD describing them.

  If you want IDREFs resolved anyway, you have to provide an `attr-type-fn`
  that takes 3 arguments: an element's expanded name, an attribute name, and
  that attribute's value.  It should return that attribute's type as string:
  \"ID\", \"IDREF\", \"IDREFS\", \"EMFFragmentPath\", or nil (meaning CDATA).

  Also, some poor XML documents model references with custom elements, like

    <person>
      <pid>PID</pid>
      <spouse>PID1</spouse>
      <children>PID2 PID3</children>
    </person>

  So here, the pid element's text has the meaning of an ID attribute, the
  spouse element's text has the meaning of an IDREF attribute, and the
  children's text has the meaning of an IDREFS attribute.  To resolve
  references with such documents, you can specify an `text-type-fn` which
  receives 3 arguments: the parent Element's qualified name (see
  `qualified-name`, \"person\" in the example), the current Element's qualified
  name (\"pid\", \"spouse\", or \"children\" in the example) and the text
  value.  It should return the type of the text as string: \"ID\", \"IDREF\",
  \"IDREFS\", \"EMFFragmentPath\", or nil (meaning CDATA)."
  ([f]
     (xml2xml-graph f nil nil))
  ([f attr-type-fn]
     (xml2xml-graph f attr-type-fn nil))
  ([f attr-type-fn text-type-fn]
     (binding [*graph* (create-graph
                        (load-schema (io/resource "xml-schema.tg")) f)
               *stack*   nil
               *current* nil
               *id2elem* {}
               *referent2refed-ids* {}
               *attr-type-fn* attr-type-fn
               *text-type-fn* text-type-fn
               *emf-fragment-path-attrs* #{}]
       (parse f)
       *graph*)))

;;# Saving back to XML

;;## Internal vars and fns

(def ^:private ^:dynamic ^java.io.Writer *writer*)
(def ^:private ^:dynamic *indent-level*)
(def ^:private ^:dynamic *last-node-was-text*)

(defn ^:private xml-escape-chars ^String [val]
  (str/escape val {\" "&quot;", \' "&apos;", \& "&amp;", \< "&lt;", \> "&gt;"}))

(defn ^:private attributes-str [elem]
  (let [s (str/join " " (map (fn [a]
                               (format "%s=\"%s\""
                                       (declared-name a)
                                       (xml-escape-chars (value a :value))))
                             (adjs elem :attributes)))]
    (if (seq s)
      (str " " s)
      s)))

(defn ^:private indent []
  (.write *writer* ^String (apply str (repeat (* 2 *indent-level*) \space))))

(defn ^:private emit-text [txt]
  (set! *last-node-was-text* true)
  (.write *writer* (xml-escape-chars (value txt :content))))

(defn ^:private emit-element [elem]
  (indent)
  (let [has-contents (seq (iseq elem 'HasContent :out))
        contains-text-first (when-let [i (first (iseq elem 'HasContent :out))]
                              (has-type? i 'HasText))]
    (.write *writer* (format "<%s%s%s>%s"
                             (declared-name elem)
                             (attributes-str elem)
                             (if has-contents "" "/")
                             (if contains-text-first
                               "" "\n")))
    (when has-contents
      (binding [*indent-level* (inc *indent-level*)]
        (doseq [c (adjs elem :contents)]
          (if (has-type? c 'Text)
            (emit-text c)
            (emit-element c))))
      (when-not *last-node-was-text*
        (indent))
      (.write *writer* (format "</%s>\n" (declared-name elem)))
      (set! *last-node-was-text* false))))

;;## The user API

(defn xml-graph2xml
  "Serializes the given XMLGraph `g` back to an XML document `f`."
  [g f]
  (with-open [w (io/writer f)]
    (binding [*writer* w
              *indent-level* 0
              *last-node-was-text* false]
      (.write *writer* "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
      (doseq [re (vseq g 'RootElement)]
        (emit-element re))
      (.flush *writer*))))

#_(xml-graph2xml (xml2xml-graph "test/input/xmltg-example-with-dtd.xml")
                 "/home/horn/xmltg-example-with-dtd.xml")

#_(show-graph (xml2xml-graph "test/input/example.families"
                             (fn [en a v]
                               (when (re-matches #".*@.*" v)
                                 "EMFFragmentPath"))))

#_(show-graph (xml2xml-graph "/home/horn/example.families"
                             (fn [en a v]
                               (when (re-matches #".*@.*" v)
                                 "EMFFragmentPath"))))

#_(let [root (the (vseq (xml2xml-graph "test/input/example.families") 'RootElement))]
    (adjs root :attributes))

#_(xml-graph2xml (xml2xml-graph "test/input/example.families")
                 "/home/horn/example.families")

#_(attributes-str (the (vseq (xml2xml-graph "test/input/example.families") 'RootElement)))
