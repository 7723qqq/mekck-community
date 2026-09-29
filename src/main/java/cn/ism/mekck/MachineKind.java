package cn.ism.mekck;

/**
 * 四合一基础机器类型（本轮新增，均无工厂版本）：
 * 寿司卷制机（妖怪们的归家料理台联动）、平均切段机（寿司卷切段专用，2×速度 0.5×能耗）、
 * 饭团成型机（含做米饭功能）、凝乳成型机（樱途旅事凝乳块→奶酪轮）。
 */
public enum MachineKind {
    SUSHI_MAKER("sushi_maker", 200, 20),
    AVERAGE_SLICER("average_slicer", 100, 10),
    RICE_BALL_MAKER("rice_ball_maker", 200, 20),
    CURD_MAKER("curd_maker", 1200, 20),
    DEHYDRATOR("dehydrator", 200, 20),
    FERMENTER("fermenter", 1200, 20),
    STEAMER("steamer", 200, 20),
    /**
     * 智能陈酿机。基础时长 2026-09-25 由 1200 **上调为 2400**（用户要求「制作葡园酒香的配方时间改为现在的两倍」）。
     * <p>陈酿机做 {@code vinery:wine_fermentation}（26 条）时 {@code matchWinery} 传的 processTime 恒为 0，
     * 实际时长就取本常量（见 {@code SimpleMachineBlockEntity#getEffectiveProcessTime}），因此这一格就是那 26 条配方的耗时。
     * WINERY 无工厂版本、中央厨房也不接 wine_fermentation，全仓仅陈酿机消费此值；速度升级仍按倍率缩短。
     * 酒馆 barrel 批次的时长另有来源（配方 {@code unit_time}，见 {@link cn.ism.mekck.blockentity.TavernBrewBatch}）。</p>
     */
    WINERY("winery", 2400, 20),
    JUICER("juicer", 200, 20),
    BAKERY_OVEN("bakery_oven", 200, 20),
    STOVE("stove", 200, 20),
    /** 调酒机（酒馆 shaker：3 个酒类/原料 → 鸡尾酒）。 */
    COCKTAIL_SHAKER("cocktail_shaker", 200, 20),
    /** 搅拌机（烘焙坊 blender：1~9 输入 → 1 输出；使用扩展输入槽 10..13）。 */
    BLENDER("blender", 200, 20),

    /** 智能茶艺机（简单的茶联动）：把「茶杯 + 茶包 + 热茶壶」的手工合成变成流水线。 */
    TEA_BREWER("tea_brewer", 200, 20),

    /** 智能萃取机（F8/F11 §四.1）：读 {@code create:mixing} 中「产物为流体·茶/咖啡/溶糖/糖浆系」的配方（+ oreo 酱破例白名单），只出流体。 */
    SMART_EXTRACTOR("smart_extractor", 200, 20),

    /** 饮品调配机（F8/F11 §四.2）：读自有类型 {@code mekck:beverage_assembly}（杯+小料+饮品流体→杯装饮品），不再运行时读 {@code create:filling}。 */
    BEVERAGE_BLENDER("beverage_blender", 200, 20),

    /** 包材组装机（F7/F11 §四.4）：只读自有类型 {@code mekck:packaging}（仅产包材：杯/包装盒/碗等），无流体。 */
    PACKAGING_STATION("packaging_station", 50, 20);

    public final String id;
    /** 基础处理时间（tick）。 */
    public final int processTime;
    /** 基础能耗（FE/t）。 */
    public final int energyPerTick;

    MachineKind(String id, int processTime, int energyPerTick) {
        this.id = id;
        this.processTime = processTime;
        this.energyPerTick = energyPerTick;
    }
}
