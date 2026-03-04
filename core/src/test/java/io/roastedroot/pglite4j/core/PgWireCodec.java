package io.roastedroot.pglite4j.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PostgreSQL v3 wire protocol message builders and parsers.
 *
 * <p>This class handles the low-level encoding/decoding of PostgreSQL wire protocol messages used
 * for the internal handshake and communication between the Java host and the embedded PGLite WASM
 * instance. It is not a full protocol implementation — only the subset needed for startup, auth, and
 * simple query is covered.
 */
final class PgWireCodec {

    private PgWireCodec() {}

    /** Build a StartupMessage (protocol version 3.0). */
    static byte[] startupMessage(String user, String db) {
        String params =
                "user\0"
                        + user
                        + "\0"
                        + "database\0"
                        + db
                        + "\0"
                        + "client_encoding\0UTF8\0"
                        + "application_name\0pglite4j\0"
                        + "\0";
        byte[] paramsBytes = params.getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[4 + 4 + paramsBytes.length];
        int len = msg.length;
        msg[0] = (byte) (len >> 24);
        msg[1] = (byte) (len >> 16);
        msg[2] = (byte) (len >> 8);
        msg[3] = (byte) len;
        msg[4] = 0;
        msg[5] = 3;
        msg[6] = 0;
        msg[7] = 0; // Protocol 3.0
        System.arraycopy(paramsBytes, 0, msg, 8, paramsBytes.length);
        return msg;
    }

