package potato_server_mod.modid.item;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.*;

/*
 * FoodServerPotato
 *
 * 这是一个自定义物品类（继承自 Item），在玩家使用（食用）时会触发服务器端的一系列“戏剧化”效果：
 * - 发送消息（给本人和其他玩家）
 * - 给玩家施加中毒 / 盲目等状态效果
 * - 五秒钟内持续播放粒子和声音特效以及“屏幕震动”模拟
 * - 十秒后断开该玩家的连接（模拟“服务器被吃掉”后的断线）
 *
 * 注：
 * - 本类在设计上使用了一个简单的延迟任务调度器（基于服务器 tick）来安排延时执行的 Runnable。
 * - 所有对玩家、网络包、粒子与声音的发送都在服务器线程（非客户端）执行，因为 packet 发送需要服务器端的 player.networkHandler。
 * - 代码尽量避免在客户端分支执行这些操作（使用 world.isClient() 做判断）。
 *
 * 兼容性提示：
 * - 代码针对 Fabric 1.21.1 API 编写（例如对 RegistryEntry / Registries 的使用）。
 * - 在不同的 MC / Fabric 版本中，Sound/Packet API 可能有细微差别；注意 PlaySoundS2CPacket 与 RegistryEntry 的构造签名。
 */

public class FoodServerPotato extends Item {

    // ----------------- 食物属性定义 -----------------
    // 使用 FoodComponent 来定义食物提供的饥饿值、饱和度、是否为零食等属性。
    private static final FoodComponent POTATO_SERVER_FOOD = new FoodComponent(
            2,              // int hunger：食用后回复的饥饿值（饥饿条单位）
            0.1F,           // float saturationModifier：饱和度修正值（影响饱和度恢复）
            true,           // boolean isSnack：是否为零食（true 表示可在饥饿值未满时也能快速使用）
            1.6F,           // float probabilityOfMeat？（注意：FoodComponent 构造函数的参数含义请以本版本 API 为准，这里与源码签名对应）
            Optional.empty(),   // Optional<StatusEffectInstance>：吃后可能附带的效果（此处为空）
            Collections.emptyList() // List<Pair<StatusEffectInstance, Float>>：概率效果列表（此处为空）
    );

    // 构造器：把食物组件放入 Item.Settings 中
    public FoodServerPotato() {
        // Settings.maxCount(1)：堆叠上限 1
        // component(DataComponentTypes.FOOD, POTATO_SERVER_FOOD)：把 FoodComponent 注入到物品数据组件（Fabric/MC 的 DataComponentTypes）
        super(new Settings().maxCount(1)
                .component(DataComponentTypes.FOOD, POTATO_SERVER_FOOD)
        );
    }

    // ========================= 延迟任务调度器（基于服务器 tick） =========================
    /*
     * 设计意图：
     * - 需要在若干 tick 后执行一些动作（例如在 200 tick 后踢出玩家）。
     * - 直接使用一个 Map<tickIndex, List<Runnable>> 存储要在未来某 tick 执行的任务。
     *
     * 重要点：
     * - currentTick 随服务器每个 END_SERVER_TICK 自增（使用 Fabric 的 ServerTickEvents.END_SERVER_TICK）。
     * - runLater(delayTicks, action) 会把 action 注册到 currentTick + delayTicks 对应的键下。
     * - initScheduler() 保证只注册一次 END_SERVER_TICK 的监听器（使用 schedulerInitialized 标志）。
     *
     * 注意事项 / 风险：
     * - TASKS Map 会随着时间移除已经执行的键（使用 remove），避免内存泄露。
     * - 如果 delayTicks 非常大或 action 非常多，内存占用会增加；若需要生产环境使用，考虑使用更健壮的调度器或限制任务数量。
     * - 当前实现没有并发保护（synchronized），但由于所有操作均发生在服务器主线程（tick 回调与 runLater 预计在相同线程），一般是安全的。如果你在其他线程调用 runLater，则需要加锁。
     */
    private static final Map<Integer, List<Runnable>> TASKS = new HashMap<>();
    private static int currentTick = 0;
    private static boolean schedulerInitialized = false;

