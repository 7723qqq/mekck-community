package cn.ism.mekck.machine;

import cn.ism.mekck.ae2.AE2InputSpec;
import cn.ism.mekck.recipe.RecipeInputMatcher;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>无档位单机 tile 的公共基类</b> —— 补上 AE2「网络拉料 / 自动补料」这套能力。
 *
 * <h3>为什么必须有它</h3>
 * {@code IMekCkPorted} 与 {@code INetworkPullable} 是 AE2 的两条路：
 * <ul>
 *   <li><b>工厂家族</b>走 {@code IMekCkPorted}（端口声明），因为它们的输入槽位多、
 *       由档位决定，且经 {@code MekCkMachineTile} 统一实现；</li>
 *   <li><b>单机</b>走 {@code INetworkPullable}（{@code getInputSlotRange} + 一个
 *       {@code ItemStackHandler} 视图）。</li>
 * </ul>
 *
 * <p>{@code MekCkMachineTile}（工厂基类）已经实现了 {@code INetworkPullable}，
 * 所以 6 个工厂家族 + 电力烧烤架自动有这套能力。<b>但单机没有对应的基类</b>
 * —— 每台单机都得自己写一遍，于是：</p>
 * <ul>
 *   <li>烧烤架（已迁）自己写了一份；</li>
 *   <li><b>电力研磨机在迁移时整份漏掉了</b> —— 新 tile 只 implements {@code MenuProvider}，
 *       AE2 的「网络拉料」「自动补料」「面板下单」全部静默消失。
 *       编译通过、732 个测试全绿，因为<b>没有任何护栏守这台机器的能力清单</b>。</li>
 * </ul>
 *
 * <h3>本类提供什么</h3>
 * 把 {@code INetworkPullable} 那五个方法的通用形态收在这里，子类只需回答两个问题：
 * <ol>
 *   <li>{@link #networkPullRecipeTypeIds()} —— 本机处理哪几类配方（用于空输入槽时的补料并集）；</li>
 *   <li>{@link #networkPullSlots()} —— 参与拉料的槽位（通常就是输入槽）。</li>
 * </ol>
 *
 * <p>{@code getNetworkPullItems()} 用 {@link MekCkSlotHandler} 做适配：它是
 * {@code IInventorySlot -> ItemStackHandler} 的<b>只读视图</b>，不持有任何
 * {@code ItemStack}，读写直接落到槽对象上，因此 AE2 看到的永远是机器真实内容。</p>
 *
 * <h3>空输入槽时的补料并集为什么重要</h3>
 * 空槽时无法从机器反推"该拉什么"，只能按本机支持的配方类型取<b>首个 ingredient 的并集</b>。
 * 取错了会怎样（本仓实测过一次）：插入侧 {@code IntHandlerBulkView} 会先查
 * {@code isItemValid}，抽出来的料若全被拒，就被 {@code BigStackDrops} 掉在机器旁
 * —— <b>物品离开了 ME 网络</b>。所以这个并集必须与本机 {@code isItemValid} 认可的范围一致。
 */
public abstract class MekCkNetworkPullableTile extends TileEntityConfigurableMachine
        implements cn.ism.mekck.ae2.INetworkPullable {

    /**
     * {@link MekCkSlotHandler} 视图缓存。
     *
     * <p>构造期不能建：{@code getInitialInventory} 在父类构造器内部被回调，
     * 那一刻子类的槽位字段还没赋值。所以懒加载 —— 只在 AE2 真正来取时才建。</p>
     */
    private ItemStackHandler pullItemsView;

    protected MekCkNetworkPullableTile(mekanism.api.providers.IBlockProvider blockProvider,
                                       net.minecraft.core.BlockPos pos,
                                       net.minecraft.world.level.block.state.BlockState state) {
        super(blockProvider, pos, state);
    }

    // ── 子类要回答的两个问题 ────────────────────────────────────────────

    /**
     * 本机处理的配方类型注册名（{@code namespace:path}）。
     *
     * <p>只用于「输入槽为空时该拉什么」的并集计算，见类注释。返回空表表示
     * 本机不支持空槽补料（此时只有槽里有样品才能拉料）。</p>
     */
    protected List<String> networkPullRecipeTypeIds() {
        return List.of();
    }

    /** 参与网络拉料的槽位（只读）。通常就是 {@code getInputSlots()}。 */
    protected abstract List<IInventorySlot> networkPullSlots();

    // ── INetworkPullable ────────────────────────────────────────────────

    @Override
    public BlockEntity getNetworkPullable() {
        return this;
    }

    /**
     * 拉料把材料插进 {@code [0, 槽数)} —— 视图里只有输入槽，产物槽不参与。
     *
     * <p>区间是 <b>{@code MekCkSlotHandler} 视图的下标</b>，不是 menu.slots 下标。</p>
     */
    @Override
    public int[] getInputSlotRange() {
        List<IInventorySlot> slots = networkPullSlots();
        return slots == null ? new int[]{0, 0} : new int[]{0, slots.size()};
    }

    @Override
    public ItemStackHandler getNetworkPullItems() {
        ItemStackHandler view = pullItemsView;
        if (view == null) {
            List<IInventorySlot> slots = networkPullSlots();
            if (slots == null) {
                return null;
            }
            view = new MekCkSlotHandler(slots);
            pullItemsView = view;
        }
        return view;
    }

    @Override
    public boolean supportsAutoPull() {
        return true;
    }

    /**
     * 当前该从网络拉什么材料。
     *
     * <p>输入槽有样品 ⇒ 就拉那一样；空槽 ⇒ 取本机配方类型表里首个 ingredient 的并集。</p>
     */
    @Override
    public List<AE2InputSpec> getNetworkPullInputs() {
        List<IInventorySlot> slots = networkPullSlots();
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        ItemStack first = slots.get(0).getStack();
        if (!first.isEmpty()) {
            return List.of(new AE2InputSpec(Ingredient.of(first.getItem())));
        }
        List<Ingredient> unions = new ArrayList<>();
        for (String typeId : networkPullRecipeTypeIds()) {
            Ingredient union = RecipeInputMatcher.unionFirstIngredients(
                    getLevel(), new ResourceLocation(typeId));
            if (!union.isEmpty()) {
                unions.add(union);
            }
        }
        if (unions.isEmpty()) {
            return List.of();
        }
        return List.of(new AE2InputSpec(Ingredient.merge(unions)));
    }
}
