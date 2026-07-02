(ns vector-search.core-test
  (:require [clojure.test :refer [deftest is]]
            [vector-search.core :as vs]))

(set! *warn-on-reflection* true)

(defn approx=
  ([expected actual]
   (approx= expected actual 1.0e-5))
  ([expected actual epsilon]
   (<= (Math/abs (- (double expected) (double actual))) epsilon)))

(defn vec-approx=
  [expected actual]
  (and (= (count expected) (count actual))
       (every? true? (map approx= expected actual))))

(defn ex-data-for
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn dot
  ^double [xs ys]
  (reduce + 0.0 (map (fn [x y] (* (double x) (double y))) xs ys)))

(defn magnitude
  ^double [xs]
  (Math/sqrt (dot xs xs)))

(defn cosine
  ^double [xs ys]
  (/ (dot xs ys) (* (magnitude xs) (magnitude ys))))

(defn recall-at
  ^double [expected actual k]
  (let [expected-set (set (take k expected))
        actual-set (set (take k actual))]
    (/ (double (count (filter expected-set actual-set))) k)))

(deftest creates-index-with-validation
  (is (= :missing-dim
         (:vector-search/error (ex-data-for #(vs/index {})))))
  (is (= {:vector-search/error :unknown-metric
          :metric :taxicab}
         (ex-data-for #(vs/index {:dim 2 :metric :taxicab}))))
  (is (= 50 (get-in (vs/index {:dim 2}) [:opts :ef]))))

(deftest cosine-search-uses-cosine-similarity-scores
  (let [idx (vs/index {:dim 4 :metric :cosine :capacity 8})]
    (vs/add! idx :a [1.0 0.0 0.0 0.0] {:label "exact"})
    (vs/add! idx :b [0.9 0.1 0.0 0.0] {:label "near"})
    (vs/add! idx :c [0.0 1.0 0.0 0.0] {:label "orthogonal"})
    (let [results (vs/search idx [1.0 0.0 0.0 0.0] 3)]
      (is (= [:a :b :c] (mapv :id results)))
      (is (approx= 1.0 (:score (first results)) 1.0e-3))
      (is (= {:label "near"} (:metadata (second results)))))))

(deftest dot-search-uses-actual-dot-product-score
  (let [idx (vs/index {:dim 2 :metric :dot :capacity 4})]
    (vs/add! idx :small [1.0 0.0])
    (vs/add! idx :large [2.0 0.0])
    (let [results (vs/search idx [1.0 0.0] 2)]
      (is (= [:large :small] (mapv :id results)))
      (is (approx= 2.0 (:score (first results))))
      (is (approx= 1.0 (:score (second results)))))))

(deftest euclidean-search-uses-distance-score
  (let [idx (vs/index {:dim 2 :metric :euclidean :capacity 4})]
    (vs/add! idx :near [1.0 1.0])
    (vs/add! idx :far [3.0 4.0])
    (let [results (vs/search idx [0.0 0.0] 2)]
      (is (= [:near :far] (mapv :id results)))
      (is (approx= (Math/sqrt 2.0) (:score (first results))))
      (is (< (:score (first results)) (:score (second results)))))))

(deftest metadata-and-vectors-round-trip
  (let [idx (vs/index {:dim 2 :capacity 4})
        raw (float-array [0.25 0.75])]
    (vs/add! idx :with-meta raw {:kind :stored})
    (vs/add! idx :without-meta [0.0 1.0])
    (let [item (vs/get-item idx :with-meta)
          result (first (vs/search idx [0.25 0.75] 1))]
      (is (= :with-meta (:id item)))
      (is (vec-approx= [0.25 0.75] (seq (:vector item))))
      (is (= {:kind :stored} (:metadata item)))
      (is (= {:kind :stored} (:metadata result)))
      (is (nil? (:metadata (vs/get-item idx :without-meta)))))))

(deftest add-replaces-existing-id
  (let [idx (vs/index {:dim 2 :metric :cosine :capacity 4})]
    (vs/add! idx :x [1.0 0.0] {:v 1})
    (vs/add! idx :x [0.0 1.0] {:v 2})
    (is (= 1 (vs/size idx)))
    (is (= {:v 2} (:metadata (vs/get-item idx :x))))
    (is (= :x (:id (first (vs/search idx [0.0 1.0] 1)))))
    (is (approx= 1.0 (:score (first (vs/search idx [0.0 1.0] 1))) 1.0e-3))))

(deftest remove-deletes-item-and-metadata
  (let [idx (vs/index {:dim 2 :capacity 4})]
    (vs/add! idx :x [1.0 0.0] {:v 1})
    (vs/add! idx :y [0.0 1.0] {:v 2})
    (is (true? (vs/remove! idx :x)))
    (is (false? (vs/remove! idx :x)))
    (is (nil? (vs/get-item idx :x)))
    (is (= [:y] (mapv :id (vs/search idx [1.0 0.0] 10))))
    (is (= 1 (vs/size idx)))))

(deftest validates-dimensions
  (let [idx (vs/index {:dim 3})]
    (is (= {:vector-search/error :dim-mismatch
            :expected 3
            :actual 2}
           (ex-data-for #(vs/add! idx :x [1.0 2.0]))))
    (is (= {:vector-search/error :dim-mismatch
            :expected 3
            :actual 4}
           (ex-data-for #(vs/search idx [1.0 2.0 3.0 4.0] 1))))))

(deftest handles-empty-and-oversized-k
  (let [idx (vs/index {:dim 2 :capacity 4})]
    (is (= [] (vs/search idx [1.0 0.0] 10)))
    (vs/add! idx :a [1.0 0.0])
    (vs/add! idx :b [0.0 1.0])
    (is (= 2 (count (vs/search idx [1.0 0.0] 10))))))

(deftest sequential-inputs-work
  (let [idx (vs/index {:dim 2 :capacity 4})]
    (vs/add! idx :a (vector 1.0 0.0))
    (is (= :a (:id (first (vs/search idx (list 1.0 0.0) 1)))))))

(deftest grows-capacity-before-adding
  (let [idx (vs/index {:dim 2 :capacity 2})]
    (doseq [n (range 10)]
      (vs/add! idx n [(double n) 1.0]))
    (is (= 10 (vs/size idx)))
    (is (every? some? (map #(vs/get-item idx %) (range 10))))
    (is (= 9 (:id (first (vs/search idx [9.0 1.0] 1)))))))

(deftest seeded-cosine-recall-smoke
  (let [rng (java.util.Random. 42)
        dim 32
        total 1000
        k 10
        idx (vs/index {:dim dim :metric :cosine :capacity total})
        random-vector (fn []
                        (vec (repeatedly dim
                                         #(- (* 2.0 (.nextDouble ^java.util.Random rng)) 1.0))))
        items (vec (for [id (range total)]
                     {:id id :vector (random-vector)}))]
    (vs/add-batch! idx items)
    (let [queries (vec (repeatedly 20 random-vector))
          recalls (for [query queries
                        :let [expected (->> items
                                            (sort-by (fn [{:keys [vector]}]
                                                       (- (cosine query vector))))
                                            (map :id))
                              actual (map :id (vs/search idx query k))]]
                    (recall-at expected actual k))
          mean-recall (/ (reduce + recalls) (double (count recalls)))]
      (is (>= mean-recall 0.9)
          (str "mean recall@10 was " mean-recall)))))
