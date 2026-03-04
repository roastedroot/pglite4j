# PGLite4j Error Recovery Plan

## The Issue

Any SQL error (e.g. `SELECT * FROM nonexistent_table`) kills the WASM instance permanently.

**Root cause chain:**
1. PostgreSQL hits an error → `ereport(ERROR)` → `errfinish()` → `PG_RE_THROW()`
2. In normal PG, `PG_RE_THROW()` does `siglongjmp()` back to the error handler
3. In WASI build, `siglongjmp` is not available, so the elog.c patch replaces it with `abort()`
4. `abort()` → `proc_exit(134)` → `__builtin_unreachable()` → WASM TrapException
5. The Chicory WASM instance is dead after a trap — no further queries can run

**Immediate symptom:** Flyway sends `SELECT rolname FROM pg_roles WHERE rolname ILIKE 'rds_superuser'` (Amazon RDS detection). The `pg_roles` view doesn't exist in pglite's catalog, causing an ERROR that traps the instance. All subsequent JDBC connections fail with "This connection has been closed".

## The Fix — Imported Error Handler + Error Flag Check

Instead of `abort()` → `proc_exit()` → trap, we:

1. **C side (elog.c patch):** Replace `abort()` with a call to a custom WASM-imported function `pgl_on_error()` followed by `__builtin_unreachable()`:
   - `pgl_on_error()` is provided by Java at instance creation (same pattern as sqlite4j callbacks)
   - The imported function sets an error flag on the Java side and returns
   - `__builtin_unreachable()` still causes a trap, but `proc_exit` is never called

2. **Java side (PGLite.java):**
   - Provide the `pgl_on_error` import function (following the sqlite4j `WasmDBImports` pattern)
   - In `execProtocolRaw()`, catch RuntimeException around `interactiveOne()`
   - When caught AND errorFlag is set:
     - Call `exports.clearError()` — this does `EmitErrorReport()`, `AbortCurrentTransaction()`, `FlushErrorState()`, and sets `send_ready_for_query = true`
     - Call `exports.interactiveWrite(-1)` — signals error state via `cma_rsize < 0`
     - Call `exports.interactiveOne()` — enters `resume_on_error` → `wire_flush` path, sends `ReadyForQuery`, flushes output to CMA
     - Collect response (ErrorResponse + ReadyForQuery)
     - Reset error flag
   - The instance survives and is reusable for subsequent queries

### Key C code reference

`clear_error()` in `interactive_one.c:228-280` does the full PostgreSQL error cleanup.
`resume_on_error:` at `interactive_one.c:649` does wire flush + ReadyForQuery.

## Steps

### Step 1 — Write a failing test

Write a Java test in the pglite4j `core` module that:
- Creates a PGLite instance
- Sends a valid query (e.g. `SELECT 1`) — should succeed
- Sends an invalid query (e.g. `SELECT * FROM pg_roles`) — currently traps/crashes
- Sends another valid query (e.g. `SELECT 2`) — should succeed if recovery works
- Asserts the instance is still usable after the error

This test captures the exact failure mode and will pass once the fix is in place.

### Step 2 — C side: replace `abort()` with imported error handler

In `wasm-build/patches/postgresql-pglite/src-backend-utils-error-elog.c.diff`:
- Declare `extern void pgl_on_error(void);` (WASM import)
- Replace `abort();` with `pgl_on_error(); __builtin_unreachable();`
- Rebuild the WASM binary

### Step 3 — Java side: provide the import and recover from errors

In `PGLite.java`:
- Add `volatile boolean errorFlag` field
- Add `pgl_on_error` as a HostFunction import (sets errorFlag = true, returns)
- Follow the sqlite4j pattern: build imports with both `wasi.toHostFunctions()` and the custom import
- In `execProtocolRaw()`: catch trap, check flag, call `clearError()` + `interactiveWrite(-1)` + `interactiveOne()`, collect response

### Step 4 — Verify Flyway quickstarts

Re-run the 3 blocked Quarkus quickstart tests:
- quartz-quickstart
- hibernate-orm-multi-tenancy-schema-quickstart
- hibernate-orm-multi-tenancy-database-quickstart
