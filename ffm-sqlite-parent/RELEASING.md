# Releasing to Maven Central

Published artifacts (all `io.github.psydic`, Apache-2.0):

| Artifact | Contents |
|---|---|
| `ffm-sqlite-parent` | the parent POM (metadata, plugin versions) |
| `ffm-sqlite` | the FFM library, package `io.github.psydic.ffmsqlite.db`, module `io.github.psydic.ffmsqlite.db` |
| `ffm-sqlite-jdbc` | the `java.sql` driver, package `io.github.psydic.ffmsqlite.jdbc`, module `io.github.psydic.ffmsqlite.jdbc`; depends on `ffm-sqlite` |

`ffm-vs-jdbc` (the benchmarks) is not published.

The build is ready: `mvn -P release verify -Dgpg.skip` produces the main, `-sources` and `-javadoc` jars for both
modules. All 163 tests pass, each jar carries `META-INF/LICENSE` and an `Automatic-Module-Name`, and the enforcer
plugin requires JDK 25. What remains needs your accounts and keys.

## One-time setup

### 1. Check the groupId and repository URLs

`io.github.psydic` requires that you own the GitHub account **`psydic`** (github.com/psydic). To use another namespace, change the groupId,
URL, SCM and issue URLs in `ffm-sqlite-parent/pom.xml`, and the `<parent>` groupId in both module POMs, for
example to `io.github.<your-username>`. Also check the developer name and email.

### 2. Publish the source on GitHub

Central requires a public source repository matching the `<scm>` in the POM (`github.com/psydic/ffm-sqlite`). Include
`ffm-sqlite-parent/`, `ffm-sqlite/` and `ffm-sqlite-jdbc/`, and optionally `ffm-vs-jdbc/`. Do not include the
unrelated `ggogle/`, `goo` or `SqliteBechmark.java` from the local folder. Optionally move the parent POM to the
repository root (then change the modules to `ffm-sqlite`/`ffm-sqlite-jdbc`, and the children's `<relativePath>`
to `../pom.xml`).

### 3. Central Portal account and namespace

1. Sign in at <https://central.sonatype.com> (GitHub login is simplest).
2. **Namespaces → Add** `io.github.psydic`. With a GitHub login it is usually verified automatically; otherwise create
   the temporary public repository it asks for.
3. **Account → Generate User Token**, and put it in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

### 4. A GPG signing key

Central rejects unsigned artifacts and verifies signatures against public key servers.

```bash
gpg --full-generate-key                         # RSA 4096 (or ed25519), your name and email
gpg --list-secret-keys --keyid-format=long      # note the key id after "sec   rsa4096/"
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>   # then confirm via the email it sends
```

Back up the key and its revocation certificate (`~/.gnupg/openpgp-revocs.d/`). Losing the key means future
releases must use a new one.

## Each release

```bash
cd ffm-sqlite-parent
mvn versions:set -DnewVersion=0.1.0 -DprocessAllModules -DgenerateBackupPoms=false   # drop -SNAPSHOT everywhere
mvn clean verify                                 # the full test suite
mvn -P release deploy                            # sign, upload, wait for Central's validation
```

`autoPublish` is `false`, so the upload stops at **Validated** in the Portal (<https://central.sonatype.com/publishing>).
Check the files, then press **Publish**. Artifacts appear on Central within about 30 minutes, and in search later.
**A published version can never be changed or deleted.** If something is wrong, release a new version.

Then:

```bash
git commit -am "Release 0.1.0" && git tag v0.1.0 && git push --tags
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules -DgenerateBackupPoms=false
```

To publish without the manual Portal step, set `<autoPublish>true</autoPublish>` in the parent's release profile.
For CI releases, export the key with `gpg --armor --export-secret-keys`, store it with its passphrase and the
Portal token as secrets, and pass `-Dgpg.passphrase=…` (or use `gpg-agent`).

## What users add

```xml
<dependency>
    <groupId>io.github.psydic</groupId>
    <artifactId>ffm-sqlite-jdbc</artifactId>   <!-- the JDBC driver; or ffm-sqlite for the library API alone -->
    <version>0.1.0</version>
</dependency>
```

At run time users need JDK 25+, `--enable-native-access=ALL-UNNAMED` (module path:
`--enable-native-access=io.github.psydic.ffmsqlite.db`), and SQLite installed (`libsqlite3`, e.g. `apt install libsqlite3-0`), or
`-Dffmsqlite.lib=/path/to/libsqlite3.so`.
