(ns funnyqt.emf.test.query
  (:refer-clojure :exclude [parents])
  (:use funnyqt.utils)
  (:use funnyqt.generic)
  (:use ordered.set)
  (:use ordered.map)
  (:use funnyqt.emf.core)
  (:use funnyqt.emf.query)
  (:use [funnyqt.emf.test.core :only [family-model]])
  (:use clojure.test))

(deftest test-basic
  (let [fm (the family-model)]
    (are [x y n] (let [ox (into-oset x)]
                   (and (= ox y) (== n (count ox))))
         (erefs fm) (reachables fm -->>) 16
         (ecrossrefs fm) (reachables fm -->) 0
         (erefs fm :members) (reachables fm :members) 13
         (erefs fm :families) (reachables fm :families) 3
         (erefs fm [:members :families]) (reachables fm [p-alt :members :families]) 16
         (econtents fm) (reachables fm [p-* -->>]) 17)))

(defn get-member
  [first-name]
  (the (filter #(= (eget % :firstName) first-name)
               (econtents family-model 'Member))))

(defn get-family
  [street]
  (the (filter #(= (eget % :street) street)
               (econtents family-model 'Family))))

(deftest test--<>
  (let [fm (the family-model)
        diana (get-member "Diana")]
    (is (= #{fm} (reachables diana --<>)))
    (is (= #{}  (reachables fm --<>)))))

(deftest test<--
  (let [fm (the family-model)
        diana (get-member "Diana")
        dennis (get-member "Dennis")]
    (is (= #{(get-family "Smithway 17")} (reachables diana <--)))
    (is (= #{(get-family "Smithway 17")
             (get-family "Smith Avenue 4")}
           (reachables dennis <--)))
    ;; Using the opposite ref
    (is (= #{(get-family "Smithway 17")}
           (reachables dennis [--> :familyFather])
           (reachables dennis [<-- :father])))
    ;; Using search
    (is (= #{(get-family "Smithway 17")}
           (reachables dennis [--> :familyFather])
           (reachables dennis [<-- :father (econtents family-model)])))
    (is (= #{(get-family "Smithway 17")
             (get-family "Smith Avenue 4")}
           (reachables dennis [--> [:familyFather :familySon]])
           (reachables dennis [<-- [:father :sons]])))))

(deftest test<<--
  (let [fm (the family-model)
        diana (get-member "Diana")
        dennis (get-member "Dennis")]
    (is (= #{fm (get-family "Smithway 17")} (reachables diana <<--)))
    (is (= #{fm} (reachables diana [<<-- :members])))
    (is (= #{fm
             (get-family "Smithway 17")
             (get-family "Smith Avenue 4")}
           (reachables dennis <<--)))
    ;; Using the opposite ref
    (is (= #{(get-family "Smithway 17")}
           (reachables dennis [-->> :familyFather])
           (reachables dennis [<<-- :father])))
    ;; Using search
    (is (= #{(get-family "Smithway 17")}
           (reachables dennis [-->> :familyFather])
           (reachables dennis [<<-- :father (econtents family-model)])))
    (is (= #{(get-family "Smithway 17")
             (get-family "Smith Avenue 4")}
           (reachables dennis [-->> [:familyFather :familySon]])
           (reachables dennis [<<-- [:father :sons]])))
    (is (= #{fm
             (get-family "Smithway 17")
             (get-family "Smith Avenue 4")}
           (reachables dennis [-->> [:model :familyFather :familySon]])
           (reachables dennis [<<-- [:members :father :sons]])))))

(defn- parents
  [m]
  (reachables m [p-seq
                 [p-alt :familySon :familyDaughter]
                 [p-alt :father :mother]]))

(defn- aunts-or-uncles
  [m r]
  (let [ps (parents m)]
    (reachables ps [p-seq
                    [p-alt :familySon :familyDaughter]
                    r
                    [p-restr nil #(not (member? % ps))]])))

(defn- aunts
  [m]
  (aunts-or-uncles m :daughters))

(defn- uncles
  [m]
  (aunts-or-uncles m :sons))

(deftest test-relationships
  (let [diana (get-member "Diana")
        ps (parents diana)
        us (uncles diana)
        as (aunts diana)]
    (is (== 2 (count ps)))
    (is (= #{"Debby" "Dennis"}
           (into #{} (map #(eget % :firstName) ps))))
    (is (== 2 (count us)))
    (is (= #{"Stu" "Sven"}
           (into #{} (map #(eget % :firstName) us))))
    (is (== 3 (count as)))
    (is (= #{"Stella" "Carol" "Conzuela"}
           (into #{} (map #(eget % :firstName) as))))))

