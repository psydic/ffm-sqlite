# Results: ffm-sqlite vs rusqlite

### Application workloads (WAL, synchronous=NORMAL, tmpfs)

| Benchmark | ffm-sqlite (µs/op) | rusqlite (µs/op) | Result |
|---|---:|---:|---|
| pointSelectById | 2.28 ± 0.33 | 2.13 ± 0.14 | tie |
| pointSelectByIndexedEmail | 3.24 ± 0.20 | 3.20 ± 0.14 | tie |
| insertAutoCommit | 4.16 ± 0.36 | 3.79 ± 0.16 | tie |
| updateAutoCommit | 5.84 ± 0.79 | 5.38 ± 0.37 | tie |
| businessTransaction | 13.4 ± 0.4 | 13.5 ± 1.0 | tie |
| rangeScan100Rows | 36.7 ± 2.1 | 40.6 ± 2.7 | tie |
| joinGroupBy50Users | 76.4 ± 6.4 | 77.1 ± 6.7 | tie |
| prepareInsideLoop | 2.49 ± 0.23 | 4.24 ± 0.26 | **ffm-sqlite 1.70×** |
| prepareInsideLoop (rusqlite `prepare_cached`) | 2.49 ± 0.23 | 2.22 ± 0.19 | tie |
| connectQueryClose | 42.5 ± 1.4 | 39.6 ± 1.4 | **rusqlite 1.07×** |

`prepareInsideLoop`: ffm-sqlite caches every prepared statement automatically; rusqlite's `prepare` parses every time, its opt-in `prepare_cached` uses an LRU cache.

### Concurrent point selects, one connection per thread

| Benchmark | ffm-sqlite (ops/ms total) | rusqlite (ops/ms total) | Result |
|---|---:|---:|---|
| 1 thread | 533 ± 25 | 539 ± 28 | tie |
| 4 threads | 1,720 ± 67 | 1,837 ± 60 | tie |
| 8 threads | 1,648 ± 44 | 1,588 ± 193 | tie |

### Per data type, 1,000 rows (in-memory)

| Benchmark | ffm-sqlite (ns/row) | rusqlite (ns/row) | Result |
|---|---:|---:|---|
| read INTEGER | 49.6 ± 3.1 | 73.3 ± 18.7 | **ffm-sqlite 1.48×** |
| read REAL | 56.6 ± 16.7 | 78.1 ± 24.8 | tie |
| read TEXT | 85.2 ± 12.6 | 115 ± 19 | tie |
| read BLOB | 79.3 ± 3.5 | 103 ± 31 | tie |
| write INTEGER | 207 ± 21 | 210 ± 55 | tie |
| write REAL | 212 ± 22 | 225 ± 57 | tie |
| write TEXT | 248 ± 41 | 282 ± 70 | tie |
| write BLOB | 224 ± 10 | 276 ± 41 | **ffm-sqlite 1.23×** |

### User-defined functions and collations (10,000 rows)

| Benchmark | ffm-sqlite (ns/call) | rusqlite (ns/call) | Result |
|---|---:|---:|---|
| Built-in expression (baseline) | 26.2 ± 2.1 | 29.3 ± 2.3 | tie |
| Scalar function, integer | 73.9 ± 11.0 | 45.3 ± 5.6 | **rusqlite 1.63×** |
| Scalar function, text | 199 ± 36 | 109 ± 9 | **rusqlite 1.83×** |
| Aggregate function | 46.3 ± 2.3 | 27.8 ± 2.6 | **rusqlite 1.66×** |
| Window function | 749 ± 64 | 550 ± 33 | **rusqlite 1.36×** |
| Built-in window (baseline) | 592 ± 43 | 566 ± 75 | tie |
| Collation, max() | 88.5 ± 6.2 | 66.0 ± 4.2 | **rusqlite 1.34×** |
| Built-in NOCASE (baseline) | 43.9 ± 23.9 | 37.5 ± 3.2 | tie |
| Collation, ORDER BY (per row) | 787 ± 88 | 518 ± 42 | **rusqlite 1.52×** |
| Scalar result per row | 123 ± 7 | 87.9 ± 11.7 | **rusqlite 1.40×** |

### Bulk load, 1M rows × 20 columns (WAL + FULL, tmpfs)

| Benchmark | ffm-sqlite (s) | rusqlite (s) | Result |
|---|---:|---:|---|
| Load | 6.26 ± 0.55 | 5.32 ± 0.79 | tie |

### 1M rows: 1 writer + 4 readers (WAL + FULL, disk)

| Benchmark | ffm-sqlite (ops/s) | rusqlite (ops/s) | Result |
|---|---:|---:|---|
| point reads, 4 readers | 688,446 ± 62,537 | 747,309 ± 29,096 | tie |
| point: writer commits | 774 ± 102 | 764 ± 96 | tie |
| range reads, 4 readers | 48,804 ± 14,366 | 38,714 ± 8,825 | tie |
| range: writer commits | 549 ± 165 | 552 ± 118 | tie |
