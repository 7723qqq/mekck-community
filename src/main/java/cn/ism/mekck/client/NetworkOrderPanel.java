package cn.ism.mekck.client;

import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.network.NetworkMissingRequestPacket;
import cn.ism.mekck.network.NetworkRecipeRequestPacket;
import cn.ism.mekck.compat.AE2Compat;
import mekanism.client.gui.GuiUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 面板下单的「ME 来源」面板（AE 终端风格）—— 从 {@code CookingFactoryScreen} /
 * {@code SkeweringFactoryScreen} 已验收的 ME 段抽出，供其余屏幕共用
 * （烧烤工厂 / 智能穿串机 / 智能厨锅 / 中央厨房下单窗 / 联动机器…）。
 *
 * <p>宿主屏幕只用做三件事：</p>
 * <ol>
 *   <li>{@code bind(pos)}：下单面板打开时绑定机器坐标（会自动拉取网络配方）；</li>
 *   <li>渲染分支：{@code isMe()} 为真时调 {@link #render}，否则照旧画本机面板，
 *       再调 {@link #renderModeButtons} 画「本机 / ME」切换；</li>
 *   <li>点击分支：{@code isMe()} 为真时调 {@link #mouseClicked}，否则本机逻辑之前先调
 *       {@link #handleModeClick}。</li>
 * </ol>
 *
 * <p>服务端数据（配方表 / 可做份数 / 缺料清单）由 {@link NetworkOrderHost} 接收回包后转交
 * {@link #setData} / {@link #setMissing}；下单通过 {@link OrderSink} 回调宿主发包
 * （一般是 {@code NetworkOrderPacket}，烧烤工厂可带上调味）。</p>
 */
public final class NetworkOrderPanel {

    /** 下单回调：宿主按自己的通道发包（数量单位 = 份）。 */
    public interface OrderSink {
        void order(ResourceLocation recipeId, int quantity);
    }

    /**
     * **本机**数据源：宿主提供"机器自己输入槽里能做的配方 / 可做份数 / 本机下单通道"。
     *
     * <p>提供之后 {@code localMode} 才有意义 —— 面板会出现「本机 / ME」两个按钮，
     * 两个模式共用同一套网格 / 搜索 / 数量 / 缺料 UI，只有数据源与下单通道不同。</p>
     */
    public interface LocalSource {
        /** 当前（按机器输入槽材料）可做的配方列表。 */
        List<Recipe<?>> recipes();

        /** 该配方按机器当前材料最多可做几份（0 = 材料不足，会压暗）。 */
        int maxCraftable(Recipe<?> recipe);

        /** 本机下单（宿主发包，一般走 OrderRecipePacket）。 */
        void order(Recipe<?> recipe, int quantity);
    }

    /** ME 网格格子边长（像素）。 */
    private static final int ME_CELL = 18;
    /** 列表最多 / 最少行数（按面板高度自适应）。 */
    private static final int MAX_ROWS = 6;
    private static final int MIN_ROWS = 2;

    private static final ResourceLocation AE_PANEL = new ResourceLocation("ae2", "textures/guis/background.png");
    private static final ResourceLocation AE_TEXT_FIELD = new ResourceLocation("ae2", "textures/guis/text_field.png");

    // AE 风格配色（与烹饪/穿串工厂的 ME 面板一致）
    private static final int AE_BORDER = 0xFF7BA8CC;
    private static final int AE_SLOT = 0xFF3A5C80;
    private static final int AE_SLOT_HOVER = 0xFF5483AE;
    private static final int AE_BTN = 0xFF41648A;
    private static final int AE_BTN_HOVER = 0xFF5B87B0;
    private static final int AE_BTN_ACTIVE = 0xFF76A4CF;
    private static final int AE_TEXT = 0xFFFFFFFF;
    private static final int AE_TEXT_DIM = 0xFFB4CFE6;
    private static final int AE_TEXT_ACCENT = 0xFFB8DBF5;
    private static final int AE_TEXT_YELLOW = 0xFFFFFF55;
    private static final int AE_TEXT_RED = 0xFFFF7070;

    private final boolean searchEnabled;
    /** 宿主是否有"本机"下单面板：没有（如联动机器）时只显示 ME 模式、不画来源切换按钮。 */
    private final boolean localMode;
    /** 本机数据源（宿主设置；为 null 时本机模式显示"没有可做的材料"）。 */
    private LocalSource localSource;
    private final OrderSearchBox search = new OrderSearchBox();
    private boolean searchReady;

    private BlockPos pos;
    private boolean meMode;
    private boolean dataRequested;
    private List<ResourceLocation> recipeIds = List.of();
    private Map<ResourceLocation, Integer> maxCraftable = Map.of();
    private List<Recipe<?>> recipes = List.of();
    private boolean listDirty = true;
    private Recipe<?> selected;
    private int quantity = 1;
    private int scroll;
    private boolean customInput;
    private String customText = "";
    /** 服务端回的缺料摘要（null = 材料够 / 还没回包）。 */
    private String missingText;
    private String missingKey = "";

    // 本帧布局缓存（渲染与点击必须共用，避免"按网格画、按行点"）
    private int layoutX;
    private int layoutY;
    private int layoutW;
    private int layoutH;
    private int layoutRows;
    private int layoutCols;
    private int layoutListTop;
    private int layoutListBottom;
    private int layoutQtyBtnY;
    private int layoutQtyBtnW;
    private int layoutConfirmY;
    private int layoutConfirmW;

    public NetworkOrderPanel() {
        this(true, true);
    }

    /** @param searchEnabled 是否带终端式搜索框（GuiWindow 里键盘事件不好转发时可关掉）。 */
    public NetworkOrderPanel(boolean searchEnabled) {
        this(searchEnabled, true);
    }

    /**
     * @param searchEnabled 是否带终端式搜索框；
     * @param localMode     宿主是否有"本机"下单面板。为 false 时（联动机器这类机器本身没有
     *                      本机下单列表）面板恒为 ME 模式，且**不画「本机 / ME」切换按钮**。
     */
    public NetworkOrderPanel(boolean searchEnabled, boolean localMode) {
        this.searchEnabled = searchEnabled;
        this.localMode = localMode;
        this.meMode = !localMode;
    }

    // ==================================================================
    //  状态
    // ==================================================================

    public boolean isMe() {
        return !localMode || meMode;
    }

    public BlockPos boundPos() {
        return pos;
    }

    /** 宿主切换到 ME 模式时调用（重置选择与列表）。 */
    public void setMe(boolean me) {
        if (!localMode) return; // 没有本机面板：恒为 ME
        if (this.meMode == me) return;
        this.meMode = me;
        this.selected = null;
        this.quantity = 1;
        this.scroll = 0;
        this.customInput = false;
        this.customText = "";
        this.missingText = null;
        this.listDirty = true;
        if (me) {
            this.dataRequested = false;
            requestData();
        }
    }

    /** 下单面板打开 / 机器变化时调用：绑定坐标并按需重新拉取网络数据。 */
    public void bind(BlockPos blockPos) {
        if (blockPos == null) return;
        if (this.pos == null || !this.pos.equals(blockPos)) {
            this.pos = blockPos.immutable();
            this.recipeIds = List.of();
            this.maxCraftable = Map.of();
            this.listDirty = true;
            this.selected = null;
            this.scroll = 0;
            this.missingText = null;
            this.dataRequested = false;
        }
        if (meMode && !dataRequested) requestData();
        if (!isMe()) listDirty = true; // 本机模式：材料随时在变，打开面板就重扫一次
    }

    /** 面板关闭时调用（释放搜索框焦点，并标记网络数据下次打开时重拉）。 */
    public void onClosed() {
        if (searchReady) search.clear();
        this.customInput = false;
        this.customText = "";
        this.missingText = null;
        this.dataRequested = false;
        this.listDirty = true; // 本机模式靠这个在下次打开时重扫机器材料
    }

    /** 设置本机数据源（宿主在构造/接线时调用一次）。 */
    public void setLocalSource(LocalSource source) {
        this.localSource = source;
    }

    private void requestData() {
        if (pos == null) return;
        dataRequested = true;
        ModMessages.sendToServer(new NetworkRecipeRequestPacket(pos));
    }

    /** 服务端回包：ME 网络可下单配方 + 每配方可做份数。 */
    public void setData(BlockPos blockPos, List<String> ids, Map<String, Integer> max) {
        if (pos == null || blockPos == null || !pos.equals(blockPos)) return;
        List<ResourceLocation> newIds = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        if (ids != null) {
            for (String id : ids) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl != null && seen.add(rl)) newIds.add(rl);
            }
        }
        Map<ResourceLocation, Integer> newMax = new HashMap<>();
        if (max != null) {
            for (Map.Entry<String, Integer> e : max.entrySet()) {
                ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
                if (rl != null) newMax.put(rl, e.getValue() == null ? 0 : e.getValue());
            }
        }
        this.recipeIds = newIds;
        this.maxCraftable = newMax;
        this.listDirty = true;
    }

    /** 服务端回包：缺料摘要（只在仍是当前选择时采用）。 */
    public void setMissing(BlockPos blockPos, String recipeId, int qty, String text) {
        if (pos == null || blockPos == null || !pos.equals(blockPos)) return;
        if (selected == null || recipeId == null) return;
        if (!recipeId.equals(selected.getId().toString()) || qty != quantity) return;
        String key = recipeId + "#" + qty;
        this.missingKey = key;
        this.missingText = text == null || text.isEmpty() ? null : text;
    }

    private void requestMissing() {
        if (pos == null || selected == null) {
            missingText = null;
            return;
        }
        missingText = null;
        ModMessages.sendToServer(new NetworkMissingRequestPacket(pos, selected.getId().toString(), quantity));
    }

    /** 把网络配方 id 解析为客户端 Recipe（图标 / 名称），并按搜索关键字过滤。 */
    private List<Recipe<?>> resolveRecipes() {
        // 本机模式：直接用宿主给的本机配方表（材料在机器里 ⇒ 天然"可做"）
        if (!isMe()) {
            List<Recipe<?>> local = localSource == null ? List.of() : localSource.recipes();
            return searchEnabled ? search.filter(local) : local;
        }
        Minecraft mc = Minecraft.getInstance();
        List<Recipe<?>> out = new ArrayList<>();
        if (mc.level == null) return out;
        Set<ResourceLocation> seen = new HashSet<>();
        for (ResourceLocation id : recipeIds) {
            if (!seen.add(id)) continue;
            mc.level.getRecipeManager().byKey(id).ifPresent(out::add);
        }
        return searchEnabled ? search.filter(out) : out;
    }

    private int maxOf(Recipe<?> recipe) {
        if (recipe == null) return 0;
        if (!isMe()) return localSource == null ? 0 : Math.max(0, localSource.maxCraftable(recipe));
        return maxCraftable.getOrDefault(recipe.getId(), 0);
    }

    // ==================================================================
    //  布局
    // ==================================================================

    private int gridCols(int panelW) {
        int usable = panelW - 12 - 24 - 4;
        return Math.max(1, usable / ME_CELL);
    }

    /** 本帧布局：渲染与点击共用同一套几何。 */
    private void computeLayout(int panelX, int panelY, int panelW, int panelH) {
        layoutX = panelX;
        layoutY = panelY;
        layoutW = panelW;
        layoutH = panelH;
        int hintRow = 11;
        layoutConfirmY = panelY + panelH - 22;
        int hintY = layoutConfirmY - hintRow;
        layoutQtyBtnY = hintY - 16;
        int qtyLabelY = layoutQtyBtnY - 12;
        layoutListTop = panelY + (searchEnabled ? 42 : 27);
        int room = qtyLabelY - 4 - layoutListTop;
        layoutRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, room / ME_CELL));
        layoutListBottom = layoutListTop + layoutRows * ME_CELL;
        layoutCols = gridCols(panelW);
        layoutQtyBtnW = Math.max(16, Math.min(24, (panelW - 12 - 5 * 2) / 6));
        layoutConfirmW = Math.max(40, Math.min(74, (panelW - 12 - 8) / 2));
    }

    private int cellsPerPage() {
        return layoutCols * layoutRows;
    }

    // ==================================================================
    //  渲染
    // ==================================================================

    /** 渲染完整 ME 面板（仅在 {@link #isMe()} 为真时调用）。 */
    public void render(GuiGraphics guiGraphics, Font font, int panelX, int panelY, int panelW, int panelH,
                       int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        computeLayout(panelX, panelY, panelW, panelH);

        // 背景：AE 面板纹理（无格子）+ 淡蓝染色
        guiGraphics.setColor(0.55f, 0.72f, 0.90f, 1.0f);
        GuiUtils.blitNineSlicedSized(guiGraphics, AE_PANEL, panelX, panelY, panelW, panelH,
                4, 256, 256, 0, 0, 256, 256);
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);

        // 表头：标题 + 来源切换 + 分隔线
        guiGraphics.drawString(font, isMe() ? "ME \u7F51\u7EDC\u4E0B\u5355" : "\u672C\u673A\u4E0B\u5355",
                panelX + 8, panelY + 6, AE_TEXT_ACCENT);
        renderModeButtons(guiGraphics, font, panelX, panelY, panelW, mouseX, mouseY);
        guiGraphics.fill(panelX + 4, panelY + 21, panelX + panelW - 4, panelY + 22, AE_BORDER);

        // 搜索框（懒初始化；位置随面板走）
        if (searchEnabled) {
            if (!searchReady) {
                search.init(font, panelX + 6, panelY + 25, Math.max(40, panelW - 28));
                searchReady = true;
            } else {
                search.move(panelX + 6, panelY + 25, Math.max(40, panelW - 28));
            }
            search.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 配方列表
        if (listDirty) {
            recipes = resolveRecipes();
            listDirty = false;
            if (selected != null && !recipes.contains(selected)) selected = null;
        }
        int rowX = panelX + 6;
        int listTop = layoutListTop;
        int listBottom = layoutListBottom;
        int scrollbarX = panelX + panelW - 7;

        if (recipes.isEmpty()) {
            String msg;
            if (!isMe()) {
                msg = "\u673A\u5668\u91CC\u6CA1\u6709\u53EF\u505A\u7684\u6750\u6599"; // 机器里没有可做的材料
            } else if (!AE2Compat.isLoaded()) {
                msg = "\u672A\u5B89\u88C5 AE2\uFF0C\u65E0\u6CD5\u4F7F\u7528 ME \u7F51\u7EDC\u4E0B\u5355";
            } else {
                msg = dataRequested
                        ? "ME\u7F51\u7EDC\u4E2D\u65E0\u53EF\u7528\u98DF\u6750"
                        : "\u6B63\u5728\u83B7\u53D6ME\u7F51\u7EDC\u6570\u636E...";
            }
            guiGraphics.drawString(font, msg, rowX + 2, listTop + 8, AE_TEXT_DIM);
        }

        int visible = Math.min(cellsPerPage(), recipes.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= recipes.size()) break;
            Recipe<?> recipe = recipes.get(idx);
            int cellX = rowX + (i % layoutCols) * ME_CELL;
            int cellY = listTop + (i / layoutCols) * ME_CELL;
            boolean isSelected = recipe == selected;
            boolean hovered = isHovered(mouseX, mouseY, cellX, cellY, ME_CELL, ME_CELL);
            int bg = isSelected ? AE_SLOT_HOVER : (hovered ? 0xFF4A7BA6 : AE_SLOT);
            guiGraphics.fill(cellX, cellY, cellX + ME_CELL, cellY + ME_CELL, AE_BORDER);
            guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, bg);

            ItemStack result = resultOf(mc, recipe);
            int maxQty = maxOf(recipe);
            boolean craftable = maxQty > 0;
            if (!result.isEmpty()) {
                guiGraphics.renderItem(result, cellX + 1, cellY + 1);
                guiGraphics.renderItemDecorations(font, result, cellX + 1, cellY + 1);
            }
            if (!craftable) {
                guiGraphics.fill(cellX + 1, cellY + 1, cellX + ME_CELL - 1, cellY + ME_CELL - 1, 0x99000000);
            }
            if (hovered) {
                guiGraphics.renderTooltip(font, List.of(
                                result.isEmpty() ? Component.translatable("gui.mekck.ui.unknown") : result.getHoverName(),
                                Component.literal(craftable ? ("\u53EF\u505A " + maxQty + " \u6B21")
                                        : "\u6750\u6599\u4E0D\u8DB3")),
                        Optional.empty(), mouseX, mouseY);
            }
        }

        // 滚动条
        int perPage = cellsPerPage();
        if (recipes.size() > perPage) {
            guiGraphics.fill(scrollbarX, listTop, scrollbarX + 2, listBottom, AE_BORDER);
            int total = Math.max(1, recipes.size());
            int trackH = listBottom - listTop;
            int thumbH = Math.max(14, trackH * perPage / total);
            int range = total - perPage;
            int thumbY = listTop + (range == 0 ? 0 : scroll * (trackH - thumbH) / range);
            guiGraphics.fill(scrollbarX - 1, thumbY, scrollbarX + 3, thumbY + thumbH, AE_BTN_ACTIVE);
        }

        // 数量区
        int qtyBtnX = rowX;
        guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.quantity", quantity).getString(), qtyBtnX, layoutQtyBtnY - 12, AE_TEXT_YELLOW);
        String[] qtyLabels = {"1", "16", "32", "64", "\u81EA", "Max"};
        for (int i = 0; i < qtyLabels.length; i++) {
            int bx = qtyBtnX + i * (layoutQtyBtnW + 2);
            boolean active = i == 4 && customInput;
            drawAeButton(guiGraphics, font, bx, layoutQtyBtnY, layoutQtyBtnW, 14, qtyLabels[i], active,
                    isHovered(mouseX, mouseY, bx, layoutQtyBtnY, layoutQtyBtnW, 14));
        }

        // 自定义数量输入框：占"提示行"（宽度够时放到数量按钮右侧）
        int hintY = layoutConfirmY - 11;
        int inputW = Math.min(82, panelW - 12);
        boolean sideInput = qtyBtnX + 6 * (layoutQtyBtnW + 2) + inputW + 4 <= panelX + panelW - 4;
        if (customInput) {
            int inputX = sideInput ? qtyBtnX + 6 * (layoutQtyBtnW + 2) + 4 : qtyBtnX;
            // 输入框放在"提示行"上（高度压到 13px，避免压住下面的确认按钮）
            int inputY = sideInput ? layoutQtyBtnY : layoutConfirmY - 14;
            guiGraphics.setColor(0.62f, 0.76f, 0.92f, 1.0f);
            GuiUtils.blitNineSlicedSized(guiGraphics, AE_TEXT_FIELD, inputX - 1, inputY - 1, inputW, 13,
                    4, 128, 128, 0, 0, 128, 128);
            guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            String shown = customText.isEmpty() ? "\u8F93\u5165\u6570\u91CF..." : customText;
            guiGraphics.drawString(font, shown, inputX + 2, inputY + 2, customText.isEmpty() ? AE_TEXT_DIM : AE_TEXT);
        }

        // 提示行：缺料清单（红字，AE 终端同款位置）优先，其次"最大: N"
        if (!customInput || sideInput) {
            if (missingText != null) {
                guiGraphics.drawString(font, fit(font, missingText, panelW - 12), qtyBtnX, hintY, AE_TEXT_RED);
            } else if (selected != null) {
                int maxQty = maxOf(selected);
                if (maxQty > 0) {
                    guiGraphics.drawString(font, Component.translatable("gui.mekck.ui.maximum", maxQty).getString(), qtyBtnX, hintY, AE_TEXT_DIM);
                }
            }
        }

        // 确认 / 取消
        int confirmX = panelX + (panelW - layoutConfirmW * 2 - 8) / 2;
        drawAeButton(guiGraphics, font, confirmX, layoutConfirmY, layoutConfirmW, 18,
                "\u786E\u8BA4\u4E0B\u5355", false,
                isHovered(mouseX, mouseY, confirmX, layoutConfirmY, layoutConfirmW, 18));
        drawAeButton(guiGraphics, font, confirmX + layoutConfirmW + 8, layoutConfirmY, layoutConfirmW, 18,
                "\u53D6\u6D88", false,
                isHovered(mouseX, mouseY, confirmX + layoutConfirmW + 8, layoutConfirmY, layoutConfirmW, 18));
    }

    private static ItemStack resultOf(Minecraft mc, Recipe<?> recipe) {
        try {
            if (mc.level == null) return ItemStack.EMPTY;
            return recipe.getResultItem(mc.level.registryAccess());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(8, maxWidth - 6)) + "…";
    }

    /** 「本机 / ME」两个切换按钮（两种模式下都画，位置统一）。 */
    public void renderModeButtons(GuiGraphics guiGraphics, Font font, int panelX, int panelY, int panelW,
                                  int mouseX, int mouseY) {
        if (!localMode || !AE2Compat.isLoaded()) return;
        int btnW = 40;
        int btnH = 14;
        int localX = panelX + panelW - btnW * 2 - 8;
        int netX = panelX + panelW - btnW - 4;
        drawAeButton(guiGraphics, font, localX, panelY + 4, btnW, btnH, "\u672C\u673A", !meMode,
                isHovered(mouseX, mouseY, localX, panelY + 4, btnW, btnH));
        drawAeButton(guiGraphics, font, netX, panelY + 4, btnW, btnH, "ME", meMode,
                isHovered(mouseX, mouseY, netX, panelY + 4, btnW, btnH));
    }

    /** 「本机 / ME」点击：返回 true 表示这次点击被切换按钮吃掉。 */
    public boolean handleModeClick(double mouseX, double mouseY, int panelX, int panelY, int panelW) {
        if (!localMode || !AE2Compat.isLoaded()) return false;
        int btnW = 40;
        int btnH = 14;
        int localX = panelX + panelW - btnW * 2 - 8;
        int netX = panelX + panelW - btnW - 4;
        if (isHovered(mouseX, mouseY, localX, panelY + 4, btnW, btnH)) {
            setMe(false);
            return true;
        }
        if (isHovered(mouseX, mouseY, netX, panelY + 4, btnW, btnH)) {
            setMe(true);
            return true;
        }
        return false;
    }

    private void drawAeButton(GuiGraphics guiGraphics, Font font, int x, int y, int w, int h, String label,
                              boolean active, boolean hovered) {
        int fill = active ? AE_BTN_ACTIVE : (hovered ? AE_BTN_HOVER : AE_BTN);
        guiGraphics.fill(x, y, x + w, y + h, AE_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        int labelW = font.width(label);
        guiGraphics.drawString(font, label, x + (w - labelW) / 2, y + (h - 9) / 2 + 1, AE_TEXT);
    }

    private static boolean isHovered(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ==================================================================
    //  交互
    // ==================================================================

    /** ME 面板点击（几何与 {@link #render} 共用同一份 layout）。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button, int panelX, int panelY, int panelW,
                                int panelH, OrderSink sink) {
        computeLayout(panelX, panelY, panelW, panelH);
        if (searchEnabled && search.mouseClicked(mouseX, mouseY, button)) return true;
        if (handleModeClick(mouseX, mouseY, panelX, panelY, panelW)) return true;

        // 配方格子
        int rowX = panelX + 6;
        for (int i = 0; i < cellsPerPage(); i++) {
            int idx = scroll + i;
            if (idx >= recipes.size()) break;
            int cellX = rowX + (i % layoutCols) * ME_CELL;
            int cellY = layoutListTop + (i / layoutCols) * ME_CELL;
            if (isHovered(mouseX, mouseY, cellX, cellY, ME_CELL, ME_CELL)) {
                selected = recipes.get(idx);
                quantity = 1;
                customInput = false;
                customText = "";
                requestMissing();
                return true;
            }
        }

        // 滚动条（上下半页翻页）
        int scrollbarX = panelX + panelW - 7;
        if (mouseX >= scrollbarX - 2 && mouseX < scrollbarX + 4
                && mouseY >= layoutListTop && mouseY < layoutListBottom) {
            int step = layoutCols;
            if (mouseY < layoutListTop + (layoutListBottom - layoutListTop) / 2) {
                scroll = Math.max(0, scroll - step);
            } else {
                scroll = Math.min(Math.max(0, recipes.size() - cellsPerPage()), scroll + step);
            }
            return true;
        }

        // 数量按钮
        int qtyBtnX = rowX;
        int[] qtyValues = {1, 16, 32, 64, -1, -2};
        for (int i = 0; i < qtyValues.length; i++) {
            int bx = qtyBtnX + i * (layoutQtyBtnW + 2);
            if (!isHovered(mouseX, mouseY, bx, layoutQtyBtnY, layoutQtyBtnW, 14)) continue;
            int val = qtyValues[i];
            if (val == -1) {
                customInput = !customInput;
                if (!customInput) customText = "";
            } else if (val == -2) {
                if (selected != null) quantity = Math.max(1, maxOf(selected));
            } else {
                customInput = false;
                customText = "";
                quantity = val;
            }
            if (selected != null) requestMissing();
            return true;
        }

        // 确认 / 取消
        int confirmX = panelX + (panelW - layoutConfirmW * 2 - 8) / 2;
        if (isHovered(mouseX, mouseY, confirmX, layoutConfirmY, layoutConfirmW, 18)) {
            if (selected != null && quantity > 0) {
                if (isMe()) {
                    if (sink != null && AE2Compat.isLoaded()) sink.order(selected.getId(), quantity);
                } else if (localSource != null) {
                    localSource.order(selected, quantity); // 本机：材料在机器里，直接设订单
                }
            }
            return true;
        }
        if (isHovered(mouseX, mouseY, confirmX + layoutConfirmW + 8, layoutConfirmY, layoutConfirmW, 18)) {
            return true;
        }
        return true;
    }

    /** 滚轮：一屏 = 整个页面的格子数（与烹饪/穿串工厂的 ME 段一致）。 */
    public boolean mouseScrolled(double delta) {
        int maxScroll = Math.max(0, recipes.size() - cellsPerPage());
        int next = scroll + (delta > 0 ? -1 : 1);
        scroll = Math.max(0, Math.min(maxScroll, next));
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchEnabled && search.keyPressed(keyCode, scanCode, modifiers)) {
            listDirty = true;
            return true;
        }
        if (customInput) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                if (!customText.isEmpty()) {
                    try {
                        int val = Integer.parseInt(customText);
                        if (val > 0) quantity = val;
                    } catch (NumberFormatException ignored) {
                    }
                }
                customInput = false;
                customText = "";
                requestMissing();
                return true;
            } else if (keyCode == 259) { // Backspace
                if (!customText.isEmpty()) customText = customText.substring(0, customText.length() - 1);
                return true;
            } else if (keyCode == 256) { // Escape
                customInput = false;
                customText = "";
                return true;
            }
            return false;
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (searchEnabled && search.charTyped(codePoint, modifiers)) {
            listDirty = true;
            return true;
        }
        if (customInput) {
            if (Character.isDigit(codePoint)) customText += codePoint;
            return true;
        }
        return false;
    }
}
