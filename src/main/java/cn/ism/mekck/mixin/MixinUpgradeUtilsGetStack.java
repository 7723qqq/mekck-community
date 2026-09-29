package cn.ism.mekck.mixin;

import mekanism.api.Upgrade;
import mekanism.common.item.interfaces.IUpgradeItem;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给 {@link UpgradeUtils#getStack(Upgrade, int)} 补上注入型升级的支持。
 *
 * <h3>为什么必须有这个 Mixin</h3>
 * {@code getStack} 是 javac 生成的 {@code switch(type.ordinal())}。实测
 * {@code javap -p -c -s mekanism.common.util.UpgradeUtils}（jar
 * {@code mekanism-268560-6018299_mapped_official_1.20.1.jar}）原文：
 * <pre>
 *   public static net.minecraft.world.item.ItemStack getStack(mekanism.api.Upgrade, int);
 *       descriptor: (Lmekanism/api/Upgrade;I)Lnet/minecraft/world/item/ItemStack;
 *       Code:
 *          0: getstatic     #30  // Field UpgradeUtils$1.$SwitchMap$mekanism$api$Upgrade:[I
 *          3: aload_0
 *          4: invokevirtual #34  // Method mekanism/api/Upgrade.ordinal:()I
 *          7: iaload
 *          8: tableswitch   { // 1 to 7
 *                       1: 60
 *                       2: 70
 *                       3: 80
 *                       4: 90
 *                       5: 100
 *                       6: 110
 *                       7: 120
 *                  default: 52
 *                     }
 *         52: new           #36  // class java/lang/IncompatibleClassChangeError
 *         55: dup
 *         56: invokespecial #37  // Method java/lang/IncompatibleClassChangeError."&lt;init&gt;":()V
 *         59: athrow
 * </pre>
 * 而 {@code UpgradeUtils$1.<clinit>} 只给 7 个原生常量赋了槽位
 * （实测逐条 {@code iastore}：{@code SPEED→1, ENERGY→2, FILTER→3, MUFFLING→4,
 * GAS→5, ANCHOR→6, STONE_GENERATOR→7}）。<b>注入常量的槽位恒为 0</b> →
 * 落 {@code default} → 抛 {@code IncompatibleClassChangeError}。
 *
 * <p>触达路径不止一处：{@code TileComponentUpgrade.removeUpgrade(Upgrade,boolean)}
 * 在偏移 28 与偏移 115 各调它一次，而 {@code removeUpgrade} 的唯一外部调用者是
 * {@code mekanism.common.network.to_server.PacketGuiInteract$GuiInteraction}
 * （扫全 jar 常量池，全 jar 只有它与 {@code TileComponentUpgrade} 引用
 * {@code removeUpgrade}）——<b>即玩家在 Mek 升级界面点「移除」会崩服务端线程。</b>
 *
 * <h3>为什么用 HEAD + cancellable 而不是 @Redirect</h3>
 * 原实现对 7 个原生常量是正确的，只需在<b>它会抛之前</b>拦下注入常量即可，
 * 不必重写整张表。用 {@code @Redirect} 反而要把 7 个 case 复制一遍，
 * 而 {@code getStack(Upgrade)} 单参重载内部也是调本方法，重写两处更易漏。
 *
 * <h3>为什么用 {@code ordinal()} 判「是不是原生」</h3>
 * {@code Upgrade} 的原生 7 个常量按声明顺序是
 * {@code SPEED, ENERGY, FILTER, GAS, MUFFLING, ANCHOR, STONE_GENERATOR}
 * （实测 {@code javap -p mekanism.api.Upgrade} 的静态字段声明顺序），
 * 所以 {@code STONE_GENERATOR} 是原生里的最后一个、ordinal 恒为 6；
 * 任何注入者只能追加到数组末尾，ordinal 必然 &gt; 6。
 * <b>不要改成比较 {@code values().length}</b>——那会把别的注入者的常量一并算成原生。
 *
 * <h3>为什么扫注册表而不是只认 MekCK 自己的两张卡</h3>
 * 同样的雷对<b>所有</b>注入型 mod 都成立（Mekanism Extras 的 STACK 同样中招）。
 * 按 {@code IUpgradeItem.getUpgradeType} 反查注册表，能让任何遵循 Mek 规矩的
 * 第三方升级卡也拿到正确物品。
 */
@Mixin(value = UpgradeUtils.class, remap = false)
public abstract class MixinUpgradeUtilsGetStack {

    @Inject(method = "getStack(Lmekanism/api/Upgrade;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true)
    private static void mekck$handleInjectedUpgrades(Upgrade type, int count,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        if (type == null || count <= 0) {
            return;
        }
        // 7 个原生常量交给原实现——它是对的
        if (type.ordinal() <= Upgrade.STONE_GENERATOR.ordinal()) {
            return;
        }
        Item item = findItemFor(type);
        if (item != null) {
            cir.setReturnValue(new ItemStack(item, count));
        }
        // 找不到就放行给原实现（它会抛）。不要用空栈掩盖——
        // 空栈会让 removeUpgrade 静默扣掉数量却不给回物品。
    }

    /**
     * 按 {@code IUpgradeItem.getUpgradeType} 在物品注册表里反查该升级类型的物品。
     *
     * <p>{@code IUpgradeItem#getUpgradeType(ItemStack)} 是 Mek 识别升级卡的<b>唯一</b>入口：
     * {@code TileComponentUpgrade.tickServer} 与
     * {@code UpgradeInventorySlot.input} 都是先 {@code instanceof IUpgradeItem} 再取 type，
     * 不实现该接口的卡 Mek 自己也不认。
     *
     * <p>只在 {@code type.ordinal() &gt; 6} 的分支里被调用，不在槽位校验的逐 tick 路径上，
     * 因此整表扫描的开销可以接受。
     */
    @Unique
    private static Item findItemFor(Upgrade type) {
        for (Item item : ForgeRegistries.ITEMS) {
            if (item instanceof IUpgradeItem upgradeItem
                    && upgradeItem.getUpgradeType(new ItemStack(item)) == type) {
                return item;
            }
        }
        return null;
    }
}
