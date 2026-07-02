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
net.clojars.savya/vector-search-clj {:mvn/version "0.1.0"}
```

Leiningen:

```clojure
[net.clojars.savya/vector-search-clj "0.1.0"]
```

Pure JVM - no native dependencies, no server.

## Usage

```clojure
(require '[vector-search.core :as vs])

(def idx (vs/index {:dim 384 :metric :cosine}))

(vs/add! idx "chunk-1" vec-1 {:source "report.pdf" :page 3})
(vs/add! idx "chunk-2" vec-2 {:source "report.pdf" :page 7})
(vs/add-batch! idx [{:id "chunk-3" :vector vec-3 :metadata {:source "notes.md"}}])

(vs/search idx query-vec 10)
;; => [{:id "chunk-2" :score 0.87 :metadata {:source "report.pdf" :page 7}} ...]

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
| `:metric` | `:cosine` | `:cosine`, `:dot`, or `:euclidean` |
| `:capacity` | `10000` | initial max items; grows automatically when full |
| `:m` | `16` | HNSW graph degree |
| `:ef-construction` | `200` | build-time search breadth |
| `:ef` | `50` | query-time search breadth; higher = better recall, slower |

Semantics worth knowing:

- **Scores**: for `:cosine` and `:dot`, `:score` is a similarity (higher is
  better; cosine of an exact match ≈ 1.0). For `:euclidean` it is the L2
  distance (lower is better). Results are always ordered best-first.
- **Vectors**: `float[]` (zero-copy) or any sequential of numbers.
- **Ids**: any EDN-round-trippable, `Serializable` value (strings, keywords,
  numbers, ...).
- **`add!` with an existing id replaces** the stored vector and metadata.
- **HNSW is approximate**: recall is tuned by `:ef` (the seeded test suite
  holds recall@10 ≈ 0.99 on defaults).

Errors are `ex-info` maps keyed `:vector-search/error`
(`:missing-dim`, `:unknown-metric`, `:dim-mismatch`, `:invalid-vector`,
`:index-not-found`).

## Running tests

```bash
clojure -M:test
```

Everything is deterministic and self-contained (the recall smoke test uses a
seeded RNG); there is nothing to download.

## License

Copyright © 2026 Savyasachi.

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
