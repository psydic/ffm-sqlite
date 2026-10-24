| Category | Benchmarks | Geo-mean speedup | Range | ffm wins / ties / jdbc wins |
|---|---:|---:|---|---|
| Application workloads (file DB, WAL) | 9 | 0.84× | 0.09× – 3.90× | 5 / 4 / 0 |
| Concurrent reads (own connection per thread) | 3 | 1.74× | 1.55× – 1.88× | 3 / 0 / 0 |
| Bulk rows: 1,000 x (int, text, int) | 3 | 2.36× | 1.66× – 3.78× | 3 / 0 / 0 |
| Per data type, 1,000 rows | 22 | 2.93× | 1.53× – 34.33× | 22 / 0 / 0 |
| Large values | 6 | 1.27× | 0.74× – 3.07× | 2 / 3 / 1 |
| Schema, PRAGMAs, functions, backup, serialize | 19 | 1.34× | 0.89× – 3.56× | 8 / 11 / 0 |
| User-defined functions and collations | 10 | 2.80× | 1.16× – 4.66× | 10 / 0 / 0 |
| Hooks | 2 | 2.30× | 1.93× – 2.74× | 2 / 0 / 0 |
| Cold start (fresh JVM) | 1 | 1.00× | 1.00× – 1.00× | 0 / 1 / 0 |
| **All** | **75** | **1.83×** | 0.09× – 34.33× | |

### Application workloads (file DB, WAL)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| businessTransaction |  | 53.2 ± 57.3 | 194 ± 740 | us/op | 0.27× | tie | 1,184 / 330 |
| connectQueryClose |  | 65.4 ± 10.9 | 44.7 ± 8.25 | us/op | 1.46× | **ffm** | 7,958 / 1,641 |
| insertAutoCommit |  | 12.3 ± 1.27 | 54.5 ± 180 | us/op | 0.23× | tie | 112 / 58.8 |
| joinGroupBy50Users |  | 99.2 ± 7.09 | 71.4 ± 6.72 | us/op | 1.39× | **ffm** | 9,313 / 4,434 |
| pointSelectById |  | 4.27 ± 0.89 | 2.37 ± 0.27 | us/op | 1.80× | **ffm** | 968 / 232 |
| pointSelectByIndexedEmail |  | 5.17 ± 0.53 | 3.78 ± 0.95 | us/op | 1.37× | tie | 592 / 232 |
| prepareInsideLoop |  | 10.4 ± 2.50 | 2.66 ± 0.19 | us/op | 3.90× | **ffm** | 1,800 / 472 |
| rangeScan100Rows |  | 83.7 ± 16.5 | 43.1 ± 6.03 | us/op | 1.94× | **ffm** | 15,277 / 7,169 |
| updateAutoCommit |  | 15.6 ± 1.15 | 181 ± 327 | us/op | 0.09× | tie | 32.1 / 1.18 |

### Concurrent reads (own connection per thread)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| pointSelect_1thread |  | 235 ± 28.2 | 421 ± 73.3 | ops/ms | 1.79× | **ffm** | 976 / 232 |
| pointSelect_4threads |  | 715 ± 91.6 | 1,347 ± 107 | ops/ms | 1.88× | **ffm** | 976 / 232 |
| pointSelect_8threads |  | 875 ± 65.5 | 1,355 ± 220 | ops/ms | 1.55× | **ffm** | 976 / 232 |

### Bulk rows: 1,000 x (int, text, int)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| read_ |  | 422 ± 134 | 112 ± 4.71 | ns/op | 3.78× | **ffm** | 136 / 72.0 |
| write_Batch |  | 551 ± 41.9 | 332 ± 22.1 | ns/op | 1.66× | **ffm** | 68.4 / 196 |
| write_ExecuteUpdate |  | 639 ± 40.7 | 306 ± 21.8 | ns/op | 2.09× | **ffm** | 56.1 / 24.1 |

