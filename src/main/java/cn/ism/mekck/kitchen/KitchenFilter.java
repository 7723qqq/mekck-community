package cn.ism.mekck.kitchen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * 中央厨房**每个系列的处理能力过滤器**：控制「自动加工模式」下该系列可以处理哪些配方。
 *
 * <p>过滤依据是**配方的输入材料**（Ingredient）：</p>
 * <ul>
 *   <li>{@link Mode#OFF}：不过滤，处理该系列的全部配方（默认）；</li>
 *   <li>{@link Mode#WHITELIST}：配方用到的**每一种**材料都必须在列表内，才允许处理
 *       （例如"只切土豆和胡萝卜"）；</li>
 *   <li>{@link Mode#BLACKLIST}：配方只要用到列表中的**任意一种**材料就跳过
 *       （例如"别动我的松露"）。</li>
 * </ul>
 *
 * <p>过滤器只影响**自动加工模式**；手动下单与 ME 下单不受影响。</p>
 */
public final class KitchenFilter {

    /** 过滤模式。 */
    public enum Mode {
        /** 不过滤（默认）。 */
        OFF,
        /** 白名单：只处理材料全部在列表内的配方。 */
        WHITELIST,
        /** 黑名单：跳过用到列表内任一材料的配方。 */
        BLACKLIST
    }

    /** 每个系列最多记录的过滤材料数。 */
    public static final int MAX_ITEMS = 9;

    private Mode mode = Mode.OFF;
    private final List<ItemStack> items = new ArrayList<>();

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.OFF : mode;
    }

    /** 循环切换模式：关 → 白名单 → 黑名单 → 关。 */
    public Mode cycleMode() {
        this.mode = switch (this.mode) {
            case OFF -> Mode.WHITELIST;
            case WHITELIST -> Mode.BLACKLIST;
            case BLACKLIST -> Mode.OFF;
        };
        return this.mode;
    }

    public List<ItemStack> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean isFiltering() {
        return mode != Mode.OFF && !items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public ItemStack get(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : ItemStack.EMPTY;
    }

    /** 添加一种过滤材料（按物品去重，最多 {@link #MAX_ITEMS} 种）。返回是否添加成功。 */
    public boolean add(ItemStack stack) {
        if (stack == null || stack.isEmpty() || items.size() >= MAX_ITEMS) return false;
        for (ItemStack existing : items) {
            if (ItemStack.isSameItemSameTags(existing, stack)) return false;
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        items.add(copy);
        return true;
    }

    /** 移除第 index 项。 */
    public boolean remove(int index) {
        if (index < 0 || index >= items.size()) return false;
        items.remove(index);
        return true;
    }

    public void clear() {
        items.clear();
    }

    /**
     * 依据配方输入材料判断是否允许处理。
     *
     * @param ingredients 配方的材料（会跳过空 Ingredient）
     */
    public boolean allows(List<Ingredient> ingredients) {
        if (mode == Mode.OFF || items.isEmpty()) return true;
        if (mode == Mode.WHITELIST) {
            for (Ingredient ing : ingredients) {
                if (ing == null || ing.isEmpty()) continue;
                if (!matchesAny(ing)) return false;
            }
            return true;
        }
        // BLACKLIST
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) continue;
            if (matchesAny(ing)) return false;
        }
        return true;
    }

    /** 该 Ingredient 是否与列表中任一过滤材料匹配。 */
    private boolean matchesAny(Ingredient ing) {
        for (ItemStack filter : items) {
            if (filter.isEmpty()) continue;
            try {
                if (ing.test(filter)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ================== NBT ==================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Mode", (byte) mode.ordinal());
        ListTag list = new ListTag();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) list.add(stack.save(new CompoundTag()));
        }
        tag.put("Items", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag == null) return;
        int ord = tag.getByte("Mode");
        Mode[] modes = Mode.values();
        mode = (ord >= 0 && ord < modes.length) ? modes[ord] : Mode.OFF;
        items.clear();
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && items.size() < MAX_ITEMS; i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) items.add(stack);
        }
    }
}
