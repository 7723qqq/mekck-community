package cn.ism.mekck.blockentity;

import cn.ism.mekck.RedstoneControl;

/**
 * 所有支持红石控制的机器方块实体需实现的接口，以便网络包统一处理。
 */
public interface IRedstoneControllable {
    RedstoneControl getRedstoneControl();

    void setRedstoneControl(RedstoneControl control);

    default void cycleRedstoneControl(int direction) {
        RedstoneControl current = getRedstoneControl();
        setRedstoneControl(direction > 0 ? current.next() : current.previous());
    }
}