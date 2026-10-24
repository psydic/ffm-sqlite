# ffm-sqlite

SQLite for Java 25+ through the **Foreign Function & Memory API**: no JNI, no bundled native code.

| Module | Artifact | What it is |
|---|---|---|
| [`ffm-sqlite`](ffm-sqlite) | `io.github.psydic:ffm-sqlite` | the library: a JDBC-like API (`Connection`, `PreparedStatement`, `ResultSet`, …) plus UDFs, window functions, collations, hooks, backup, serialize, extensions, metadata |
| [`ffm-sqlite-jdbc`](ffm-sqlite-jdbc) | `io.github.psydic:ffm-sqlite-jdbc` | a `java.sql` driver on top of it (`jdbc:ffmsqlite:`), drop-in for sqlite-jdbc in most applications; tested with HikariCP, Spring JDBC and jOOQ |
| [`ffm-sqlite-parent`](ffm-sqlite-parent) | `io.github.psydic:ffm-sqlite-parent` | build and release configuration ([RELEASING.md](ffm-sqlite-parent/RELEASING.md)) |
| [`ffm-vs-jdbc`](ffm-vs-jdbc) | not published | JMH benchmarks against sqlite-jdbc: [PERFORMANCE.md](ffm-vs-jdbc/PERFORMANCE.md), [INTERFACE-COMPARISON.md](ffm-vs-jdbc/INTERFACE-COMPARISON.md) |

```xml
<dependency>
    <groupId>io.github.psydic</groupId>
    <artifactId>ffm-sqlite-jdbc</artifactId>   <!-- or ffm-sqlite for the library API -->
    <version>0.1.0</version>
</dependency>
```

### From GitHub Packages

Releases are published to [GitHub Packages](https://github.com/psydic/ffm-sqlite/packages). GitHub requires
authentication even for public Maven packages: create a personal access token (classic) with the
`read:packages` scope, then add the repository and credentials to `~/.m2/settings.xml`:

```xml
<settings>
  <profiles>
    <profile>
      <id>github-psydic</id>
      <repositories>
        <repository>
          <id>github-psydic</id>
          <url>https://maven.pkg.github.com/psydic/ffm-sqlite</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>github-psydic</activeProfile>
  </activeProfiles>
  <servers>
    <server>
      <id>github-psydic</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN_WITH_read:packages</password>
    </server>
  </servers>
</settings>
```

At run time you need JDK 25+, `--enable-native-access=ALL-UNNAMED`, and SQLite installed (`libsqlite3`), or
`-Dffmsqlite.lib=/path/to/libsqlite3.so`.

## Build

```bash
cd ffm-sqlite-parent && mvn install     # both modules, 163 tests
```

## License

Apache License 2.0, see [LICENSE](LICENSE).
