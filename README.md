# vector-search-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/vector-search-clj.svg)](https://clojars.org/net.clojars.savya/vector-search-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/vector-search-clj)](https://cljdoc.org/d/net.clojars.savya/vector-search-clj/CURRENT)
[![test](https://github.com/jsavyasachi/vector-search-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/vector-search-clj/actions/workflows/test.yml)

Embedded approximate-nearest-neighbor vector search for Clojure: an in-process
HNSW index with metadata and save/load, over [hnswlib](https://github.com/jelmerk/hnswlib).

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=fff" alt="Clojure" /></a>
<a href="https://github.com/jelmerk/hnswlib"><img src="https://img.shields.io/badge/hnswlib-2D3748?style=flat" alt="hnswlib" /></a>

## Installation

deps.edn:

```clojure
net.clojars.savya/vector-search-clj {:mvn/version "0.4.0"}
```

Leiningen:

```clojure
[net.clojars.savya/vector-search-clj "0.4.0"]
```

Pure JVM - no native dependencies, no server.

## Usage

```clojure
(require '[vector-search.core :as vs])

(def idx (vs/index {:dim 384 :metric :cosine}))

(vs/add! idx "chunk-1" vec-1 {:source "report.pdf" :page 3}
         "Quarterly revenue for product ZX-81")
(vs/add! idx "chunk-2" vec-2 {:source "report.pdf" :page 7})
(vs/add-batch! idx [{:id "chunk-3" :vector vec-3
                     :metadata {:source "notes.md"}
                     :text "ZX-81 launch notes"}])

(vs/search idx query-vec 10)
;; => [{:id "chunk-2" :score 0.87 :metadata {:source "report.pdf" :page 7}} ...]

(vs/bm25-search idx "ZX-81 revenue" 10)
;; => [{:id "chunk-1" :score 1.31 :metadata {:source "report.pdf" :page 3}} ...]

(vs/hybrid-search idx query-vec "ZX-81 revenue" 10)
;; Reciprocal Rank Fusion by default; :score is the fused score.

(vs/hybrid-search idx query-vec "ZX-81 revenue" 10
                  {:fusion :weighted
                   :dense-weight 0.4
                   :sparse-weight 0.6})

(vs/get-item idx "chunk-1")   ;; => {:id .. :vector float[] :metadata ..}
(vs/remove! idx "chunk-1")    ;; => true
(vs/size idx)                 ;; => 2

;; persistence: a directory with hnswlib's index.bin + an EDN sidecar
(vs/save idx "data/my-index")
(def idx2 (vs/load-index "data/my-index"))
```

Options to `index` (defaults shown):

| option | default | meaning |
|---|---|---|
| `:dim` | required | vector dimensionality |
| `:type` | `:hnsw` | `:hnsw` (approximate) or `:exact` (exhaustive brute force) |
| `:metric` | `:cosine` | `:cosine`, `:dot`, or `:euclidean` |
| `:capacity` | `10000` | initial max items; grows automatically when full (`:hnsw` only) |
| `:m` | `16` | HNSW graph degree |
| `:ef-construction` | `200` | build-time search breadth |
| `:ef` | `50` | query-time search breadth; higher = better recall, slower |

`:exact` builds a brute-force index: exhaustive exact search, O(n) per query,
no capacity or tuning knobs (passing `:m`, `:ef-construction`, or `:ef` with
`:exact` throws `:invalid-option`). Useful as ground truth for recall testing
or for small corpora. The rest of the API - including `:filter`, metadata,
and `save`/`load-index` - behaves identically; `meta.edn` records the index
type, and legacy saves load as `:hnsw`.

```clojure
(def exact (vs/index {:dim 384 :type :exact}))
```

Filtered search accepts a structured metadata filter:

```clojure
(vs/search idx query 5 {:filter {:eq [:kind :report]}})
(vs/search idx query 5 {:filter {:in [:status #{:draft :published}]}})
(vs/search idx query 5 {:filter {:range [:page 3 10]}}) ; inclusive
(vs/search idx query 5
           {:filter {:and [{:eq [:kind :report]}
                           {:not {:range [:page 1 2]}}]}})
```

The DSL operators are `{:eq [key value]}`, `{:in [key values]}`,
`{:range [key low high]}` (inclusive), `{:gt [key bound]}`,
`{:lt [key bound]}`, `{:and [filters...]}`, `{:or [filters...]}`, and
`{:not filter}`. Keys address top-level metadata fields. Equality and
membership use an inverted metadata index. Boolean expressions apply their
range comparisons only to the candidates surviving indexed clauses, and the
resolved IDs are scored directly instead of over-fetching the ANN index.
`hybrid-search` accepts the same `:filter` option.

The original arbitrary predicate form remains supported:

```clojure
(vs/search idx query 5 {:filter #(= :report (get-in % [:metadata :kind]))})
```

Predicate filtering over-fetches candidates and doubles the candidate set (up
to the whole index) until `k` matches are found. Use the structured DSL for
indexed filtering.

Semantics worth knowing:

- **Scores**: for `:cosine` and `:dot`, `:score` is a similarity (higher is
  better; cosine of an exact match ≈ 1.0). For `:euclidean` it is the L2
  distance (lower is better). Results are always ordered best-first.
- **Vectors**: `float[]` (zero-copy) or any sequential of numbers.
- **Ids**: any EDN-round-trippable, `Serializable` value (strings, keywords,
  numbers, ...).
- **BM25 text**: optional fifth argument to `add!`, or `:text` in an
  `add-batch!` item. Tokenization lowercases and splits on non-alphanumeric
  characters. `bm25-search` accepts optional `:k1` and `:b` values, defaulting
  to `1.2` and `0.75`.
- **Hybrid retrieval**: `hybrid-search` fuses dense and BM25 candidates with
  Reciprocal Rank Fusion by default (`:rrf-k` defaults to `60`). Set `:fusion`
  to `:weighted` for min-max normalized score fusion; `:dense-weight` and
  `:sparse-weight` each default to `0.5`. `:candidate-count` controls each
  retrieval list's depth and defaults to four times the requested result count.
- **`add!` with an existing id replaces** the stored vector and metadata.
- **HNSW is approximate**: recall is tuned by `:ef` (the seeded test suite
  holds recall@10 ≈ 0.99 on defaults, measured against an `:exact` index as
  ground truth).

Errors are `ex-info` maps keyed `:vector-search/error`
(`:missing-dim`, `:unknown-metric`, `:unknown-index-type`, `:invalid-option`,
`:dim-mismatch`, `:invalid-vector`, `:index-not-found`).

## Running tests

```bash
clojure -M:test
```

Everything is deterministic and self-contained (the recall smoke test uses a
seeded RNG); there is nothing to download.

## License

Copyright © 2026 Savyasachi.

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
