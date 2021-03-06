(ns ^{:pattern-expansion-context :tg}
  funnyqt.in-place.tg-test
  (:require [funnyqt.generic :as g]
            [funnyqt.query   :as q])
  (:use funnyqt.tg)
  (:use funnyqt.in-place)
  (:use funnyqt.tg-test)
  (:use clojure.test))


;;* BinTree eval

(defrule replace-binaryop
  "Replaces a binary operation with constant args with
  a constant of the result."
  [g] [b<BinaryOp> -<HasArg>-> a1<Const>
       b -<HasArg>-> a2<Const>
       :when (not= a1 a2)]
  (let [c (create-vertex! g 'Const)]
    (set-value! c :value (eval-exp b))
    (relink! b c nil :in))
  (g/delete! [b a1 a2]))

(deftest test-replace-binops
  (let [tree (bin-tree)]
    (is (== 4 (apply-repeatedly replace-binaryop tree)))
    (is (== 1 (vcount tree)))
    (is (== 0 (ecount tree)))
    (is (== 1.65 (value (q/the (vseq tree)) :value)))))

(deftest test-replace-binops2
  (let [tree (bin-tree)]
    (is (== 4 (apply-repeatedly
               ;; Also try with an anonymous rule
               (rule [g]
                     [b<BinaryOp> -<HasArg>-> a1<Const>
                      b -<HasArg>-> a2<Const>
                      :when (not= a1 a2)]
                     (let [c (create-vertex! g 'Const)]
                       (set-value! c :value (eval-exp b))
                       (relink! b c nil :in))
                     (g/delete! [b a1 a2]))
               tree)))
    (is (== 1 (vcount tree)))
    (is (== 0 (ecount tree)))
    (is (== 1.65 (value (q/the (vseq tree)) :value)))))

(deftest test-replace-binops3
  (let [tree (bin-tree)]
    (is (== 4 (apply-repeatedly
               ;; Also try with an anonymous rule with a label and more than
               ;; one sig.
               (rule foo
                     ([g x] [x --> y]
                        (throw (RuntimeException. "Must not have happened.")))
                     ([g]
                        [b<BinaryOp> -<HasArg>-> a1<Const>
                         b -<HasArg>-> a2<Const>
                         :when (not= a1 a2)]
                          (let [c (create-vertex! g 'Const)]
                            (set-value! c :value (eval-exp b))
                            (relink! b c nil :in))
                          (g/delete! [b a1 a2])))
               tree)))
    (is (== 1 (vcount tree)))
    (is (== 0 (ecount tree)))
    (is (== 1.65 (value (q/the (vseq tree)) :value)))))

(deftest test-replace-binops4
  (let [tree (bin-tree)]
    (letrule [(repl-bin-op
               [g] [b<BinaryOp> -<HasArg>-> a1<Const>
                    b -<HasArg>-> a2<Const>
                    :when (not= a1 a2)]
               (let [c (create-vertex! g 'Const)]
                 (set-value! c :value (eval-exp b))
                 (relink! b c nil :in))
               (g/delete! [b a1 a2]))]
      (is (== 4 (apply-repeatedly repl-bin-op tree)))
      (is (== 1 (vcount tree)))
      (is (== 0 (ecount tree)))
      (is (== 1.65 (value (q/the (vseq tree)) :value))))))

(deftest test-replace-binops5
  (let [tree (bin-tree)]
    (letrule [(repl-bin-op
               ([g x] [x --> y]
                  (throw (RuntimeException. "Must not have happened.")))
               ([g] [b<BinaryOp> -<HasArg>-> a1<Const>
                     b -<HasArg>-> a2<Const>
                     :when (not= a1 a2)]
                  (let [c (create-vertex! g 'Const)]
                    (set-value! c :value (eval-exp b))
                    (relink! b c nil :in))
                  (g/delete! [b a1 a2])))]
      (is (== 4 (apply-repeatedly repl-bin-op tree)))
      (is (== 1 (vcount tree)))
      (is (== 0 (ecount tree)))
      (is (== 1.65 (value (q/the (vseq tree)) :value))))))
