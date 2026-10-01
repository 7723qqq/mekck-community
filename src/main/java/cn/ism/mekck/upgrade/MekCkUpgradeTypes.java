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
import cn.ism.mekck.CuttingMachineFactoryTier;

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
 * {@code TileComponentUpgrade#supports(Upgrade)}（单参版，实测无 {@code Set} 重载）
 * 是两套独立的准入判断，本类不参与后者——后者是 Task 6 的 Mixin 要接的线。
 *
 * <p>读档路径上 Mek 自己还有第三处裁剪：{@code Upgrade.buildMap} 对每个条目做
 * {@code Mth.clamp(getInt("amount"), 0, maxStack)}，同样以 {@code getMax()} 为上界。
 * 也就是说本类的 {@code capOf} 裁剪与 Mek 的行为同构，而不是在它之外另加一道闸。
 *
 * <h3>准入闸门为什么落在 {@code capOf}</h3>
 * 读档路径上没有 {@code TileComponentUpgrade.supports()} 关卡，
 * {@code decode} 的结果就是最终生效值——所以准入必须在这里做，
 * 否则手改存档能把任意 Upgrade 塞进机器。
 * <p><b>「保留未知条目」与「准入过滤」是两件事</b>：
 * 前者（{@code unknownRaw}）针对<b>解析不出常量</b>的注入者缺席情形，
 * 后者针对<b>能解析但本机不接受</b>的类型。
 * 两者分别由 {@link MekCkUpgradeCodec} 与 {@link #capOf(Upgrade, CuttingMachineFactoryTier)}
 * 承担，不要互相顶替：把 FILTER / GAS / ANCHOR 之类判成「未知条目」会让它们在存档里
 * 长期残留并被原样回写，把它们判成「已知且为 0」才是本机的真实语义。
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
     * MekCK 机器基类的<b>类名</b>——升级持久化 Mixin 沿类链按名字找的唯一目标。
     *
     * <p>这是一条<b>不可改的跨文件契约</b>（与 {@code MekCkMachineTile} 类注释里那条同源）：
     * 改类名/包名会让 {@link #isMekCkOwnedTile(Class)} 恒判 false，于是 MekCK 工厂的升级
     * 持久化退回 Mek 原生 ordinal 编解码——<b>不报错、不写日志</b>，只是存储卡的数量键
     * 静默变回 ordinal 语义。{@code TestUpgradePersistenceOwnership} 钉住了这个字符串。
     */
    public static final String MEKCK_TILE_CLASS_NAME = "cn.ism.mekck.machine.MekCkMachineTile";

    /**
     * 该 tile 类是否由 MekCK 的机器体系托管（= 类链上存在 {@link #MEKCK_TILE_CLASS_NAME}）。
     *
     * <h3>为什么必须按类链判定，而不是「tier 是否为 null」</h3>
     * {@code MixinTileComponentUpgradePersistence.mekck$tier()} 返回 null 有<b>两种</b>成因：
     * <ol>
     *   <li>不是 MekCK 的机器（Mek 自家的机器、第三方挂 {@code TileComponentUpgrade} 的机器）；</li>
     *   <li><b>是</b> MekCK 的机器，但反射调 {@code getTier()} 抛 {@code ReflectiveOperationException}，
     *       或组件尚未挂上 tile（{@code tile == null}）。</li>
     * </ol>
     * 两者要采取<b>相反</b>的处理：前者必须把持久化整个交回 Mek——否则 Mek 机器上的
     * MUFFLING / FILTER / GAS / ANCHOR / STONE_GENERATOR 会因
     * {@link #capOf(Upgrade, CuttingMachineFactoryTier)} 返回 0 而被 {@code decode} 丢弃，
     * 并在下一次存档时从 NBT 永久消失；后者仍应使用 MekCK 的名字键，只是按「档位未知」裁剪。
     * 用 {@code tier != null} 当判据会把第 2 类误判成第 1 类。
     *
     * <p>沿 {@code getSuperclass()} 逐级比较而不是只比直接父类：阶段 2/3 的机器 tile 都是
     * {@code MekCkMachineTile} 的中间子类（{@code CookingFactoryTile} 等），只比直接父类会全部落空。
     *
     * @param tileClass 目标类，{@code null} 视为「不是 MekCK 机器」
     */
    public static boolean isMekCkOwnedTile(Class<?> tileClass) {
        return isMekCkOwnedTile(tileClass, MEKCK_TILE_CLASS_NAME);
    }

    /**
     * 沿类链按名字找基类。
     *
     * <p>把「要找的名字」参数化的唯一理由是<b>可测</b>：普通 JUnit 里加载不了
     * {@code cn.ism.mekck.machine.MekCkMachineTile}（它需要游戏环境），所以测试用本地嵌套类
     * 造一条真实继承链来验证比较逻辑。
     *
     * @param baseClassName 目标基类全限定名；{@code null} 返回 false
     */
    static boolean isMekCkOwnedTile(Class<?> tileClass, String baseClassName) {
        if (tileClass == null || baseClassName == null) {
            return false;
        }
        for (Class<?> c = tileClass; c != null; c = c.getSuperclass()) {
            if (baseClassName.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 存储卡倍增系数 = {@code 2^min(已安装数, 本档上限)}，再被
     * 「基础并行 × 倍增 ≤ 配置允许的最大并行」钳一次。
     *
     * <h3>为什么住在升级类里，而不是各家族执行器各写一份</h3>
     * 阶段 3 Task 1（研磨工厂）是第一个<b>第二个</b>需要它的家族：旧
     * {@code GrindingFactoryBlockEntity.getStackMultiplier()} 与切菜执行器里那份是
     * 同一段算术的两份拷贝。两份一旦漂移，表现是「并行数与耗电量对不上」——
     * 因为耗电公式里也要乘这个系数，而它<b>不报错、不留日志</b>。
     * 而「存储卡倍增」本来就是升级体系的产物（{@link #capOf} 是它的上限闸门），
     * 放这里与 {@link #capOf} 同属一处，升级语义不再散落在 6 个家族里。
     *
     * <p>抽成<b>纯静态、4 个入参全裸</b>（不接 {@code MekckConfig}）的原因同
     * {@code MekCkMachineTile#gatedEnergyCost}：{@code MekckConfig} 在裸 JVM 里加载即抛，
     * 真 tile 也造不出来，所以这段算术必须能被普通 JUnit 直接跑。
     *
     * @param installed  已装的存储卡张数（读 {@code TileComponentUpgrade.getUpgrades}）
     * @param cap        本档安装上限（{@code capOf(storage, tier)}）
     * @param base       基础并行（{@code MekckConfig.getMultithreadedBase}）
     * @param maxParallel 配置允许的最大并行（{@code MekckConfig.getMultithreadedMax}）
     * @return 恒为正；无卡 / 基础并行已追平上限时返回 1
     */
    public static int stackMultiplier(int installed, int cap, int base, int maxParallel) {
        if (base <= 0 || base >= maxParallel) {
            // base 为 0 时下面的 maxParallel / base 会除零；base >= maxParallel 时已经追平上限。
            return 1;
        }
        int maxMult = maxParallel / base;
        // 上限再钳一道 30：Java 的移位按 mod 32 处理，1<<31 是负数、1<<32 直接绕回 1，
        // 后者会让「装满卡」静默变成「不倍增」。当前 STORAGE 的 getMax() 是 6，走不到这里，
        // 但这段算术已经被提成可单测的纯函数，将来上限调大时不会有人记得回来补。
        int safeCap = Math.min(cap, 30);
        int raw = 1 << Math.min(installed, safeCap);
        return Math.min(raw, Math.max(1, maxMult));
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
     * 无等级概念的场合用：等价于 {@code capOf(type, null)}，仍走准入闸门。
     *
     * <p>{@code tier == null} 时 {@link #isSupportedBy} 对存储卡判 {@code false}
     * （缺依据即不放行），其余按 {@code getMax()} 返回。
     */
    public static int capOf(Upgrade type) {
        return capOf(type, null);
    }

    /**
     * 某升级类型在某等级的安装上限。
     *
     * <p><b>先过准入闸门，再谈数量</b>：{@link #isSupportedBy} 不接受的类型一律返回 0。
     * 原因是读档路径（{@code TileComponentUpgrade} 的
     * {@code lambda$read$1} = {@code upgrades.clear(); upgrades.putAll(decode 结果)}）
     * 中间没有任何 {@code supports()} 检查，{@link #decode} 的结果就是最终生效值。
     * 若这里直接落进「取 {@code getMax()}」的分支，FILTER / GAS / ANCHOR /
     * STONE_GENERATOR 这些本机不接受的类型会被手改存档原样装进 EnumMap，
     * 绕开「避免装上无效果的卡」这个设计意图。
     *
     * @return 0（不接受），否则永远在 {@code [1, type.getMax()]} 区间——
     *         这是 Mek 那两处 {@code getMax()} 检查不被触发的条件
     */
    public static int capOf(Upgrade type, CuttingMachineFactoryTier tier) {
        if (!isSupportedBy(type, tier)) {
            return 0;
        }
        if (type == MekCkUpgradeRefs.storage()) {
            return Math.max(0, Math.min(MekckConfig.getFactoryStackUpgradeMax(tier), type.getMax()));
        }
        return type.getMax();
    }

    /**
     * 解码：<b>不</b>按 MekCK 的按等级配置裁剪，只走准入闸门 + {@link Upgrade#getMax()}。
     *
     * <p>等价于 {@code decode(tag, null)}。仅供「确实没有档位概念」的场合使用；
     * <b>凡是能拿到档位的地方（方块实体读档）一律用
     * {@link #decode(CompoundTag, CuttingMachineFactoryTier)}</b>：
     * {@code tier == null} 时 {@link #isSupportedBy} 对存储卡判 {@code false}
     * （缺依据即不放行），存档里的存储卡会被整条丢弃；
     * 拿得到档位时它才会按 {@link #capOf(Upgrade, CuttingMachineFactoryTier)}
     * 读到该档位的真实上限。
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
