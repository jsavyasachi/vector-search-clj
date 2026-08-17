# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [0.4.1] - 2026-08-17

### Fixed

- Index snapshots are written atomically: both files are staged to temporary
  files and atomically renamed, and a SHA-256 of `index.bin` is recorded in the
  sidecar and verified on load, so a load can no longer pair mismatched files.
- `search` honors `:ef` for HNSW indexes instead of silently ignoring it.

## [0.4.0] - 2026-07-16
### Added
- BM25 sparse retrieval.
- Hybrid dense+sparse search with RRF and weighted fusion.
- Indexed metadata filter DSL with `eq`, `in`, `range`, `and`, `or`, and `not` operators.

## [0.3.1] - 2026-07-09
### Fixed
- POM now includes the project description, homepage URL, and full SCM connection metadata, so Clojars shows a description/homepage and cljdoc has complete source-link data.
