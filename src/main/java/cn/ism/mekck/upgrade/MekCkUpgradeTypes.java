package cn.ism.mekck.upgrade;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import mekanism.common.util.UpgradeUtils;
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
     * <p>优先走 {@link IUpgradeItem}：卡片自身知道自己是哪张，O(1)，且对
     * 任何按 Mek 规矩实现的第三方卡片都成立。Mek 自己的 7 种卡片全部走这条
     * （{@code mekanism.common.item.ItemUpgrade implements IUpgradeItem}）。
     * 不实现该接口的物品再回退到逐个比对原生卡片。
     *
     * <p><b>回退只遍历 Mek 原生的 7 个常量，不能遍历 {@link #all()}</b>：
     * {@code UpgradeUtils.getStack} 是 javac 合成的 {@code switch (type.ordinal())}，
     * 映射表 {@code UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade} 只给
     * Mek 自己声明的 7 个常量填了值，注入常量的槽位保持 0。实测：
     * <pre>
     *   $ javap -p -c mekanism.common.util.UpgradeUtils
     *     public static net.minecraft.world.item.ItemStack getStack(mekanism.api.Upgrade, int);
     *         Code:
     *            0: getstatic     #30  // Field UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade:[I
     *            3: aload_0
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
     *
     *   $ javap -p -c "mekanism.common.util.UpgradeUtils$1"
     *     static {};
     *         ...
     *         0: invokestatic  #19  // Method mekanism/api/Upgrade.values:()[Lmekanism/api/Upgrade;
     *         3: arraylength
     *         4: newarray       int
     *         6: putstatic      #21  // Field $SwitchMap$mekanism$api$Upgrade:[I
     *         9: getstatic      #21  // Field $SwitchMap$mekanism$api$Upgrade:[I
     *        12: getstatic      #25  // Field mekanism/api/Upgrade.SPEED:Lmekanism/api/Upgrade;
     *        15: invokevirtual #29  // Method mekanism/api/Upgrade.ordinal:()I
     *        18: iconst_1
     *        19: iastore
     *         ...
     * </pre>
     * 数组长度取自那一刻的 {@code values().length}，而只有 7 个 {@code iastore}
     * 分别写入 {@code Upgrade.SPEED} 之类的原生常量，注入常量的槽位恒为 0。
     * 换句话说，{@code getStack} 对注入常量（ordinal ≥ 7）会<b>抛异常而不是返回空栈</b>：
     * 映射表若在注入之后初始化是 {@code IncompatibleClassChangeError}（落 default），
     * 若在注入之前初始化则数组长度不够、{@code iaload} 越界是
     * {@code ArrayIndexOutOfBoundsException}——<b>两种都取决于加载顺序</b>。
     * 而 {@link #byItem} 的入参是「槽位里的任意物品」，普通物品必然走到回退循环，
     * 于是每一次非升级物品的查询都会崩。原生卡片本来就走 {@code IUpgradeItem}，
     * 这层回退只是兜底，按原生清单遍历即可。
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
        for (Upgrade type : nativeUpgrades()) {
            if (stack.is(UpgradeUtils.getStack(type, 1).getItem())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code UpgradeUtils.getStack} 的 {@code tableswitch} 认识的那 7 个常量。
     *
     * <p><b>不要缓存成 static final 字段</b>：那会把 {@code Upgrade.values()} 的取值
     * 钉死在本类首次加载的瞬间。若那次加载发生在 {@code Upgrade.<clinit>} 执行途中
     * （例如被某个 Mixin 的 TAIL 触达），拿到的就是还没被追加完的半成品数组，
     * 且此后永不更新——正是 {@link MekCkUpgradeCodec#byName} 注释里要避开的那类时序。
     * 这里每次现造数组，代价可以忽略（本方法只在非 {@code IUpgradeItem} 的物品上才走到）。
     */
    private static Upgrade[] nativeUpgrades() {
        return new Upgrade[]{
                Upgrade.SPEED, Upgrade.ENERGY, Upgrade.FILTER, Upgrade.GAS,
                Upgrade.MUFFLING, Upgrade.ANCHOR, Upgrade.STONE_GENERATOR
        };
    }

    /**
     * 某等级是否接受该升级类型。
     *
     * <p>MekCK 自有的两种卡对所有工厂家族开放；Mek 原生类型只接受
     * {@code MekckConfig} 参与配置的三种（速度/能量/存储），其余留给 Mek 自己的
     * 机器语义，避免装上无效果的卡。
     *
     * <p><b>本方法目前不看 {@code tier}</b>（全档位同一答案，见上面的枚举）。
     * 因此 {@code SINGULARITY} 也会被判为接受存储卡，尽管
     * {@code MekckConfig.getFactoryStackUpgradeMax} 在该档返回 0
     * （{@code CuttingMachineFactoryTier#supportsStackUpgrade} 明确排除它），
     * 最终由 {@link #capOf(Upgrade, CuttingMachineFactoryTier)} 把上限裁到 0 兜住。
     * 若将来要让「接受」本身也随档位变化，在这里补分支。
     */
    public static boolean isSupportedBy(Upgrade type, CuttingMachineFactoryTier tier) {
        return type == MekCkUpgradeRefs.storage()
                || type == MekCkUpgradeRefs.randomize()
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

    /** 解码，按 {@link #capOf(Upgrade)} 裁剪。 */
    public static MekCkUpgradeCodec.Decoded<Upgrade> decode(CompoundTag tag) {
        return MekCkUpgradeCodec.decode(tag, MekCkUpgradeTypes::resolve, MekCkUpgradeTypes::capOf);
    }

    /** 编码，带上无法解析的原始条目以免丢失。 */
    public static CompoundTag encode(Map<Upgrade, Integer> known, List<CompoundTag> unknownRaw) {
        return MekCkUpgradeCodec.encode(known, MekCkUpgradeTypes::nameOf, unknownRaw);
    }
}
