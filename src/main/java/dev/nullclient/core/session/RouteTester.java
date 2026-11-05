package dev.nullclient.core.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Verifies that a route accepts a tunnel request to a public host. Blocking; run off the render thread. */
public final class RouteTester {
    private static final String TARGET_HOST = "example.com";
    private static final int TARGET_PORT = 443;
    private static final int TIMEOUT_MS = 6000;

    public record Result(boolean ok, long latencyMs, String message) {
    }

    private RouteTester() {
    }

    public static Result run(Route route) {
        long start = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(route.host, route.port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            String err = route.type == Route.Type.SOCKS5 ? socks5(s, route) : http(s, route);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            return err == null ? new Result(true, ms, "OK " + ms + " ms") : new Result(false, -1, err);
        } catch (IOException e) {
            return new Result(false, -1, "Failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    private static String socks5(Socket s, Route r) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        if (r.hasAuth()) {
            out.write(new byte[]{5, 2, 0, 2});
        } else {
            out.write(new byte[]{5, 1, 0});
        }
        out.flush();
        byte[] choice = in.readNBytes(2);
        if (choice.length < 2 || choice[0] != 5) {
            return "Not a SOCKS5 proxy";
        }
        int method = choice[1] & 0xFF;
        if (method == 0xFF) {
            return "No acceptable auth method";
        }
        if (method == 2) {
            byte[] u = r.username.getBytes(StandardCharsets.UTF_8);
            byte[] p = r.password.getBytes(StandardCharsets.UTF_8);
            if (u.length > 255 || p.length > 255) {
                return "Credentials too long";
            }
            out.write(1);
            out.write(u.length);
            out.write(u);
            out.write(p.length);
            out.write(p);
            out.flush();
            byte[] auth = in.readNBytes(2);
            if (auth.length < 2 || auth[1] != 0) {
                return "Authentication rejected";
            }
        }
        byte[] host = TARGET_HOST.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[]{5, 1, 0, 3, (byte) host.length});
        out.write(host);
        out.write(new byte[]{(byte) (TARGET_PORT >> 8), (byte) TARGET_PORT});
        out.flush();
        byte[] reply = in.readNBytes(4);
        if (reply.length < 4) {
            return "Truncated reply";
        }
        return reply[1] == 0 ? null : "Connect refused (code " + (reply[1] & 0xFF) + ")";
    }

    private static String http(Socket s, Route r) throws IOException {
        StringBuilder req = new StringBuilder()
                .append("CONNECT ").append(TARGET_HOST).append(':').append(TARGET_PORT).append(" HTTP/1.1\r\n")
                .append("Host: ").append(TARGET_HOST).append(':').append(TARGET_PORT).append("\r\n");
        if (r.hasAuth()) {
            String cred = r.username + ":" + r.password;
            req.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder line = new StringBuilder();
        InputStream in = s.getInputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n' && line.length() < 256) {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        String status = line.toString();
        if (!status.startsWith("HTTP/")) {
            return "Not an HTTP proxy";
        }
        String[] parts = status.split(" ", 3);
        return parts.length >= 2 && parts[1].equals("200") ? null : "Proxy replied: " + status;
    }
}