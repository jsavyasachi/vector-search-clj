(ns vector-search.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [vector-search.bm25 :as bm25]
            [vector-search.filter :as metadata-filter]
            [vector-search.hybrid :as hybrid])
  (:import [com.github.jelmerk.hnswlib.core DistanceFunction DistanceFunctions Index Item SearchResult SparseVector]
           [com.github.jelmerk.hnswlib.core.bruteforce BruteForceIndex]
           [com.github.jelmerk.hnswlib.core.hnsw HnswIndex]
           [java.io File FileOutputStream Serializable]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util Comparator Optional]))

(set! *warn-on-reflection* true)

(deftype VItem [id ^floats vec]
  Item
  (id [_] id)
  (vector [_] vec)
  (dimensions [_] (alength vec))
  Serializable)

(deftype SparseVItem [id ^SparseVector vec dimensions]
  Item
  (id [_] id)
  (vector [_] vec)
  (dimensions [_] dimensions)
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

(def ^:private score-directions
  #{:higher-is-better :lower-is-better})

(defn- metric-distance-fn
  ^DistanceFunction [metric]
  (case metric
    :sparse-dot DistanceFunctions/FLOAT_SPARSE_VECTOR_INNER_PRODUCT
    :cosine DistanceFunctions/FLOAT_COSINE_DISTANCE
    :dot DistanceFunctions/FLOAT_INNER_PRODUCT
    :euclidean DistanceFunctions/FLOAT_EUCLIDEAN_DISTANCE
    :manhattan DistanceFunctions/FLOAT_MANHATTAN_DISTANCE
    :correlation DistanceFunctions/FLOAT_CORRELATION_DISTANCE
    :canberra DistanceFunctions/FLOAT_CANBERRA_DISTANCE
    :bray-curtis DistanceFunctions/FLOAT_BRAY_CURTIS_DISTANCE
    (throw (ex-info "Unknown vector-search metric"
                    {:vector-search/error :unknown-metric
                     :metric metric}))))

(defn- custom-distance-fn
  ^DistanceFunction [distance-fn]
  (reify DistanceFunction
    (distance [_ query candidate]
      (let [^floats query query
            ^floats candidate candidate]
        (float (distance-fn query candidate))))))

(defn- distance-fn
  ^DistanceFunction [{:keys [metric distance-fn]}]
  (if (= :custom metric)
    (custom-distance-fn distance-fn)
    (metric-distance-fn metric)))

(defn- score-higher?
  [{:keys [metric score-direction]}]
  (if (= :custom metric)
    (= :higher-is-better score-direction)
    (#{:cosine :dot :sparse-dot} metric)))

(defn- custom-distance-comparator
  ^Comparator [direction]
  (reify Comparator
    (compare [_ left right]
      (let [comparison (Double/compare (double left) (double right))]
        (if (= :higher-is-better direction)
          (- comparison)
          comparison)))))

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
  (let [type (get opts :type :hnsw)
        defaults (case type
                   :hnsw default-hnsw-opts
                   :exact (do
                            (validate-exact-index-opts! opts)
                            default-exact-opts)
                   (throw (ex-info "Unknown vector-search index type"
                                   {:vector-search/error :unknown-index-type
                                    :type type})))]
    (if (contains? opts :distance-fn)
      (do
        (when-not (ifn? (:distance-fn opts))
          (throw (ex-info "Custom distance function must be callable"
                          {:vector-search/error :invalid-distance-fn})))
        (when-not (contains? opts :score-direction)
          (throw (ex-info "Custom distance functions require a score direction"
                          {:vector-search/error :missing-score-direction})))
        (when-not (score-directions (:score-direction opts))
          (throw (ex-info "Unknown custom distance score direction"
                          {:vector-search/error :invalid-score-direction
                           :score-direction (:score-direction opts)})))
        (assoc (merge defaults opts) :metric :custom))
      (merge defaults opts))))

(defn- build-hnsw-index
  ^HnswIndex [{:keys [dim capacity m ef-construction ef] :as opts}]
  (let [builder (if (= :custom (:metric opts))
                  (HnswIndex/newBuilder (int dim)
                                        (distance-fn opts)
                                        (custom-distance-comparator (:score-direction opts))
                                        (int capacity))
                  (HnswIndex/newBuilder (int dim) (distance-fn opts) (int capacity)))]
    (-> (doto builder
        (.withM (int m))
        (.withEfConstruction (int ef-construction))
        (.withEf (int ef))
        (.withRemoveEnabled))
        (.build))))

(defn- build-exact-index
  ^BruteForceIndex [{:keys [dim] :as opts}]
  (if (= :custom (:metric opts))
    (.build (BruteForceIndex/newBuilder (int dim)
                                        (distance-fn opts)
                                        (custom-distance-comparator (:score-direction opts))))
    (.build (BruteForceIndex/newBuilder (int dim) (distance-fn opts)))))

(defn- build-index
  ^Index [{:keys [type] :as opts}]
  (case type
    :hnsw (build-hnsw-index opts)
    :exact (build-exact-index opts)))

(defn index
  "Creates an embedded vector index handle.

  :type is :hnsw by default. :exact does an exhaustive exact search, O(n) per
  query, with no tuning knobs. Use it as ground truth, or for a small corpus.
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

(defn- sparse-vector
  ^SparseVector [idx v]
  (let [[indices values] (if (and (map? v) (contains? v :indices) (contains? v :values))
                           [(:indices v) (:values v)]
                           [(keys v) (vals v)])
        entries (sort-by first (map vector indices values))
        indices (mapv first entries)
        values (mapv second entries)
        dimension (long (get-in idx [:opts :dim]))]
    (when-not (= (count indices) (count values))
      (throw (ex-info "Sparse vector indices and values must have equal lengths"
                      {:vector-search/error :invalid-vector})))
    (when-not (every? #(and (integer? %) (<= 0 % (dec dimension))) indices)
      (throw (ex-info "Sparse vector indices must be integers within the dimension"
                      {:vector-search/error :invalid-vector})))
    (when-not (= (count indices) (count (distinct indices)))
      (throw (ex-info "Sparse vector indices must be unique"
                      {:vector-search/error :invalid-vector})))
    (SparseVector. (int-array indices) (float-array (map float values)))))

(defn- checked-vector
  [idx v]
  (if (= :sparse-dot (get-in idx [:opts :metric]))
    (sparse-vector idx v)
    (let [^floats coerced (coerce-vector v)
        expected (long (get-in idx [:opts :dim]))
        actual (long (alength coerced))]
      (when-not (= expected actual)
        (throw (ex-info "Vector dimension mismatch"
                        {:vector-search/error :dim-mismatch
                         :expected expected
                         :actual actual})))
      coerced)))

(defn- get-optional
  ^Optional [idx id]
  (.get ^Index (:index idx) id))

(defn- optional-item
  [^Optional optional]
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
  "Adds or replaces id with vector v. The index stores metadata outside hnswlib.

  The five-argument form optionally indexes text for BM25 retrieval."
  ([idx id v]
   (add! idx id v nil))
  ([idx id v metadata]
   (add! idx id v metadata nil))
  ([idx id v metadata text]
   (let [vector (checked-vector idx v)
         item (if (= :sparse-dot (get-in idx [:opts :metric]))
                (SparseVItem. id vector (get-in idx [:opts :dim]))
                (VItem. id vector))]
     (locking (:lock idx)
       (let [existing (optional-item (get-optional idx id))
             old-metadata (get @(:metadata idx) id)
             ^Index index (:index idx)]
         (when existing
           (.remove index id (.version ^Item existing)))
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

  The index lowercases the text and splits it on non-alphanumeric characters.
  Options `:k1` and `:b` default to 1.2 and 0.75."
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
      :sparse-dot (- 1.0 d)
      d)))

(defn- exposed-vector
  [idx ^Item item]
  (if (= :sparse-dot (get-in idx [:opts :metric]))
    (let [^SparseVector vector (.vector item)]
      {:indices (vec (.indices vector))
       :values (vec (.values vector))})
    (.vector item)))

(defn- result-map
  [idx ^SearchResult result]
  (let [^Item item (.item result)
        id (.id item)]
    {:id id
     :score (raw-score (get-in idx [:opts :metric]) (.distance result))
     :metadata (get @(:metadata idx) id)
     :vector (when (= :sparse-dot (get-in idx [:opts :metric]))
               (exposed-vector idx item))}))

(defn- raw-search
  [idx query candidate-count]
  (mapv #(result-map idx %)
        (.findNearest ^Index (:index idx) query (int candidate-count))))

(defn- with-query-ef
  [idx opts f]
  (if (and (= :hnsw (get-in idx [:opts :type])) (contains? opts :ef))
    (locking (:lock idx)
      (let [^HnswIndex hnsw (:index idx)
            previous-ef (.getEf hnsw)]
        (try
          (.setEf hnsw (int (:ef opts)))
          (f)
          (finally
            (.setEf hnsw previous-ef)))))
    (f)))

(defn- candidate-search
  [idx query candidate-ids k]
  (let [index-opts (:opts idx)
        metric (:metric index-opts)
        ^DistanceFunction distance-fn (distance-fn index-opts)]
    (->> candidate-ids
         (keep (fn [id]
                 (when-let [^Item item (optional-item (get-optional idx id))]
                   {:id id
                    :score (raw-score metric
                                      (.distance distance-fn query (.vector item)))
                    :metadata (get @(:metadata idx) id)
                    :vector (when (= :sparse-dot metric)
                              (exposed-vector idx item))})))
         (sort-by (if (score-higher? index-opts)
                    (fn [{:keys [id score]}] [(- score) (pr-str id)])
                    (fn [{:keys [id score]}] [score (pr-str id)])))
         (take k)
         vec)))

(defn search
  "Returns nearest results best-first.

  For :cosine and :dot, :score is a similarity where higher is better. For
  :euclidean, :manhattan, :correlation, :canberra, and :bray-curtis, :score is
  a distance where lower is better.

  With opts, `:filter` can be a structured metadata filter (`:eq`, `:in`,
  `:range`, `:gt`, `:lt`, `:and`, `:or`, or `:not`) or a predicate over the
  result map. Structured equality and membership use an inverted metadata
  index. The index then scores only the matching vectors. Predicate filtering
  keeps the original candidate over-fetch behavior."
  ([idx query-vec k]
   (search idx query-vec k nil))
  ([idx query-vec k {:keys [filter] :as opts}]
   (when (and (= :exact (get-in idx [:opts :type])) (contains? opts :ef))
     (invalid-option! :exact :ef))
   (with-query-ef
     idx opts
     #(let [query (checked-vector idx query-vec)
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
                (recur (min item-count (* 2 n)))))))))))

(defn hybrid-search
  "Fuses dense vector and BM25 text retrieval into standard result maps.

  The default `:fusion` is reciprocal rank fusion (`:rrf`) with `:rrf-k` 60.
  `:fusion :weighted` min-max normalizes each score list and combines it with
  `:dense-weight` and `:sparse-weight`. Each weight defaults to 0.5."
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
                         (score-higher? (:opts idx)))))))

