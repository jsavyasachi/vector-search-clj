(ns vector-search.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [com.github.jelmerk.hnswlib.core DistanceFunction DistanceFunctions Item SearchResult]
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

(def ^:private default-opts
  {:metric :cosine
   :capacity 10000
   :m 16
   :ef-construction 200
   :ef 50})

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

(defn- build-index
  ^HnswIndex [{:keys [dim metric capacity m ef-construction ef]}]
  (-> (doto (HnswIndex/newBuilder (int dim) (metric-distance-fn metric) (int capacity))
        (.withM (int m))
        (.withEfConstruction (int ef-construction))
        (.withEf (int ef))
        (.withRemoveEnabled))
      (.build)))

(defn index
  "Creates an embedded HNSW vector index handle.

  :ef trades recall for speed during search; higher values improve recall."
  [opts]
  (when-not (:dim opts)
    (throw (ex-info "Missing vector dimension"
                    {:vector-search/error :missing-dim})))
  (let [merged (merge default-opts opts)]
    {:index (build-index merged)
     :opts merged
     :metadata (atom {})
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
  (.get ^HnswIndex (:index idx) id))

(defn- optional-item
  ^VItem [^Optional optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- grow-if-full!
  [idx]
  (let [^HnswIndex hnsw (:index idx)
        capacity-ref (:capacity idx)
        capacity (long @capacity-ref)]
    (when (>= (.size hnsw) capacity)
      (let [new-capacity (* 2 capacity)]
        (.resize hnsw (int new-capacity))
        (reset! capacity-ref new-capacity)))))

(defn add!
  "Adds or replaces id with vector v. Metadata is stored outside hnswlib."
  ([idx id v]
   (add! idx id v nil))
  ([idx id v metadata]
   (let [^floats vector (checked-vector idx v)
         item (VItem. id vector)]
     (locking (:lock idx)
       (let [existing (optional-item (get-optional idx id))
             ^HnswIndex hnsw (:index idx)]
         (when existing
           (.remove hnsw id (.version ^VItem existing)))
         (grow-if-full! idx)
         (.add hnsw item)
         (if (nil? metadata)
           (swap! (:metadata idx) dissoc id)
           (swap! (:metadata idx) assoc id metadata))))
     id)))

(defn add-batch!
  "Adds each {:id .. :vector .. :metadata ..} item and returns the count added."
  [idx items]
  (reduce (fn [n {:keys [id vector metadata] :as item}]
            (if (contains? item :metadata)
              (add! idx id vector metadata)
              (add! idx id vector))
            (inc n))
          0
          items))

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

(defn search
  "Returns nearest results best-first.

  For :cosine and :dot, :score is a similarity where higher is better. For
  :euclidean, :score is L2 distance where lower is better."
  [idx query-vec k]
  (let [^floats query (checked-vector idx query-vec)
        item-count (.size ^HnswIndex (:index idx))
        candidate-count (min (long k) item-count)]
    (if (zero? candidate-count)
      []
      (mapv #(result-map idx %)
            (.findNearest ^HnswIndex (:index idx) query (int candidate-count))))))

(defn remove!
  "Removes id from the index. Returns true when an item was removed."
  [idx id]
  (locking (:lock idx)
    (if-let [existing (optional-item (get-optional idx id))]
      (let [removed? (.remove ^HnswIndex (:index idx) id (.version ^VItem existing))]
        (when removed?
          (swap! (:metadata idx) dissoc id))
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
  (.size ^HnswIndex (:index idx)))

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
      (.save ^HnswIndex (:index idx) out))
    (spit meta-file
          (pr-str {:opts (:opts idx)
                   :capacity @(:capacity idx)
                   :metadata @(:metadata idx)}))
    path))

(defn load-index
  "Loads an index handle from path, a directory containing index.bin and meta.edn."
  [path]
  (let [dir (path-file path)
        index-file (io/file dir "index.bin")
        meta-file (io/file dir "meta.edn")]
    (when-not (and (.isFile ^File index-file) (.isFile ^File meta-file))
      (missing-index! dir))
    (let [loader (.getClassLoader ^Class vitem-class)
          hnsw (HnswIndex/load ^File index-file ^ClassLoader loader)
          {:keys [opts capacity metadata]} (edn/read-string (slurp meta-file))]
      {:index hnsw
       :opts opts
       :metadata (atom metadata)
       :capacity (atom capacity)
       :lock (Object.)})))