    /** Build a Query ('Q') message. */
    static byte[] queryMessage(String sql) {
        byte[] sqlBytes = (sql + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[1 + 4 + sqlBytes.length];
        msg[0] = 'Q';
        int len = 4 + sqlBytes.length;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        System.arraycopy(sqlBytes, 0, msg, 5, sqlBytes.length);
        return msg;
    }

    /** Build a cleartext PasswordMessage ('p'). */
    static byte[] passwordMessage(String pw) {
        byte[] pwBytes = (pw + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[1 + 4 + pwBytes.length];
        msg[0] = 'p';
        int len = 4 + pwBytes.length;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        System.arraycopy(pwBytes, 0, msg, 5, pwBytes.length);
        return msg;
    }

    /** Build an MD5 PasswordMessage ('p'). */
    static byte[] md5PasswordMessage(String password, String user, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(password.getBytes(StandardCharsets.UTF_8));
            md5.update(user.getBytes(StandardCharsets.UTF_8));
            String innerHex = bytesToHex(md5.digest());
            md5.reset();
            md5.update(innerHex.getBytes(StandardCharsets.UTF_8));
            md5.update(salt);
            return passwordMessage("md5" + bytesToHex(md5.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parse an AuthenticationOk/MD5/Cleartext response ('R' message).
     *
     * @return int[5]: {authCode, salt0, salt1, salt2, salt3}. authCode -1 if not found.
     */
    static int[] parseAuth(byte[] data) {
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4) {
                break;
            }
            if (tag == 'R' && len >= 8) {
                int code =
                        ((data[i + 5] & 0xFF) << 24)
                                | ((data[i + 6] & 0xFF) << 16)
                                | ((data[i + 7] & 0xFF) << 8)
                                | (data[i + 8] & 0xFF);
                if (code == 5 && len >= 12) { // MD5 with salt
                    return new int[] {
                        code,
                        data[i + 9] & 0xFF,
                        data[i + 10] & 0xFF,
                        data[i + 11] & 0xFF,
                        data[i + 12] & 0xFF
                    };
                }
                return new int[] {code, 0, 0, 0, 0};
            }
            i += 1 + len;
        }
        return new int[] {-1, 0, 0, 0, 0};
    }

    /** Check whether a response buffer contains a ReadyForQuery ('Z') message. */
    static boolean hasReadyForQuery(byte[] data) {
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4) {
                break;
            }
            if (tag == 'Z') {
                return true;
            }
            i += 1 + len;
        }
        return false;
    }

    /** Extract column values from DataRow ('D') messages as a comma-separated string. */
    static String parseDataRows(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 5 <= data.length) {
            char tag = (char) data[i];
            int len =
                    ((data[i + 1] & 0xFF) << 24)
                            | ((data[i + 2] & 0xFF) << 16)
                            | ((data[i + 3] & 0xFF) << 8)
                            | (data[i + 4] & 0xFF);
            if (len < 4 || i + 1 + len > data.length) {
                break;
            }
            if (tag == 'D' && len > 6) {
                int pos = i + 7;
                int fc = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                for (int f = 0; f < fc && pos + 4 <= i + 1 + len; f++) {
                    int fl =
                            ((data[pos] & 0xFF) << 24)
                                    | ((data[pos + 1] & 0xFF) << 16)
                                    | ((data[pos + 2] & 0xFF) << 8)
                                    | (data[pos + 3] & 0xFF);
                    pos += 4;
                    if (fl > 0 && pos + fl <= i + 1 + len) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(new String(data, pos, fl, StandardCharsets.UTF_8));
                        pos += fl;
                    }
                }
            }
            i += 1 + len;
        }
        return sb.toString();
    }

    // === Extended Query Protocol ===

    /** Build a Parse ('P') message. */
    static byte[] parseMessage(String stmtName, String sql) {
        byte[] nameBytes = (stmtName + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] sqlBytes = (sql + "\0").getBytes(StandardCharsets.UTF_8);
        int bodyLen = nameBytes.length + sqlBytes.length + 2; // 2 for numParamTypes
        byte[] msg = new byte[1 + 4 + bodyLen];
        msg[0] = 'P';
        int len = 4 + bodyLen;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        int pos = 5;
        System.arraycopy(nameBytes, 0, msg, pos, nameBytes.length);
        pos += nameBytes.length;
        System.arraycopy(sqlBytes, 0, msg, pos, sqlBytes.length);
        pos += sqlBytes.length;
        msg[pos] = 0;
        msg[pos + 1] = 0; // 0 parameter types
        return msg;
    }

    /** Build a Bind ('B') message with no parameters. */
    static byte[] bindMessage(String portal, String stmtName) {
        byte[] portalBytes = (portal + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = (stmtName + "\0").getBytes(StandardCharsets.UTF_8);
        int bodyLen = portalBytes.length + nameBytes.length + 2 + 2 + 2;
        byte[] msg = new byte[1 + 4 + bodyLen];
        msg[0] = 'B';
        int len = 4 + bodyLen;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        int pos = 5;
        System.arraycopy(portalBytes, 0, msg, pos, portalBytes.length);
        pos += portalBytes.length;
        System.arraycopy(nameBytes, 0, msg, pos, nameBytes.length);
        pos += nameBytes.length;
        // 0 format codes, 0 parameters, 0 result format codes
        msg[pos] = 0;
        msg[pos + 1] = 0;
        pos += 2;
        msg[pos] = 0;
        msg[pos + 1] = 0;
        pos += 2;
        msg[pos] = 0;
        msg[pos + 1] = 0;
        return msg;
    }

    /** Build a Describe ('D') message. */
    static byte[] describeMessage(char type, String name) {
        byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
        int bodyLen = 1 + nameBytes.length;
        byte[] msg = new byte[1 + 4 + bodyLen];
        msg[0] = 'D';
        int len = 4 + bodyLen;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        msg[5] = (byte) type;
        System.arraycopy(nameBytes, 0, msg, 6, nameBytes.length);
        return msg;
    }

    /** Build an Execute ('E') message. */
    static byte[] executeMessage(String portal, int maxRows) {
        byte[] portalBytes = (portal + "\0").getBytes(StandardCharsets.UTF_8);
        int bodyLen = portalBytes.length + 4;
        byte[] msg = new byte[1 + 4 + bodyLen];
        msg[0] = 'E';
        int len = 4 + bodyLen;
        msg[1] = (byte) (len >> 24);
        msg[2] = (byte) (len >> 16);
        msg[3] = (byte) (len >> 8);
        msg[4] = (byte) len;
        int pos = 5;
        System.arraycopy(portalBytes, 0, msg, pos, portalBytes.length);
        pos += portalBytes.length;
        msg[pos] = (byte) (maxRows >> 24);
        msg[pos + 1] = (byte) (maxRows >> 16);
        msg[pos + 2] = (byte) (maxRows >> 8);
        msg[pos + 3] = (byte) maxRows;
        return msg;
    }

    /** Build a Sync ('S') message. */
    static byte[] syncMessage() {
        return new byte[] {'S', 0, 0, 0, 4};
    }

    /** Build a complete extended query batch: Parse + Bind + Describe + Execute + Sync. */
    static byte[] extendedQueryBatch(String sql) {
        byte[] parse = parseMessage("", sql);
        byte[] bind = bindMessage("", "");
        byte[] describe = describeMessage('S', "");
        byte[] execute = executeMessage("", 0);
        byte[] sync = syncMessage();
        byte[] batch =
                new byte
                        [parse.length
                                + bind.length
                                + describe.length
                                + execute.length
                                + sync.length];
        int pos = 0;
        System.arraycopy(parse, 0, batch, pos, parse.length);
        pos += parse.length;
        System.arraycopy(bind, 0, batch, pos, bind.length);
        pos += bind.length;
        System.arraycopy(describe, 0, batch, pos, describe.length);
        pos += describe.length;
        System.arraycopy(execute, 0, batch, pos, execute.length);
        pos += execute.length;
        System.arraycopy(sync, 0, batch, pos, sync.length);
        return batch;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