### Per data type, 1,000 rows

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| read | type=INTEGER | 123 ± 22.0 | 60.1 ± 10.3 | ns/op | 2.05× | **ffm** | 0.18 / 0.04 |
| read | type=REAL | 166 ± 20.0 | 63.8 ± 8.61 | ns/op | 2.61× | **ffm** | 0.18 / 0.04 |
| read | type=TEXT | 291 ± 28.6 | 95.2 ± 23.5 | ns/op | 3.06× | **ffm** | 152 / 88.0 |
| read | type=BLOB | 216 ± 24.4 | 88.3 ± 12.5 | ns/op | 2.45× | **ffm** | 32.2 / 32.0 |
| read | type=BOOLEAN | 112 ± 9.13 | 50.9 ± 2.61 | ns/op | 2.19× | **ffm** | 0.18 / 0.04 |
| read | type=NULL | 196 ± 24.4 | 87.2 ± 22.6 | ns/op | 2.25× | **ffm** | 0.18 / 0.04 |
| read | type=BIGDECIMAL | 395 ± 49.8 | 142 ± 36.8 | ns/op | 2.79× | **ffm** | 262 / 198 |
| read | type=LOCALDATE | 3,154 ± 475 | 96.5 ± 16.3 | ns/op | 32.68× | **ffm** | 2,688 / 56.0 |
| read | type=LOCALDATETIME | 3,803 ± 322 | 111 ± 12.9 | ns/op | 34.33× | **ffm** | 3,696 / 160 |
| read | type=INSTANT | 177 ± 19.5 | 115 ± 9.00 | ns/op | 1.53× | **ffm** | 24.2 / 112 |
| read | type=UUID | 332 ± 40.0 | 101 ± 11.3 | ns/op | 3.28× | **ffm** | 232 / 168 |
| write | type=INTEGER | 524 ± 153 | 206 ± 15.1 | ns/op | 2.54× | **ffm** | 16.1 / 0.08 |
| write | type=REAL | 600 ± 136 | 215 ± 11.2 | ns/op | 2.79× | **ffm** | 24.1 / 0.08 |
| write | type=TEXT | 584 ± 67.8 | 247 ± 17.9 | ns/op | 2.36× | **ffm** | 32.1 / 32.1 |
| write | type=BLOB | 543 ± 76.7 | 266 ± 61.4 | ns/op | 2.04× | **ffm** | 0.13 / 32.1 |
| write | type=BOOLEAN | 500 ± 123 | 225 ± 31.9 | ns/op | 2.22× | **ffm** | 16.1 / 0.08 |
| write | type=NULL | 423 ± 30.4 | 226 ± 56.5 | ns/op | 1.87× | **ffm** | 0.13 / 0.08 |
| write | type=BIGDECIMAL | 595 ± 97.1 | 359 ± 33.3 | ns/op | 1.66× | **ffm** | 39.3 / 158 |
| write | type=LOCALDATE | 744 ± 143 | 285 ± 30.4 | ns/op | 2.61× | **ffm** | 120 / 32.1 |
| write | type=LOCALDATETIME | 740 ± 144 | 314 ± 24.4 | ns/op | 2.36× | **ffm** | 172 / 40.1 |
| write | type=INSTANT | 653 ± 103 | 321 ± 28.8 | ns/op | 2.03× | **ffm** | 600 / 40.1 |
| write | type=UUID | 552 ± 48.7 | 279 ± 39.3 | ns/op | 1.98× | **ffm** | 136 / 112 |

### Large values

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| read | size=1024 | 0.96 ± 0.24 | 0.31 ± 0.02 | us/op | 3.07× | **ffm** | 1,224 / 1,080 |
| read | size=65536 | 9.10 ± 0.95 | 8.91 ± 1.13 | us/op | 1.02× | tie | 65,752 / 65,592 |
| read | size=1048576 | 172 ± 21.6 | 166 ± 17.4 | us/op | 1.03× | tie | 1,048,793 / 1,048,633 |
| write | size=1024 | 1.57 ± 0.33 | 0.76 ± 0.06 | us/op | 2.07× | **ffm** | 0.01 / 1,040 |
| write | size=65536 | 10.9 ± 1.69 | 14.7 ± 1.83 | us/op | 0.74× | jdbc | 16.1 / 65,552 |
| write | size=1048576 | 279 ± 27.8 | 331 ± 37.3 | us/op | 0.84× | tie | 18.0 / 1,048,594 |

