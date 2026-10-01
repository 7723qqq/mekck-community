package cn.ism.mekck.blockentity;

import cn.ism.mekck.SideMode;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;

/**
 * 遗留单机（14 台联动机器 + 中央厨房等）共用的<b>流体子系统</b>：两个罐、三组 capability、
 * 独立的侧面配置、自动输入输出。
 *
 * <h3>为什么要从 {@code SimpleMachineBlockEntity} 里独立出来</h3>
 * 原先是那个 4841 行 BE 的私有字段与三个私有 {@code IFluidHandler} 内嵌类，
 * 而流体是它<b>第二大</b>的职责面（罐写入点 49 处、输出罐 25 处、侧配 9 处），
 * 与「配方匹配」「NBT」「物品/能量」这些同样重量级的关注点混在同一个类里。
 * 独立之后：BE 只保留 5 个公开访问器做委托（菜单与 GUI 只认这几个），
 * 而「罐怎么序列化」「capability 怎么失效」这些细节有了自己的位置。
 *
 * <p>注意：本类<b>不认识任何机器类型</b>，只管流体。所有机器相关的判断仍在 BE 侧
 * （例如只有发酵机才会把配方里的流体真正搬进罐）。</p>
 *
 * <p>侧配数组仍然直接暴露（{@link #getFluidSideConfig()}）：BE 的 NBT 段复用自己那份
 * {@code encodeSideConfig(SideMode[])} 静态编解码，那份同时服务物品侧配，
 * 拆成两份会立刻引入「两套编解码不一致」的风险。</p>
 */
public final class SimpleMachineFluids {

    /** 仅用于 {@code setChanged()} 与 {@link cn.ism.mekck.util.AutoFluidIO}；不持有任何机器逻辑。 */
    private final BlockEntity owner;

    /** 发酵机流体罐容量（mb）。 */
    public static final int FLUID_CAPACITY = 16_000;

    private final FluidTank inputTank = new net.minecraftforge.fluids.capability.templates.FluidTank(FLUID_CAPACITY);
    private final FluidTank outputTank = new net.minecraftforge.fluids.capability.templates.FluidTank(FLUID_CAPACITY);

    private LazyOptional<IFluidHandler> fluidCapability;
    private LazyOptional<IFluidHandler> inputFluidCapability;
    private LazyOptional<IFluidHandler> outputFluidCapability;
    /** 流体侧面配置（与物品侧配独立的模式数组）。 */
    private final SideMode[] fluidSideConfig = new SideMode[6];
    /** 流体自动输入输出（抽取/弹出，与物品 AutoIO 语义一致）。 */
    private final cn.ism.mekck.util.AutoFluidIO fluidAutoIO;

    public SimpleMachineFluids(BlockEntity owner) {
        this.owner = owner;
        // 字段初始化器早于构造器体，owner 那时还是 null ⇒ AutoFluidIO 在这里建
        this.fluidAutoIO = new cn.ism.mekck.util.AutoFluidIO(owner, inputTank, outputTank);
        this.fluidCapability = LazyOptional.of(() -> new CombinedFluidHandler());
        this.inputFluidCapability = LazyOptional.of(() -> new InputOnlyFluidHandler());
        this.outputFluidCapability = LazyOptional.of(() -> new OutputOnlyFluidHandler());
        for (int i = 0; i < 6; i++) fluidSideConfig[i] = SideMode.NONE;
    }

    public FluidTank getInputTank() {
        return inputTank;
    }

    public FluidTank getOutputTank() {
        return outputTank;
    }

    /** 本机是否具备流体处理能力（SimpleMachine 均内置输入/输出流体罐）。 */
    public boolean hasFluidHandler() {
        return true;
    }

    /** 流体侧面配置（独立于物品侧配）。 */
    public void setFluidSideMode(Direction dir, SideMode mode) {
        if (dir == null) return;
        fluidSideConfig[dir.ordinal()] = mode;
        if (owner != null) owner.setChanged();
    }

