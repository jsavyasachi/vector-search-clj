(ns vector-search.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [vector-search.bm25 :as bm25]
            [vector-search.filter :as metadata-filter]
            [vector-search.hybrid :as hybrid])
  (:import [com.github.jelmerk.hnswlib.core DistanceFunction DistanceFunctions Index Item SearchResult]
           [com.github.jelmerk.hnswlib.core.bruteforce BruteForceIndex]
           [com.github.jelmerk.hnswlib.core.hnsw HnswIndex]
           [java.io File FileOutputStream Serializable]
           [java.util Optional]))

(set! *warn-on-reflection* true)

(deftype VItem [id ^floats vec]
  Item
  (id [_] id)
  (vector [_] vec)
  (dimensions [_] (alength vec))
  Serializable)

(def ^:private vitem-class
  (class (VItem. nil (float-array 0))))

(def ^:private default-hnsw-opts
  {:type :hnsw
   :metric :cosine
   :capacity 10000
   :m 16
   :ef-construction 200
   :ef 50})

(def ^:private default-exact-opts
  {:type :exact
   :metric :cosine})

(def ^:private hnsw-only-opts
  #{:m :ef-construction :ef})

(def ^:private float-array-class
  (Class/forName "[F"))

(defn- metric-distance-fn
  ^DistanceFunction [metric]
  (case metric
    :cosine DistanceFunctions/FLOAT_COSINE_DISTANCE
    :dot DistanceFunctions/FLOAT_INNER_PRODUCT
    :euclidean DistanceFunctions/FLOAT_EUCLIDEAN_DISTANCE
    (throw (ex-info "Unknown vector-search metric"
                    {:vector-search/error :unknown-metric
                     :metric metric}))))

(defn- invalid-option!
  [type option]
  (throw (ex-info "Invalid vector-search option"
                  {:vector-search/error :invalid-option
                   :type type
                   :option option})))

(defn- validate-exact-index-opts!
  [opts]
  (doseq [option hnsw-only-opts]
    (when (contains? opts option)
      (invalid-option! :exact option))))

(defn- normalize-opts
  [opts]
  (case (get opts :type :hnsw)
    :hnsw (merge default-hnsw-opts opts)
    :exact (do
             (validate-exact-index-opts! opts)
             (merge default-exact-opts opts))
    (throw (ex-info "Unknown vector-search index type"
                    {:vector-search/error :unknown-index-type
                     :type (:type opts)}))))

(defn- build-hnsw-index
  ^HnswIndex [{:keys [dim metric capacity m ef-construction ef]}]
  (-> (doto (HnswIndex/newBuilder (int dim) (metric-distance-fn metric) (int capacity))
        (.withM (int m))
        (.withEfConstruction (int ef-construction))
        (.withEf (int ef))
        (.withRemoveEnabled))
      (.build)))

(defn- build-exact-index
  ^BruteForceIndex [{:keys [dim metric]}]
  (-> (BruteForceIndex/newBuilder (int dim) (metric-distance-fn metric))
      (.build)))

(defn- build-index
  ^Index [{:keys [type] :as opts}]
  (case type
    :hnsw (build-hnsw-index opts)
    :exact (build-exact-index opts)))

(defn index
  "Creates an embedded vector index handle.

  :type is :hnsw by default. :exact uses exhaustive exact search, O(n) per
  query, with no tuning knobs; it is useful as ground truth or for small
  corpora.
  :ef trades recall for speed during search; higher values improve recall."
  [opts]
  (when-not (:dim opts)
    (throw (ex-info "Missing vector dimension"
                    {:vector-search/error :missing-dim})))
  (let [merged (normalize-opts opts)]
    {:index (build-index merged)
     :opts merged
     :metadata (atom {})
     :metadata-index (atom (metadata-filter/empty-index))
     :bm25 (atom (bm25/empty-index))
     :capacity (atom (:capacity merged))
     :lock (Object.)}))

(defn- coerce-vector
  ^floats [v]
  (cond
    (instance? float-array-class v) v
    (sequential? v) (float-array (map float v))
    :else (throw (ex-info "Vector must be a float array or sequence of numbers"
                          {:vector-search/error :invalid-vector}))))

(defn- checked-vector
  ^floats [idx v]
  (let [^floats coerced (coerce-vector v)
        expected (long (get-in idx [:opts :dim]))
        actual (long (alength coerced))]
    (when-not (= expected actual)
      (throw (ex-info "Vector dimension mismatch"
                      {:vector-search/error :dim-mismatch
                       :expected expected
                       :actual actual})))
    coerced))

(defn- get-optional
  ^Optional [idx id]
  (.get ^Index (:index idx) id))

(defn- optional-item
  ^VItem [^Optional optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- grow-if-full!
  [idx]
  (when (= :hnsw (get-in idx [:opts :type]))
    (let [^HnswIndex hnsw (:index idx)
          capacity-ref (:capacity idx)
          capacity (long @capacity-ref)]
      (when (>= (.size hnsw) capacity)
        (let [new-capacity (* 2 capacity)]
          (.resize hnsw (int new-capacity))
          (reset! capacity-ref new-capacity))))))

(defn add!
  "Adds or replaces id with vector v. Metadata is stored outside hnswlib.

  The five-argument form optionally indexes text for BM25 retrieval."
  ([idx id v]
   (add! idx id v nil))
  ([idx id v metadata]
   (add! idx id v metadata nil))
  ([idx id v metadata text]
   (let [^floats vector (checked-vector idx v)
         item (VItem. id vector)]
     (locking (:lock idx)
       (let [existing (optional-item (get-optional idx id))
             old-metadata (get @(:metadata idx) id)
             ^Index index (:index idx)]
         (when existing
           (.remove index id (.version ^VItem existing)))
         (grow-if-full! idx)
         (.add index item)
         (if (nil? metadata)
           (swap! (:metadata idx) dissoc id)
           (swap! (:metadata idx) assoc id metadata))
         (swap! (:metadata-index idx)
                #(-> %
                     (metadata-filter/remove-item id old-metadata)
                     (metadata-filter/add-item id metadata)))
         (swap! (:bm25 idx) bm25/remove-doc id)
         (when (some? text)
           (swap! (:bm25 idx) bm25/add-doc id text))))
     id)))

(defn add-batch!
  "Adds each {:id .. :vector .. :metadata .. :text ..} item and returns the count added."
  [idx items]
  (reduce (fn [n {:keys [id vector metadata text] :as item}]
            (cond
              (contains? item :text) (add! idx id vector metadata text)
              (contains? item :metadata) (add! idx id vector metadata)
              :else (add! idx id vector))
            (inc n))
          0
          items))

(defn bm25-search
  "Returns BM25 text matches best-first in the standard result-map shape.

  Text is lowercased and split on non-alphanumeric characters. Options `:k1`
  and `:b` default to 1.2 and 0.75."
  ([idx query k]
   (bm25-search idx query k nil))
  ([idx query k opts]
   (mapv (fn [{:keys [id] :as result}]
           (assoc result :metadata (get @(:metadata idx) id)))
         (bm25/search @(:bm25 idx) query k (or opts {})))))

(defn- raw-score
  ^double [metric distance]
  (let [d (double distance)]
    (case metric
      :cosine (- 1.0 d)
      :dot (- 1.0 d)
      :euclidean d)))

(defn- result-map
  [idx ^SearchResult result]
  (let [^VItem item (.item result)
        id (.id item)]
    {:id id
     :score (raw-score (get-in idx [:opts :metric]) (.distance result))
     :metadata (get @(:metadata idx) id)}))

(defn- raw-search
  [idx ^floats query candidate-count]
  (mapv #(result-map idx %)
        (.findNearest ^Index (:index idx) query (int candidate-count))))

(defn- candidate-search
  [idx ^floats query candidate-ids k]
  (let [metric (get-in idx [:opts :metric])
        ^DistanceFunction distance-fn (metric-distance-fn metric)]
    (->> candidate-ids
         (keep (fn [id]
                 (when-let [^VItem item (optional-item (get-optional idx id))]
                   {:id id
                    :score (raw-score metric
                                      (.distance distance-fn query (.vector item)))
                    :metadata (get @(:metadata idx) id)})))
         (sort-by (if (= :euclidean metric)
                    (fn [{:keys [id score]}] [score (pr-str id)])
                    (fn [{:keys [id score]}] [(- score) (pr-str id)])))
         (take k)
         vec)))

(defn search
  "Returns nearest results best-first.

  For :cosine and :dot, :score is a similarity where higher is better. For
  :euclidean, :score is L2 distance where lower is better.

  With opts, `:filter` can be a structured metadata filter (`:eq`, `:in`,
  `:range`, `:gt`, `:lt`, `:and`, `:or`, or `:not`) or a predicate over the
  result map. Structured equality and membership use an inverted metadata
  index, then only matching vectors are scored. Predicate filtering retains
  the original candidate over-fetching behavior."
  ([idx query-vec k]
   (search idx query-vec k nil))
  ([idx query-vec k {:keys [filter] :as opts}]
   (when (and (= :exact (get-in idx [:opts :type])) (contains? opts :ef))
     (invalid-option! :exact :ef))
   (let [^floats query (checked-vector idx query-vec)
         item-count (.size ^Index (:index idx))
         k (long k)]
     (cond
       (zero? (min k item-count)) []

       (nil? filter)
       (raw-search idx query (min k item-count))

       (map? filter)
       (candidate-search idx query
                         (metadata-filter/matching-ids @(:metadata-index idx)
                                                       @(:metadata idx)
                                                       filter)
                         k)

       :else
       (loop [n (if (= :exact (get-in idx [:opts :type]))
                  item-count
                  (min item-count (max (* 2 k) 32)))]
         (let [hits (into [] (comp (clojure.core/filter filter) (take k))
                          (raw-search idx query n))]
           (if (or (= (count hits) k) (>= n item-count))
             hits
             (recur (min item-count (* 2 n))))))))))

(defn hybrid-search
  "Fuses dense vector and BM25 text retrieval into standard result maps.

  The default `:fusion` is reciprocal rank fusion (`:rrf`) with `:rrf-k` 60.
  `:fusion :weighted` min-max normalizes each score list and combines it with
  `:dense-weight` and `:sparse-weight`, each defaulting to 0.5."
  ([idx query-vec query-text k]
   (hybrid-search idx query-vec query-text k nil))
  ([idx query-vec query-text k opts]
   (let [opts (or opts {})
         filter (:filter opts)
         filter-ids (when (map? filter)
                      (metadata-filter/matching-ids @(:metadata-index idx)
                                                    @(:metadata idx)
                                                    filter))
         candidate-count (min (.size ^Index (:index idx))
                              (long (get opts :candidate-count (max k (* 4 k)))))
         dense-results (search idx query-vec candidate-count
                               (when filter {:filter filter}))
         sparse-results (bm25-search idx query-text candidate-count
                                     (when filter-ids {:ids filter-ids}))
         sparse-results (if (and filter (not (map? filter)))
                          (into [] (clojure.core/filter filter) sparse-results)
                          sparse-results)]
     (hybrid/fuse dense-results sparse-results k
                  (assoc opts :dense-higher?
                         (not= :euclidean (get-in idx [:opts :metric])))))))

(defn remove!
  "Removes id from the index. Returns true when an item was removed."
  [idx id]
  (locking (:lock idx)
    (if-let [existing (optional-item (get-optional idx id))]
      (let [removed? (.remove ^Index (:index idx) id (.version ^VItem existing))]
        (when removed?
          (swap! (:metadata-index idx) metadata-filter/remove-item id
                 (get @(:metadata idx) id))
          (swap! (:metadata idx) dissoc id)
          (swap! (:bm25 idx) bm25/remove-doc id))
        removed?)
      false)))

(defn get-item
  "Returns {:id .. :vector float[] .. :metadata ..} for id, or nil."
  [idx id]
  (when-let [^VItem item (optional-item (get-optional idx id))]
    (let [id (.id item)]
      {:id id
       :vector (.vector item)
       :metadata (get @(:metadata idx) id)})))

(defn size
  "Returns the number of indexed items."
  ^long [idx]
  (.size ^Index (:index idx)))

(defn- path-file
  ^File [path]
  (io/file path))

(defn- missing-index!
  [^File dir]
  (throw (ex-info "Vector search index files not found"
                  {:vector-search/error :index-not-found
                   :path (.getPath dir)})))

(defn save
  "Persists idx into path, a directory created when absent. Returns path."
  [idx path]
  (let [dir (path-file path)
        index-file (io/file dir "index.bin")
        meta-file (io/file dir "meta.edn")]
    (.mkdirs dir)
    (with-open [out (FileOutputStream. ^File index-file)]
      (.save ^Index (:index idx) out))
    (spit meta-file
          (pr-str {:opts (:opts idx)
                   :capacity @(:capacity idx)
                   :metadata @(:metadata idx)
                   :metadata-index @(:metadata-index idx)
                   :bm25 @(:bm25 idx)}))
    path))

(defn load-index
  "Loads an index handle from path, a directory containing index.bin and meta.edn.

  :type in meta.edn selects :hnsw or :exact. Legacy saves without :type load as
  :hnsw. :exact uses exhaustive exact search, O(n) per query, with no tuning
  knobs; it is useful as ground truth or for small corpora."
  [path]
  (let [dir (path-file path)
        index-file (io/file dir "index.bin")
        meta-file (io/file dir "meta.edn")]
    (when-not (and (.isFile ^File index-file) (.isFile ^File meta-file))
      (missing-index! dir))
    (let [loader (.getClassLoader ^Class vitem-class)
          {:keys [opts capacity metadata metadata-index bm25]}
          (edn/read-string (slurp meta-file))
          type (get opts :type :hnsw)
          loaded (case type
                   :hnsw (HnswIndex/load ^File index-file ^ClassLoader loader)
                   :exact (BruteForceIndex/load ^File index-file ^ClassLoader loader)
                   (throw (ex-info "Unknown vector-search index type"
                                   {:vector-search/error :unknown-index-type
                                    :type type})))]
      {:index loaded
       :opts (assoc opts :type type)
       :metadata (atom metadata)
       :metadata-index (atom (or metadata-index
                                 (metadata-filter/from-metadata metadata)))
       :bm25 (atom (or bm25 (bm25/empty-index)))
       :capacity (atom capacity)
       :lock (Object.)})))
