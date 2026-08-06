package com.zenith.webui.api;

import com.google.gson.JsonObject;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.webui.http.HttpHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.COMMAND;

/**
 * Handles /api/commands — list available commands and execute commands.
 */
public class CommandHandler {

    public void handleCommands(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.GET.equals(req.method())) {
            List<LinkedHashMap<String, Object>> commands = new ArrayList<>();
            for (var cmd : COMMAND.getCommands()) {
                var usage = cmd.commandUsage();
                LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", usage.getName());
                String desc = usage.getDescription();
                if (desc != null && !desc.isEmpty()) entry.put("description", desc);
                var aliases = usage.getAliases();
                if (aliases != null && !aliases.isEmpty()) entry.put("aliases", aliases);
                commands.add(entry);
            }
            HttpHelper.sendJson(ctx, commands);
        } else if (HttpMethod.POST.equals(req.method())) {
            try {
                String body = req.content().toString(CharsetUtil.UTF_8);
                if (body == null || body.isEmpty()) {
                    HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Empty request body"));
                    return;
                }
                JsonObject json = HttpHelper.GSON.fromJson(body, JsonObject.class);
                String command = json != null && json.has("command") ? json.get("command").getAsString() : "";
                if (command.isEmpty()) {
                    HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Empty command"));
                    return;
                }
                CommandContext context = CommandContext.create(command, CommandSources.TERMINAL);
                COMMAND.execute(context);

                List<String> output = new ArrayList<>();
                var embed = context.getEmbed();
                if (embed != null) {
                    if (embed.title() != null) output.add(embed.title());
                    if (embed.description() != null) output.add(embed.description());
                    var fields = embed.fields();
                    if (fields != null) {
                        for (var field : fields) {
                            String name = field.name();
                            String value = field.value();
                            if (name != null && !name.isEmpty()) output.add(name + ": " + value);
                            else if (value != null) output.add(value);
                        }
                    }
                }
                var ml = context.getMultiLineOutput();
                if (ml != null && !ml.isEmpty()) output.addAll(ml);

                HttpHelper.sendJson(ctx, Map.of("success", true, "output", output));
            } catch (Exception e) {
                HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Command execution failed"));
            }
        }
    }
}