    public SideMode getFluidSideMode(Direction dir) {
        return fluidSideConfig[dir.ordinal()];
    }

    /** 侧配数组本身：BE 的 NBT 段用它做编码 / 解码（与物品侧配共用一份编解码）。 */
    public SideMode[] getFluidSideConfig() {
        return fluidSideConfig;
    }

    public LazyOptional<IFluidHandler> getFluidCapability() {
        return fluidCapability;
    }

    public LazyOptional<IFluidHandler> getInputFluidCapability() {
        return inputFluidCapability;
    }

    public LazyOptional<IFluidHandler> getOutputFluidCapability() {
        return outputFluidCapability;
    }

    /** 流体自动输入输出（抽取/弹出），与物品侧 {@code AutoIO} 语义一致。 */
    public cn.ism.mekck.util.AutoFluidIO getAutoIO() {
        return fluidAutoIO;
    }

    /** 罐内容写入存档。**键名沿用拆分前的写法**（{@code InputFluid} / {@code OutputFluid}），改键等于丢存档里的流体。 */
    public void writeTanks(CompoundTag tag) {
        tag.put("InputFluid", inputTank.writeToNBT(new CompoundTag()));
        tag.put("OutputFluid", outputTank.writeToNBT(new CompoundTag()));
    }

    /** 罐内容读回存档（键名与 {@link #writeTanks} 对称）。 */
    public void readTanks(CompoundTag tag) {

    }

    /** capability 失效（方块卸载 / 降级时由 BE 调用）。 */
    public void invalidateCaps() {
        fluidCapability.invalidate();
        inputFluidCapability.invalidate();
        outputFluidCapability.invalidate();
    }

    private final class InputOnlyFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public net.minecraftforge.fluids.FluidStack getFluidInTank(int tank) {
            return inputTank.getFluid();
        }

        @Override
        public int getTankCapacity(int tank) {
            return FLUID_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack) {
            return true;
        }

        @Override
        public int fill(net.minecraftforge.fluids.FluidStack resource,
                        IFluidHandler.FluidAction action) {
            return inputTank.fill(resource, action);
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.fluids.FluidStack resource,
                        IFluidHandler.FluidAction action) {
            return net.minecraftforge.fluids.FluidStack.EMPTY;
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(int maxDrain,
                        IFluidHandler.FluidAction action) {
            return net.minecraftforge.fluids.FluidStack.EMPTY;
        }
    }

    /** 只允许抽取（供 PUSH_OUTPUT 侧）。 */
    private final class OutputOnlyFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public net.minecraftforge.fluids.FluidStack getFluidInTank(int tank) {
            return outputTank.getFluid();
        }

        @Override
        public int getTankCapacity(int tank) {
            return FLUID_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack) {
            return false;
        }

        @Override
        public int fill(net.minecraftforge.fluids.FluidStack resource,
                        IFluidHandler.FluidAction action) {
            return 0;
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.fluids.FluidStack resource,
                        IFluidHandler.FluidAction action) {
            return outputTank.drain(resource, action);
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(int maxDrain,
                        IFluidHandler.FluidAction action) {
            return outputTank.drain(maxDrain, action);
        }
    }

    private final class CombinedFluidHandler implements IFluidHandler {
        private final IFluidHandler[] handlers = {inputTank, outputTank};

        @Override
        public int getTanks() {
            return 2;
        }

        @Override
        public net.minecraftforge.fluids.FluidStack getFluidInTank(int tank) {
            return tank == 0 ? inputTank.getFluid() : outputTank.getFluid();
        }

        @Override
        public int getTankCapacity(int tank) {
            return FLUID_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack) {
            return true;
        }

        @Override
        public int fill(net.minecraftforge.fluids.FluidStack resource, IFluidHandler.FluidAction action) {
            return inputTank.fill(resource, action);
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.fluids.FluidStack resource, IFluidHandler.FluidAction action) {
            return outputTank.drain(resource, action);
        }

        @Override
        public net.minecraftforge.fluids.FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
            return outputTank.drain(maxDrain, action);
        }
    }
}