(defn remove!
  "Removes id from the index. Returns true when an item was removed."
  [idx id]
  (locking (:lock idx)
    (if-let [existing (optional-item (get-optional idx id))]
      (let [removed? (.remove ^Index (:index idx) id (.version ^Item existing))]
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
  (when-let [^Item item (optional-item (get-optional idx id))]
    (let [id (.id item)]
      {:id id
       :vector (if (= :sparse-dot (get-in idx [:opts :metric]))
                 (exposed-vector idx item)
                 (.vector item))
       :metadata (get @(:metadata idx) id)})))

(defn items
  "Returns all indexed items as stable wrapper maps, sorted by ID text."
  [idx]
  (->> (.items ^Index (:index idx))
       (map #(get-item idx (.id ^Item %)))
       (sort-by (comp pr-str :id))
       vec))

(defn as-exact-index
  "Returns an exhaustive index containing the current HNSW items.

  The returned handle is a snapshot: later mutations of either handle are
  independent. Calling this on an exact index returns that handle unchanged."
  [idx]
  (if (= :exact (get-in idx [:opts :type]))
    idx
    (let [^HnswIndex hnsw (:index idx)]
      {:index (.asExactIndex hnsw)
       :opts (-> (:opts idx)
                 (assoc :type :exact)
                 (dissoc :m :ef-construction :ef :capacity))
       :metadata (atom @(:metadata idx))
       :metadata-index (atom @(:metadata-index idx))
       :bm25 (atom @(:bm25 idx))
       :capacity (atom (.size ^Index (:index idx)))
       :lock (Object.)})))

(defn size
  "Returns the number of indexed items."
  ^long [idx]
  (.size ^Index (:index idx)))

(defn find-neighbors
  "Returns up to `k` nearest indexed items to `id`, excluding `id` itself.

  Returns nil when `id` is not indexed. Results use the same maps as `search`."
  [idx id k]
  (when (get-item idx id)
    (mapv #(result-map idx %)
          (.findNeighbors ^Index (:index idx) id (int k)))))


(defn- path-file
  ^File [path]
  (io/file path))

(defn- missing-index!
  [^File dir]
  (throw (ex-info "Vector search index files not found"
                  {:vector-search/error :index-not-found
                   :path (.getPath dir)})))

(defn- snapshot-mismatch!
  [^File dir]
  (throw (ex-info "Vector search index snapshot files do not match"
                  {:vector-search/error :snapshot-mismatch
                   :path (.getPath dir)})))

(defn- sha256
  [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (with-open [in (io/input-stream file)]
      (loop [read (.read in buffer)]
        (when-not (neg? read)
          (.update digest buffer 0 read)
          (recur (.read in buffer)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn- atomic-move!
  [^File from ^File to]
  (Files/move (.toPath from) (.toPath to)
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING])))

(defn save
  "Saves idx into path, a directory. Creates the directory when absent. Returns path."
  [idx path]
  (when (= :custom (get-in idx [:opts :metric]))
    (throw (ex-info "Indexes with custom distance functions cannot be persisted"
                    {:vector-search/error :custom-distance-not-persistable})))
  (let [dir (path-file path)
        index-file (io/file dir "index.bin")
        meta-file (io/file dir "meta.edn")]
    (.mkdirs dir)
    (let [index-temp (File/createTempFile "index-" ".tmp" dir)
          meta-temp (File/createTempFile "meta-" ".tmp" dir)]
      (try
        (locking (:lock idx)
          (with-open [out (FileOutputStream. ^File index-temp)]
            (.save ^Index (:index idx) out))
          (spit meta-temp
                (pr-str {:opts (:opts idx)
                         :capacity @(:capacity idx)
                         :metadata @(:metadata idx)
                         :metadata-index @(:metadata-index idx)
                         :bm25 @(:bm25 idx)
                         :index-sha256 (sha256 index-temp)})))
        (atomic-move! index-temp index-file)
        (atomic-move! meta-temp meta-file)
        (finally
          (.delete index-temp)
          (.delete meta-temp))))
    path))

(defn load-index
  "Loads an index handle from path, a directory containing index.bin and meta.edn.

  :type in meta.edn selects :hnsw or :exact. An older save without :type loads
  as :hnsw. :exact does an exhaustive exact search, O(n) per query, with no
  tuning knobs. Use it as ground truth, or for a small corpus."
  [path]
  (let [dir (path-file path)
        index-file (io/file dir "index.bin")
        meta-file (io/file dir "meta.edn")]
    (when-not (and (.isFile ^File index-file) (.isFile ^File meta-file))
      (missing-index! dir))
    (let [loader (.getClassLoader ^Class vitem-class)
          {:keys [opts capacity metadata metadata-index bm25 index-sha256]}
          (edn/read-string (slurp meta-file))
          _ (when (and index-sha256 (not= index-sha256 (sha256 index-file)))
              (snapshot-mismatch! dir))
          _custom-distance-check (when (= :custom (:metric opts))
                                  (throw (ex-info "Indexes with custom distance functions cannot be loaded"
                                                  {:vector-search/error :custom-distance-not-persistable})))
          type (get opts :type :hnsw)
          loaded (case type
                   :hnsw (HnswIndex/load ^File index-file ^ClassLoader loader)
                   :exact (BruteForceIndex/load ^File index-file ^ClassLoader loader)
                   (throw (ex-info "Unknown vector-search index type"
                                   {:vector-search/error :unknown-index-type
                                    :type type})))
          verified-index-sha256 (when index-sha256 (sha256 index-file))]
      (when (and index-sha256 (not= index-sha256 verified-index-sha256))
        (snapshot-mismatch! dir))
      {:index loaded
       :opts (assoc opts :type type)
       :metadata (atom metadata)
       :metadata-index (atom (or metadata-index
                                 (metadata-filter/from-metadata metadata)))
       :bm25 (atom (or bm25 (bm25/empty-index)))
       :capacity (atom capacity)
       :lock (Object.)})))
