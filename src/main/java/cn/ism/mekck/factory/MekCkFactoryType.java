package cn.ism.mekck.factory;

import mekanism.api.text.ILangEntry;
import net.minecraft.network.chat.Component;

/**
 * MekCK 工厂工艺类型（7 家族）。
 *
 * <h3>为什么自建而不是复用 Mek 的 FactoryType</h3>
 * Mek 的 {@code FactoryType} 是<b>封闭枚举</b>（SMELTING/ENRICHING/CRUSHING/COMPRESSING/
 * COMBINING/PURIFYING/INJECTING/INFUSING/SAWING），且每个常量强绑定 Mek 自己的
 * 配方类型与方块注册对象，无法为外部模组的工艺（切菜/烹饪/穿串/烧烤/研磨/制冰/种植切配）扩项。
 *
 * <p>Extras 在需要 Mek 没有的工艺时同样走自建路线：它另立
 * {@code MoreMachineFactoryType} / {@code AdvancedFactoryType} 两套枚举，并在
 * {@code ExtraFactoryMachine} 上提供对应的重载构造器。本枚举照此范式实现，
 * 用 {@link ILangEntry} 自带译名（Mek 的译名接口，可外部实现）。</p>
 *
 * <p>{@link #recipeTypeName} 用于后续接入配方查找时定位本工艺的 {@code RecipeType}。</p>
 */
public enum MekCkFactoryType implements ILangEntry {

    /** 切菜（砧板刀工）。 */
    CUTTING("cutting", "cutting_factory", "block.mekck.cutting_factory"),
    /** 种植与切配。 */
    PLANTING_CUTTING("planting_cutting", "planting_cutting_factory", "block.mekck.planting_cutting_factory"),
    /** 烹饪。 */
    COOKING("cooking", "cooking_factory", "block.mekck.cooking_factory"),
    /** 穿串。 */
    SKEWERING("skewering", "skewering_factory", "block.mekck.skewering_factory"),
    /** 烧烤。注意模型目录是 grill_factory（不是 grilling_factory）。 */
    GRILLING("grilling", "grill_factory", "block.mekck.grill_factory"),
    /** 研磨。 */
    GRINDING("grinding", "grinding_factory", "block.mekck.grinding_factory"),
    /** 制冰。 */
    ICE("ice", "ice_factory", "block.mekck.ice_factory");

    private final String typeName;
    /** 贴图/模型目录名（与旧套资源目录逐字对应，不能由 typeName 推导）。 */
    private final String modelDir;
    private final String translationKey;

    MekCkFactoryType(String typeName, String modelDir, String translationKey) {
        this.typeName = typeName;
        this.modelDir = modelDir;
        this.translationKey = translationKey;
    }

    /** 贴图/模型所在目录名（旧套资源复用点）。 */
    public String getModelDir() {
        return modelDir;
    }

    /** 工艺短名（用于方块 ID 拼接与配方类型定位）。 */
    public String getTypeName() {
        return typeName;
    }

    // ── Mek ILangEntry ─────────────────────────────────────────────────────

    @Override
    public String getTranslationKey() {
        return translationKey;
    }

    // ── 便捷 ───────────────────────────────────────────────────────────────

    /** 本工艺 + 指定等级对应的方块注册名，如 {@code basic_cutting_factory}。 */
    public String blockId(MekCkFactoryTier tier) {
        return tier.getLowerName() + "_" + typeName + "_factory";
    }

    /** 供 GUI 标题使用的译名组件。 */
    public Component displayName() {
        return Component.translatable(translationKey);
    }
}
