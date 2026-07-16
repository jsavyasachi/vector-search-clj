(ns vector-search.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [vector-search.core :as vs])
  (:import [java.io File]
           [java.nio.file Files]))

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

(defn delete-recursive!
  [^File file]
  (when (.exists file)
    (doseq [^File f (reverse (file-seq file))]
      (.delete f))))

(defn temp-dir
  ^File []
  (.toFile (Files/createTempDirectory "vector-search-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn comparable-results
  [results]
  (mapv #(update % :score double) results))

(defn results-approx=
  [expected actual]
  (and (= (mapv :id expected) (mapv :id actual))
       (= (mapv :metadata expected) (mapv :metadata actual))
       (every? true?
               (map approx=
                    (map :score expected)
                    (map :score actual)))))

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

(deftest exact-search-returns-nearest-neighbors
  (testing "cosine"
    (let [idx (vs/index {:type :exact :dim 2 :metric :cosine})]
      (vs/add-batch! idx [{:id :x :vector [1.0 0.0] :metadata {:label "x"}}
                          {:id :near :vector [0.8 0.6] :metadata {:label "near"}}
                          {:id :orthogonal :vector [0.0 1.0] :metadata {:label "orthogonal"}}
                          {:id :opposite :vector [-1.0 0.0] :metadata {:label "opposite"}}])
      (let [results (vs/search idx [1.0 0.0] 4)]
        (is (= [:x :near :orthogonal :opposite] (mapv :id results)))
        (is (= {:label "near"} (:metadata (second results))))
        (is (approx= 1.0 (:score (first results))))
        (is (approx= 0.8 (:score (second results))))
        (is (approx= 0.0 (:score (nth results 2))))
        (is (approx= -1.0 (:score (nth results 3)))))))
  (testing "euclidean"
    (let [idx (vs/index {:type :exact :dim 2 :metric :euclidean})]
      (vs/add-batch! idx [{:id :origin :vector [0.0 0.0]}
                          {:id :one-one :vector [1.0 1.0]}
                          {:id :three-four :vector [3.0 4.0]}])
      (let [results (vs/search idx [0.0 0.0] 3)]
        (is (= [:origin :one-one :three-four] (mapv :id results)))
        (is (approx= 0.0 (:score (first results))))
        (is (approx= (Math/sqrt 2.0) (:score (second results))))
        (is (approx= 5.0 (:score (nth results 2))))))))

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

(deftest hnsw-recall-matches-exact-ground-truth
  (let [rng (java.util.Random. 42)
        dim 32
        total 1000
        k 10
        hnsw (vs/index {:type :hnsw :dim dim :metric :cosine :capacity total})
        exact (vs/index {:type :exact :dim dim :metric :cosine})
        random-vector (fn []
                        (vec (repeatedly dim
                                         #(- (* 2.0 (.nextDouble ^java.util.Random rng)) 1.0))))
        items (vec (for [id (range total)]
                     {:id id :vector (random-vector)}))]
    (vs/add-batch! hnsw items)
    (vs/add-batch! exact items)
    (let [queries (vec (repeatedly 20 random-vector))
          recalls (for [query queries
                        :let [expected (map :id (vs/search exact query k))
                              actual (map :id (vs/search hnsw query k))]]
                    (recall-at expected actual k))
          mean-recall (/ (reduce + recalls) (double (count recalls)))]
      (is (>= mean-recall 0.9)
          (str "mean recall@10 was " mean-recall)))))

(deftest exact-rejects-hnsw-only-options
  (doseq [option [:m :ef-construction :ef]]
    (is (= {:vector-search/error :invalid-option
            :type :exact
            :option option}
           (ex-data-for #(vs/index (assoc {:type :exact :dim 2} option 10))))))
  (let [idx (vs/index {:type :exact :dim 2})]
    (vs/add! idx :x [1.0 0.0])
    (is (= {:vector-search/error :invalid-option
            :type :exact
            :option :ef}
           (ex-data-for #(vs/search idx [1.0 0.0] 1 {:ef 10}))))))

(deftest save-load-round-trips-index-and-metadata
  (let [dir (temp-dir)]
    (try
      (let [idx (vs/index {:dim 3 :metric :cosine :capacity 4 :ef 25})
            metadata {:label "alpha"
                      :nested {:tags [:one :two]
                               :settings {:enabled? true
                                          :threshold 0.75}}}]
        (vs/add! idx :alpha [1.0 0.0 0.0] metadata)
        (vs/add! idx :beta [0.8 0.2 0.0] {:label "beta"})
        (vs/add! idx :gamma [0.0 1.0 0.0] {:label "gamma"})
        (let [before (comparable-results (vs/search idx [1.0 0.0 0.0] 3))]
          (is (= dir (vs/save idx dir)))
          (let [loaded (vs/load-index dir)
                after (comparable-results (vs/search loaded [1.0 0.0 0.0] 3))]
            (is (results-approx= before after))
            (is (= metadata (:metadata (vs/get-item loaded :alpha))))
            (is (= 3 (vs/size loaded))))))
      (finally
        (delete-recursive! dir)))))

(deftest exact-save-load-round-trips-index-and-metadata
  (let [dir (temp-dir)]
    (try
      (let [idx (vs/index {:type :exact :dim 3 :metric :cosine})
            metadata {:label "alpha"
                      :nested {:tags [:one :two]}}]
        (vs/add! idx :alpha [1.0 0.0 0.0] metadata)
        (vs/add! idx :beta [0.8 0.2 0.0] {:label "beta"})
        (vs/add! idx :gamma [0.0 1.0 0.0] {:label "gamma"})
        (let [before (comparable-results (vs/search idx [1.0 0.0 0.0] 3))]
          (is (= dir (vs/save idx dir)))
          (let [loaded (vs/load-index dir)
                after (comparable-results (vs/search loaded [1.0 0.0 0.0] 3))]
            (is (= :exact (get-in loaded [:opts :type])))
            (is (results-approx= before after))
            (is (= metadata (:metadata (vs/get-item loaded :alpha))))
            (is (= 3 (vs/size loaded))))))
      (finally
        (delete-recursive! dir)))))

(deftest legacy-save-without-type-loads-as-hnsw
  (let [dir (temp-dir)]
    (try
      (let [idx (vs/index {:dim 2 :metric :cosine :capacity 4})]
        (vs/add! idx :a [1.0 0.0] {:label "a"})
        (vs/add! idx :b [0.0 1.0] {:label "b"})
        (vs/save idx dir)
        (let [meta-file (File. dir "meta.edn")
              meta (read-string (slurp meta-file))]
          (spit meta-file (pr-str (update meta :opts dissoc :type))))
        (let [loaded (vs/load-index dir)]
          (is (= :hnsw (get-in loaded [:opts :type])))
          (is (= [:a :b] (mapv :id (vs/search loaded [1.0 0.0] 2))))
          (is (= {:label "a"} (:metadata (vs/get-item loaded :a))))))
      (finally
        (delete-recursive! dir)))))

(deftest loaded-index-can-be-mutated
  (let [dir (temp-dir)]
    (try
      (let [idx (vs/index {:dim 2 :capacity 2})]
        (vs/add! idx :a [1.0 0.0] {:n 1})
        (vs/add! idx :b [0.0 1.0] {:n 2})
        (vs/save idx dir)
        (let [loaded (vs/load-index dir)]
          (vs/add! loaded :c [0.9 0.1] {:n 3})
          (is (true? (vs/remove! loaded :b)))
          (is (= 2 (vs/size loaded)))
          (is (= [:a :c] (mapv :id (vs/search loaded [1.0 0.0] 10))))
          (is (= {:n 3} (:metadata (vs/get-item loaded :c))))))
      (finally
        (delete-recursive! dir)))))

(deftest load-index-reports-missing-index-files
  (let [dir (temp-dir)]
    (try
      (delete-recursive! dir)
      (is (= {:vector-search/error :index-not-found
              :path (.getPath dir)}
             (ex-data-for #(vs/load-index dir))))
      (finally
        (delete-recursive! dir)))))

(deftest save-overwrites-existing-directory
  (let [dir (temp-dir)]
    (try
      (let [idx (vs/index {:dim 2 :capacity 4})]
        (vs/add! idx :a [1.0 0.0])
        (vs/save idx dir)
        (vs/add! idx :b [0.0 1.0])
        (vs/save idx dir)
        (is (= 2 (vs/size (vs/load-index dir)))))
      (finally
        (delete-recursive! dir)))))

(deftest filtered-search
  (let [idx (vs/index {:dim 2 :metric :cosine :capacity 100})]
    (doseq [i (range 20)]
      (vs/add! idx i [(Math/cos (* 0.05 i)) (Math/sin (* 0.05 i))]
               {:group (if (even? i) :a :b)}))
    (testing "filter predicate restricts results and still returns k best-first"
      (let [res (vs/search idx [1.0 0.0] 5 {:filter #(= :a (get-in % [:metadata :group]))})]
        (is (= 5 (count res)))
        (is (every? #(= :a (get-in % [:metadata :group])) res))
        (is (= [0 2 4 6 8] (mapv :id res)))
        (is (apply >= (map :score res)))))
    (testing "filter that matches nothing returns empty"
      (is (= [] (vs/search idx [1.0 0.0] 3 {:filter (constantly false)}))))
    (testing "fewer matches than k returns all matches"
      (let [res (vs/search idx [1.0 0.0] 50 {:filter #(= :b (get-in % [:metadata :group]))})]
        (is (= 10 (count res)))))
    (testing "no opts arg behaves as before"
      (is (= 3 (count (vs/search idx [1.0 0.0] 3)))))
    (testing "empty opts map behaves as unfiltered"
      (is (= 3 (count (vs/search idx [1.0 0.0] 3 {})))))))

(deftest exact-filtered-search
  (let [idx (vs/index {:type :exact :dim 2 :metric :cosine})]
    (doseq [i (range 20)]
      (vs/add! idx i [(Math/cos (* 0.05 i)) (Math/sin (* 0.05 i))]
               {:group (if (even? i) :a :b)}))
    (let [res (vs/search idx [1.0 0.0] 5 {:filter #(= :a (get-in % [:metadata :group]))})]
      (is (= 5 (count res)))
      (is (every? #(= :a (get-in % [:metadata :group])) res))
      (is (= [0 2 4 6 8] (mapv :id res)))
      (is (apply >= (map :score res))))
    (is (= [] (vs/search idx [1.0 0.0] 3 {:filter (constantly false)})))))

(deftest bm25-search-matches-worked-example
  ;; Robertson BM25 with k1=1.2, b=0.75. N=3, avgdl=3, and both
  ;; query terms have df=2, giving idf=log(1 + 1.5/2.5).
  (let [idx (vs/index {:type :exact :dim 2})]
    (vs/add-batch! idx [{:id :d1 :vector [1.0 0.0]
                         :text "The quick brown fox"}
                        {:id :d2 :vector [0.0 1.0]
                         :text "the quick fox"}
                        {:id :d3 :vector [-1.0 0.0]
                         :text "lazy dog"}])
    (if-let [bm25-search (ns-resolve 'vector-search.core 'bm25-search)]
      (let [results (bm25-search idx "QUICK, fox!" 3)]
        (is (= [:d2 :d1] (mapv :id results)))
        (is (approx= 0.9400072585 (:score (first results))))
        (is (approx= 0.8272063875 (:score (second results))))
        (is (= nil (:metadata (first results)))))
      (is false "bm25-search is not implemented"))))

(deftest bm25-index-tracks-replacement-removal-and-options
  (let [idx (vs/index {:type :exact :dim 2})]
    (vs/add-batch! idx [{:id :a :vector [1.0 0.0] :text "alpha alpha"}
                        {:id :b :vector [0.0 1.0] :text "beta"}])
    (if-let [bm25-search (ns-resolve 'vector-search.core 'bm25-search)]
      (do
        (is (= [:a] (mapv :id (bm25-search idx "alpha" 10))))
        (vs/add-batch! idx [{:id :a :vector [1.0 0.0] :text "gamma"}])
        (is (= [] (bm25-search idx "alpha" 10)))
        (is (= [:a] (mapv :id (bm25-search idx "gamma" 10 {:k1 2.0 :b 0.5}))))
        (vs/remove! idx :a)
        (is (= [] (bm25-search idx "gamma" 10))))
      (is false "bm25-search is not implemented"))))
