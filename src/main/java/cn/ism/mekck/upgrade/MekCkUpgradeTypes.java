package cn.ism.mekck.upgrade;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MekCkUpgradeCodec} 与 {@link Upgrade} 的绑定层，同时是升级体系的聚合入口。
 *
 * <h3>为什么单独一层</h3>
 * {@code Upgrade} 的静态初始化链
 * （{@code Upgrade → EnumColor → DyeColor → ItemTags → Registries}）在无游戏环境的
 * 普通 JVM 里必然抛 {@code ExceptionInInitializerError}，
 * {@code Bootstrap.bootStrap()} 同样失败（实测：需要 {@code Util.fetchChoiceType}
 * 与完整注册表）。因此泛型化的 codec 核心可以在普通 JUnit 里完整覆盖，
 * 而这一层只能在游戏内加载——本类<b>不含任何普通 JUnit 测试</b>，
 * 正确性由 Task 6 的 Mixin 与阶段 2 的 GameTest 兜底。
 *
 * <h3>all() 覆盖全部注入者</h3>
 * {@link #all()} 直接返回 {@code Upgrade.values()}。注入是在
 * {@code Upgrade.<clinit>} 的 TAIL 做的，而任何触达 {@code Upgrade} 的代码
 * 都会先跑完它的 {@code <clinit>}，所以拿到的必然是「Mek 原生 + 所有已加载注入者
 * 追加的」完整集合——不依赖任何注入顺序假设。
 *
 * <h3>上限裁定</h3>
 * Mek 在两处硬卡 {@code Upgrade.getMax()}。实测字节码（MC 1.20.1，
 * jar {@code mekanism-268560-6018299_mapped_official_1.20.1.jar}）：
 * <pre>
 *   $ javap -p -c mekanism.common.tile.component.TileComponentUpgrade
 *     public void tickServer();
 *         ...
 *        50: invokevirtual #114  // Method getUpgrades:(Lmekanism/api/Upgrade;)I
 *        53: aload_3
 *        54: invokevirtual #118  // Method mekanism/api/Upgrade.getMax:()I
 *        57: if_icmpge     127
 *
 *     public int addUpgrades(mekanism.api.Upgrade, int);
 *         ...
 *         8: invokevirtual #118  // Method mekanism/api/Upgrade.getMax:()I
 *        11: if_icmpge     81
 *        14: aload_1
 *        15: invokevirtual #118  // Method mekanism/api/Upgrade.getMax:()I
 *        18: iload_3
 *        19: isub
 *        20: iload_2
 *        21: invokestatic  #180  // Method java/lang/Math.min:(II)I
 * </pre>
 * {@code tickServer} 里那处是 {@code getUpgrades(type) < getMax()} 才推进读条，
 * 另一处是 {@code Math.min(getMax() - getUpgrades(type), amount)} 决定实际装几个。
 * 只要本类返回的上限<b>永远不超过 {@code getMax()}</b>，两处检查就自动满足，
 * 不需要额外的 Mixin。代价是 MekCK 的配置值不能突破枚举自带的 {@code maxStack}——
 * 对 Mek 原生类型这是对的（那是 Mek 的平衡）；对 MekCK 自注入的类型无影响，
 * 因为它们的 {@code maxStack} 由 Task 4 自己指定。
 *
 * <p><b>注意</b>：MekCK 的 {@link #isSupportedBy} 与 Mek 的
 * {@code TileComponentUpgrade#supports(Set&lt;Upgrade&gt;)} 是两套独立的准入判断，
 * 本类不参与后者——后者是 Task 6 的 Mixin 要接的线。
 *
 * <p>读档路径上 Mek 自己还有第三处裁剪：{@code Upgrade.buildMap} 对每个条目做
 * {@code Mth.clamp(getInt("amount"), 0, maxStack)}，同样以 {@code getMax()} 为上界。
 * 也就是说本类的 {@code capOf} 裁剪与 Mek 的行为同构，而不是在它之外另加一道闸。
 */
public final class MekCkUpgradeTypes {

    private MekCkUpgradeTypes() {
    }

    /** 当前已加载的全部升级常量：Mek 原生 7 种 + 所有已加载注入者追加的。 */
    public static List<Upgrade> all() {
        return List.of(Upgrade.values());
    }

    /** 按 {@code getRawName()} 反查。找不到（注入者缺席）返回 {@code null}。 */
    public static Upgrade resolve(String rawName) {
        return MekCkUpgradeCodec.byName(all(), Upgrade::getRawName, rawName);
    }

    /**
     * 存档里存的字符串名。
     *
     * <p><b>这是 MekCK 自己的存档键，与 Mek 的升级持久化无关。</b>
     * Mek 自己按 {@code ordinal()} 存（{@code getTag} 写 {@code putInt("type", ordinal())}，
     * {@code buildMap} 读 {@code byIndexStatic}），
     * {@code getRawName()} 在全 jar 里只有 {@code Upgrade} 自身与
     * {@code MekanismItems} 引用，后者在 {@code registerUpgrade} 里把它与字面量
     * {@code "upgrade_"} 拼出物品注册 id {@code mekanism:upgrade_<rawName>}。
     * 不要按「Mek 按 rawName 存存档」去推理，详见 {@link MekCkUpgradeRefs} 的类注释。
     */
    public static String nameOf(Upgrade type) {
        return type.getRawName();
    }

    /**
     * 升级物品 → 升级常量。
     *
     * <p><b>设计约束：升级物品一律经 {@link IUpgradeItem} 识别，没有第二条路。</b>
     * 这不是省事，而是被 Mek 自己的代码逼出来的：
     *
     * <p>① Mek 的 7 张原生卡全部走这条。实测
     * {@code mekanism.common.item.ItemUpgrade implements IUpgradeItem}，
     * 且 {@code MekanismItems.registerUpgrade} 的 supplier 就是
     * {@code new ItemUpgrade(upgrade, properties)}。
     *
     * <p>② <b>不实现该接口的卡，Mek 自己也不认。</b>
     * {@code UpgradeInventorySlot} 的输入槽判定就是一把「非 {@code IUpgradeItem} 即拒绝」：
     * <pre>
     *   $ javap -p -c mekanism.common.inventory.slot.UpgradeInventorySlot
     *     private static boolean lambda$input$0(java.util.Set, net.minecraft.world.item.ItemStack, mekanism.api.AutomationType);
     *         Code:
     *            6: instanceof    #137  // class mekanism/common/item/interfaces/IUpgradeItem
     *            9: ifeq          37
     *           ...
     *           21: invokeinterface #143 // InterfaceMethod .../IUpgradeItem.getUpgradeType:(...)Lmekanism/api/Upgrade;
     *           ...
     *           31: invokeinterface #148 // InterfaceMethod java/util/Set.contains:(Ljava/lang/Object;)Z
     *           37: iconst_0
     *           38: ireturn              // 不是 IUpgradeItem → 一律拒绝
     * </pre>
     * {@code TileComponentUpgrade.tickServer()} 同样先
     * {@code instanceof IUpgradeItem} 再取 type。
     * 所以「能装进 Mek 升级槽」与「实现 {@code IUpgradeItem}」是同一条线，
     * 本方法没有额外能捞回来的东西。
     *
     * <p><b>不要试图用 {@code UpgradeUtils.getStack(type, n)} 反查</b>：
     * 它不是查表，而是 javac 合成的 {@code switch (type.ordinal())}，
     * 索引 {@code UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade}。
     * 该表只给 Mek 自己声明的 7 个常量填了槽位，<b>注入常量的槽位恒为 0</b>，
     * 于是落进 {@code default} 分支。实测：
     * <pre>
     *   $ javap -p -c mekanism.common.util.UpgradeUtils
     *     public static net.minecraft.world.item.ItemStack getStack(mekanism.api.Upgrade, int);
     *         Code:
     *            0: getstatic     #30  // Field UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade:[I
     *            ...
     *            4: invokevirtual #34  // Method mekanism/api/Upgrade.ordinal:()I
     *            7: iaload
     *            8: tableswitch   { // 1 to 7
     *                        1: 60
     *                        ...
     *                        7: 120
     *                   default: 52
     *                      }
     *           52: new           #36  // class java/lang/IncompatibleClassChangeError
     *           55: dup
     *           56: invokespecial #37  // Method java/lang/IncompatibleClassChangeError."&lt;init&gt;":()V
     *           59: athrow
     * </pre>
     * 也就是<b>对注入常量抛 {@code IncompatibleClassChangeError} 而不是返回空栈</b>
     * （若该映射表在注入之前初始化，数组长度不够，{@code iaload} 越界则是
     * {@code ArrayIndexOutOfBoundsException}——两种都取决于加载顺序）。
     * 这正是上一版「按原生 7 常量回退」被换成「完全不回退」的原因：
     * 原生常量本来就走 {@code IUpgradeItem}，而唯一能被回退「救回来」的
     * 第三方卡，恰好就是会让 {@code getStack} 崩的那一批。
     *
     * <p><b>不要记日志</b>：本方法会被槽位校验逐 tick 调用，普通物品查不到是常态。
     */
    public static Optional<Upgrade> byItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.getItem() instanceof IUpgradeItem upgradeItem) {
            return Optional.ofNullable(upgradeItem.getUpgradeType(stack));
        }
        return Optional.empty();
    }

    /**
     * 某等级是否接受该升级类型。
     *
     * <p>MekCK 自有的两种卡对所有工厂家族开放；Mek 原生类型只接受
     * {@code MekckConfig} 参与配置的三种（速度/能量/存储），其余留给 Mek 自己的
     * 机器语义，避免装上无效果的卡。
     *
     * <p><b>存储卡是唯一随档位变化的分支，判据只能取
     * {@link CuttingMachineFactoryTier#supportsStackUpgrade()}</b>：
     * 它是「本档位是否有倍增资格」的<b>唯一权威定义</b>，明确排除 {@code SINGULARITY}。
     * <b>不要在这里另写一遍 {@code ordinal()} 或 {@code processes >= N} 的推导</b>——
     * 历史上正是这类重复推导导致各机器对「哪些档能叠」的判断互相矛盾。
     *
     * <p>{@code SINGULARITY} 因此直接判 false，而不是像旧写法那样判 true 再靠
     * {@link #capOf(Upgrade, CuttingMachineFactoryTier)} 把上限裁到 0 兜底：
     * 「不接受」与「能装但上限为 0」是两件事，槽位校验要的是前者。
     *
     * <p>{@code RANDOMIZE} / {@code SPEED} / {@code ENERGY} 与档位无关：
     * 随机化是全局特性；速度与能量的<b>数量</b>由档位配置裁剪（见
     * {@link #capOf(Upgrade, CuttingMachineFactoryTier)}），资格不在这里拦。
     *
     * @param tier {@code null} 表示「不知道档位」。此时存储卡判 {@code false}——
     *              宁可漏判为不接受，也不要在缺依据时放过一档没有倍增资格的机器。
     */
    public static boolean isSupportedBy(Upgrade type, CuttingMachineFactoryTier tier) {
        if (type == MekCkUpgradeRefs.storage()) {
            return tier != null && tier.supportsStackUpgrade();
        }
        return type == MekCkUpgradeRefs.randomize()
                || type == Upgrade.SPEED
                || type == Upgrade.ENERGY;
    }

    /**
     * 无等级概念的场合用：直接取枚举自带上限。
     *
     * <p>没有配置值可裁时，{@code getMax()} 就是「{@code min(配置值, getMax())}」
     * 在缺配置那一侧的取值，仍然满足不超过 {@code getMax()} 的不变式。
     */
    public static int capOf(Upgrade type) {
        return type.getMax();
    }

    /**
     * 某升级类型在某等级的安装上限。
     *
     * @return 永远在 {@code [0, type.getMax()]} 区间——这是 Mek 那两处检查不被触发的条件
     */
    public static int capOf(Upgrade type, CuttingMachineFactoryTier tier) {
        if (tier == null) {
            return type.getMax();
        }
        int configured;
        if (type == MekCkUpgradeRefs.storage()) {
            configured = MekckConfig.getFactoryStackUpgradeMax(tier);
        } else if (type == Upgrade.SPEED) {
            configured = MekckConfig.getFactorySpeedUpgradeMax(tier);
        } else if (type == Upgrade.ENERGY) {
            configured = MekckConfig.getFactoryEnergyUpgradeMax(tier);
        } else {
            return type.getMax();
        }
        return Math.max(0, Math.min(configured, type.getMax()));
    }

    /**
     * 解码，<b>不</b>按 MekCK 配置裁剪，只按 {@link Upgrade#getMax()} 裁。
     *
     * <p>等价于 {@code decode(tag, null)}。仅供「确实没有档位概念」的场合使用；
     * <b>凡是能拿到档位的地方（方块实体读档）一律用
     * {@link #decode(CompoundTag, CuttingMachineFactoryTier)}</b>，
     * 否则不支持倍增的档位会把存档里的存储卡数量读成 {@code getMax()}（6），
     * 而不是它在 MekCK 配置里真正的上限。
     */
    public static MekCkUpgradeCodec.Decoded<Upgrade> decode(CompoundTag tag) {
        return decode(tag, null);
    }

    /**
     * 解码，按 {@link #capOf(Upgrade, CuttingMachineFactoryTier)} 裁剪。
     *
     * <p>读档与运行期走<b>同一条</b>裁剪契约
     * {@code min(MekCK 配置的按等级上限, type.getMax())}，
     * 这样存档里的数量在进内存的那一刻就已经是合法的，不会出现
     * 「读进来一堆、再被 tick 逻辑逐个削掉」的中间态。
     *
     * @param tier {@code null} 时回落到 {@link #capOf(Upgrade)}（不按配置裁）
     */
    public static MekCkUpgradeCodec.Decoded<Upgrade> decode(CompoundTag tag, CuttingMachineFactoryTier tier) {
        return MekCkUpgradeCodec.decode(tag, MekCkUpgradeTypes::resolve, type -> capOf(type, tier));
    }

    /** 编码，带上无法解析的原始条目以免丢失。 */
    public static CompoundTag encode(Map<Upgrade, Integer> known, List<CompoundTag> unknownRaw) {
        return MekCkUpgradeCodec.encode(known, MekCkUpgradeTypes::nameOf, unknownRaw);
    }
}
