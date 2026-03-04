package io.roastedroot.pglite4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PGLiteTest {

    @Test
    public void selectOne() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(result);
            assertTrue(result.length > 0);

            String data = PgWireCodec.parseDataRows(result);
            System.out.println("SELECT 1 => " + data);
            assertTrue(data.contains("1"));
        }
    }

    @Test
    public void handshake() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // After handshake, queries should work
            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 42 AS answer;"));
            assertNotNull(result);
            String data = PgWireCodec.parseDataRows(result);
            System.out.println("After handshake: SELECT 42 => " + data);
            assertTrue(data.contains("42"));
        }
    }

    @Test
    public void createTableAndInsert() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // DDL via simple query protocol
            byte[] r1 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("CREATE TABLE test (id INTEGER, name TEXT);"));
            System.out.println("CREATE TABLE: " + r1.length + " bytes");

            // SERIAL column
            byte[] r2 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage(
                                    "CREATE TABLE test_serial (id SERIAL PRIMARY KEY, val TEXT);"));
            System.out.println("CREATE TABLE SERIAL: " + r2.length + " bytes");

            // INSERT
            byte[] r3 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("INSERT INTO test VALUES (1, 'hello');"));
            System.out.println("INSERT: " + r3.length + " bytes");

            // SELECT
            byte[] r4 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT * FROM test;"));
            String data = PgWireCodec.parseDataRows(r4);
            System.out.println("SELECT: " + data);
            assertTrue(data.contains("hello"));
        }
    }

    @Test
    public void errorRecovery() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // 1. Valid query should succeed
            byte[] r1 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(r1);
            String data1 = PgWireCodec.parseDataRows(r1);
            System.out.println("Before error: SELECT 1 => " + data1);
            assertTrue(data1.contains("1"));

            // 2. Invalid query — references a non-existent table.
            //    Without error recovery this traps the WASM instance permanently.
            byte[] r2 =
                    pg.execProtocolRaw(
                            PgWireCodec.queryMessage("SELECT * FROM nonexistent_table_xyz;"));
            assertNotNull(r2);
            System.out.println("Error response length: " + r2.length + " bytes");
            // The response should contain an ErrorResponse (tag 'E')
            assertTrue(r2.length > 0, "Expected non-empty error response");

            // 3. Valid query should still work after the error
            byte[] r3 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 2;"));
            assertNotNull(r3);
            String data3 = PgWireCodec.parseDataRows(r3);
            System.out.println("After error: SELECT 2 => " + data3);
            assertTrue(data3.contains("2"), "Instance should be reusable after SQL error");
        }
    }

    @Test
    public void cmaBufferOverflow() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            int bufSize = pg.getBufferSize();
            System.out.println(
                    "CMA buffer size: " + bufSize + " bytes (" + (bufSize / 1024) + " KB)");

            // Generate a wire protocol response that exceeds the CMA buffer.
            // repeat('x', N) returns an N-byte string in the DataRow message.
            int repeatLen = bufSize + 1000;
            String sql = "SELECT repeat('x', " + repeatLen + ");";
            System.out.println("Query: SELECT repeat('x', " + repeatLen + ")");

            byte[] result = pg.execProtocolRaw(PgWireCodec.queryMessage(sql));
            System.out.println("Response length: " + result.length + " bytes");
            assertNotNull(result);
            // The response must contain the full string + wire protocol overhead
            assertTrue(
                    result.length > repeatLen,
                    "Expected response > " + repeatLen + " but got " + result.length);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(result),
                    "Expected ReadyForQuery in overflow response");

            // Verify a normal query still works after the overflow
            byte[] r2 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 42;"));
            String data = PgWireCodec.parseDataRows(r2);
            System.out.println("Post-overflow query: SELECT 42 => " + data);
            assertTrue(data.contains("42"), "Normal query should work after CMA overflow");
        }
    }

    @Test
    public void extendedProtocolErrorRecovery() {
        try (PGLite pg = PGLite.builder().build()) {
            doHandshake(pg);

            // 1. Valid simple query first
            byte[] r1 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 1;"));
            assertNotNull(r1);
            String data1 = PgWireCodec.parseDataRows(r1);
            System.out.println("Extended proto test: SELECT 1 => " + data1);
            assertTrue(data1.contains("1"));

            // 2. Extended protocol batch (Parse+Bind+Describe+Execute+Sync)
            //    for a query that will fail (nonexistent table).
            //    This reproduces the Flyway hang: pgjdbc uses extended protocol
            //    and the error recovery must send ReadyForQuery.
            byte[] batch = PgWireCodec.extendedQueryBatch("SELECT * FROM nonexistent_table_xyz");
            byte[] r2 = pg.execProtocolRaw(batch);
            assertNotNull(r2);
            System.out.println("Extended proto error response: " + r2.length + " bytes");
            assertTrue(r2.length > 0, "Expected non-empty error response");
            assertTrue(
                    PgWireCodec.hasReadyForQuery(r2),
                    "Expected ReadyForQuery after extended protocol error");

            // 3. Valid query should still work (verifies no buffer corruption)
            byte[] r3 = pg.execProtocolRaw(PgWireCodec.queryMessage("SELECT 2;"));
            assertNotNull(r3);
            String data3 = PgWireCodec.parseDataRows(r3);
            System.out.println("After extended proto error: SELECT 2 => " + data3);
            assertTrue(
                    data3.contains("2"),
                    "Instance should be reusable after extended protocol error");
        }
    }

    static void doHandshake(PGLite pg) {
        byte[] startup = PgWireCodec.startupMessage("postgres", "template1");
        byte[] resp1 = pg.execProtocolRaw(startup);

        int[] auth = PgWireCodec.parseAuth(resp1);
        if (auth[0] == 5) { // MD5
            byte[] salt = {(byte) auth[1], (byte) auth[2], (byte) auth[3], (byte) auth[4]};
            byte[] pwMsg = PgWireCodec.md5PasswordMessage("password", "postgres", salt);
            byte[] resp2 = pg.execProtocolRaw(pwMsg);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp2), "Expected ReadyForQuery after password");
        } else if (auth[0] == 3) { // Cleartext
            byte[] pwMsg = PgWireCodec.passwordMessage("password");
            byte[] resp2 = pg.execProtocolRaw(pwMsg);
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp2), "Expected ReadyForQuery after password");
        } else if (auth[0] == 0) { // AuthenticationOk
            assertTrue(
                    PgWireCodec.hasReadyForQuery(resp1),
                    "Expected ReadyForQuery in auth OK response");
        }
    }
}
