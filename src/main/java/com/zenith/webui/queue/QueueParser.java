package com.zenith.webui.queue;

import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;

import java.util.Optional;

import static com.zenith.Globals.*;

/**
 * 3c3u.org 服务器队列解析器
 * 拦截 Subtitle 包，解析格式: <onlinePlayers> | Position in queue: <position>
 */
public class QueueParser implements ClientEventLoopPacketHandler<ClientboundSetSubtitleTextPacket, ClientSession> {

    private static final String TARGET_SERVER = "3c3u.org";

    public static volatile String queuePos = "";
    public static volatile int onlinePlayers = 0;
    public static volatile int positionInQueue = -1;

    @Override
    public boolean applyAsync(ClientboundSetSubtitleTextPacket packet, ClientSession session) {
        // Only parse for 3c3u.org
        String serverAddress = CONFIG.client.server.address;
        if (serverAddress == null || !serverAddress.toLowerCase().contains(TARGET_SERVER)) {
            return true;
        }

        try {
            parse3cQueuePos(packet);
        } catch (Exception ignored) {}
        return true;
    }

    public static boolean is3cQueue() {
        return positionInQueue > 0;
    }

    public static void reset() {
        queuePos = "";
        onlinePlayers = 0;
        positionInQueue = -1;
    }

    private void parse3cQueuePos(ClientboundSetSubtitleTextPacket packet) {
        String text = Optional.of(packet)
            .map(p -> com.zenith.util.ComponentSerializer.serializePlain(p.getText()))
            .orElse("");

        if (text.isEmpty()) {
            positionInQueue = -1;
            return;
        }

        // Format: "<onlinePlayers> | Position in queue: <position>"
        try {
            String[] parts = text.split("\\|");
            if (parts.length < 2) {
                positionInQueue = -1;
                return;
            }

            String onlinePart = parts[0].trim();
            try {
                onlinePlayers = Integer.parseInt(onlinePart.split("\\s+")[0]);
            } catch (NumberFormatException e) {
                onlinePlayers = 0;
            }

            String positionPart = parts[1].trim();
            String[] positionSplit = positionPart.split(":");
            if (positionSplit.length >= 2) {
                positionInQueue = Integer.parseInt(positionSplit[1].trim());
            } else {
                positionInQueue = -1;
            }
        } catch (Exception e) {
            CLIENT_LOG.warn("Error parsing queue info from text: {}", text, e);
            positionInQueue = -1;
        }
    }
}
