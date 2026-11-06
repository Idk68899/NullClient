package dev.nullclient.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.nullclient.NullClient;
import dev.nullclient.core.event.Events;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/** Registers /ai as a real client command so Minecraft shows suggestions; it feeds the same handler as ".ai". */
public final class AiCommandTree {
    private static final List<String> VERBS = List.of(
            "get", "mine", "plan", "goto", "wander", "follow", "stop", "stopall", "list", "profiles", "new", "menu", "debug", "help");
    private static final int MAX_SUGGESTIONS = 60;

    private AiCommandTree() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("ai")
                .executes(ctx -> run(".ai help"))
                .then(ClientCommandManager.argument("request", StringArgumentType.greedyString())
                        .suggests(AiCommandTree::suggest)
                        .executes(ctx -> run(".ai " + StringArgumentType.getString(ctx, "request")))));
    }

    private static int run(String line) {
        NullClient nc = NullClient.get();
        if (nc != null) {
            nc.bus().post(new Events.ChatSend(line));
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        String text = builder.getRemaining();
        int space = text.lastIndexOf(' ');
        String word = text.substring(space + 1).toLowerCase(Locale.ROOT);
        SuggestionsBuilder wb = builder.createOffset(builder.getStart() + space + 1);
        String[] before = space < 0 ? new String[0] : text.substring(0, space).trim().toLowerCase(Locale.ROOT).split("\\s+");

        String verb = null;
        for (String s : before) {
            if (VERBS.contains(s)) {
                verb = s;
                break;
            }
        }

        TreeSet<String> out = new TreeSet<>();
        if (verb == null) {
            for (String v : VERBS) {
                add(out, v, word);
            }
            if (before.length == 0) {
                NullClient nc = NullClient.get();
                if (nc != null) {
                    nc.profiles().all().forEach(p -> add(out, p.name, word));
                }
            }
        } else {
            switch (verb) {
                case "get", "plan" -> {
                    for (var id : BuiltInRegistries.ITEM.keySet()) {
                        add(out, id.getPath(), word);
                    }
                }
                case "mine" -> {
                    for (var id : BuiltInRegistries.BLOCK.keySet()) {
                        add(out, id.getPath(), word);
                    }
                }
                case "goto" -> add(out, "~", word);
                case "follow" -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level != null) {
                        List<AbstractClientPlayer> players = new ArrayList<>(mc.level.players());
                        for (AbstractClientPlayer p : players) {
                            add(out, p.getName().getString(), word);
                        }
                    }
                }
                default -> {
                }
            }
        }
        int n = 0;
        for (String s : out) {
            if (n++ >= MAX_SUGGESTIONS) {
                break;
            }
            wb.suggest(s);
        }
        return wb.buildFuture();
    }

    private static void add(TreeSet<String> out, String candidate, String word) {
        if (word.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(word)) {
            out.add(candidate);
        }
    }
}