# Changes

## 0.1.0 (unreleased)

Initial release.

- `vector-search.core`: embedded HNSW index over hnswlib-core -
  `index`, `add!` / `add-batch!` (replace-on-existing-id), `search` with
  `:cosine` / `:dot` / `:euclidean` metrics and similarity-oriented scores,
  `remove!`, `get-item`, `size`, automatic capacity growth.
- Metadata: arbitrary Clojure maps stored alongside each id, returned in
  search results.
- Persistence: `save` / `load-index` on a directory (hnswlib serialization +
  EDN sidecar); loaded indexes remain fully mutable.
- Seeded recall smoke test: recall@10 ~0.99 at default settings over 1000
  random vectors.
