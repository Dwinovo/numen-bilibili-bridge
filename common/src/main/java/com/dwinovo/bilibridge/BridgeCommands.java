package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.bind.BindingRegistry;
import com.dwinovo.bilibridge.config.BridgeConfig;
import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * The {@code /bilibridge} command tree (server side, registered by both loaders).
 * One companion serves one live room; binding is connecting.
 *
 * <pre>
 *   /bilibridge bind &lt;同伴名&gt; &lt;房间号&gt;   bind + connect (a room disconnects when its last binding leaves)
 *   /bilibridge unbind &lt;同伴名&gt;          remove the binding
 *   /bilibridge status                   every room ↔ companion pair, connection state, batch settings
 *   /bilibridge window &lt;seconds&gt;         batch time window (global)
 *   /bilibridge max &lt;count&gt;              raw message count that forces a flush (global)
 *   /bilibridge lines &lt;count&gt;            max aggregated danmaku lines per batch (global)
 *   /bilibridge peruser &lt;count&gt;          newest danmaku kept per user per window, 0 = unlimited (global)
 *   /bilibridge urgent &lt;true|false&gt;      whether a batch wakes an idle companion immediately (global)
 *   /bilibridge test [同伴名] &lt;文本&gt;     inject one fake danmaku — full pipeline, no network;
 *                                        the name may be omitted while exactly one binding exists
 * </pre>
 */
public final class BridgeCommands {

    private BridgeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bilibridge")
                .then(Commands.literal("bind")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(BridgeCommands::suggestLiveCompanions)
                                .then(Commands.argument("room", LongArgumentType.longArg(1))
                                        .executes(BridgeCommands::bind))))
                .then(Commands.literal("unbind")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests(BridgeCommands::suggestBoundNames)
                                .executes(BridgeCommands::unbind)))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            reply(ctx, BridgeRuntime.get().status());
                            return 1;
                        }))
                .then(Commands.literal("window")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                .executes(ctx -> {
                                    BridgeConfig cfg = BridgeConfig.get();
                                    cfg.batchWindowSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    cfg.save();
                                    reply(ctx, "批次时间窗 = " + cfg.batchWindowSeconds + "s");
                                    return 1;
                                })))
                .then(Commands.literal("max")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> {
                                    BridgeConfig cfg = BridgeConfig.get();
                                    cfg.batchMaxCount = IntegerArgumentType.getInteger(ctx, "count");
                                    cfg.save();
                                    reply(ctx, "触发发送的原始条数阈值 = " + cfg.batchMaxCount);
                                    return 1;
                                })))
                .then(Commands.literal("lines")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> {
                                    BridgeConfig cfg = BridgeConfig.get();
                                    cfg.maxLines = IntegerArgumentType.getInteger(ctx, "count");
                                    cfg.save();
                                    reply(ctx, "聚合后普通弹幕最多 " + cfg.maxLines + " 行（超出保留最新）");
                                    return 1;
                                })))
                .then(Commands.literal("peruser")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 50))
                                .executes(ctx -> {
                                    BridgeConfig cfg = BridgeConfig.get();
                                    cfg.perUserPerWindow = IntegerArgumentType.getInteger(ctx, "count");
                                    cfg.save();
                                    reply(ctx, cfg.perUserPerWindow == 0
                                            ? "每用户限流已关闭"
                                            : "每用户每窗口保留最新 " + cfg.perUserPerWindow + " 条");
                                    return 1;
                                })))
                .then(Commands.literal("urgent")
                        .then(Commands.literal("true").executes(ctx -> setUrgent(ctx, true)))
                        .then(Commands.literal("false").executes(ctx -> setUrgent(ctx, false))))
                .then(Commands.literal("test")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(BridgeCommands::test))));
    }

    private static int bind(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        long room = LongArgumentType.getLong(ctx, "room");
        BindingRegistry registry = BridgeRuntime.get().registry();
        Long prev = BridgeRuntime.get().bind(name, room);

        StringBuilder msg = new StringBuilder("已绑定 " + name + " ↔ 房间 " + room
                + "，正在连接（/bilibridge status 查看进度）");
        if (prev != null && prev != room) {
            msg.append("；原房间 ").append(prev)
               .append(registry.hasRoom(prev) ? " 仍有其他绑定，连接保留" : " 已无绑定，连接已断开");
        }
        if (!companionIsLive(ctx, name)) {
            msg.append("。注意：当前没有叫 ").append(name).append(" 的在世同伴，它不在场期间该房间的批次会被丢弃");
        }
        reply(ctx, msg.toString());
        return 1;
    }

    private static int unbind(CommandContext<CommandSourceStack> ctx) {
        String input = StringArgumentType.getString(ctx, "name").trim();
        BindingRegistry registry = BridgeRuntime.get().registry();
        String name = registry.resolveName(input);
        if (name == null) {
            ctx.getSource().sendFailure(Component.literal("同伴 " + input + " 没有绑定任何直播间"));
            return 0;
        }
        long room = BridgeRuntime.get().unbind(name);
        reply(ctx, "已解绑 " + name + " ↔ 房间 " + room
                + (registry.hasRoom(room) ? "（房间仍有其他绑定，连接保留）" : "，该房间已断开"));
        return 1;
    }

    private static int test(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "text");
        BindingRegistry registry = BridgeRuntime.get().registry();
        if (registry.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "尚未绑定任何同伴：先 /bilibridge bind <同伴名> <房间号>"));
            return 0;
        }
        BindingRegistry.TestTarget target = registry.resolveTest(raw);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "有多个绑定，请指明同伴名：/bilibridge test <同伴名> <文本>"));
            return 0;
        }
        if (!BridgeRuntime.get().injectTest(target.roomId(), target.text())) {
            ctx.getSource().sendFailure(Component.literal("房间 " + target.roomId() + " 的连接尚未建立"));
            return 0;
        }
        reply(ctx, "已给同伴 " + target.name() + "（房间 " + target.roomId()
                + "）注入测试弹幕（可连发攒批，window=" + BridgeConfig.get().batchWindowSeconds + "s 后送达）");
        return 1;
    }

    private static int setUrgent(CommandContext<CommandSourceStack> ctx, boolean value) {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.urgent = value;
        cfg.save();
        reply(ctx, "urgent = " + value + (value ? "（弹幕批次会立刻唤醒同伴）" : "（弹幕随下一次对话送达）"));
        return 1;
    }

    private static boolean companionIsLive(CommandContext<CommandSourceStack> ctx, String name) {
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer && p.getName().getString().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Live companion names for {@code bind} — quoted when needed (中文名需要引号). */
    private static CompletableFuture<Suggestions> suggestLiveCompanions(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer) {
                builder.suggest(StringArgumentType.escapeIfRequired(p.getName().getString()));
            }
        }
        return builder.buildFuture();
    }

    /** Bound names for {@code unbind} — raw, because the greedy argument takes no quotes. */
    private static CompletableFuture<Suggestions> suggestBoundNames(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (String name : BridgeRuntime.get().registry().snapshot().keySet()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