### Schema, PRAGMAs, functions, backup, serialize

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| db_backupToFile |  | 4,272 ± 251 | 4,817 ± 1,880 | us/op | 0.89× | tie | 1,039 / 2,421 |
| db_create |  | 3,541 ± 84.9 | 3,655 ± 1,064 | us/op | 0.97× | tie | 7,826 / 2,433 |
| db_deserialize |  | 32.5 ± 2.81 | 31.0 ± 5.60 | us/op | 1.05× | tie | 0.24 / 0.61 |
| db_openWithPragmas |  | 113 ± 15.9 | 90.1 ± 10.2 | us/op | 1.25× | tie | 8,742 / 1,861 |
| db_restoreFromFile |  | 219 ± 35.8 | 200 ± 23.9 | us/op | 1.10× | tie | 602 / 1,336 |
| db_serialize |  | 83.1 ± 6.00 | 83.3 ± 7.80 | us/op | 1.00× | tie | 614,417 / 614,514 |
| function_aggregate |  | 1,390 ± 213 | 1,423 ± 264 | us/op | 0.98× | tie | 41,820 / 40,940 |
| function_json |  | 758 ± 87.5 | 492 ± 105 | us/op | 1.54× | **ffm** | 150,881 / 86,698 |
| function_scalar1000Rows |  | 2,005 ± 462 | 1,009 ± 92.1 | us/op | 1.99× | **ffm** | 575,311 / 318,850 |
| index_createDrop |  | 2,729 ± 280 | 2,331 ± 243 | us/op | 1.17× | tie | 1,344 / 1,192 |
| index_createDropPartialExpression |  | 3,254 ± 317 | 3,096 ± 799 | us/op | 1.05× | tie | 1,376 / 1,294 |
| index_createDropUniqueComposite |  | 3,230 ± 308 | 3,188 ± 524 | us/op | 1.01× | tie | 1,360 / 1,296 |
| pragma_read |  | 1.61 ± 0.18 | 0.75 ± 0.10 | us/op | 2.15× | **ffm** | 448 / 360 |
| pragma_schemaListing |  | 14.5 ± 4.91 | 6.87 ± 1.75 | us/op | 2.10× | **ffm** | 3,048 / 1,368 |
| pragma_tableInfo |  | 9.91 ± 2.44 | 2.79 ± 0.38 | us/op | 3.56× | **ffm** | 5,120 / 2,088 |
| pragma_tableInfoFunction |  | 9.39 ± 1.48 | 5.31 ± 0.89 | us/op | 1.77× | **ffm** | 1,728 / 864 |
| pragma_write |  | 0.92 ± 0.27 | 0.78 ± 0.15 | us/op | 1.18× | tie | 600 / 224 |
| table_alterAddColumn |  | 44.3 ± 2.44 | 35.0 ± 3.80 | us/op | 1.27× | **ffm** | 1,808 / 681 |
| table_createDrop |  | 33.9 ± 3.25 | 26.4 ± 2.50 | us/op | 1.28× | **ffm** | 1,296 / 545 |

### User-defined functions and collations

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| aggregate |  | 231 ± 21.5 | 50.3 ± 5.30 | ns/op | 4.60× | **ffm** | 0.05 / 24.0 |
| builtin |  | 36.4 ± 1.76 | 26.6 ± 4.64 | ns/op | 1.37× | **ffm** | 0.04 / 0.03 |
| collationMax |  | 320 ± 16.3 | 80.7 ± 6.24 | ns/op | 3.96× | **ffm** | 103 / 143 |
| collationMaxBuiltin |  | 37.9 ± 2.67 | 32.7 ± 1.94 | ns/op | 1.16× | **ffm** | 0.05 / 0.03 |
| collationOrderBy |  | 2,844 ± 264 | 633 ± 40.2 | ns/op | 4.49× | **ffm** | 973 / 1,851 |
| scalarLong |  | 238 ± 13.5 | 51.2 ± 3.60 | ns/op | 4.66× | **ffm** | 0.05 / 63.7 |
| scalarPerRowResult |  | 362 ± 20.5 | 78.0 ± 5.61 | ns/op | 4.64× | **ffm** | 0.05 / 63.7 |
| scalarText |  | 519 ± 33.9 | 126 ± 5.71 | ns/op | 4.11× | **ffm** | 237 / 285 |
| windowSliding |  | 1,290 ± 295 | 659 ± 74.3 | ns/op | 1.96× | **ffm** | 0.07 / 256 |
| windowSlidingBuiltin |  | 638 ± 70.6 | 487 ± 13.9 | ns/op | 1.31× | **ffm** | 0.06 / 0.06 |

### Hooks

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| insertRows | hook=none | 692 ± 120 | 359 ± 53.9 | ns/op | 1.93× | **ffm** | 24.1 / 24.1 |
| insertRows | hook=updateListener | 1,461 ± 207 | 534 ± 69.1 | ns/op | 2.74× | **ffm** | 576 / 432 |

### Cold start (fresh JVM)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | Unit | Speedup | Result | Alloc jdbc / ffm (B/op) |
|---|---|---:|---:|---|---:|:---:|---:|
| firstQuery |  | 152 ± 14.9 | 151 ± 16.8 | ms/op | 1.00× | tie | – |

sqlite-jdbc with its default configuration (`jdbc.get_generated_keys=true`), for reference:

- SQLiteBenchmark.write_Batch (jdbc default: generated keys on): 550 ± 31.6 ns/op, 68.8 B/op
- SQLiteBenchmark.write_ExecuteUpdate (jdbc default: generated keys on): 2,496 ± 139 ns/op, 873 B/op
