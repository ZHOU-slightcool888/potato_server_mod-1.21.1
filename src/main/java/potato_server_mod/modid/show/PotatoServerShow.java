package potato_server_mod.modid.show;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import potato_server_mod.modid.Potato_server_mod;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PotatoServerShow {
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

    private PotatoServerShow() {}

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> STATES.put(server, new State(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(STATES::remove);
        ServerTickEvents.END_SERVER_TICK.register(PotatoServerShow::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            State state = STATES.get(server);
            if (state != null && state.active.remove(handler.player.getUuid()) != null) {
                broadcastRecovery(server, state);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("potatoserver")
                        .then(literal("blindness")
                                .executes(context -> preference(context.getSource(), null))
                                .then(argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> preference(context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")))))));
    }

    public static boolean canEat(ServerPlayerEntity player) {
        State state = STATES.get(player.getServer());
        if (state == null) return false;
        if (state.active.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("§e服务器正在冒烟，请等待本次演出结束。"), true);
            return false;
        }
        long remaining = state.cooldowns.remainingSeconds(player.getUuid());
        if (remaining == 0) return true;
        player.sendMessage(Text.literal("§e土豆服务器还在冷却，请等待 " + remaining + " 秒。"), true);
        return false;
    }

    public static boolean start(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        State state = STATES.get(server);
        if (state == null || !canEat(player) || !state.cooldowns.tryAcquire(player.getUuid())) return false;

        state.active.put(player.getUuid(), new PotatoTimeline());
        player.sendMessage(Text.literal("§c你把服务器吃掉了！"), true);
        player.sendMessage(Text.literal("§6服务器被吃掉了，开始冒烟……"), false);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 100, 0));

        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            if (other == player) continue;
            other.sendMessage(Text.literal("§e玩家 " + player.getName().getString() + " 把服务器吃掉了！"), false);
            if (state.preferences.isEnabled(other.getUuid())) {
                other.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 100, 0));
            }
        }
        return true;
    }

    private static void tick(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return;
        state.cooldowns.clearExpired();
        Iterator<Map.Entry<UUID, PotatoTimeline>> iterator = state.active.entrySet().iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            Map.Entry<UUID, PotatoTimeline> entry = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || player.isDisconnected() || !player.isAlive()) {
                iterator.remove();
                removed = true;
                continue;
            }
            PotatoTimeline.Cue cue = entry.getValue().advance();
            if (cue.smoke()) {
                particles(player, ParticleTypes.SMOKE, 8);
                particles(player, ParticleTypes.FLAME, 3);
            }
            if (cue.alarm()) {
                player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6F, 0.8F);
                if (cue.countdown() == 0) player.sendMessage(Text.literal("§c警报！服务器异常！"), true);
            }
            if (cue.explosion()) {
                particles(player, ParticleTypes.EXPLOSION, 1);
                player.playSoundToPlayer(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7F, 0.85F);
            }
            if (cue.countdown() > 0) {
                player.sendMessage(Text.literal("§c服务器崩溃倒计时：" + cue.countdown() + "……"), true);
            }
            if (cue.finished()) {
                iterator.remove();
                removed = true;
                player.networkHandler.disconnect(Text.literal("§4服务器被你吃掉了！已断开连接"));
            }
        }
        if (removed) broadcastRecovery(server, state);
    }

    private static void particles(ServerPlayerEntity player, SimpleParticleType type, int count) {
        // One packet per particle type, rather than one packet per individual particle.
        player.networkHandler.sendPacket(new ParticleS2CPacket(type, false,
                player.getX(), player.getY() + 1.0, player.getZ(),
                0.7F, 0.6F, 0.7F, 0.01F, count));
    }

    private static void broadcastRecovery(MinecraftServer server, State state) {
        if (!state.active.isEmpty()) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal("§a服务器恢复正常。"), false);
        }
    }

    private static int preference(ServerCommandSource source, Boolean enabled)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        State state = STATES.get(source.getServer());
        if (state == null) return 0;
        if (enabled != null) {
            try {
                state.preferences.setEnabled(player.getUuid(), enabled);
            } catch (IOException exception) {
                Potato_server_mod.LOGGER.error("Could not save potato server preferences", exception);
                source.sendError(Text.literal("保存设置失败，请稍后重试。"));
                return 0;
            }
        }
        boolean current = state.preferences.isEnabled(player.getUuid());
        source.sendFeedback(() -> Text.literal("其他玩家吃土豆时，你的失明效果："
                + (current ? "已开启" : "已关闭")
                + "。使用 /potatoserver blindness true 或 false 修改。"), false);
        return 1;
    }

    private static final class State {
        private final Map<UUID, PotatoTimeline> active = new HashMap<>();
        private final PotatoCooldowns cooldowns = new PotatoCooldowns(System::nanoTime);
        private final BlindnessPreferences preferences;

        private State(MinecraftServer server) {
            preferences = new BlindnessPreferences(server.getSavePath(WorldSavePath.ROOT)
                    .resolve("data/potato_server_mod/blindness.properties"));
            try {
                preferences.load();
            } catch (IOException | IllegalArgumentException exception) {
                Potato_server_mod.LOGGER.error("Could not load potato server preferences; using defaults", exception);
            }
        }
    }
}
