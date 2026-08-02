package com.zenith.webui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class WebSocketServer {
    private static final Logger LOGGER = Logger.getLogger("WebSocket");
    private static final Gson GSON = new Gson();
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-5AB9161C9712";

    private final int port;
    private ServerSocket serverSocket;
    private final List<WSClient> clients = new CopyOnWriteArrayList<>();
    private Thread acceptThread;
    private volatile boolean running;

    public WebSocketServer(int port) { this.port = port; }

    public void start() {
        running = true;
        acceptThread = new Thread(this::accept, "WebUI-WS");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        clients.forEach(WSClient::close);
        clients.clear();
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void accept() {
        try {
            serverSocket = new ServerSocket(port);
            LOGGER.info("WebSocket server started on port " + port);
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    WSClient client = new WSClient(s);
                    clients.add(client);
                    new Thread(client, "WS-Client").start();
                } catch (IOException e) {
                    if (running) LOGGER.warning("WS accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) { LOGGER.severe("WebSocket server error: " + e.getMessage()); }
    }

    public void broadcastLog(String level, String logger, String message) {
        JsonObject log = new JsonObject();
        log.addProperty("level", level);
        log.addProperty("logger", logger);
        log.addProperty("message", message);
        log.addProperty("timestamp", System.currentTimeMillis());

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "log");
        msg.add("data", GSON.toJsonTree(log));
        String json = GSON.toJson(msg);
        clients.removeIf(c -> !c.isOpen());
        clients.forEach(c -> c.send(json));
    }

    class WSClient implements Runnable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private volatile boolean open;

        WSClient(Socket s) throws IOException {
            this.socket = s;
            this.in = new DataInputStream(s.getInputStream());
            this.out = new DataOutputStream(s.getOutputStream());
            this.open = true;
        }

        public void run() {
            try {
                handshake();
                while (open) {
                    String msg = readFrame();
                    if (msg == null) break;
                }
            } catch (Exception e) { /* closed */ } finally { close(); }
        }

        private void handshake() throws IOException {
            StringBuilder req = new StringBuilder();
            String line;
            while (!(line = readLine()).isEmpty()) req.append(line).append("\r\n");
            String key = null;
            for (String l : req.toString().split("\r\n")) {
                if (l.toLowerCase().startsWith("sec-websocket-key:")) { key = l.substring(l.indexOf(':') + 1).trim(); break; }
            }
            if (key == null) throw new IOException("Invalid handshake");
            byte[] digest;
            try { digest = MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes("UTF-8")); }
            catch (Exception e) { throw new IOException(e); }
            String accept = java.util.Base64.getEncoder().encodeToString(digest);
            out.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
            out.flush();
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') { in.read(); break; }
                if (c == '\n') break;
                sb.append((char) c);
            }
            return sb.toString();
        }

        private String readFrame() throws IOException {
            int b0 = in.readUnsignedByte();
            int op = b0 & 0x0F;
            if (op == 0x08) return null;
            if (op == 0x09) { sendPong(); return readFrame(); }
            if (op == 0x0A) return readFrame();
            int b1 = in.readUnsignedByte();
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] mask = masked ? new byte[4] : null;
            if (masked) in.readFully(mask);
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            return new String(payload, "UTF-8");
        }

        public void send(String msg) {
            if (!open) return;
            try {
                byte[] payload = msg.getBytes("UTF-8");
                out.writeByte(0x81);
                if (payload.length < 126) out.writeByte(payload.length);
                else if (payload.length < 65536) { out.writeByte(126); out.writeShort(payload.length); }
                else { out.writeByte(127); out.writeLong(payload.length); }
                out.write(payload);
                out.flush();
            } catch (IOException e) { close(); }
        }

        private void sendPong() {
            try { out.writeByte(0x8A); out.writeByte(0); out.flush(); } catch (IOException e) { close(); }
        }

        public void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) {}
            clients.remove(this);
        }

        public boolean isOpen() { return open && !socket.isClosed(); }
    }
}
