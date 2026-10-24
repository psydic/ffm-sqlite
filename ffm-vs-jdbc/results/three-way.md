| Category | Benchmarks | ffm-sqlite vs sqlite-jdbc (geo-mean) | ffm-sqlite-jdbc vs sqlite-jdbc (geo-mean) | Wrapper cost vs ffm-sqlite (geo-mean) |
|---|---:|---:|---:|---:|
| Application workloads (file DB in WAL mode, on tmpfs) | 9 | 1.61× | 1.82× | -11.3% |
| Concurrent reads (own connection per thread; throughput) | 3 | 1.62× | 1.50× | +7.8% |
| Per data type, 1,000 rows | 22 | 2.59× | 2.55× | +1.5% |

### Application workloads (file DB in WAL mode, on tmpfs)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc | Unit | ffm-sqlite vs jdbc | wrapper vs jdbc | wrapper cost vs ffm-sqlite |
|---|---|---:|---:|---:|---|---:|---:|---:|
| businessTransaction |  | 18.5 ± 3.77 | 13.8 ± 4.06 | 12.6 ± 0.79 | us/op | 1.34× | 1.46× | -9% |
| connectQueryClose |  | 66.2 ± 20.7 | 46.2 ± 14.2 | 43.4 ± 5.46 | us/op | 1.43× | 1.53× | -6% |
| insertAutoCommit |  | 5.10 ± 0.73 | 4.18 ± 0.78 | 4.76 ± 1.28 | us/op | 1.22× | 1.07× | +14% |
| joinGroupBy50Users |  | 178 ± 63.8 | 116 ± 68.2 | 74.2 ± 7.08 | us/op | 1.53× | 2.40× | -36% |
| pointSelectById |  | 4.68 ± 1.28 | 3.38 ± 0.61 | 2.61 ± 0.56 | us/op | 1.39× | 1.79× | -23% |
| pointSelectByIndexedEmail |  | 7.23 ± 1.80 | 4.10 ± 1.20 | 4.79 ± 0.81 | us/op | 1.76× | 1.51× | +17% |
| prepareInsideLoop |  | 10.4 ± 1.04 | 3.14 ± 0.27 | 2.96 ± 0.33 | us/op | 3.31× | 3.52× | -6% |
| rangeScan100Rows |  | 107 ± 25.0 | 50.8 ± 17.0 | 39.7 ± 4.30 | us/op | 2.10× | 2.69× | -22% |
| updateAutoCommit |  | 8.53 ± 3.43 | 7.11 ± 2.23 | 5.81 ± 0.81 | us/op | 1.20× | 1.47× | -18% |

### Concurrent reads (own connection per thread; throughput)

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc | Unit | ffm-sqlite vs jdbc | wrapper vs jdbc | wrapper cost vs ffm-sqlite |
|---|---|---:|---:|---:|---|---:|---:|---:|
| pointSelect_1thread |  | 273 ± 19.3 | 518 ± 43.6 | 478 ± 33.7 | ops/ms | 1.90× | 1.75× | +8% |
| pointSelect_4threads |  | 833 ± 125 | 1,447 ± 203 | 1,112 ± 194 | ops/ms | 1.74× | 1.34× | +30% |
| pointSelect_8threads |  | 785 ± 137 | 1,006 ± 84.0 | 1,131 ± 143 | ops/ms | 1.28× | 1.44× | -11% |

### Per data type, 1,000 rows

| Benchmark | Params | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc | Unit | ffm-sqlite vs jdbc | wrapper vs jdbc | wrapper cost vs ffm-sqlite |
|---|---|---:|---:|---:|---|---:|---:|---:|
| read | type=INTEGER | 130 ± 45.3 | 53.1 ± 11.9 | 64.3 ± 1.96 | ns/op | 2.45× | 2.03× | +21% |
| read | type=REAL | 154 ± 26.1 | 56.7 ± 7.26 | 65.8 ± 4.28 | ns/op | 2.72× | 2.34× | +16% |
| read | type=TEXT | 264 ± 15.1 | 89.1 ± 8.97 | 106 ± 22.3 | ns/op | 2.96× | 2.49× | +19% |
| read | type=BLOB | 176 ± 8.93 | 86.5 ± 8.37 | 96.1 ± 18.3 | ns/op | 2.03× | 1.83× | +11% |
| read | type=BOOLEAN | 112 ± 21.2 | 75.2 ± 15.2 | 67.1 ± 4.75 | ns/op | 1.50× | 1.68× | -11% |
| read | type=NULL | 164 ± 4.88 | 90.5 ± 20.6 | 93.9 ± 16.1 | ns/op | 1.81× | 1.74× | +4% |
| read | type=BIGDECIMAL | 336 ± 36.9 | 122 ± 7.42 | 144 ± 31.8 | ns/op | 2.75× | 2.33× | +18% |
| read | type=LOCALDATE | 3,016 ± 691 | 87.0 ± 2.47 | 152 ± 7.82 | ns/op | 34.66× | 19.79× | +75% |
| read | type=LOCALDATETIME | 3,422 ± 262 | 107 ± 9.31 | 184 ± 15.8 | ns/op | 32.08× | 18.57× | +73% |
| read | type=INSTANT | 171 ± 39.0 | 109 ± 3.57 | 71.2 ± 2.99 | ns/op | 1.57× | 2.40× | -35% |
| read | type=UUID | 313 ± 49.8 | 101 ± 6.26 | 102 ± 9.31 | ns/op | 3.10× | 3.07× | +1% |
| write | type=INTEGER | 429 ± 15.1 | 244 ± 70.4 | 235 ± 32.5 | ns/op | 1.76× | 1.82× | -3% |
| write | type=REAL | 523 ± 201 | 242 ± 35.5 | 238 ± 31.1 | ns/op | 2.16× | 2.20× | -2% |
| write | type=TEXT | 628 ± 178 | 299 ± 62.3 | 269 ± 12.1 | ns/op | 2.10× | 2.33× | -10% |
| write | type=BLOB | 586 ± 167 | 316 ± 54.8 | 259 ± 24.8 | ns/op | 1.85× | 2.27× | -18% |
| write | type=BOOLEAN | 453 ± 65.0 | 222 ± 25.4 | 222 ± 24.6 | ns/op | 2.04× | 2.04× | -0% |
| write | type=NULL | 423 ± 29.6 | 232 ± 88.0 | 218 ± 17.6 | ns/op | 1.82× | 1.94× | -6% |
| write | type=BIGDECIMAL | 559 ± 96.5 | 350 ± 71.8 | 318 ± 28.5 | ns/op | 1.60× | 1.76× | -9% |
| write | type=LOCALDATE | 587 ± 37.8 | 352 ± 86.5 | 353 ± 73.6 | ns/op | 1.67× | 1.66× | +0% |
| write | type=LOCALDATETIME | 617 ± 32.7 | 355 ± 99.8 | 311 ± 16.8 | ns/op | 1.74× | 1.98× | -12% |
| write | type=INSTANT | 554 ± 24.5 | 339 ± 49.3 | 229 ± 14.7 | ns/op | 1.64× | 2.42× | -32% |
| write | type=UUID | 545 ± 32.7 | 290 ± 69.2 | 285 ± 22.3 | ns/op | 1.88× | 1.91× | -2% |
