# pglite4j

Embedded PostgreSQL in plain Java bytecode. No containers, no native binaries, no external processes: just add a dependency and use a JDBC URL.

> **⚠️ Warning**
> PGLite is a patched version of PostgreSQL, which was not designed for embedding. Do not use it in production! This driver is intended for testing and demo applications only.

## How it works

`pglite4j` bundles a full PostgreSQL 17 instance compiled to WebAssembly (WASI) and runs it directly inside the JVM via [Chicory](https://github.com/dylibso/chicory) (a pure-Java WebAssembly runtime). The JDBC driver opens an internal loopback socket and transparently bridges TCP to PostgreSQL's wire protocol running in WASM linear memory — no network traffic ever leaves the process.

```
DriverManager.getConnection("jdbc:pglite:memory://")
        |
        v
PgLiteDriver ──> boots WASM instance (pre-initialized via Wizer)
        |
        v
ServerSocket(127.0.0.1:<random-port>)
        |           ^
        |           |  raw PG wire protocol bytes
        v           |
   WASM shared memory  <──>  PostgreSQL (in WASM linear memory)
        |
        v
pgjdbc connects to localhost:<port>  ──>  returns java.sql.Connection
```

## Quick start

### Maven

Add the JDBC driver dependency:

```xml
<dependency>
  <groupId>io.roastedroot</groupId>
  <artifactId>pglite4j-jdbc</artifactId>
  <version>latest</version>
</dependency>
```

### Plain JDBC

```java
Connection conn = DriverManager.getConnection("jdbc:pglite:memory://");
conn.createStatement().execute("CREATE TABLE demo (id serial PRIMARY KEY, name text)");
conn.createStatement().execute("INSERT INTO demo (name) VALUES ('hello')");
```

### Persistent storage

Point the JDBC URL to a file path and `pglite4j` will periodically snapshot the in-memory database to a zip file on disk. On the next JVM startup, the database is restored from that snapshot.

> **Note:** This is **not** traditional disk-backed storage. PostgreSQL runs entirely in memory. The driver takes periodic snapshots (backup/restore), similar to Redis RDB persistence. Data written between the last snapshot and a crash will be lost.

```java
// File-backed — data survives JVM restarts
Connection conn = DriverManager.getConnection("jdbc:pglite:/var/data/mydb.zip");
```

The driver backs up the database on a fixed schedule (default: every 60 seconds) and writes a final snapshot on shutdown. You can configure the backup interval via a connection property:

```java
Properties props = new Properties();
props.setProperty("pgliteBackupIntervalSeconds", "30");
Connection conn = DriverManager.getConnection("jdbc:pglite:/var/data/mydb.zip", props);
```

You can also use named in-memory databases for test isolation (separate PG instances, no persistence):

```java
Connection db1 = DriverManager.getConnection("jdbc:pglite:memory:testA");
Connection db2 = DriverManager.getConnection("jdbc:pglite:memory:testB");
```

### Quarkus

```properties
# application.properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:pglite:memory://
# or persistent: jdbc:pglite:/var/data/myapp.zip
quarkus.datasource.jdbc.driver=io.roastedroot.pglite4j.jdbc.PgLiteDriver
quarkus.devservices.enabled=false
```

### Spring Boot

```properties
# application.properties
spring.datasource.url=jdbc:pglite:memory://
# or persistent: jdbc:pglite:/var/data/myapp.zip
spring.datasource.driver-class-name=io.roastedroot.pglite4j.jdbc.PgLiteDriver
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

### HikariCP

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:pglite:memory://");
config.setMaximumPoolSize(5);
DataSource ds = new HikariDataSource(config);
```

## Known limitations

- **No connection isolation** — PostgreSQL runs in single-user mode; all connections share the same session state. Queries are serialized via a lock, so there is no data corruption, but concurrent transactions are not isolated from each other.
- **Server-side prepared statements disabled** — all connections share a single backend, so named prepared statements would collide. The driver sets `prepareThreshold=0` to always use unnamed statements. This has no functional impact.
- **Limited extensions** — only `plpgsql` and `dict_snowball` are bundled; adding more requires rebuilding the WASM binary.
- **Binary size** — the WASM binary and pgdata resources add ~10 MB to the classpath.
- **High memory usage** — each PGLite instance runs a full PostgreSQL backend in WASM linear memory. Expect significant heap consumption (1 GB+); make sure to size `-Xmx` accordingly.

If any of these are limiting your use of the library, please [file an issue](https://github.com/roastedroot/pglite4j/issues) to discuss.

## Project structure

```
pglite4j/
  core/          Core module — WASM lifecycle, wire protocol bridge
  jdbc/          JDBC driver — PgLiteDriver, socket bridge, ServiceLoader registration
  it/            Integration tests — Quarkus and Spring Boot sample apps
  wasm-build/    Dockerized build pipeline for the PostgreSQL WASM binary
```

## Building from source

```bash
# 1. Build the WASM binary (requires Docker)
cd wasm-build
make build

# 2. Unpack into core module resources
make unpack

# 3. Build the Java modules
cd ..
mvn install
```

## Acknowledgements

Special thanks to:
- **[PGLite](https://github.com/electric-sql/postgres-pglite)** — the PostgreSQL build patched for Wasm
- **[PGLite-build](https://github.com/electric-sql/pglite-build)** — spearheaded the WASI build target
- **[Chicory](https://github.com/dylibso/chicory)** — pure-Java WebAssembly runtime that makes this possible
