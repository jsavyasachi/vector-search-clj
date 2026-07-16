(ns vector-search.bm25
  (:require [clojure.string :as str]))

(def default-k1 1.2)
(def default-b 0.75)

(defn empty-index
  []
  {:docs {}
   :postings {}
   :total-length 0})

(defn tokenize
  "Lowercases text and splits it on one or more non-alphanumeric characters."
  [text]
  (if (nil? text)
    []
    (->> (str/split (str/lower-case (str text)) #"[^\p{L}\p{N}]+")
         (remove str/blank?)
         vec)))

(defn remove-doc
  [state id]
  (if-let [{:keys [length terms]} (get-in state [:docs id])]
    (-> (reduce-kv (fn [s term _]
                     (let [remaining (dissoc (get-in s [:postings term]) id)]
                       (if (empty? remaining)
                         (update s :postings dissoc term)
                         (assoc-in s [:postings term] remaining))))
                   state
                   terms)
        (update :docs dissoc id)
        (update :total-length - length))
    state))

(defn add-doc
  [state id text]
  (let [tokens (tokenize text)
        terms (frequencies tokens)
        state (remove-doc state id)]
    (-> (reduce-kv (fn [s term frequency]
                     (assoc-in s [:postings term id] frequency))
                   state
                   terms)
        (assoc-in [:docs id] {:length (count tokens) :terms terms})
        (update :total-length + (count tokens)))))

(defn- inverse-document-frequency
  ^double [doc-count doc-frequency]
  (Math/log (+ 1.0 (/ (+ (- doc-count doc-frequency) 0.5)
                        (+ doc-frequency 0.5)))))

(defn search
  [state query k {:keys [k1 b ids]
                  :or {k1 default-k1 b default-b}}]
  (let [doc-count (count (:docs state))
        avg-length (if (pos? doc-count)
                     (/ (double (:total-length state)) doc-count)
                     0.0)
        terms (distinct (tokenize query))
        scores (reduce
                (fn [acc term]
                  (let [posting (get-in state [:postings term] {})
                        idf (inverse-document-frequency doc-count (count posting))]
                    (reduce-kv
                     (fn [doc-scores id frequency]
                       (if (and (some? ids) (not (contains? ids id)))
                         doc-scores
                         (let [doc-length (get-in state [:docs id :length])
                               length-ratio (if (pos? avg-length)
                                              (/ doc-length avg-length)
                                              0.0)
                               denominator (+ frequency
                                              (* k1 (+ (- 1.0 b)
                                                       (* b length-ratio))))
                               score (* idf (/ (* frequency (+ k1 1.0))
                                               denominator))]
                           (update doc-scores id (fnil + 0.0) score))))
                     acc
                     posting)))
                {}
                terms)]
    (->> scores
         (sort-by (fn [[id score]] [(- score) (pr-str id)]))
         (take k)
         (mapv (fn [[id score]] {:id id :score score})))))
