(ns vector-search.filter
  (:require [clojure.set :as set]))

(defn empty-index
  []
  {:ids #{}
   :terms {}})

(defn add-item
  [index id metadata]
  (reduce-kv (fn [state key value]
               (update-in state [:terms key value] (fnil conj #{}) id))
             (update index :ids conj id)
             (or metadata {})))

(defn remove-item
  [index id metadata]
  (reduce-kv
   (fn [state key value]
     (let [remaining (disj (get-in state [:terms key value] #{}) id)]
       (if (seq remaining)
         (assoc-in state [:terms key value] remaining)
         (let [without-value (update-in state [:terms key] dissoc value)]
           (if (seq (get-in without-value [:terms key]))
             without-value
             (update without-value :terms dissoc key))))))
   (update index :ids disj id)
   (or metadata {})))

(defn from-metadata
  [metadata]
  (reduce-kv add-item (empty-index) metadata))

(defn- indexed-ids
  [index key values]
  (reduce (fn [ids value]
            (into ids (get-in index [:terms key value] #{})))
          #{}
          values))

(defn- comparable-match?
  [metadata id key pred]
  (let [item-metadata (get metadata id)]
    (and (contains? item-metadata key)
         (try
           (pred (get item-metadata key))
           (catch ClassCastException _ false)
           (catch NullPointerException _ false)))))

(defn- operator-priority
  [filter]
  (cond
    (or (contains? filter :eq) (contains? filter :in)) 0
    (contains? filter :and) 1
    :else 2))

(declare matching-ids*)

(defn- matching-ids*
  [index metadata filter candidates]
  (cond
    (contains? filter :eq)
    (let [[key value] (:eq filter)]
      (set/intersection candidates (get-in index [:terms key value] #{})))

    (contains? filter :in)
    (let [[key values] (:in filter)]
      (set/intersection candidates (indexed-ids index key values)))

    (contains? filter :range)
    (let [[key low high] (:range filter)]
      (into #{} (clojure.core/filter
                 #(comparable-match? metadata % key
                                     (fn [value]
                                       (and (<= (compare low value) 0)
                                            (<= (compare value high) 0)))))
            candidates))

    (contains? filter :gt)
    (let [[key bound] (:gt filter)]
      (into #{} (clojure.core/filter
                 #(comparable-match? metadata % key
                                     (fn [value] (pos? (compare value bound)))))
            candidates))

    (contains? filter :lt)
    (let [[key bound] (:lt filter)]
      (into #{} (clojure.core/filter
                 #(comparable-match? metadata % key
                                     (fn [value] (neg? (compare value bound)))))
            candidates))

    (contains? filter :and)
    (reduce (fn [ids clause]
              (matching-ids* index metadata clause ids))
            candidates
            (sort-by operator-priority (:and filter)))

    (contains? filter :or)
    (reduce (fn [ids clause]
              (into ids (matching-ids* index metadata clause candidates)))
            #{}
            (:or filter))

    (contains? filter :not)
    (set/difference candidates
                    (matching-ids* index metadata (:not filter) candidates))

    :else
    (throw (ex-info "Unknown metadata filter"
                    {:vector-search/error :invalid-filter
                     :filter filter}))))

(defn matching-ids
  [index metadata filter]
  (matching-ids* index metadata filter (:ids index)))
