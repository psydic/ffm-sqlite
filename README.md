# ffm-sqlite

SQLite for Java 25+ through the **Foreign Function & Memory API**: no JNI, no bundled native code.

| Module | Artifact | What it is |
|---|---|---|
| [`ffm-sqlite`](ffm-sqlite) | `io.github.revsnai:ffm-sqlite` | the library: a JDBC-like API (`Connection`, `PreparedStatement`, `ResultSet`, …) plus UDFs, window functions, collations, hooks, backup, serialize, extensions, metadata |
| [`ffm-sqlite-jdbc`](ffm-sqlite-jdbc) | `io.github.revsnai:ffm-sqlite-jdbc` | a `java.sql` driver on top of it (`jdbc:ffmsqlite:`), drop-in for sqlite-jdbc in most applications; tested with HikariCP, Spring JDBC and jOOQ |
| [`micronaut-starter-ffm-sqlite`](micronaut-starter-ffm-sqlite) | `io.github.revsnai:micronaut-starter-ffm-sqlite` | Micronaut integration: pooled `DataSource` beans from `application.yml` (Micronaut Data JDBC, `@Transactional`), ffm-sqlite API connections, a health indicator |
| [`ffm-sqlite-parent`](ffm-sqlite-parent) | `io.github.revsnai:ffm-sqlite-parent` | build and release configuration ([RELEASING.md](ffm-sqlite-parent/RELEASING.md)) |
| [`ffm-sqlite-shop`](ffm-sqlite-shop) | not published | a sample e-commerce backend on ffm-sqlite-jdbc, written as a training guide for integrating the driver: pools, transactions, migrations, search, checkout, error handling |
| [`ffm-vs-jdbc`](ffm-vs-jdbc) | not published | JMH benchmarks against sqlite-jdbc: [PERFORMANCE.md](ffm-vs-jdbc/PERFORMANCE.md), [INTERFACE-COMPARISON.md](ffm-vs-jdbc/INTERFACE-COMPARISON.md) |

```xml
<dependency>
    <groupId>io.github.revsnai</groupId>
    <artifactId>ffm-sqlite-jdbc</artifactId>   <!-- or ffm-sqlite for the library API -->
    <version>0.2.5</version>
</dependency>
```

### From GitHub Packages

Releases are published to [GitHub Packages](https://github.com/revsnai/ffm-sqlite/packages). GitHub requires
authentication even for public Maven packages: create a personal access token (classic) with the
`read:packages` scope, then add the repository and credentials to `~/.m2/settings.xml`:

```xml
<settings>
  <profiles>
    <profile>
      <id>github-revsnai</id>
      <repositories>
        <repository>
          <id>github-revsnai</id>
          <url>https://maven.pkg.github.com/revsnai/ffm-sqlite</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>github-revsnai</activeProfile>
  </activeProfiles>
  <servers>
    <server>
      <id>github-revsnai</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN_WITH_read:packages</password>
    </server>
  </servers>
</settings>
```

At run time you need JDK 25+, `--enable-native-access=ALL-UNNAMED`, and SQLite installed (`libsqlite3`), or
`-Dffmsqlite.lib=/path/to/libsqlite3.so`.

## Performance

1M rows × 20 columns (mixed-width integers, REAL, texts of 8–128 bytes, dates, UUID, decimal, two BLOBs;
~600 bytes/row, 704 MB file), WAL + `synchronous=FULL` on disk. Threads each have their own connection; one
writer inserts rows in autocommit while N readers run. JMH, 2 forks, pinned to 14 cores. Both drivers run the
same SQLite engine build: 3.50.3 compiled by gcc 4.1.2 with sqlite-jdbc's own flags (identical
`PRAGMA compile_options`).

| Readers | Read workload | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc |
|---|---|---|---|---|
| 4 | row by id, all 20 columns (reads/s) | 199k | **505k (2.5×)** | 487k (2.5×) |
| 8 | row by id, all 20 columns (reads/s) | 281k | **701k (2.5×)** | 671k (2.4×) |
| 4 | 100-row range scan (scans/s) | 18.4k | **37.1k (2.0×)** | 32.5k (1.8×) |
| 8 | 100-row range scan (scans/s) | 25.3k | **62.5k (2.5×)** | 44.3k (1.8×) |

The writer is fsync-bound (~400–800 commits/s) with no significant difference between libraries. Loading the
1M rows is fsync-bound on disk; with I/O removed (tmpfs) ffm-sqlite needs 8.95 s against sqlite-jdbc's 11.0 s.
Full conditions, the 1-reader results, the earlier run on a gcc 13 build and other workloads are in
[PERFORMANCE.md](ffm-vs-jdbc/PERFORMANCE.md#1m-rows--20-columns-bulk-load-then-1-writer--n-readers-wal--synchronousfull).

## Build

```bash
cd ffm-sqlite-parent && mvn install     # both modules, 163 tests
```

## License

Apache License 2.0, see [LICENSE](LICENSE).
