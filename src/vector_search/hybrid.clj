(ns vector-search.hybrid)

(defn- ranks
  [results]
  (into {} (map-indexed (fn [rank {:keys [id]}] [id (inc rank)]) results)))

(defn rrf
  [dense sparse rrf-k]
  (let [dense-ranks (ranks dense)
        sparse-ranks (ranks sparse)
        ids (into (set (keys dense-ranks)) (keys sparse-ranks))]
    (into {}
          (map (fn [id]
                 [id (+ (if-let [rank (get dense-ranks id)]
                          (/ 1.0 (+ rrf-k rank))
                          0.0)
                        (if-let [rank (get sparse-ranks id)]
                          (/ 1.0 (+ rrf-k rank))
                          0.0))]))
          ids)))

(defn- normalize
  [results higher-is-better?]
  (if (empty? results)
    {}
    (let [scores (map (comp double :score) results)
          low (reduce min scores)
          high (reduce max scores)
          span (- high low)]
      (into {}
            (map (fn [{:keys [id score]}]
                   [id (if (zero? span)
                         1.0
                         (if higher-is-better?
                           (/ (- (double score) low) span)
                           (/ (- high (double score)) span)))])
                 results)))))

(defn weighted
  [dense sparse dense-higher? dense-weight sparse-weight]
  (let [dense-scores (normalize dense dense-higher?)
        sparse-scores (normalize sparse true)
        ids (into (set (keys dense-scores)) (keys sparse-scores))]
    (into {}
          (map (fn [id]
                 [id (+ (* dense-weight (get dense-scores id 0.0))
                        (* sparse-weight (get sparse-scores id 0.0)))]))
          ids)))

(defn fuse
  [dense sparse k {:keys [fusion rrf-k dense-weight sparse-weight dense-higher?]
                   :or {fusion :rrf
                        rrf-k 60
                        dense-weight 0.5
                        sparse-weight 0.5
                        dense-higher? true}}]
  (let [scores (case fusion
                 :rrf (rrf dense sparse rrf-k)
                 :weighted (weighted dense sparse dense-higher?
                                     dense-weight sparse-weight)
                 (throw (ex-info "Unknown hybrid fusion method"
                                 {:vector-search/error :unknown-fusion
                                  :fusion fusion})))
        results-by-id (into {} (map (juxt :id identity)) (concat sparse dense))]
    (->> scores
         (map (fn [[id score]]
                (assoc (get results-by-id id) :score score)))
         (sort-by (fn [{:keys [id score]}] [(- score) (pr-str id)]))
         (take k)
         vec)))
