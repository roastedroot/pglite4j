package io.roastedroot.pglite4j.jdbc;

import io.roastedroot.pglite4j.core.PGLite;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class PgLiteDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:pglite:";
    private static final ConcurrentHashMap<String, ManagedInstance> INSTANCES =
            new ConcurrentHashMap<>();

    static {
        try {
            DriverManager.registerDriver(new PgLiteDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    for (ManagedInstance inst : INSTANCES.values()) {
                                        try {
                                            inst.close();
                                        } catch (RuntimeException e) {
                                            // best effort during shutdown
                                        }
                                    }
                                }));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        String dataPath = url.substring(URL_PREFIX.length());
        ManagedInstance instance =
                INSTANCES.computeIfAbsent(
                        dataPath,
                        k -> {
                            ManagedInstance inst = new ManagedInstance();
                            inst.boot();
                            return inst;
                        });

        Properties props = new Properties();
        if (info != null) {
            props.putAll(info);
        }
        props.putIfAbsent("user", "postgres");
        props.putIfAbsent("password", "password");
        props.setProperty("sslmode", "disable");
        props.setProperty("gssEncMode", "disable");
        props.putIfAbsent("connectTimeout", "60");

        String pgUrl = "jdbc:postgresql://127.0.0.1:" + instance.getPort() + "/template1";
        return new org.postgresql.Driver().connect(pgUrl, props);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    static final class ManagedInstance {
        private PGLite pgLite;
        private ServerSocket serverSocket;
        private volatile boolean running;
        private final Object pgLock = new Object();
        private final AtomicInteger connectionCounter = new AtomicInteger();
        private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
        private volatile List<byte[]> cachedStartupResponses;

        void boot() {
            pgLite = PGLite.builder().build();
            try {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                pgLite.close();
                throw new RuntimeException("Failed to create ServerSocket", e);
            }
            running = true;
            Thread acceptThread = new Thread(this::acceptLoop, "pglite-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread handler =
                            new Thread(
                                    () -> handleConnection(socket),
                                    "pglite-conn-" + connectionCounter.getAndIncrement());
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (running) {
                        // log but don't crash the accept loop
                    }
                }
            }
        }

        private void handleConnection(Socket socket) {
            activeSockets.add(socket);
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[65536];

                String connName = Thread.currentThread().getName();
                System.err.println("[pglite4j] " + connName + " starting startup");
                handleStartup(in, out, buf);
                System.err.println("[pglite4j] " + connName + " startup complete");

                int msgCount = 0;
                while (running) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        System.err.println(
                                "[pglite4j] "
                                        + connName
                                        + " read returned "
                                        + n
                                        + " (EOF), closing");
                        break;
                    }
                    byte[] message = Arrays.copyOf(buf, n);
                    msgCount++;
                    // Try to extract SQL from message
                    String debugMsg = "";
                    if (message.length > 5 && message[0] == 'Q') {
                        // Simple Query: Q + len(4) + query\0
                        debugMsg = new String(message, 5, Math.min(message.length - 6, 200));
                    } else if (message.length > 5 && message[0] == 'P') {
                        // Parse: P + len(4) + stmtName\0 + query\0 + ...
                        int nameEnd = 5;
                        while (nameEnd < message.length && message[nameEnd] != 0) {
                            nameEnd++;
                        }
                        if (nameEnd + 1 < message.length) {
                            int qStart = nameEnd + 1;
                            int qEnd = qStart;
                            while (qEnd < message.length && message[qEnd] != 0) {
                                qEnd++;
                            }
                            debugMsg =
                                    "PARSE: "
                                            + new String(
                                                    message, qStart, Math.min(qEnd - qStart, 200));
                        }
                    }
                    System.err.println(
                            "[pglite4j] "
                                    + connName
                                    + " msg#"
                                    + msgCount
                                    + " len="
                                    + n
                                    + " type="
                                    + (char) message[0]
                                    + " "
                                    + debugMsg);
                    byte[] response;
                    synchronized (pgLock) {
                        response = pgLite.execProtocolRaw(message);
                    }
                    if (response.length > 0) {
                        out.write(response);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // one connection failure must not crash other connections
                System.err.println("[pglite4j] IOException in handleConnection: " + e);
                e.printStackTrace(System.err);
            } catch (RuntimeException e) {
                // protect other connections from PGLite errors
                System.err.println("[pglite4j] RuntimeException in handleConnection: " + e);
                e.printStackTrace(System.err);
            } finally {
                activeSockets.remove(socket);
                try {
                    socket.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
        }

        private void handleStartup(InputStream in, OutputStream out, byte[] buf)
                throws IOException {
            List<byte[]> cached = cachedStartupResponses;
            if (cached != null) {
                replayStartup(cached, in, out, buf);
                return;
            }
            synchronized (pgLock) {
                cached = cachedStartupResponses;
                if (cached != null) {
                    replayStartup(cached, in, out, buf);
                    return;
                }
                List<byte[]> responses = new ArrayList<>();
                while (running) {
                    int n = in.read(buf);
                    if (n <= 0) {
                        throw new IOException("Connection closed during startup");
                    }
                    byte[] message = Arrays.copyOf(buf, n);
                    byte[] response = pgLite.execProtocolRaw(message);
                    responses.add(response);
                    if (response.length > 0) {
                        out.write(response);
                        out.flush();
                    }
                    if (endsWithReadyForQuery(response)) {
                        break;
                    }
                }
                cachedStartupResponses = responses;
            }
        }

        private static void replayStartup(
                List<byte[]> cached, InputStream in, OutputStream out, byte[] buf)
                throws IOException {
            for (byte[] cachedResp : cached) {
                int n = in.read(buf);
                if (n <= 0) {
                    throw new IOException("Connection closed during startup replay");
                }
                if (cachedResp.length > 0) {
                    out.write(cachedResp);
                    out.flush();
                }
            }
        }

        private static boolean endsWithReadyForQuery(byte[] response) {
            // ReadyForQuery: type='Z' (0x5A), length=5 (00 00 00 05), status byte
            if (response.length < 6) {
                return false;
            }
            int off = response.length - 6;
            return response[off] == 'Z'
                    && response[off + 1] == 0
                    && response[off + 2] == 0
                    && response[off + 3] == 0
                    && response[off + 4] == 5;
        }

        void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException e) {
                // cleanup
            }
            for (Socket s : activeSockets) {
                try {
                    s.close();
                } catch (IOException e) {
                    // cleanup
                }
            }
            pgLite.close();
        }
    }
}
