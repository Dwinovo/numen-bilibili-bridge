package com.dwinovo.bilibridge;

import com.dwinovo.bilibridge.config.BridgeConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The {@code /bilibridge} command tree (server side, registered by both loaders).
 *
 * <pre>
 *   /bilibridge connect [房间号]      connect (optionally saving a new room id first)
 *   /bilibridge disconnect            drop the connection and stop reconnecting
 *   /bilibridge status                connection state + counters + batch settings
 *   /bilibridge window &lt;seconds&gt;      batch time window
 *   /bilibridge max &lt;count&gt;           batch max message count
 *   /bilibridge urgent &lt;true|false&gt;   whether a batch wakes an idle companion immediately
 * </pre>
 */
public final class BridgeCommands {

    private BridgeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bilibridge")
                .then(Commands.literal("connect")
                        .executes(BridgeCommands::connect)
                        .then(Commands.argument("room", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    BridgeConfig cfg = BridgeConfig.get();
                                    cfg.roomId = LongArgumentType.getLong(ctx, "room");
                                    cfg.save();
                                    return connect(ctx);
                                })))
                .then(Commands.literal("disconnect")
                        .executes(ctx -> {
                            BridgeRuntime.get().disconnect();
                            reply(ctx, "已断开弹幕连接");
                            return 1;
                        }))
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
                                    reply(ctx, "批次最大条数 = " + cfg.batchMaxCount);
                                    return 1;
                                })))
                .then(Commands.literal("urgent")
                        .then(Commands.literal("true").executes(ctx -> setUrgent(ctx, true)))
                        .then(Commands.literal("false").executes(ctx -> setUrgent(ctx, false)))));
    }

    private static int connect(CommandContext<CommandSourceStack> ctx) {
        String error = BridgeRuntime.get().connect();
        if (error != null) {
            ctx.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        reply(ctx, "正在连接直播间 " + BridgeConfig.get().roomId + "（/bilibridge status 查看进度）");
        return 1;
    }

    private static int setUrgent(CommandContext<CommandSourceStack> ctx, boolean value) {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.urgent = value;
        cfg.save();
        reply(ctx, "urgent = " + value + (value ? "（弹幕批次会立刻唤醒同伴）" : "（弹幕随下一次对话送达）"));
        return 1;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
