package cn.ism.mekck.blockentity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * WINERY 专属的 KaleidoscopeTavern 酿造批次状态组件（第一阶段：数据模型 + 状态转换 + NBT）。
 * <p>
 * 语义对齐参考示例 BarrelBlockEntity：
 * <ul>
 *   <li>批次瓶数 = 输入配料槽中最少物品数量，默认上限 16（纯流体配方固定 16）；</li>
 *   <li>brewLevel 0→6，每阶段时长 = {@link #stageUnit()} × 当前level（level 从 1 起），满级(6)后停止升级；
 *       {@code stageUnit = 配方 unit_time / 15} ⇒ **整批（起批→满级）总长 = unit_time**（用户 2026-09-25 拍板，
 *       与陈酿机酿一瓶葡园酒香同长；原版语义的 unit_time 是每阶段单位量，总长为其 15 倍）；
 *       **吃速度升级**（用户 2026-09-25 追加）：整批实际耗时 = {@code unit_time ÷ 10^(速度升级数/8)}，
 *       预算与进度柱口径不变，只是被更快烧完（见 {@link #tickBatch(double)}）；</li>
 *   <li>可分装条件 = 批次已陈到满级（level ≥ 6 典藏）且**完工扣料事务已完成**（consumed）且剩余瓶数 &gt; 0
 *       （用户 2026-09-25 两次拍板：满级门槛 + 完工才扣料）；</li>
 *   <li>剩余瓶数归零 → 批次清空（等价 resetIfOutputEmpty）。</li>
 * </ul>
 * <p>
 * 本组件只持有批次数据与状态转换；创建/持守校验/满级扣料/分装事务由
 * {@code SimpleMachineBlockEntity} 在验证容器、输出空间后调用。
 */
public final class TavernBrewBatch {

    public static final int MAX_BOTTLES = 16;
    public static final int BREWING_NOT_STARTED = 0;
    public static final int BREWING_STARTED = 1;
    public static final int BREWING_FINISHED = 6;

    /**
     * 起批（level 1）到满级（level 6）共经历 5 个阶段，原版按等级加权 ⇒ 总时长 = 单位量 × (1+2+3+4+5)。
     * <p>用户 2026-09-25 拍板：把「一桶酒陈到可出货」的总长拉平到与陈酿机酿一瓶葡园酒香同长
     * （{@code MachineKind.WINERY.processTime = 2400 tick = 2 分钟；酒馆 24 条 barrel 配方的
     * {@code unit_time} 实测全为 2400），而**不破坏原版「等级越高越慢」的 1:2:3:4:5 曲线**
     * ⇒ 内部单位量 = {@code unit_time / STAGE_UNITS}（2400/15 = 160）。</p>
     * <p>闭式：1+2+…+(BREWING_FINISHED-1) = (n-1)×n/2 = 15。缩放只作用于推进计时，
     * {@link #unitTime} 字段仍保存**配方原值**（参与 NBT 持久化与合法性校验），
     * 故旧存档里存着 2400 的进行中批次读档后也会自动归到新节奏，无需数据迁移。</p>
     */
    private static final int STAGE_UNITS = (BREWING_FINISHED - 1) * BREWING_FINISHED / 2;

    private static final String NBT_STATE = "State";
    private static final String NBT_RECIPE = "RecipeId";
    private static final String NBT_RESULT = "ResultId";
    private static final String NBT_TOTAL = "TotalBottles";
    private static final String NBT_REMAIN = "RemainingBottles";
    private static final String NBT_LEVEL = "BrewLevel";
    private static final String NBT_TIME = "BrewTimeRemaining";
    private static final String NBT_UNIT = "UnitTime";
    private static final String NBT_CARRIER = "CarrierJson";
    private static final String NBT_CONSUMED = "Consumed";
    private static final String NBT_HOLD_SLOTS = "HoldSlots";
    private static final String NBT_HOLD_SNAP = "HoldSnap";
    private static final String NBT_HOLD_SLOT = "Slot";
    private static final String NBT_HOLD_STACK = "Stack";
    private static final String NBT_HOLD_FLUID_NAME = "HoldFluidName";
    private static final String NBT_HOLD_FLUID_AMOUNT = "HoldFluidAmount";

    /** 状态：IDLE=无批次；BREWING=批次存在且酿造中（可分装）；STALLED=批次数据损坏/配方不可恢复时的安全停滞。 */
    public enum State { IDLE, BREWING, STALLED }

    private State state = State.IDLE;
    private ResourceLocation recipeId;
    private ResourceLocation resultItemId;      // 成品身份（酒类物品）
    private int totalBottles;                    // 初始瓶数（1..16）
    private int remainingBottles;                // 剩余可装瓶数
    private int brewLevel;                       // 0..6
    private int brewTimeRemaining;               // 当前阶段剩余 tick（陈化预算，按速度升级倍率每 tick 折算消耗）
    private double brewTickAccumulator;          // 速度升级小数余量累加器（每游戏 tick 累加 speedMult−1 的零头，攒满 1 tick 才扣预算）
    private int unitTime;                        // 配方 unitTime 副本（配方消失时仍可推进）
    private String carrierJson;                  // carrier(Ingredient) 的 JSON 序列化副本
    private net.minecraft.nbt.CompoundTag corruptNbt; // STALLED 时保留的原始批次数据（供恢复，不丢失已投入材料信息）

    // ── 完工扣料（用户 2026-09-25 工单：陈化期间不扣料，满级才扣；失配归零） ──
    /** 完工扣料是否已完成；满级且 consumed 才可分装。旧签名/旧存档默认 true（旧口径起批即扣）。 */
    private boolean consumed = true;
    private java.util.List<Integer> holdSlots = java.util.List.of();
    private java.util.List<net.minecraft.world.item.ItemStack> holdSnapshots = java.util.List.of();
    /** 陈化期间持有的流体要求（名字, 量）；amount≤0 = 无要求（比较时走 ≥，完工恰好抽走该量）。 */
    private String holdFluidName = "";
    private int holdFluidAmount;

    public TavernBrewBatch() {
        reset();
    }

    // ── 状态查询 ──

    public State getState() { return state; }
    public boolean isIdle() { return state == State.IDLE; }
    public boolean isBrewing() { return state == State.BREWING; }
    public boolean isStalled() { return state == State.STALLED; }
    public boolean isMaxBrewLevel() { return brewLevel >= BREWING_FINISHED; }
    public ResourceLocation getRecipeId() { return recipeId; }
    public ResourceLocation getResultItemId() { return resultItemId; }
    public int getTotalBottles() { return totalBottles; }
    public int getRemainingBottles() { return remainingBottles; }
    public int getBrewLevel() { return brewLevel; }
    public int getBrewTimeRemaining() { return brewTimeRemaining; }
    public int getUnitTime() { return unitTime; }

    /**
     * 每阶段的单位时长 = 配方 {@code unit_time} / {@link #STAGE_UNITS}（至少 1 tick）。
     * <p>效果：整批从起批到满级的总长恰为配方的 unit_time（2400 tick = 2 分钟），
     * 与陈酿机做葡园酒香葡萄酒的耗时对齐；理由与推导见 {@link #STAGE_UNITS}。</p>
     */
    private int stageUnit() {
        return Math.max(1, unitTime / STAGE_UNITS);
    }

    /**
     * 整批陈化的总时长（陈化预算 tick，未除速度倍率）；非酿造中返回 0（调用方据此避开除零）。
     * <p>供 GUI 进度柱当分母：与 {@link #getBrewElapsedTicks()} 同口径。两者都是**预算 tick**单量：
     * 速度升级不改变该总量，只改变它被消耗的游戏速度（见 {@link #tickBatch(double)}）⇒ 柱子依旧从空到满，只是走得快。</p>
     */
    public int getBrewTotalTicks() {
        if (state != State.BREWING) return 0;
        return stageUnit() * STAGE_UNITS;
    }

    /**
     * 整批陈化已走过的 tick（进度柱分子）：已完成阶段的权重 + 当前阶段已陈部分；满级后恒等于总时长。
     * <p>取**整批**而非当前阶段的比例，是为了让「柱子满 = 开始出酒」直接成立；
     * 若按阶段重置，同一根柱会在满级前反复充满 5 次，看起来反而像卡住。</p>
     */
    public int getBrewElapsedTicks() {
        if (state != State.BREWING) return 0;
        int unit = stageUnit();
        int total = unit * STAGE_UNITS;
        if (isMaxBrewLevel() || brewTimeRemaining < 0) return total;
        int completed = unit * (brewLevel - 1) * brewLevel / 2; // 已完成阶段权重 1+2+…+(level-1)
        int currentTotal = unit * brewLevel;
        int inStage = Math.max(0, Math.min(currentTotal, currentTotal - brewTimeRemaining));
        return Math.min(total, completed + inStage);
    }
    public String getCarrierJson() { return carrierJson; }

    /**
     * 可分装：批次已陈到**满级（level ≥ {@link #BREWING_FINISHED} 典藏）** 且剩余瓶数 &gt; 0。
     * <p>用户 2026-09-25 拍板。原先是 {@code level ≥ 1} 即可分装，叠加 {@code tryTavernDispense} 的
     * 20 tick 固定节奏 ⇒ 起批约 16 秒就把整批（最多 16 瓶）抽干，出货清一色 1 级「难以下咽」；
     * 而参考实现 {@code BarrelBlockEntity} 的语义是酒**留在桶里继续陈化**（每阶段 2400×level tick），
     * 玩家主动取瓶时才按当时等级结算 ⇒ 机器把「取」自动化了，就必须把「等陈够」这一半补回来。</p>
     * <p>代价：起批到首次出货需 {@code stageUnit() × (1+2+3+4+5) = 配方 unit_time} tick
     * （实测酒馆 24 条 barrel 配方 {@code unit_time = 2400}，即 2 分钟；已与陈酿机酿一瓶葡园酒香同长）。
     * 陈化期间的总进度已由 vinery 同款竖直进度柱承担（{@code SimpleMachineBlockEntity#containerProgress}，
     * 用户 2026-09-25 第二批工单）；但**等级数字**仍未同步（{@code ContainerData} 无 brewLevel 槽）。</p>
     */
    public boolean canDispense() {
        return state == State.BREWING && isMaxBrewLevel() && consumed && remainingBottles > 0;
    }

    // ── 完工扣料支撑（调用方：SimpleMachineBlockEntity 陈化持守校验与满级扣料） ──

    /** 完工扣料是否已完成（旧存档/旧签名恒 true）。 */
    public boolean isConsumed() { return consumed; }

    /** 满级扣料事务成功后置位：此后才允许分装，且不再参与持守校验（配料槽已清空，可供他用）。 */
    void markConsumed() { this.consumed = true; }

    public java.util.List<Integer> getHoldSlots() { return holdSlots; }
    public java.util.List<net.minecraft.world.item.ItemStack> getHoldSnapshots() { return holdSnapshots; }
    public String getHoldFluidName() { return holdFluidName; }
    public int getHoldFluidAmount() { return holdFluidAmount; }

    // ── 状态转换 ──

    /**
     * 创建批次（旧口径：起批即扣，恒 consumed=true；供测试与兼容调用）。
     * 校验：瓶数 ∈ [1,16]，recipeId/result/carrier 非空；非法返回 false 且不修改状态。
     */
    boolean createBatch(ResourceLocation recipeId, ResourceLocation resultItemId,
                               int bottles, int unitTime, String carrierJson) {
        return createBatch(recipeId, resultItemId, bottles, unitTime, carrierJson, null, null, "", 0, false);
    }

    /**
     * 创建批次（完工扣料口径，用户 2026-09-25 工单）：携带陈化期持守快照（配料槽索引+内容、流体要求），
     * 起批**不扣任何资源**（consumed=false）；陈化期间调用方每 tick 比对快照，失配则中止批次；
     * 满级时由调用方执行扣料事务并 {@link #markConsumed()}，此后才可分装。
     * holdSlots/holdSnapshots 必须同长（可空表）；fluidName/fluidAmount ≤0 表示无流体要求。
     */
    boolean createBatch(ResourceLocation recipeId, ResourceLocation resultItemId,
                               int bottles, int unitTime, String carrierJson,
                               java.util.List<Integer> holdSlots,
                               java.util.List<net.minecraft.world.item.ItemStack> holdSnapshots,
                               String holdFluidName, int holdFluidAmount, boolean deferConsume) {
        // 拒绝覆盖非 IDLE 状态（BREWING/STALLED 均不接受新批次）——不依赖外层调用者检查
        if (state != State.IDLE) return false;
        if (recipeId == null || resultItemId == null) return false;
        if (bottles < 1 || bottles > MAX_BOTTLES) return false;
        if (unitTime < 1) return false;
        if (carrierJson == null || carrierJson.isBlank()) return false;
        if (deferConsume) {
            if (holdSlots == null || holdSnapshots == null || holdSlots.size() != holdSnapshots.size()) return false;
        }
        this.state = State.BREWING;
        this.recipeId = recipeId;
        this.resultItemId = resultItemId;
        this.totalBottles = bottles;
        this.remainingBottles = bottles;
        this.brewLevel = BREWING_STARTED;
        // 注意赋值顺序：stageUnit() 读的是 unitTime 字段，必须先存后算（否则第一阶段拿到 0/15 钳成 1 tick，
        // 整批会比 unit_time 提前 159 tick 满级；由 TestTavernBrewBatch 的计时断言抓到）。
        this.unitTime = unitTime;
        // 第一阶段时长 = stageUnit() × 1（unitTime 入参按配方原值校验并存档，缩放只在 stageUnit() 里生效）
        this.brewTimeRemaining = stageUnit();
        this.brewTickAccumulator = 0; // 新批次从零头累加器起算
        this.carrierJson = carrierJson;
        // 完工扣料口径：存快照、consumed=false；旧口径：清快照、consumed=true
        this.consumed = !deferConsume;
        this.holdSlots = deferConsume ? java.util.List.copyOf(holdSlots) : java.util.List.of();
        if (deferConsume) {
            java.util.List<net.minecraft.world.item.ItemStack> copies = new java.util.ArrayList<>();
            for (net.minecraft.world.item.ItemStack s : holdSnapshots) copies.add(s.copy());
            this.holdSnapshots = copies;
            this.holdFluidName = holdFluidName == null ? "" : holdFluidName;
            this.holdFluidAmount = Math.max(0, holdFluidAmount);
        } else {
            this.holdSnapshots = java.util.List.of();
            this.holdFluidName = "";
            this.holdFluidAmount = 0;
        }
        return true;
    }

    /**
     * 品质推进（无速度升级口径，等价 {@code tickBatch(1.0)}）：供测试与旧语义调用。
     */
    boolean tickBatch() {
        return tickBatch(1.0);
    }

    /**
     * 品质推进（服务端每 tick 调用）：每阶段预算 = {@link #stageUnit()} × 当前 level（level 从 1 起），
     * 满级后停止升级。<b>速度升级以「每游戏 tick 消耗 {@code speedMult} 个陈化 tick」的方式生效</b>：
     * 内部阶段预算与进度柱口径（{@link #getBrewTotalTicks()}/{@link #getBrewElapsedTicks()}）完全不变，
     * 只是被更快地烧掉 ⇒ 1:2:3:4:5 曲线原样保留、总耗时缩短为 {@code 整批 ÷ speedMult}。
     * <p>{@code speedMult≤1} 时每 tick 恰好扣 1 个预算 tick（与无升级逐 tick 完全等价，零头累加器恒为 0）；
     * {@code speedMult>1} 时零头进 {@link #brewTickAccumulator} 攒满再扣，一步可能跨多个阶段（高速/创造下），
     * 故逐级结算并把溢出结转给下一阶段。</p>
     * 返回本 tick 是否发生至少一次阶段提升。
     */
    boolean tickBatch(double speedMult) {
        if (state != State.BREWING || isMaxBrewLevel()) {
            brewTickAccumulator = 0;
            return false;
        }
        double rate = Math.max(1.0, speedMult);
        brewTickAccumulator += rate;
        int steps = (int) brewTickAccumulator;
        if (steps <= 0) return false;
        brewTickAccumulator -= steps;
        brewTimeRemaining -= steps;
        if (brewTimeRemaining > 0) return false;
        boolean leveled = false;
        while (!isMaxBrewLevel() && brewTimeRemaining <= 0) {
            brewLevel = Math.min(brewLevel + 1, BREWING_FINISHED);
            leveled = true;
            if (!isMaxBrewLevel()) {
                brewTimeRemaining += stageUnit() * brewLevel; // 结转溢出到下一阶段
            } else {
                brewTimeRemaining = 0; // 满级：无后续阶段
            }
        }
        return leveled;
    }

    /**
     * 消耗一个批次计数（下一阶段由分装事务调用）。剩余为 0 时返回 false，不允许负数。
     * 消耗至 0 后自动清空批次（等价参考实现的 resetIfOutputEmpty）。
     */
    boolean consumeOne() {
        if (!canDispense()) return false;
        remainingBottles--;
        if (remainingBottles <= 0) {
            reset();
        }
        return true;
    }

    /** 清空批次（等价参考实现的 resetIfOutputEmpty / 初始化）。 */
    void reset() {
        state = State.IDLE;
        recipeId = null;
        resultItemId = null;
        totalBottles = 0;
        remainingBottles = 0;
        brewLevel = BREWING_NOT_STARTED;
        brewTimeRemaining = -1;
        brewTickAccumulator = 0;
        unitTime = 0;
        carrierJson = null;
        corruptNbt = null;
        consumed = true;
        holdSlots = java.util.List.of();
        holdSnapshots = java.util.List.of();
        holdFluidName = "";
        holdFluidAmount = 0;
    }

    // ── NBT 保存/恢复 ──

    /** 保存到独立键（调用方放入机器 NBT，不与 progress/CurrentRecipeId/流体/AE2 键冲突）。 */
    void save(CompoundTag tag) {
        // STALLED：原样保留损坏批次原始数据（不丢失已投入材料信息，供人工/未来恢复）
        if (state == State.STALLED && corruptNbt != null) {
            for (String key : corruptNbt.getAllKeys()) {
                tag.put(key, corruptNbt.get(key));
            }
            return;
        }
        tag.putByte(NBT_STATE, (byte) state.ordinal());
        if (recipeId != null) tag.putString(NBT_RECIPE, recipeId.toString());
        if (resultItemId != null) tag.putString(NBT_RESULT, resultItemId.toString());
        tag.putInt(NBT_TOTAL, totalBottles);
        tag.putInt(NBT_REMAIN, remainingBottles);
        tag.putInt(NBT_LEVEL, brewLevel);
        tag.putInt(NBT_TIME, brewTimeRemaining);
        tag.putInt(NBT_UNIT, unitTime);
        if (carrierJson != null) tag.putString(NBT_CARRIER, carrierJson);
        tag.putBoolean(NBT_CONSUMED, consumed);
        if (!consumed) {
            // 完工扣料口径的陈化持守快照（配料槽索引+内容、流体要求）：随机器存档，重启后继续比对
            int[] slots = new int[holdSlots.size()];
            for (int i = 0; i < slots.length; i++) slots[i] = holdSlots.get(i);
            tag.putIntArray(NBT_HOLD_SLOTS, slots);
            net.minecraft.nbt.ListTag snaps = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < holdSnapshots.size(); i++) {
                CompoundTag entry = new CompoundTag();
                entry.putInt(NBT_HOLD_SLOT, holdSlots.get(i));
                entry.put(NBT_HOLD_STACK, holdSnapshots.get(i).save(new CompoundTag()));
                snaps.add(entry);
            }
            tag.put(NBT_HOLD_SNAP, snaps);
            tag.putString(NBT_HOLD_FLUID_NAME, holdFluidName);
            tag.putInt(NBT_HOLD_FLUID_AMOUNT, holdFluidAmount);
        }
    }

    /**
     * 从独立键恢复。安全处理：
     * <ul>
     *   <li>无批次字段 → 空闲（旧存档/不影响 Vinery）；</li>
     *   <li>字段存在但损坏（缺身份/负/超上限）→ <b>STALLED 停滞</b>：保留原始数据副本
     *       （不凭空恢复产物，也不静默清空可能已投入材料的真实批次；save 原样保留供恢复）；</li>
     *   <li>配方在数据包更新后消失：recipeId 保留、可用 unitTime 副本继续推进（安全停滞），
     *       不直接清空玩家已投入批次；分装由下一阶段按"配方仍存在"决定。</li>
     * </ul>
     */
    void load(CompoundTag tag) {
        reset();
        if (tag == null || !tag.contains(NBT_STATE)) return;
        int st = tag.getByte(NBT_STATE) & 0xFF;
        if (st == State.STALLED.ordinal()) {
            // 停滞批次：保留原始数据副本，保持停滞
            this.state = State.STALLED;
            this.corruptNbt = tag.copy();
            return;
        }
        if (st != State.BREWING.ordinal()) return; // IDLE 或未知 → 空闲
        ResourceLocation rId = tag.contains(NBT_RECIPE)
                ? ResourceLocation.tryParse(tag.getString(NBT_RECIPE)) : null;
        ResourceLocation resId = tag.contains(NBT_RESULT)
                ? ResourceLocation.tryParse(tag.getString(NBT_RESULT)) : null;
        int total = tag.getInt(NBT_TOTAL);
        int remain = tag.getInt(NBT_REMAIN);
        int level = tag.getInt(NBT_LEVEL);
        int time = tag.getInt(NBT_TIME);
        int unit = tag.getInt(NBT_UNIT);
        if (rId == null || resId == null
                || total < 1 || total > MAX_BOTTLES
                || remain < 0 || remain > total
                || level < BREWING_STARTED || level > BREWING_FINISHED
                || unit < 1) {
            // 已投入材料的批次数据损坏 → 停滞保留原始数据，而不是静默清空
            this.state = State.STALLED;
            this.corruptNbt = tag.copy();
            return;
        }
        this.state = State.BREWING;
        this.recipeId = rId;
        this.resultItemId = resId;
        this.totalBottles = total;
        this.remainingBottles = remain;
        this.brewLevel = level;
        this.brewTimeRemaining = Math.max(-1, time); // -1 = 满级后无计时
        this.unitTime = unit;
        this.carrierJson = tag.contains(NBT_CARRIER) ? tag.getString(NBT_CARRIER) : null;
        // 完工扣料字段：无 Consumed 键（旧存档）⇒ 恒已扣料，维持旧行为；有快照才允许未扣态
        boolean cons = !tag.contains(NBT_CONSUMED) || tag.getBoolean(NBT_CONSUMED);
        if (!cons) {
            if (!tag.contains(NBT_HOLD_SLOTS) || !tag.contains(NBT_HOLD_SNAP)) {
                // 声称未扣料却无持守快照（数据损坏）⇒ 停滞保留，不凭空扣/退任何材料
                this.state = State.STALLED;
                this.corruptNbt = tag.copy();
                return;
            }
            int[] slots = tag.getIntArray(NBT_HOLD_SLOTS);
            net.minecraft.nbt.ListTag snaps = tag.getList(NBT_HOLD_SNAP, 10);
            if (slots.length != snaps.size()) {
                this.state = State.STALLED;
                this.corruptNbt = tag.copy();
                return;
            }
            java.util.List<ItemStack> copies = new java.util.ArrayList<>();
            for (int i = 0; i < snaps.size(); i++) {
                CompoundTag entry = snaps.getCompound(i);
                if (entry.getInt(NBT_HOLD_SLOT) != slots[i]) {
                    this.state = State.STALLED;
                    this.corruptNbt = tag.copy();
                    return;
                }
                copies.add(ItemStack.of(entry.getCompound(NBT_HOLD_STACK)));
            }
            this.consumed = false;
            this.holdSlots = java.util.List.of();
            for (int s : slots) this.holdSlots = concatSlot(this.holdSlots, s);
            this.holdSnapshots = java.util.List.copyOf(copies);
            this.holdFluidName = tag.getString(NBT_HOLD_FLUID_NAME);
            this.holdFluidAmount = Math.max(0, tag.getInt(NBT_HOLD_FLUID_AMOUNT));
        } else {
            this.consumed = true;
        }
    }

    /** 只读 List 追加（load 路径专用，避开不可变列表 add）。 */
    private static java.util.List<Integer> concatSlot(java.util.List<Integer> base, int slot) {
        java.util.List<Integer> out = new java.util.ArrayList<>(base);
        out.add(slot);
        return out;
    }
}