    private static void initScheduler() {
        if (schedulerInitialized) return; // 已经初始化则直接返回
        schedulerInitialized = true;

        // 注册在服务器每个结束 tick 时触发的事件（Fabric 提供的事件钩子）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            currentTick++; // 增加全局 tick 计数
            List<Runnable> list = TASKS.remove(currentTick); // 获取并移除需要在当前 tick 执行的任务列表
            if (list != null) list.forEach(Runnable::run); // 逐个执行注册的 Runnable
        });
    }

    // 将动作安排在 delayTicks 后执行（delayTicks 单位为服务器 tick，20 tick ≈ 1 秒）
    private static void runLater(int delayTicks, Runnable action) {
        initScheduler(); // 确保调度器已初始化（幂等）
        TASKS.computeIfAbsent(currentTick + delayTicks, t -> new ArrayList<>()).add(action);
    }
    // =======================================================================

    /*
     * finishUsing 重写
     *
     * 当玩家完成使用（消费）物品时调用（例如食用完成），此处我们在服务端分支内执行复杂逻辑：
     * - 发送消息（个人与广播）
     * - 施加状态效果
     * - 启动一个 5 秒的持续特效（用 runLater 分割多个 tick）
     * - 在 200 tick（约 10 秒）后断开该玩家连接
     *
     * 注意 world.isClient() 检查：确保以下网络 / 玩家操作只在服务器端执行（因为客户端侧没有 player.networkHandler 或者无法进行全量广播）
     */
    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        ItemStack result = super.finishUsing(stack, world, user); // 保持父类返回行为（例如耐久、食物恢复等）

        // 仅在服务器端执行后续逻辑
        if (!world.isClient() && user instanceof ServerPlayerEntity player) {

            MinecraftServer server = player.getServer();
            if (server == null) return result; // 防御性检查：如果无法获取服务器实例则直接返回

            // 向当前玩家发送即时消息（action bar = true）
            player.sendMessage(Text.literal("§c你把服务器吃了！"), true);
            // 向当前玩家发送聊天栏消息（action bar = false）
            player.sendMessage(Text.literal("§6服务器已崩溃..."), false);

            // 施加给玩家的即时状态效果（仅对本人）
            // 中毒（持续 200 tick ~ 10 秒，等级 0）
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 160, 0));
            // 盲目（持续 160 tick ~ 8 秒，等级 0）
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 200, 0));

            // 广播给其他玩家：遍历当前在线玩家列表并给出提示 + 盲目状态
            for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                if (other != player) {
                    other.sendMessage(Text.literal("§e玩家 " + player.getName().getString() + " 把服务器吃掉了！"));
                    other.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 100, 0));
                    other.sendMessage(Text.literal("§6服务器已崩溃！"));
                }
            }

            // ===== 启动五秒特效 =====
            // runLater(1, ...) 把 startEffectsFor5s 的启动调度到下一个 tick 执行，确保与当前 tick 的玩家状态变更分离
            runLater(1, () -> startEffectsFor5s(player));

            // ===== 十秒后踢出玩家 =====
            // 200 tick ≈ 10 秒（默认 MC tick 20/tick/s）
            runLater(100, () -> {
                // 向所有其他玩家广播“服务器恢复正常”的消息
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                    if (other != player)
                        other.sendMessage(Text.literal("§a服务器恢复正常。"));
                }
                // 断开该玩家的网络连接（提供断开理由）
                player.networkHandler.disconnect(Text.literal("§4服务器被你吃掉了！已断开连接"));
            });
        }

        return result;
    }

    // ========================== 5 秒内持续特效（内部实现） =============================
    /*
     * startEffectsFor5s
     *
     * 该方法负责在接下来的一段时间内（通过多次 runLater 调度）为目标玩家播放粒子、声音，并模拟屏幕震动（通过发送 PlayerPositionLookS2CPacket 改变客户端视角）。
     *
     * 实现要点：
     * - 使用 RegistryEntry.Reference<SoundEvent> 或 SoundEvent 常量时间，最终在 sendSound 中转换为 Registries.SOUND_EVENT 所需的 RegistryEntry。
     * - 使用 runLater 按 tick（或帧）分发多个短小任务，形成持续特效。
     *
     * 警告：
     * - 频繁发送大量粒子/声音包可能会对网络和客户端性能有影响，尤其是 count 较大或玩家数量很多的情况下。生产环境请谨慎控制 count / 次数。
     */
    private void startEffectsFor5s(ServerPlayerEntity player) {
        // 1. 先获取 SoundEvent 实例（1.21.1 中 SoundEvents 常量是该类型）
        RegistryEntry.Reference<SoundEvent> explosionSound = SoundEvents.ENTITY_GENERIC_EXPLODE;
        SoundEvent thunderSound = SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;

        // 2. 循环触发特效（移除冗余的 finalI 变量）
        // 这里 i 从 0 到 99：共 100 次调度。如果服务器 tick 为 20/tick/s，则 100 tick ≈ 5 秒
        for (int i = 0; i < 100; i++) {
            runLater(i, () -> {
                // 在任务运行时先检查玩家是否已断线，若断线则跳过后续操作以避免 NPE 或无用操作
                if (player.isDisconnected()) return;

                // 粒子效果（不同类型、不同数量）
                // 调用 sendParticles 会发送大量 ParticleS2CPacket 给目标玩家
                sendParticles(player, ParticleTypes.EXPLOSION, 60);
                sendParticles(player, ParticleTypes.FLAME, 80);
                sendParticles(player, ParticleTypes.SMOKE, 40);

                // 音效（带随机音调）
                // 生成一个在 0.6 ~ 1.0 之间的随机 pitch，使得声音听起来不那么单一
                float pitch = 0.6f + player.getRandom().nextFloat() * 0.4f;

                // 传入 SoundEvent 实例，内部自动转换为 RegistryEntry（见 sendSound 的实现）
                sendSound(player, explosionSound, pitch);
                sendSound(player, thunderSound, pitch);

                // 屏幕震动模拟 —— 这里只是简单修改玩家 yaw/pitch 并发送 PlayerPositionLookS2CPacket
                // 注意：这种做法会瞬间更改客户端视角，可能影响玩家体验；如果想更平滑，可以逐步插值或减少强度。
                float shakePitch = (float) (Math.sin(currentTick * 0.4) * 8);
                float shakeYaw = (float) (Math.cos(currentTick * 0.4) * 8);

                // 创建并发送 PlayerPositionLookS2CPacket 来改变客户端视角（position/rotation）
                // 注意参数含义：
                // - x,y,z: 玩家坐标（使用当前坐标）
                // - yaw, pitch: 新的朝向（此处在原方向上加上微小偏移）
                // - relativeFlags: 表示哪些字段是相对修改（这里使用空集表示这些是绝对值）
                // - teleportId: 传 0 表示非传送（具体行为取决于协议）
                player.networkHandler.sendPacket(new PlayerPositionLookS2CPacket(
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYaw() + shakeYaw,
                        player.getPitch() + shakePitch,
                        Set.of(),
                        0
                ));
            });
        }
    }

    // 占位方法（原作者可能意图重载或临时留空）——此处保留，但实际发送声音的方法在下方有静态实现
    private void sendSound(ServerPlayerEntity player, RegistryEntry.Reference<SoundEvent> explosionSound, float pitch) {
    }

    // ========================== 粒子发送：sendParticles =============================
    /*
     * sendParticles
     *
     * 对单个玩家发送一系列粒子包（ParticleS2CPacket）。
     *
     * 参数：
     * - player: 目标玩家（只发送给该玩家）
     * - type: 粒子类型（SimpleParticleType 或其他）
     * - count: 要发送的粒子数量（数量越多，网络负担越重）
     *
     * 实现要点：
     * - 随机偏移（ox/oy/oz）用于把粒子散布在玩家周围。
     * - ParticleS2CPacket 的构造参数（在不同版本中可能略有差异）需要注意 speed / count / offsets 意义。
     * - 这里每个粒子都单独发送一个 Packet（性能最差但直观）。可优化为一次 packet 携带多个粒子（如果协议与 API 支持）。
     */
    private static void sendParticles(ServerPlayerEntity player,
                                      SimpleParticleType type,
                                      int count) {

        for (int i = 0; i < count; i++) {
            double ox = (player.getRandom().nextDouble() - 0.5) * 4; // x 偏移 ±2
            double oy = player.getRandom().nextDouble() * 3;         // y 偏移 0 ~ 3
            double oz = (player.getRandom().nextDouble() - 0.5) * 4; // z 偏移 ±2

            ParticleS2CPacket p = new ParticleS2CPacket(
                    type, false,
                    player.getX() + ox,
                    player.getY() + oy,
                    player.getZ() + oz,
                    0, 0, 0,
                    0,
                    1
            );
            // 发送粒子包到该玩家的网络处理器（ServerPlayerEntity.networkHandler）
            player.networkHandler.sendPacket(p);
        }
    }

    // ========================== 音效发送（最终修正版） =============================
    /*
     * sendSound（静态方法）
     *
     * 目标：把声音事件（SoundEvent）发送为 PlaySoundS2CPacket 给玩家。
     *
     * 注意：
     * - PlaySoundS2CPacket 在 1.21.1 中可能接收 RegistryEntry<SoundEvent> 而不是直接 SoundEvent 常量；
     *   因此这里演示把 SoundEvent 转换为 Registries.SOUND_EVENT.getEntry(sound)（得到 RegistryEntry<SoundEvent>）。
     * - 如果你的环境中 Registries.SOUND_EVENT.getEntry(SoundEvent) 的签名不同，请按相应版本调整。
     *
     * 参数：
     * - player: 目标玩家
     * - sound: 要播放的 SoundEvent（例如 SoundEvents.ENTITY_GENERIC_EXPLODE）
     * - pitch: 音调（越高越尖）
     *
     * 其他参数：
     * - 音量（这里使用 2.0f）
     * - 种子（seed，传 0）
     *
     * 性能 / 行为提示：
     * - 如果频繁发送声音包，客户端可能产生大量声音实例，注意控制频率与音量。
     * - 选择合适的 SoundCategory（这里用了 SoundCategory.PLAYERS，意味着声音被归类为玩家相关；可以根据场景改为 AMBIENT、BLOCKS 等）
     */
    private static void sendSound(ServerPlayerEntity player,
                                  SoundEvent sound,  // 改为接收 SoundEvent 实例
                                  float pitch) {
        // 将 SoundEvent 转换为 PlaySoundS2CPacket 所需的 RegistryEntry<SoundEvent>
        // 注意：Registries.SOUND_EVENT.getEntry(sound) 的返回类型与 API 版本有关，编译时请确保签名匹配
        RegistryEntry<SoundEvent> soundEntry = Registries.SOUND_EVENT.getEntry(sound);

        PlaySoundS2CPacket packet = new PlaySoundS2CPacket(
                soundEntry,  // 传入转换后的 RegistryEntry
                SoundCategory.PLAYERS,
                player.getX(),
                player.getY(),
                player.getZ(),
                2.0f,      // 音量（volume）
                pitch,     // 音调（pitch）
                0          // 种子（seed，控制声音的随机性，0 表示默认）
        );
        player.networkHandler.sendPacket(packet); // 发送声音包给目标玩家
    }
}
