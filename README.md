# pglite4j

Embedded PostgreSQL in plain Java bytecode. No containers, no native binaries, no external processes: just add a dependency and use a JDBC URL.

> **⚠️ Warning**
> This project is highly experimental and in a very early stage of development. APIs may change, features are incomplete, and it is not yet recommended for production use. Feedback and contributions are very welcome!

## How it works

`pglite4j` bundles a full PostgreSQL 17 instance compiled to WebAssembly (WASI) and runs it directly inside the JVM via [Chicory](https://github.com/dylibso/chicory) (a pure-Java WebAssembly runtime). The JDBC driver opens an internal loopback socket and transparently bridges TCP to the WASM module's CMA (Contiguous Memory Allocator) shared memory, no network traffic ever leaves the process.

The build pipeline that produces the WASM binary runs inside Docker and chains several tools:

1. **wasi-sdk** — cross-compiles PostgreSQL + [PGlite](https://github.com/electric-sql/postgres-pglite) patches to a WASI target
2. **wasmtime** — runs `initdb` to create the database cluster
3. **Wizer** — snapshots the fully initialized PostgreSQL state (post-initdb, post-backend-start) so runtime startup skips all of that
4. **wasi-vfs** — embeds the read-only PostgreSQL distribution (`share/`, `lib/`) directly into the WASM binary
5. **wasm-opt** — optimizes the final binary for size

At build-time the Chicory compiler translates the WASM module to JVM bytecode. The JDBC driver (`PgLiteDriver`) opens a `ServerSocket` on a random loopback port, delegates to [pgjdbc](https://jdbc.postgresql.org/), and acts as a transparent byte shuttle between the TCP socket and PostgreSQL's wire protocol on the WASM memory.

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
   CMA shared memory  <──>  PostgreSQL (in WASM linear memory)
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

### Quarkus

```properties
# application.properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:pglite:memory://
quarkus.datasource.jdbc.driver=io.roastedroot.pglite4j.jdbc.PgLiteDriver
quarkus.datasource.username=postgres
quarkus.datasource.password=password
quarkus.datasource.jdbc.min-size=1
quarkus.datasource.jdbc.max-size=5
quarkus.devservices.enabled=false
quarkus.hibernate-orm.dialect=org.hibernate.dialect.PostgreSQLDialect
quarkus.hibernate-orm.unsupported-properties."hibernate.boot.allow_jdbc_metadata_access"=false
```

### Spring Boot

```properties
# application-test.properties
spring.datasource.url=jdbc:pglite:memory://
spring.datasource.driver-class-name=io.roastedroot.pglite4j.jdbc.PgLiteDriver
spring.datasource.username=postgres
spring.datasource.password=password
spring.datasource.hikari.maximum-pool-size=5
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false
```

### HikariCP

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:pglite:memory://");
config.setMaximumPoolSize(5);
DataSource ds = new HikariDataSource(config);
```

## Project structure

```
pglite4j/
  core/          Core module — WASM lifecycle, CMA transport, wire protocol bridge
  jdbc/          JDBC driver — PgLiteDriver, ServiceLoader registration, socket bridge
  it/            Integration tests (Quarkus pet-clinic app with Hibernate + Panache)
  wasm-build/    Dockerized build pipeline for the PostgreSQL WASM binary
```

## Status and known limitations

- [x] ~~**Only `memory://` is supported**~~ — persistent / file-backed databases are not planned; the WASM backend uses an in-memory virtual filesystem (ZeroFS) with no disk I/O, which is fundamental to the architecture
- [x] ~~**Single connection only**~~ — multiple JDBC connections are now supported per database instance; requests are serialized through a single PGLite backend via a lock, so connection pools with `max-size > 1` work correctly (queries execute one at a time, not in parallel)
- [x] ~~**Error recovery**~~ — both simple and extended query protocol errors are handled correctly; PostgreSQL errors trap the WASM instance and are caught by the Java side, which resets the backend state and drains stale protocol buffers so subsequent queries work cleanly
- [ ] **No connection isolation** — PostgreSQL runs in single-user mode with one session; all connections share the same session state (transactions, session variables). Queries are serialized, so there is no data corruption, but concurrent transactions are not isolated from each other. This is fine for connection pools that use connections sequentially (borrow, use, return).
- [ ] **Server-side prepared statements disabled** — because all connections share a single PostgreSQL backend, named prepared statements (`S_1`, `S_2`, …) would collide across connections. The driver sets `prepareThreshold=0` so pgjdbc always uses the unnamed prepared statement. This has no functional impact but means PostgreSQL cannot cache query plans across executions.
- [ ] **Limited extensions** — only `plpgsql` and `dict_snowball` are bundled; adding more requires rebuilding the WASM binary
- [ ] **Startup time** — first connection has some overhead that can be optimized further
- [ ] **Binary size** — the WASM binary + pgdata resources add several MBs to the classpath

### CMA (Contiguous Memory Allocator)

CMA is a preallocated contiguous region at the start of WASM linear memory used for zero-copy data transfer between Java and the PostgreSQL backend (similar concept to [Linux CMA](https://developer.toradex.com/software/linux-resources/linux-features/contiguous-memory-allocator-cma-linux/)). Messages that fit within the CMA buffer (default 12 MB) are transferred directly via shared memory. For responses that exceed the CMA buffer, the C code automatically falls back to file-based transport (`/pgdata/.s.PGSQL.5432.out`), which the Java side reads transparently.

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
- **[PGLite](https://github.com/electric-sql/postgres-pglite)** - The Postgres build already mostly patched for Wasm
- **[PGLite-build](https://github.com/electric-sql/pglite-build)** - Spearheaded the build for the WASI target
- **[Chicory](https://github.com/dylibso/chicory)** - Pure Java WebAssembly runtime that makes this possible
