package cn.ism.mekck.client;

import cn.ism.mekck.menu.IUpgradeMenu;
import mekanism.api.text.EnumColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 本模组机器支持的升级类型（用于 Mekanism 风格升级窗口的列表/信息/槽位绑定）。
 * 颜色与 Mekanism 的 Upgrade 枚举一致：速度=红、能量=亮绿、气体=黄；堆叠取暗绿。
 */
public enum MekCkUpgradeType {

    SPEED("speed", ResourceLocation.fromNamespaceAndPath("mekanism", "upgrade_speed"), EnumColor.RED),
    ENERGY("energy", ResourceLocation.fromNamespaceAndPath("mekanism", "upgrade_energy"), EnumColor.BRIGHT_GREEN),
    STACK("stack", ResourceLocation.fromNamespaceAndPath("mekanism_extras", "upgrade_stack"), EnumColor.DARK_GREEN),
    CREATIVE("creative", ResourceLocation.fromNamespaceAndPath("mekanism_extras", "upgrade_creative"), EnumColor.DARK_AQUA),
    GAS("gas", ResourceLocation.fromNamespaceAndPath("mekanism", "upgrade_gas"), EnumColor.YELLOW);

    public final String id;
    private final ResourceLocation iconId;
    private final EnumColor color;

    MekCkUpgradeType(String id, ResourceLocation iconId, EnumColor color) {
        this.id = id;
        this.iconId = iconId;
        this.color = color;
    }

    public EnumColor getColor() {
        return color;
    }

    /** 列表/支持条中显示的升级物品图标（物品未注册时返回空栈）。 */
    public ItemStack getIcon() {
        Item item = ForgeRegistries.ITEMS.getValue(iconId);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public int getCount(IUpgradeMenu menu) {
        return switch (this) {
            case SPEED -> menu.getSpeedUpgradeCount();
            case ENERGY -> menu.getEnergyUpgradeCount();
            case STACK -> menu.getStackUpgradeCount();
            case CREATIVE -> menu.getCreativeUpgradeCount();
            case GAS -> menu.getGasUpgradeCount();
        };
    }

    public int getMax(IUpgradeMenu menu) {
        return switch (this) {
            case SPEED -> menu.getSpeedUpgradeMax();
            case ENERGY -> menu.getEnergyUpgradeMax();
            case STACK -> 6;
            case CREATIVE -> 1;
            case GAS -> 1;
        };
    }
}
