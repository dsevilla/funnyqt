(ns funnyqt.test.pmatch
  (:use funnyqt.pmatch)
  (:use funnyqt.protocols)
  (:use funnyqt.tg)
  (:use funnyqt.query)
  (:use funnyqt.query.tg)
  (:use clojure.test))

(def fg (load-graph "test/input/familygraph.tg"))

(defpattern families-with-fathers-simple
  {:pattern-expansion-context :tg}
  [g]
  [f<Family> -hf<HasFather>-> m<Member>])

(deftest test-families-with-fathers-simple
  (is (= 3 (count (families-with-fathers-simple fg)))))

(defpattern families-with-fathers
  {:pattern-expansion-context :tg}
  ([g]
     [f<Family> -hf<HasFather>-> m<Member>])
  ([g famconst]
     [f<Family> -hf<HasFather>-> m<Member>
      :when (famconst f)]))

(deftest test-families-with-fathers
  (is (= 3 (count (families-with-fathers fg))))
  (is (= 3 (count (families-with-fathers fg (constantly true)))))
  (is (= 2 (count (families-with-fathers fg #(= "Smith" (value % :lastName)))))))

(defpattern given-fam-with-all-members
  {:pattern-expansion-context :tg}
  [g fam]
  [mem<Member> <-- fam])
