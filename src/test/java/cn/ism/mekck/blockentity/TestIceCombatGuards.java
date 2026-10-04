package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M13「制冰 / 战斗 / 烧烤类 BE 缺陷批」的护栏。
 *
 * <h3>为什么是源码形态断言</h3>
 * 本批 10 条修复里有 8 条落在 {@code load} / {@code serverTick} / 配方匹配路径上，
 * 而它们的失败形态<b>全是静默的</b>：创造升级装完即失效、签子每批 +1、拉料抽错配方类型、
 * 客户端水量恒空、订单门永不完成 —— 没有异常、没有日志，只有玩家过一阵子发现「不对劲」。
 * 这些路径又都需要 {@code Level} / {@code BlockEntityType} / 物品注册表，裸 JVM 里起不来
 * （与 {@code TestNbtPersistenceInvariants} 注释所述同一个限制）。
 *
 * <p>所以判据钉在<b>源码结构</b>上：与 {@code TestAttackRadiusClamp} /
 * {@code TestMinorDefectGuards} 同源。每条断言都对应一个「改回去就变红」的具体形态，
 * 而不是「看起来对」。</p>
 *
 * <p>读源码一律走 {@link TestSourceText#read}（剥注释）—— 本类的 javadoc 与各方法注释里
 * 逐字引用了旧形态，不剥注释会把说明当成代码（本仓库已栽过三次，见 {@code TestSourceText}）。</p>
 */
public class TestIceCombatGuards {

    private static final String BE_DIR = "src/main/java/cn/ism/mekck/blockentity/";
    private static final String MENU_DIR = "src/main/java/cn/ism/mekck/menu/";

    private static String be(String name) throws IOException {
        return TestSourceText.read(BE_DIR + name + ".java");
    }

    private static String menu(String name) throws IOException {
        return TestSourceText.read(MENU_DIR + name + ".java");
    }

    /** 取方法体并断言签名仍在（改名后静默返回空串才是这类测试最危险的失败方式）。 */
    private static String body(String src, String signature, String where) {
        String body = TestSourceText.methodBody(src, signature);
        assertFalse(where + " 里找不到 " + signature + "（改名了就同步更新本测试）", body.isEmpty());
        return body;
    }

    // ================== 1. IceFactory 创造升级 ==================

    /**
     * {@code hasCreativeUpgrade()} 必须读「已安装数量」而非槽内物品数。
     *
     * <p>{@code MekCkUpgradeTracker.tick()} 读条满 20 tick 后 {@code installed += canAdd}
     * 并 {@code stack.shrink(canAdd)} ⇒ 槽位被清空。读槽位的写法在安装完成后恒为 false：
     * 免电 / 1 tick / 无限弹药全部失效，{@code DATA_CREATIVE_UPGRADE} 显示 0，
     * 且 {@code uninstallUpgrade} 的创造分支不可达（物品拿不回来）。
     * 同族 IceMaker / ChocolateCannon 早已是正确写法，只有 IceFactory 漏了。</p>
     */
    @Test
    public void iceFactoryCreativeUpgradeReadsInstalledCountNotSlot() throws IOException {
        String src = be("IceFactoryBlockEntity");
        String body = body(src, "public boolean hasCreativeUpgrade() {", "IceFactoryBlockEntity");
        assertTrue("IceFactory 的创造升级必须读 creativeTracker.getInstalled()："
                        + "tracker 安装时会把槽内堆叠 shrink() 掉，读槽位在安装完成后恒为 false",
                body.contains("creativeTracker.getInstalled() > 0"));
        assertFalse("IceFactory 的创造升级不得再读槽位（安装后槽位被清空 ⇒ 恒 false）",
                body.contains("items.getStackInSlot(CREATIVE_SLOT)"));
    }

    // ================== 2. Skewering 签子返还 / Max 按钮 ==================

    /**
     * tool 返还块必须带 {@code toolCount > 0} 守卫。
     *
     * <p>自有 8 条配方的序列化器写死 {@code toolCount = 0}（签子不消耗），
     * {@code consumeInput(tool, 0)} 不消耗任何东西；随后的返还块却无条件把 slot 0 的
     * 物品复制 1 个进 RETURN_SLOT ⇒ <b>每批 +1 个签子</b>（数据驱动配方若 tool 是贵重物品
     * 则复制贵重物品）。工厂执行器 {@code SkeweringFactoryExecutor} 只按 toolCount 消耗、
     * 没有返还逻辑，本机应与它同口径。</p>
     */
    @Test
    public void skeweringToolReturnOnlyWhenConsumed() throws IOException {
        String src = be("SkeweringMachineBlockEntity");
        String body = body(src, "private void completeRecipe(Level level, Recipe<?> recipe) {",
                "SkeweringMachineBlockEntity");
        assertTrue("tool 返还块必须带 toolCount > 0 守卫：自有配方 toolCount = 0（签子不消耗），"
                        + "无条件返还等于每批复制 1 个签子",
                body.contains("!tool.isEmpty() && toolCount > 0"));
    }

    /**
     * Max 按钮的 tool 项必须跳过 {@code toolCount <= 0}。
     *
     * <p>原实现 {@code toolAvailable / toolCount} 在 toolCount = 0 时抛
     * {@code ArithmeticException}，被外层 {@code catch (Exception) { return 0; }} 吞掉
     * ⇒ 自有配方的 Max 恒为 0（玩家看到的是「材料够但按钮没反应」）。</p>
     */
    @Test
    public void skeweringMaxButtonSkipsUnconsumedTool() throws IOException {
        String src = be("SkeweringMachineBlockEntity");
        String body = body(src, "public int getMaxConsumableCountForOrder(Recipe<?> recipe) {",
                "SkeweringMachineBlockEntity");
        assertTrue("Max 按钮的 tool 项必须带 toolCount > 0 守卫："
                        + "toolAvailable / 0 抛 ArithmeticException，被外层 catch 吞成「Max 恒 0」",
                body.contains("!tool.isEmpty() && toolCount > 0"));
    }

    // ================== 3. 电力研磨机 AE2 拉料 ==================

    /**
     * 空输入槽时的拉料并集必须与本机实际处理的四类配方一致。
     *
     * <p>原先取 {@code farmersdelight:cutting}（切菜板），而插入侧
     * {@code IntHandlerBulkView} 会先查 {@code handler.isItemValid} ⇒ 抽出来的切菜配料
     * 全被拒、{@code BigStackDrops} 掉在机器旁（物品离开 ME 网络）。
     * 本机 {@code isItemValid} / {@code findRecipe} 收的是石磨 / 筛粉 / 绞碎 / mekck 磨粉四类。</p>
     */
    @Test
    public void grindingNetworkPullUnionMatchesItsOwnRecipeTypes() throws IOException {
        String src = be("ElectricGrindingMachineBlockEntity");
        String body = body(src, "public List<cn.ism.mekck.util.AE2InputSpec> getNetworkPullInputs() {",
                "ElectricGrindingMachineBlockEntity");
        assertFalse("电力研磨机的 AE2 拉料并集不得再取 farmersdelight:cutting（切菜板）："
                        + "本机 isItemValid 只收四类研磨配方，切菜配料会被拒收并掉在地上",
                body.contains("farmersdelight"));
        for (String typeId : new String[]{"kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                "farm_and_charm:mincer", "mekck:grinding"}) {
            assertTrue("拉料并集缺少本机实际处理的配方类型 " + typeId, body.contains(typeId));
        }
    }

    // ================== 4. IceFactory 两个宽槽 ==================

    /**
     * 水罐量（256,000）与 24-bit 侧配编码都必须拆高低两槽，菜单侧走 {@link cn.ism.mekck.util.WideDataSlot#read}。
     *
     * <p>{@code ContainerData} 经 {@code ClientboundContainerSetDataPacket} 只 {@code writeShort}
     * ⇒ 裸读低槽：水量 &gt; 32767 变负数（菜单 {@code amount <= 0 → EMPTY}，满罐显示空）；
     * 侧配 WEST(4)/EAST(5) 位于 bit16..23，恒为 NONE。</p>
     *
     * <p>IceFactory 曾以「方块未注册、不可达」被 {@code TestWideDataSlot} 豁免 ——
     * 该理由不成立：{@code ice_factory.enable_ice_factory} 是普通配置项，翻开即注册
     * （见 {@code TestIceFactoryToggle}）。</p>
     */
    @Test
    public void iceFactoryWideSlotsAreSplitAndReadAsWide() throws IOException {
        String beSrc = be("IceFactoryBlockEntity");
        assertTrue("IceFactoryBlockEntity 缺 DATA_SIDE_CONFIG_HI 槽（24-bit 侧配裸读低槽会丢 WEST/EAST）",
                beSrc.contains("DATA_SIDE_CONFIG_HI"));
        assertTrue("IceFactoryBlockEntity 缺 DATA_WATER_AMOUNT_HI 槽（256,000 裸读低槽变负数 ⇒ 满罐显示空）",
                beSrc.contains("DATA_WATER_AMOUNT_HI"));
        assertTrue("侧配高位没有产出（>>> 16）", beSrc.contains("(encodeSideConfig() >>> 16)"));
        assertTrue("水量高位没有产出（>>> 16）", beSrc.contains("(waterTank.getFluidAmount() >>> 16)"));

        String menuSrc = menu("IceFactoryMenu");
        String side = body(menuSrc, "public int getEncodedSideConfig() {", "IceFactoryMenu");
        assertTrue("IceFactoryMenu.getEncodedSideConfig() 必须走 WideDataSlot.read",
                side.contains("WideDataSlot.read"));
        assertTrue("IceFactoryMenu.getEncodedSideConfig() 必须读 DATA_SIDE_CONFIG_HI",
                side.contains("DATA_SIDE_CONFIG_HI"));
        String water = body(menuSrc, "public FluidStack getWaterStack() {", "IceFactoryMenu");
        assertTrue("IceFactoryMenu.getWaterStack() 必须走 WideDataSlot.read",
                water.contains("WideDataSlot.read"));
        assertTrue("IceFactoryMenu.getWaterStack() 必须读 DATA_WATER_AMOUNT_HI",
                water.contains("DATA_WATER_AMOUNT_HI"));
    }

    // ================== 5. 读档夹紧（与 radius/targetType 同型） ==================

    /**
     * IceMaker 读档的 {@code targetTemperature} 必须过与 setter 同一道钳制闸门。
     *
     * <p>setter 一直夹在 -27315 ~ 0，读档没有 —— 被改过的存档可写入越界设定温度
     * （GUI 显示异常；行为上只是不制冷 / 满功率制冷）。与第 6 轮给 radius/targetType
     * 补的读档夹紧同型：只堵 setter 会留下「旧档即越界值」的后门。</p>
     */
    @Test
    public void iceMakerTargetTemperatureIsClampedOnLoad() throws IOException {
        String src = be("IceMakerBlockEntity");
        assertTrue("IceMaker 读档的 targetTemperature 必须过 clampTargetTemperature（与 radius/targetType 同型）",
                src.contains("clampTargetTemperature(tag.getInt(\"TargetTemperature\"))"));
        assertFalse("IceMaker 读档仍有绕过闸门的裸读 targetTemperature = tag.getInt(\"TargetTemperature\")",
                src.contains("targetTemperature = tag.getInt(\"TargetTemperature\")"));
        String setter = body(src, "public void setTargetTemperature(int milliCelsius) {", "IceMakerBlockEntity");
        assertTrue("setter 与读档必须共用同一道钳制闸门（否则两处会各自漂移）",
                setter.contains("clampTargetTemperature(milliCelsius)"));
    }

    /**
     * Grill / Skewering 读档时，{@code orderRecipeId != null} ⇒ {@code orderQuantity} 必须 ≥ 1。
     *
     * <p>{@code setOrder} 的契约是「取消清零、激活夹到 ≥ 1」，读档路径原先绕过它。
     * 若出现 {@code orderRecipeId != null && orderQuantity == 0}：订单门禁
     * （{@code orderQuantity > 0}）与完成推进同时失效 ⇒ <b>机器无限加工、订单永不完成</b>，
     * 且 AE2 侧 {@code orderStateOf} 读 {@code getOrderQuantity()} 会判 done。写坏之后是静默故障。</p>
     */
    @Test
    public void grillAndSkeweringClampOrderQuantityOnLoad() throws IOException {
        String[][] cases = {
                {"GrillBlockEntity", "orderQuantity = tag.getInt(TAG_ORDER_QUANTITY);"},
                {"SkeweringMachineBlockEntity", "orderQuantity = tag.getInt(\"OrderQuantity\");"},
        };
        String clamp = "if (orderRecipeId != null) orderQuantity = Math.max(1, orderQuantity);";
        for (String[] c : cases) {
            String src = be(c[0]);
            int read = src.indexOf(c[1]);
            assertTrue(c[0] + " 里找不到订单数量读档（改名了就同步更新本测试）", read >= 0);
            int clampAt = src.indexOf(clamp);
            assertTrue(c[0] + " 读档必须把 orderQuantity 夹到 ≥ 1（orderRecipeId != null 时）："
                            + "否则订单门禁与完成推进同时失效 ⇒ 机器无限加工、订单永不完成",
                    clampAt > read);
        }
    }

    // ================== 6. 热能力随失效/复活收口 ==================

    /**
     * IceMaker / IceFactory / NutRoaster 的 {@code heatCapability} 必须随
     * {@code invalidateCaps} / {@code reviveCaps} 收口（对齐 {@code CentralKitchenBlockEntity}）。
     *
     * <p>supplier 恒返回同一 handler，功能上仍可用；缺的是 Forge 的失效通知契约 ——
     * 方块实体被移除后，外部仍持有「有效」的 LazyOptional，会继续往一个已失效的
     * 热处理器写热量。三台同型，一起钉住。</p>
     */
    @Test
    public void heatCapabilityFollowsInvalidateAndRevive() throws IOException {
        String[] machines = {"IceMakerBlockEntity", "IceFactoryBlockEntity", "NutRoasterBlockEntity"};
        for (String m : machines) {
            String src = be(m);
            String invalidate = body(src, "public void invalidateCaps() {", m);
            assertTrue(m + " 的 heatCapability 未随 invalidateCaps 收口（对齐 CentralKitchenBlockEntity）",
                    invalidate.contains("heatCapability.invalidate()"));
            String revive = body(src, "public void reviveCaps() {", m);
            assertTrue(m + " 的 heatCapability 未随 reviveCaps 重建（失效后必须换一个新的 LazyOptional）",
                    revive.contains("heatCapability = LazyOptional.of("));
        }
    }
}
