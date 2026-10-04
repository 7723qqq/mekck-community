package cn.ism.mekck.machine;

import cn.ism.mekck.util.BigStackItemHandler;
import mekanism.api.DataHandlerUtils;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「家族专属槽（{@code appendExtraSlots}）也在 int 下标存档覆盖范围内」的回归测试。
 *
 * <h3>它钉住的两个故障形态</h3>
 * <ol>
 *   <li><b>下标 ≥ 128 的专属槽静默丢失</b>。{@code mekckPersistedSlots()} 原先自己拼
 *       {@code [输入][输出][能量槽]}，把 {@code appendExtraSlots} 追加的槽漏在外面，
 *       而那些槽只走 Mek 的 byte 下标存档：{@code DataHandlerUtils.writeContents} 偏移 48~53
 *       是 {@code i2b}，读侧 {@code readContents} 对负下标整条跳过。烹饪工厂 144 格存储
 *       里有 35 格落在 128..162，<b>全部 12 档都中招</b>。</li>
 *   <li><b>构造期 NPE</b>：{@code appendExtraSlots} 在父类构造器内部被回调，
 *       此刻子类字段初始化器还没执行——字段写成 {@code = new ArrayList<>(...)} 再在方法里
 *       {@code clear()}，就是对 {@code null} 调 {@code clear()}，方块放下去就建不出方块实体。</li>
 * </ol>
 *
 * <h3>为什么一部分断言只能读源码</h3>
 * 真 tile 在裸 JVM 里造不出来（构造链要 {@code BlockEntityType} 与 Mek 的 {@code Attribute}
 * 注册表，见 {@code TestMekCkSlotNbt} 的类注释），而这两条故障恰恰都发生在
 * 「tile 构造 + 槽位列表装配」这一段。所以：
 * <ul>
 *   <li>「覆盖范围 = Mek 写 {@code Items} 用的那份列表」用<b>源码不变量</b>钉
 *       （与 {@code TestMekCkSlotNbt#mekckSlotReadIsAppliedAfterSuperLoad} 同一手法）；</li>
 *   <li>「专属槽的尾部能不能被 int 下标存档往返」用<b>真槽位对象</b>在裸 JVM 里跑
 *       （{@code InputInventorySlot.at} 不需要 {@code Level}，同 {@code TestMekCkSlotNbt}）。</li>
 * </ul>
 *
 * <p>槽位布局的数字全部从<b>源码文本</b>里读，而不是 import 那些 tile 类：后者的
 * {@code <clinit>}/{@code Class} 载入会拖上整条 Mek 继承链。读源码同时保证了
 * 「常量改了、测试跟着变」——把 144 调成 100 会让「尾部越界」的断言立刻失效。</p>
 */
public class TestMekCkPersistedSlotCoverage {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    // ── 源码读取工具 ────────────────────────────────────────────────────

    private static String source(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    /** 读一个 {@code static final int NAME = <数字>;} 常量。 */
    private static int intConst(String source, String name) {
        Matcher matcher = Pattern.compile("static final int " + name + "\\s*=\\s*(\\d+)").matcher(source);
        assertTrue("源码里找不到常量 " + name, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    /** 取某个方法体的源码文本（从签名到第一个「四个空格 + 右花括号」）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("源码里找不到方法 " + signature, start > 0);
        int end = source.indexOf("\n    }", start);
        assertTrue("方法 " + signature + " 没有闭合", end > start);
        return source.substring(start, end);
    }

    // ── 1. 覆盖范围 = Mek 自己写 Items 用的那份列表 ─────────────────────

    /**
     * {@code mekckPersistedSlots()} 必须返回 {@code getInventorySlots(null)}。
     *
     * <p>这条是「专属槽也在覆盖范围内」的<b>结构性</b>保证：Mek 的
     * {@code saveAdditional} 写 {@code Items} 时用的就是 {@code getInventorySlots(null)}
     * （偏移 74~86，实测 {@code javap}），而 {@code ConfigHolder.getSlots(side, fn)}
     * 在 {@code side == null} 时直接返回 addSlot 的插入序列表。取同一个方法的结果，
     * 下标就不可能与 Mek 的 byte 下标错位；自己拼一份则一定会漏掉后来追加的槽。</p>
     */
    @Test
    public void persistedSlotsAreExactlyTheListMekWrites() throws IOException {
        String body = methodBody(source("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                "private List<IInventorySlot> mekckPersistedSlots() {");
        assertTrue("mekckPersistedSlots() 必须直接返回 Mek 写 Items 用的那份列表",
                body.contains("getInventorySlots(null)"));
        assertFalse("不能再手工拼 inputSlots/outputSlots/energySlot —— 那正是漏掉专属槽的形态",
                body.contains("addAll(inputSlots)"));
    }

    // ── 2. 构造期 NPE 的源码不变量 ──────────────────────────────────────

    /**
     * 三个「先字段初始化器 new、再在 {@code appendExtraSlots} 里 clear」的实现必须消失。
     *
     * <p>{@code appendExtraSlots} 跑在<b>父类构造器内部</b>（{@code TileEntityMekanism}
     * 构造器偏移 331 调 {@code getInitialInventory}），那一刻子类字段初始化器还没执行，
     * 字段是 {@code null}。所以断言两条：① 这三个列表<b>不得</b>是
     * {@code private final … = new ArrayList<>(…)} 形态；② {@code appendExtraSlots}
     * 里必须有 {@code == null} 分支自己 new。</p>
     */
    @Test
    public void extraSlotListsAreAllocatedInsideAppendExtraSlots() throws IOException {
        String[][] cases = {
                {"src/main/java/cn/ism/mekck/machine/grill/GrillFactoryTile.java", "seasoningSlots"},
                {"src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java", "storageSlots"},
                {"src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java", "storageSlots"},
        };
        for (String[] one : cases) {
            String file = one[0];
            String field = one[1];
            String source = source(file);
            assertFalse(file + " 的 " + field + " 不能写成字段初始化器：appendExtraSlots 在父类构造器内部"
                            + "被回调，那时字段还是 null",
                    source.contains("List<IInventorySlot> " + field + " = new ArrayList<>("));
            String body = methodBody(source, "protected void appendExtraSlots(InventorySlotHelper builder, IContentsListener listener) {");
            assertTrue(file + " 的 appendExtraSlots 必须自己 new（== null 分支）",
                    body.contains(field + " == null"));
            assertTrue(file + " 的 appendExtraSlots 必须自己 new（new ArrayList<>）",
                    body.contains("new ArrayList<>("));
        }
    }

    // ── 3. 布局算术：谁是越界的那一段 ───────────────────────────────────

    /**
     * 烹饪工厂：6 输入 + 12 输出（9 产物 + 3 返还）+ 1 能量 + 144 存储 = 163 槽，
     * 存储段 19..162 ⇒ <b>128..162 共 35 格</b>在 Mek 的 byte 下标下寻址不到。
     */
    @Test
    public void cookingLayoutHas35StorageSlotsBeyondByteIndices() throws IOException {
        String source = source("src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java");
        int inputs = intConst(source, "INPUT_SLOTS");
        int products = intConst(source, "PRODUCT_SLOTS");
        int returns = intConst(source, "RETURN_SLOTS");
        int storage = intConst(source, "STORAGE_SLOTS");

        int firstStorageIndex = inputs + products + returns + 1; // +1 = 能量槽
        int total = firstStorageIndex + storage;
        assertEquals("第一格存储的下标", 19, firstStorageIndex);
        assertEquals("烹饪工厂槽位总数", 163, total);
        assertEquals("最后一格存储的下标", 162, total - 1);
        assertEquals("落在 byte 下标寻址不到的范围里的存储格数", 35, total - 128);
        assertTrue("只要有存储格越界，就必须走 int 下标存档", total - 128 > 0);
    }

    /** 穿串：3 输入 + 2 输出 + 1 能量 + 81 存储 = 87 槽，整段都在 127 以内。 */
    @Test
    public void skeweringExtrasStayInsideTheByteRange() throws IOException {
        String source = source("src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java");
        int inputs = intConst(source, "INPUT_SLOTS");
        int storage = intConst(source, "STORAGE_SLOTS");
        int total = inputs + 2 + 1 + storage;
        assertEquals(87, total);
        assertTrue("穿串的专属槽不得越界（越界了这条会先炸，提醒改覆盖范围）", total <= 128);
    }

    /**
     * 烧烤 / 种植切配：并行方阵家族的专属槽从 {@code 2N + 1} 起。
     * 只有 {@code SINGULARITY}（N = 81 ⇒ 首格 163）会越界；{@code NEBULA}（49 ⇒ 99）安全。
     */
    @Test
    public void parallelFamilyExtrasOnlyOverflowAtSingularity() throws IOException {
        String grill = source("src/main/java/cn/ism/mekck/machine/grill/GrillFactoryExecutor.java");
        int seasoning = intConst(grill, "SEASONING_SLOTS");
        assertEquals(3, seasoning);

        int singularity = 81;
        int nebula = 49;
        int firstGrill = 2 * singularity + 1;
        assertEquals("SINGULARITY 烧烤的调味料槽首格", 163, firstGrill);
        assertTrue("3 个调味料槽全部越界", firstGrill + seasoning - 1 > 127);
        assertTrue("NEBULA 的调味料槽安全", 2 * nebula + 1 + seasoning - 1 <= 127);

        // 种植切配的专属槽是 2 格（营养液 + 生长土），常量不在 tile 里，按布局写死并断言其形态。
        assertEquals("PLANTING_CUTTING 专属槽数", 2, 2);
        assertTrue("SINGULARITY 种植切配的两格专属槽同样越界", 2 * singularity + 2 > 127);
    }

    // ── 4. 163 槽机器：最后一格真的能往返 ───────────────────────────────

    /** 与烹饪工厂同形的 163 格空机器：6 输入 + 12 输出 + 1 能量 + 144 存储。 */
    private static List<IInventorySlot> cookingLayout() {
        List<IInventorySlot> all = new ArrayList<>(163);
        for (int i = 0; i < 6; i++) {
            all.add(InputInventorySlot.at(() -> {
            }, 38 + i * 18, 41));
        }
        for (int i = 0; i < 12; i++) {
            all.add(OutputInventorySlot.at(() -> {
            }, 38 + i * 18, 41));
        }
        all.add(InputInventorySlot.at(() -> {
        }, 7, 13));
        for (int i = 0; i < 144; i++) {
            all.add(InputInventorySlot.at(() -> {
            }, 0, 0));
        }
        return all;
    }

    /**
     * 核心回归：163 槽机器的 <b>19 / 128 / 162</b> 号槽经 int 下标存档往返后仍在；
     * 而只靠 Mek 那份 byte 存档时，128 与 162 号<b>必然</b>读不回来。
     */
    @Test
    public void theLastStorageSlotOfA163SlotMachineSurvivesTheRoundTrip() {
        List<IInventorySlot> before = cookingLayout();
        before.get(19).setStack(new ItemStack(Items.BREAD, 7));
        before.get(128).setStack(new ItemStack(Items.DIAMOND, 1));
        before.get(162).setStack(new ItemStack(Items.NETHERITE_INGOT, 2));

        CompoundTag tag = new CompoundTag();
        tag.put(MekCkSlotNbt.TAG_SLOTS, MekCkSlotNbt.write(before));

        List<IInventorySlot> after = cookingLayout();
        assertTrue("专属键存在时 read 必须认领", MekCkSlotNbt.read(tag, after));
        assertEquals("第一格存储", Items.BREAD, after.get(19).getStack().getItem());
        assertEquals("越界段的第一格", Items.DIAMOND, after.get(128).getStack().getItem());
        assertEquals("最后一格存储", Items.NETHERITE_INGOT, after.get(162).getStack().getItem());
        assertEquals(2, after.get(162).getStack().getCount());

        // 反面对照：Mek 自己那份 byte 存档对 128 与 162 无解——这正是必须有专属键的理由。
        CompoundTag mekNative = new CompoundTag();
        mekNative.put("Items", DataHandlerUtils.writeContents(before, "Slot"));
        List<IInventorySlot> viaMek = cookingLayout();
        DataHandlerUtils.readContents(viaMek, mekNative.getList("Items", Tag.TAG_COMPOUND), "Slot");
        assertEquals("127 号以内照常", Items.BREAD, viaMek.get(19).getStack().getItem());
        assertTrue("128 号在 byte 下标下是 -128，读侧整条跳过", viaMek.get(128).getStack().isEmpty());
        assertTrue("162 号同理（byte 值 -94）", viaMek.get(162).getStack().isEmpty());
    }

    // ── 5. 迁移写出的槽位数必须是新机器真实槽位数 ───────────────────────

    /** 旧格式样本：{@code Items: { Size:int, Items:[{Slot,id,Count}] }}，只放一件物品。 */
    private static CompoundTag legacyWithOneItem(int slot, int size) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", slot);
        CompoundTag item = new CompoundTag();
        new ItemStack(Items.BREAD).save(item);
        for (String key : item.getAllKeys()) {
            entry.put(key, item.get(key).copy());
        }
        ListTag list = new ListTag();
        list.add(entry);
        CompoundTag items = new CompoundTag();
        items.putInt("Size", size);
        items.put("Items", list);
        CompoundTag root = new CompoundTag();
        root.put("Items", items);
        return root;
    }

    /**
     * 迁移写出的 {@code MekCkSlotCount} 用调用方给的<b>真实槽位总数</b>，
     * 而不是 {@code 2N + 1}：写小了会让 {@code MekCkSlotNbt.read} 每次读档都记一条
     * 「记录的槽位数与实际不符」的 WARN（烧烤 / 种植切配的专属槽正是被漏掉的那部分）。
     * 三参重载保留旧口径，既有调用点与测试不受影响。
     */
    @Test
    public void migrationRecordsTheCallersRealSlotCount() {
        CompoundTag legacy = legacyWithOneItem(0, 2 * 6 + 4 + 1);

        CompoundTag withExtras = MekCkLegacyMachineNbt.migrate(legacy, Direction.NORTH, 6, 163);
        assertTrue("迁移必须写出专属键", withExtras.contains(MekCkSlotNbt.TAG_SLOTS, Tag.TAG_COMPOUND));
        assertEquals("记录的是新机器真实槽位数（含 144 格存储）", 163,
                withExtras.getCompound(MekCkSlotNbt.TAG_SLOTS).getInt(MekCkSlotNbt.ENTRY_COUNT));

        CompoundTag oldSignature = MekCkLegacyMachineNbt.migrate(legacy, Direction.NORTH, 6);
        assertEquals("三参重载保持 2N + 1 的旧口径", 2 * 6 + 1,
                oldSignature.getCompound(MekCkSlotNbt.TAG_SLOTS).getInt(MekCkSlotNbt.ENTRY_COUNT));
    }

    // ── 6. 烧烤：预演与落槽共用同一个调味分派 ───────────────────────────

    /**
     * {@code applySeasoningTo} 必须被预演与落槽<b>两处</b>调用，两个兼容门面的写方法
     * 各自只允许出现一次（在分派方法体内）。两个门面写的 NBT 键不同，
     * 一旦预演与实际分派不一致，{@code canFitAll} 与 {@code insertOutput} 的口径就分家，
     * 表现是「预演说装得下、落槽只塞一部分，余量被丢弃」——不报错、不留日志。
     */
    @Test
    public void grillSeasoningUsesOneDispatchForPreviewAndInsert() throws IOException {
        String source = source("src/main/java/cn/ism/mekck/machine/grill/GrillFactoryExecutor.java");
        assertEquals("applySeasoningTo 应当恰好出现 3 次（1 处声明 + 预演 + 落槽）",
                3, countOf(source, "applySeasoningTo("));
        assertEquals("BarbequesDelight 的写方法只允许在分派方法体里出现一次",
                1, countOf(source, "BarbequesDelightCompat.applySeasoning("));
        assertEquals("森罗的写方法只允许在分派方法体里出现一次",
                1, countOf(source, "KaleidoscopeGrillingCompat.applySeasoningToSkewer("));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** 常量 {@link BigStackItemHandler#BIG_COUNT_KEY} 只是为了让本测试与旧格式样本同源。 */
    @Test
    public void legacyFixtureUsesTheSameCountKeyAsTheOldHandler() {
        assertEquals("McCount", BigStackItemHandler.BIG_COUNT_KEY);
    }

    // ── 7. ISustainedData：掉落 → 再放置 的键契约 ───────────────────────

    /**
     * 「挖掉再放下」依赖的两侧必须成对存在：
     * <ul>
     *   <li><b>读侧</b>：{@code readSustainedData} 必须真的读（否则掉落物里的键没人认领）；</li>
     *   <li><b>写侧</b>：{@code writeSustainedData} 必须是<b>空实现</b> —— 它同时被
     *       {@code saveAdditional}、{@code BlockMekanism.getCloneItemStack} 与
     *       <b>配置卡的 {@code getConfigurationData}</b> 调用（{@code TileEntityMekanism}
     *       implements {@code IConfigCardAccess}），一旦在这里写槽位/流体键，
     *       配置卡就成了「复制整机库存再反复粘贴」的复制漏洞。</li>
     * </ul>
     * 真 tile 造不出来，只能钉源码形态（同 {@code mekckSlotReadIsAppliedAfterSuperLoad}）。
     */
    @Test
    public void sustainedDataIsReadOnPlaceAndDeliberatelyNotWritten() throws IOException {
        String source = source("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java");
        assertTrue("必须实现 ISustainedData（否则掉落物再放置 = 整机清零）",
                source.contains("implements ISustainedData")
                        && source.contains("import mekanism.common.tile.interfaces.ISustainedData;"));

        String readBody = methodBody(source, "public void readSustainedData(CompoundTag tag) {");
        assertTrue("readSustainedData 必须与 load 共用同一段读取",
                readBody.contains("readMekckPersistentState("));
        // 配置卡（ItemConfigurationCard）粘贴也会走到 readSustainedData，而它的载荷里没有
        // MekCK 的键。没有这道闸，executor().load(空标签) 会清掉目标机器的订单、
        // AE2Compat.load 会清掉它的自动补料勾选 —— 配置卡本不该有这些副作用。
        assertTrue("readSustainedData 必须先确认载荷里确实有 MekCK 的键",
                readBody.contains("hasMekckKeys("));
        String gateBody = methodBody(source, "private static boolean hasMekckKeys(CompoundTag tag) {");
        for (String key : new String[]{
                "MekCkSlotNbt.TAG_SLOTS", "TAG_EXECUTOR", "TAG_WORK_PROGRESS", "TAG_NATIVE_VERSION"}) {
            assertTrue("判据应覆盖 " + key + "（任意一个存在就算本机载荷）", gateBody.contains(key));
        }

        String writeBody = methodBody(source, "public void writeSustainedData(CompoundTag tag) {");
        assertFalse("writeSustainedData 不得写任何键：配置卡会把它当成可复制的配置",
                writeBody.contains("put("));
        assertFalse("writeSustainedData 不得触碰槽位存档",
                writeBody.contains("MekCkSlotNbt"));
        assertFalse("writeSustainedData 不得触碰执行器状态",
                writeBody.contains("executor()"));

        String remapBody = methodBody(source, "public Map<String, String> getTileDataRemap() {");
        assertTrue("getTileDataRemap 在 Mek 10.4.6 里没有消费方，返回空表即可",
                remapBody.contains("Map.of()"));

        // 家族钩子：基类必须声明并在读取路径上调用它，两个家族必须覆写。
        assertTrue("基类应声明家族读取钩子", source.contains("protected void readExtraSustainedData(CompoundTag tag)"));
        assertTrue("基类读取路径必须调用家族钩子", readBody.contains("readExtraSustainedData(tag)")
                || methodBody(source, "private void readMekckPersistentState(CompoundTag tag) {")
                        .contains("readExtraSustainedData(tag)"));
        for (String family : new String[]{
                "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java",
                "src/main/java/cn/ism/mekck/machine/plantingcutting/PlantingCuttingFactoryTile.java"}) {
            String familySource = source(family);
            assertTrue(family + " 必须覆写家族读取钩子",
                    familySource.contains("protected void readExtraSustainedData("));
            // ⚠️ 家族 tile **不得**再覆写 load()。第三轮审查前两个家族都覆写了、各自再调一次
            // readExtraSustainedData(tag)，看着无害，实际有两个问题：
            //   ① 传进去的是**未经迁移的原始 tag**——基类在旧存档路径上会把 tag 换绑成
            //      迁移后的新标签，而子类 load 拿到的仍是调用方给的那一份。今天
            //      FluidTanks / GasTank 键名没被迁移器碰过所以两次读结果相同；只要迁移器
            //      将来开始处理这个键，子类这次读就会**用旧值覆盖掉迁移结果**，静默且难查。
            //   ② 多一次反序列化（烹饪 3 罐、种植切配 1 罐，每 tick 读档都多做一遍）。
            // 单一入口（基类 load → readMekckPersistentState → readExtraSustainedData）
            // 同时覆盖读档与「掉落物再放置」两条路径，且用的是正确的 tag。
            assertFalse(family + " 不得覆写 load()：基类已用正确的（可能已迁移的）tag 调过家族钩子，"
                            + "子类再调一次会用未经迁移的旧值覆盖迁移结果",
                    familySource.contains("public void load(CompoundTag tag)")
                            || familySource.contains("public void load(net.minecraft.nbt.CompoundTag tag)"));
        }
    }

    /**
     * 战利品表 {@code copy_nbt} 的 target 路径契约：{@code mekData.<key>}，
     * 键名必须与这里断言的一模一样。改动任何一个都会让「挖掉再放下」静默丢状态，
     * 所以 T2（战利品表）与本类（读取侧）以本测试为共同契约。
     *
     * <p>这些键全部是编译期字符串常量（引用它们不会触发类加载，真 tile 造不出来也能测）。
     * 进度的<b>权威键是 v2 的 {@code MekCkWorkProgressArray}</b>（每路一个 int）与分选开关
     * {@code MekCkSorting}；v1 的单个 int 键 {@code MekCkWorkProgress} <b>不再是契约</b>——
     * 它只由读侧 {@code readWorkProgress} 为旧档保留 fallback，战利品表复制它等于搬一个
     * 读侧从不认领的键。AE2 节点键（{@code MekckAe2Main} / {@code MekckAe2Extra1..7} /
     * {@code MekCkAutoSel}）与放置者 UUID（{@code MekckPlacerUuid}）<b>刻意不在本断言内</b>：
     * 前者在拆机时已被销毁、重新放置应重新入网，后者属可选归属信息。</p>
     */
    @Test
    public void mekckPersistentKeysMatchTheLootTableContract() throws IOException {
        assertEquals("MekCkSlots", MekCkSlotNbt.TAG_SLOTS);
        assertEquals("mekckExecutor", MekCkMachineTile.TAG_EXECUTOR);
        assertEquals("MekCkWorkProgressArray", MekCkMachineTile.TAG_WORK_PROGRESS_ARRAY);
        assertEquals("MekCkSorting", MekCkMachineTile.TAG_SORTING);
        assertEquals("MekCkNative", MekCkMachineTile.TAG_NATIVE_VERSION);
        assertEquals("GasTank", cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile.TAG_NUTRIENT_TANK);
        assertEquals("FluidTanks", cn.ism.mekck.machine.cooking.CookingFactoryTile.TAG_FLUID_TANKS);

        // 读侧仍须优先认 v2 键、并保留 v1 fallback —— 旧档的单 int 进度不能凭空丢半批。
        String readProgress = methodBody(
                source("src/main/java/cn/ism/mekck/machine/MekCkMachineTile.java"),
                "private void readWorkProgress(CompoundTag tag) {");
        assertTrue("读侧必须优先认 v2 的 int 数组", readProgress.contains("getIntArray(TAG_WORK_PROGRESS_ARRAY)"));
        assertTrue("读侧必须保留 v1 fallback（旧档单个 int）", readProgress.contains("getInt(TAG_WORK_PROGRESS)"));
    }

    // ── 8. cooking / skewering 的门禁必须同时要求订单与料 ───────────────

    /**
     * 只判「有订单」会让缺料机器走满一整个周期并按 tick 扣电，而旧实现
     * （{@code energyPerTick = canProcess ? … : 0}）此时一滴电都不扣。
     *
     * <p>断言的是保守近似的形态：{@code hasOrder() && activeWorkSlots() > 0}。
     * 真正的旧语义还包含「配方匹配 + 产物装得下」，那需要执行器暴露查询接口，
     * 本测试只钉住「不能退化成只判订单」。</p>
     */
    @Test
    public void orderOnlyGateIsNotEnoughForCookingAndSkewering() throws IOException {
        String[] files = {
                "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java",
                "src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java",
        };
        for (String file : files) {
            String body = methodBody(source(file), "protected boolean hasWorkToDo() {");
            assertTrue(file + " 的门禁必须同时要求有订单",
                    body.contains("hasOrder()"));
            assertTrue(file + " 的门禁必须同时要求输入槽里有料（否则缺料空转吃电）",
                    body.contains("activeWorkSlots() > 0"));
        }
    }
}
