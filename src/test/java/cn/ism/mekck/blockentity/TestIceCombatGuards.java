package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.compat.KaleidoscopeGrillingCompat;
import cn.ism.mekck.util.BigStackItemHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
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
 * <p>例外是 M31 的 {@code returnPreview}：它是包级可见的静态纯函数（只用到
 * {@link ItemStackHandler} 与配方字段），能按 {@link #boot()} 把注册表拉起来后做
 * <b>真行为断言</b> —— 与 {@code TestKitchenPotGuards} 的 {@code findAndConsumeOne}
 * 同口径。能行为断言的地方就不该只钉源码形态。</p>
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

    /**
     * 裸 JVM 里把原版注册表拉起来 —— 与 {@code TestKitchenPotGuards.boot()} 同一套两步。
     *
     * <p>本类绝大多数断言只读源码文本，不需要注册表；只有
     * {@link #skeweringReturnPreviewIsSimulatedAndGated} 要造
     * {@link Ingredient} / {@link ItemStack}，才需要这一步。</p>
     */
    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    /** 森罗虚拟配方同形的配方：机器侧反射读 public 字段 {@code tool} / {@code ingredientCount}。 */
    private static Recipe<?> threadingRecipe(Ingredient tool, int toolCount) {
        return new KaleidoscopeGrillingCompat.VirtualRecipe(
                ResourceLocation.fromNamespaceAndPath("mekck", "threading/test"),
                tool, Ingredient.of(Items.COOKED_BEEF), toolCount, null, 0,
                new ItemStack(Items.COOKED_BEEF));
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
     * tool 返还必须来自「本次实际扣掉的签子」记录，不得读输入槽 0。
     *
     * <p>自有 8 条配方的序列化器写死 {@code toolCount = 0}（签子不消耗），
     * {@code consumeInput(tool, 0)} 不消耗任何东西；旧返还块却无条件把 slot 0 的
     * 物品复制 1 个进 RETURN_SLOT ⇒ <b>每批 +1 个签子</b>（数据驱动配方若 tool 是贵重物品
     * 则复制贵重物品）。M13 加的 {@code toolCount > 0} 守卫只挡住了这一路：
     * 扣料位置无关（输入槽 + 存储槽），toolCount&gt;0 且签子不在槽 0 时，
     * 读槽 0 返还的是<b>另一种物品</b>（物品复制）。M29 起返还按扣料记录落槽
     * （{@code consumeInput} 返回被扣的栈，合并走工厂执行器
     * {@code SkeweringFactoryExecutor.returnPayload}，两边同口径）。</p>
     */
    @Test
    public void skeweringToolReturnOnlyWhenConsumed() throws IOException {
        String src = be("SkeweringMachineBlockEntity");
        String body = body(src, "private void completeRecipe(Level level, Recipe<?> recipe) {",
                "SkeweringMachineBlockEntity");
        assertTrue("tool 扣料必须记录被扣的栈（consumeInput 返回记录）",
                body.contains("consumeInput(tool, toolCount)"));
        assertTrue("tool 返还必须来自本次扣料记录（consumeInput 的返回值）："
                        + "扣料位置无关，读槽 0 会在签子不在槽 0 时复制另一种物品",
                body.contains("returnPayload(consumedTool)"));
        assertFalse("tool 返还不得再读输入槽 0（旧错配形态）",
                body.contains("getStackInSlot(INPUT_SLOT_START)"));
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

    /**
     * 配方声明了 side 时，槽 2 为空必须判<b>不匹配</b>。
     *
     * <p>原实现的 side 分支比 tool / ingredient 少一支：只在「槽 2 有东西」时才校验，
     * 槽 2 为空则整段跳过 ⇒ {@code matchesSkewering} 返回 true，而
     * {@code completeRecipe} 对空槽只能扣 0 个 side ⇒ <b>不放辅料也能出货</b>（物品复制）。
     * 工厂侧 {@code SkeweringFactoryExecutor.batchForMaterial} 一向按「配方要就得有」算，
     * 本机必须与它同口径。</p>
     */
    @Test
    public void skeweringSideIsRequiredWhenTheRecipeDeclaresIt() throws IOException {
        String src = be("SkeweringMachineBlockEntity");
        String m = body(src, "private boolean matchesSkewering(Recipe<?> recipe, ItemStack[] inputStacks) {",
                "SkeweringMachineBlockEntity");
        int at = m.indexOf("else if (side != null && !side.isEmpty() && inputStacks[2].isEmpty()) {");
        assertTrue("配方声明了 side 而槽 2 为空时必须判不匹配 —— 少了这一支就能不放辅料出货"
                        + "（completeRecipe 对空槽扣 0 个 side，产出照给）", at >= 0);
        assertTrue("那一支必须真的 return false，不能只是空语句",
                m.substring(at, Math.min(m.length(), at + 120)).contains("return false;"));
    }

    /**
     * 行为：返还预览 = 本次实际会扣掉的签子，且预演零改动。
     *
     * <p>{@code returnPreview} 是包级可见的静态纯函数（只用到 {@link ItemStackHandler} 与
     * 配方字段），所以能按 {@link #boot()} 把注册表拉起来后做真行为断言 ——
     * 与 {@code TestKitchenPotGuards} 的 {@code findAndConsumeOne} 同口径。</p>
     *
     * <p>反例形态：主料在输入槽 0、签子在存储槽（扣料位置无关，签子照样扣得动）。
     * 预览必须是<b>被扣掉的签子</b>，而不是槽 0 里恰好放着的主料 —— 旧写法读槽 0
     * 复制整叠，这条路径上复制的是主料（物品复制，M29 修复）。</p>
     */
    @Test
    public void skeweringReturnPreviewIsSimulatedAndGated() {
        ItemStackHandler handler = new BigStackItemHandler(SkeweringMachineBlockEntity.TOTAL_SLOTS);
        handler.setStackInSlot(SkeweringMachineBlockEntity.INPUT_SLOT_START, new ItemStack(Items.COOKED_BEEF, 64));
        handler.setStackInSlot(SkeweringMachineBlockEntity.STORAGE_SLOT_START, new ItemStack(Items.STICK, 10));

        ItemStack preview = SkeweringMachineBlockEntity.returnPreview(
                threadingRecipe(Ingredient.of(Items.STICK), 1), handler);
        assertTrue("返还预览必须是本次会扣掉的签子，不是槽 0 的主料",
                ItemStack.isSameItemSameTags(preview, new ItemStack(Items.STICK)));
        assertEquals("toolCount = 1 ⇒ 预览 1 根", 1, preview.getCount());
        assertEquals("预演不得改动存储槽的签子", 10,
                handler.getStackInSlot(SkeweringMachineBlockEntity.STORAGE_SLOT_START).getCount());
        assertEquals("预演不得改动输入槽的主料", 64,
                handler.getStackInSlot(SkeweringMachineBlockEntity.INPUT_SLOT_START).getCount());

        assertTrue("toolCount = 0（自有配方签子不消耗）⇒ 不得返还",
                SkeweringMachineBlockEntity.returnPreview(
                        threadingRecipe(Ingredient.of(Items.STICK), 0), handler).isEmpty());
        assertTrue("签子配料为空 ⇒ 不得返还",
                SkeweringMachineBlockEntity.returnPreview(
                        threadingRecipe(Ingredient.EMPTY, 1), handler).isEmpty());
    }

    /**
     * 源码形态：{@code canFitAll} 必须把返还槽纳入容量判定，且模拟副本必须回答与真槽相同的上限。
     *
     * <p>原实现只模拟 OUTPUT_SLOT：返还槽满（或槽里是别的物品）时机器照常开工，
     * {@code completeRecipe} 先扣签子、{@code insertOutput} 的剩余量被静默丢弃
     * （每周期丢 toolCount 个签子）。返还量取 {@code returnPreview} 的预演结果，
     * 与 {@code completeRecipe} 真正要落的返还物同口径（工厂侧
     * {@code SkeweringFactoryExecutor.canFitBatch} 的两段分开判同款）。</p>
     *
     * <p>模拟副本还必须覆写 {@code getSlotLimit} 委托真 handler：
     * {@code BigStackItemHandler} 默认 64，而本机 OUTPUT_SLOT / RETURN_SLOT 是
     * {@code Integer.MAX_VALUE} —— 不覆写则返还槽堆到 64 个签子后预检永远失败、
     * 机器静默停摆（与 {@code SmartCookingPotBlockEntity.canFitAll} 同款）。</p>
     */
    @Test
    public void skeweringCanFitAllChecksReturnSlot() throws IOException {
        String src = be("SkeweringMachineBlockEntity");
        String body = body(src, "private boolean canFitAll(Recipe<?> recipe) {", "SkeweringMachineBlockEntity");
        assertTrue("canFitAll 必须对 RETURN_SLOT 做一次 insertOutput 模拟："
                        + "否则返还槽满/异物时签子被扣、返还落不进，剩余量被静默丢弃",
                body.contains("insertOutput(simulated, preview, RETURN_SLOT)"));
        assertTrue("canFitAll 的返还量必须来自 returnPreview 预演（本次实际会扣掉的签子）",
                body.contains("returnPreview(recipe, items)"));
        assertTrue("canFitAll 的模拟副本必须覆写 getSlotLimit 委托真 handler："
                        + "BigStackItemHandler 默认 64，而 OUTPUT_SLOT / RETURN_SLOT 上限是 Integer.MAX_VALUE，"
                        + "返还槽堆到 64 个签子后预检永远失败、机器静默停摆",
                body.contains("public int getSlotLimit(int slot)")
                        && body.contains("return items.getSlotLimit(slot);"));
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
        // 阶段 3 样板迁移后本机不再是旧 BE，而是 Mek 原生 tile；
        // 判据的落点随之从「BE 的 getNetworkPullInputs」改为「tile 的四类配方类型表」——
        // 断言的东西没变：拉料并集必须覆盖本机真正处理的四类配方，且不得取切菜板。
        String src = TestSourceText.read(
                "src/main/java/cn/ism/mekck/machine/grinding/GrindingMachineTile.java");
        String body = body(src, "private static final String[] RECIPE_TYPE_IDS = {", "GrindingMachineTile");
        assertFalse("电力研磨机的配方类型表不得取 farmersdelight:cutting（切菜板）："
                        + "本机 isItemValid 只收四类研磨配方，切菜配料会被拒收并掉在地上",
                body.contains("farmersdelight"));
        for (String typeId : new String[]{"kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                "farm_and_charm:mincer", "mekck:grinding"}) {
            assertTrue("配方类型表缺少本机实际处理的配方类型 " + typeId, body.contains(typeId));
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

    // ================== 7. 冷萃读条器必须与已装等级一同落盘 ==================

    /**
     * {@code ColdBrew{i}}（等级）与读条器的 {@code Installed} 必须成对写、成对读。
     *
     * <p>只写等级不写读条器会怎样：读档后 {@code coldBrewTrackers[i].getInstalled()} 回到 0，
     * 而 {@code uninstallUpgrade} 的第一道门正是它 ⇒ <b>冷萃升级卸不下来</b>。攻击档案读的是
     * {@code installedColdBrew}，所以攻击照常工作 —— 只有卸载坏掉，静默、无日志。
     * {@code TestNbtWriteReadSymmetry} 也抓不到：它检的是「写过的键有没有读回」，
     * 而这里的问题是<b>少写了一个键</b>，不在它的判据面内。</p>
     */
    @Test
    public void iceMakerColdBrewTrackerIsPersistedWithItsTier() throws IOException {
        String src = be("IceMakerBlockEntity");
        String write = body(src, "protected void saveAdditional(CompoundTag tag) {", "IceMakerBlockEntity");
        assertTrue("冷萃读条器的 Installed 必须随 ColdBrew{i} 一起写出 —— "
                        + "只写等级的话，重载后 getInstalled() 回到 0，冷萃升级卸不下来",
                write.contains("\"ColdBrewUpgradeTracker\" + i"));
        String read = body(src, "public void load(CompoundTag tag) {", "IceMakerBlockEntity");
        assertTrue("读档必须把读条器的 Installed 读回（与写出用同一个键）",
                read.contains("\"ColdBrewUpgradeTracker\" + i"));
        assertTrue("旧存档（只有 ColdBrew{i}、没有读条器）必须按「已装 1 件」补回 —— "
                        + "否则这批存档重载后同样卸不下冷萃",
                read.contains("installDirect(1)"));
    }

    // ================== 8. 自有配方的材料表必须走 RecipeRequiredInputs ==================

    /**
     * 三类自有配方（{@code FerreroRecipe} / {@code IceMakeRecipe} / {@code NutRoastingRecipe}）
     * 都<b>不覆写</b> {@code getIngredients()}，而那个默认实现返回<b>空表</b>。
     *
     * <p>任何按它遍历的通用逻辑都会退化成空循环，且编译与其余测试全绿：</p>
     * <ul>
     *   <li>{@code matchesInput} ⇒ 变成「输入槽非空就算匹配」；</li>
     *   <li>{@code getMaxConsumableCountForOrder} ⇒ {@code max} 停在
     *       {@code Integer.MAX_VALUE}，函数按约定返回 0 ⇒ 面板上的 Max 按钮<b>只会填 1</b>。</li>
     * </ul>
     */
    @Test
    public void ownRecipeIngredientTablesGoThroughRecipeRequiredInputs() throws IOException {
        String[] machines = {"ChocolateCannonBlockEntity", "IceMakerBlockEntity", "NutRoasterBlockEntity"};
        for (String m : machines) {
            String src = be(m);
            String matches = body(src,
                    "private boolean matchesInput(net.minecraft.world.item.crafting.Recipe<?> recipe) {", m);
            assertFalse(m + ".matchesInput 不得直接遍历 recipe.getIngredients() —— "
                            + "三类自有配方不覆写它、默认返回空表 ⇒ 判据退化成「输入槽非空就算匹配」",
                    matches.contains(": recipe.getIngredients()"));
            assertTrue(m + ".matchesInput 必须走 RecipeRequiredInputs.of(recipe)",
                    matches.contains("RecipeRequiredInputs.of(recipe)"));
            String max = body(src,
                    "public int getMaxConsumableCountForOrder(net.minecraft.world.item.crafting.Recipe<?> recipe) {", m);
            assertTrue(m + ".getMaxConsumableCountForOrder 必须与匹配走同一份材料表",
                    max.contains("RecipeRequiredInputs.of(recipe)") && !max.contains(": recipe.getIngredients()"));
        }
        // 判据不许空转：材料表本身必须覆盖三类自有配方。
        String helper = TestSourceText.read(
                "src/main/java/cn/ism/mekck/recipe/RecipeRequiredInputs.java");
        for (String type : new String[]{"FerreroRecipe", "IceMakeRecipe", "NutRoastingRecipe"}) {
            assertTrue("RecipeRequiredInputs 少认了一类自有配方：" + type
                    + "（新增自有配方类型时必须来这里补一支，否则那台机器的 Max 与匹配会静默失效）",
                    helper.contains(type));
        }
    }
}
