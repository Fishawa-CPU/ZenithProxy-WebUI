package com.zenith.webui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static com.zenith.Globals.COMMAND;

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
        broadcast("log", log);
    }

    void handleCommand(String clientId, String command) {
        // Echo the command back
        JsonObject echo = new JsonObject();
        echo.addProperty("text", "> " + command);
        echo.addProperty("type", "command");
        sendTo(clientId, "terminal", echo);

        // Execute the command
        try {
            CommandContext context = CommandContext.create(command, CommandSources.TERMINAL);
            COMMAND.execute(context);
            List<String> output = context.getMultiLineOutput();

            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            if (output != null && !output.isEmpty()) {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String line : output) arr.add(line);
                result.add("output", arr);
            } else {
                result.addProperty("message", "OK");
            }
            sendTo(clientId, "command_result", result);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("success", false);
            err.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            sendTo(clientId, "command_result", err);
        }
    }

    private void broadcast(String type, Object data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("data", GSON.toJsonTree(data));
        String json = GSON.toJson(msg);
        clients.removeIf(c -> !c.isOpen());
        clients.forEach(c -> c.send(json));
    }

    private void sendTo(String clientId, String type, Object data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("data", GSON.toJsonTree(data));
        String json = GSON.toJson(msg);
        clients.removeIf(c -> !c.isOpen());
        for (WSClient c : clients) {
            if (c.id.equals(clientId)) { c.send(json); break; }
        }
    }

    class WSClient implements Runnable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private volatile boolean open;
        final String id;

        WSClient(Socket s) throws IOException {
            this.socket = s;
            this.in = new DataInputStream(s.getInputStream());
            this.out = new DataOutputStream(s.getOutputStream());
            this.open = true;
            this.id = UUID.randomUUID().toString().substring(0, 8);
        }

        public void run() {
            try {
                handshake();
                // Send welcome
                JsonObject welcome = new JsonObject();
                welcome.addProperty("clientId", id);
                welcome.addProperty("message", "Connected to ZenithProxy Terminal");
                send(GSON.toJson(Map.of("type", "welcome", "data", welcome)));

                while (open) {
                    String msg = readFrame();
                    if (msg == null) break;
                    handleIncoming(msg);
                }
            } catch (Exception e) { /* closed */ } finally { close(); }
        }

        private void handleIncoming(String raw) {
            try {
                JsonObject json = GSON.fromJson(raw, JsonObject.class);
                if (json == null || !json.has("type")) return;
                String type = json.get("type").getAsString();
                if ("command".equals(type) && json.has("data")) {
                    String cmd = json.get("data").getAsString();
                    if (cmd != null && !cmd.trim().isEmpty()) {
                        handleCommand(id, cmd.trim());
                    }
                }
            } catch (Exception ignored) {}
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
            try {
                digest = MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes("UTF-8"));
            } catch (Exception e) { throw new IOException(e); }
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
