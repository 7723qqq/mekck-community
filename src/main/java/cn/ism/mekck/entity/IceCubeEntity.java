package cn.ism.mekck.entity;

import cn.ism.mekck.config.MekckConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import cn.ism.mekck.registry.MekCkEffects;
import cn.ism.mekck.registry.MekCkEntities;

/**
 * 冰块实体：外观为原版冰块（IBlockDisplayReader 通过 getBlockState 渲染），
 * 受 10 倍重力快速下落，接触实体或落块时对接触到的实体造成冰冻伤害，
 * 并播放原版冰块破碎音效。具体伤害/效果由冷萃升级档位决定（通过 BlockEntity 生成时设置）。
 */
public class IceCubeEntity extends FallingBlockEntity {

    private static final EntityDataAccessor<Float> DATA_DAMAGE = SynchedEntityData.defineId(IceCubeEntity.class, EntityDataSerializers.FLOAT);
    /** 溅射伤害（由冷萃档位决定：低温 5、凛冰 20、龙霜 40）。 */
    private static final EntityDataAccessor<Float> DATA_SPLASH = SynchedEntityData.defineId(IceCubeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(IceCubeEntity.class, EntityDataSerializers.BYTE);

    private static final byte FLAG_AOE = 1;
    private static final byte FLAG_SLOW = 1 << 1;
    private static final byte FLAG_REMOVE_AI = 1 << 2;
    private static final byte FLAG_HYPOTHERMIA = 1 << 3;

    @Nullable
    private LivingEntity lastContact;

    public IceCubeEntity(EntityType<? extends FallingBlockEntity> type, Level level) {
        super(type, level);
        this.dropItem = false;
        this.setHurtsEntities(0.0F, 0);
    }

    /** 便捷生成方法：在指定坐标上方生成冰块并设置攻击参数。 */
    public static IceCubeEntity spawn(Level level, double x, double y, double z, float damage, boolean aoe, boolean slow, boolean removeAI) {
        return spawn(level, x, y, z, damage, aoe, slow, removeAI, false, null);
    }

    /** 便捷生成方法：生成冰块，并在冰块生成位置与机器位置播放末影珍珠式传送粒子。 */
    public static IceCubeEntity spawn(Level level, double x, double y, double z, float damage, boolean aoe, boolean slow, boolean removeAI, @Nullable BlockPos machinePos) {
        return spawn(level, x, y, z, damage, aoe, slow, removeAI, false, machinePos);
    }

    /** 便捷生成方法：完整参数（含失温升级），生成冰块并播放传送粒子/音效。 */
    public static IceCubeEntity spawn(Level level, double x, double y, double z, float damage, boolean aoe, boolean slow, boolean removeAI, boolean hypothermia, @Nullable BlockPos machinePos) {
        return spawn(level, x, y, z, damage, aoe, 5.0F, slow, removeAI, hypothermia, machinePos);
    }

    /** 便捷生成方法：完整参数（含溅射伤害与失温升级），生成冰块并播放传送粒子/音效。 */
    public static IceCubeEntity spawn(Level level, double x, double y, double z, float damage, boolean aoe, float splashDamage,
                                      boolean slow, boolean removeAI, boolean hypothermia, @Nullable BlockPos machinePos) {
        IceCubeEntity cube = new IceCubeEntity(cn.ism.mekck.registry.MekCkEntities.ICE_CUBE_ENTITY.get(), level);
        cube.setPos(x, y, z);
        cube.setDeltaMovement(0, -0.05, 0);
        cube.entityData.set(DATA_DAMAGE, damage);
        cube.entityData.set(DATA_SPLASH, splashDamage);
        byte flags = 0;
        if (aoe) flags |= FLAG_AOE;
        if (slow) flags |= FLAG_SLOW;
        if (removeAI) flags |= FLAG_REMOVE_AI;
        if (hypothermia) flags |= FLAG_HYPOTHERMIA;
        cube.entityData.set(DATA_FLAGS, flags);
        level.addFreshEntity(cube);
        // 传送特效：冰块生成位置 + 制冰机器位置（与末影珍珠传送一致的 PORTAL 粒子 + 末影人传送音效）
        cn.ism.mekck.util.TeleportFxUtil.play(level, x, y, z, machinePos);
        return cube;
    }

    @Override
    public BlockState getBlockState() {
        return Blocks.ICE.defaultBlockState();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DAMAGE, 20.0F);
        this.entityData.define(DATA_SPLASH, 5.0F);
        this.entityData.define(DATA_FLAGS, (byte) 0);
    }

    private float getDamage() {
        return this.entityData.get(DATA_DAMAGE);
    }

    private float getSplashDamage() {
        return this.entityData.get(DATA_SPLASH);
    }

    private boolean hasFlag(byte flag) {
        return (this.entityData.get(DATA_FLAGS) & flag) != 0;
    }

    @Override
    public void tick() {
        Level level = this.level();
        if (level.isClientSide) {
            super.tick();
            return;
        }
        // 10 倍重力：原版下落每刻 -0.04，这里总计约 -0.40
        Vec3 d = this.getDeltaMovement();
        this.setDeltaMovement(d.x, d.y - 0.40, d.z);

        // 接触实体检测：隔 tick 查一次——每 tick 只下落 0.40 格，而碰撞箱高 1 格，
        // 相邻两次检测的包围盒仍然重叠，不会漏判；实测大批量飞行时可省掉近一半的世界实体查询。
        if ((this.tickCount & 1) == 0) {
            AABB box = this.getBoundingBox();
            for (Entity e : level.getEntities(this, box, ent -> ent instanceof LivingEntity && ent.isAlive())) {
                this.lastContact = (LivingEntity) e;
                impact();
                return;
            }
        }

        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());
        if (this.onGround() || this.horizontalCollision) {
            impact();
            return;
        }
        if (this.getY() < level.getMinBuildHeight() - 1) {
            this.discard();
            return;
        }
        this.baseTick();
    }

    private void impact() {
        if (this.isRemoved() || this.level().isClientSide) return;
        Level level = this.level();
        double cx = this.getX(), cy = this.getY(), cz = this.getZ();
        float mult = (float) MekckConfig.getIceCubeDamageMult();
        boolean aoe = hasFlag(FLAG_AOE);
        float dmg = getDamage() * mult;

        // 直接命中目标：接触实体优先，落地时取落点附近最近实体，承受完整伤害。
        LivingEntity primary = (this.lastContact != null && this.lastContact.isAlive()) ? this.lastContact : null;
        if (primary == null) {
            double best = Double.MAX_VALUE;
            AABB near = AABB.ofSize(new Vec3(cx, cy, cz), 2.0, 2.0, 2.0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, near, LivingEntity::isAlive)) {
                double d2 = e.distanceToSqr(cx, cy, cz);
                if (d2 < best) {
                    best = d2;
                    primary = e;
                }
            }
        }
        if (primary != null) applyHit(primary, dmg);

        // 范围伤害：落点 3×3×3 内其他实体承受该档位的溅射伤害（低温 5 / 凛冰 20 / 龙霜 40，与主伤害叠加生效）。
        if (aoe) {
            float splash = getSplashDamage() * mult;
            AABB area = AABB.ofSize(new Vec3(cx, cy, cz), 3.0, 3.0, 3.0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
                if (e != primary) applyHit(e, splash);
            }
        }

        this.playSound(SoundEvents.GLASS_BREAK, 1.0F, 1.0F);

        // 破碎粒子
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                    cx, cy, cz, 12, 0.3, 0.3, 0.3, 0.05);
        }
        this.discard();
    }

    private void applyHit(LivingEntity e, float dmg) {
        Level level = this.level();
        e.invulnerableTime = 0; // 无视受击无敌帧：确保每块冰都造成完整伤害（多冰连击/集火不被豁免）
        e.hurt(level.damageSources().freeze(), dmg);
        if (hasFlag(FLAG_SLOW) && e.isAlive()) {
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 10, false, true));
            // 龙霜冷萃：装有冰火传说时联动其原生冰冻状态（frozenData capability）；
            // 未装冰火时使用原生冰冻效果（减速/向下牵引/冰封视觉，逻辑与冰火 FrozenData 一致）
            if (cn.ism.mekck.compat.IceAndFireCompat.isAvailable()) {
                cn.ism.mekck.compat.IceAndFireCompat.applyDragonboneFreeze(e);
            } else {
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 2, false, true));
                e.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 100, 2, false, true));
                e.addEffect(new MobEffectInstance(cn.ism.mekck.registry.MekCkEffects.FROZEN_EFFECT.get(),
                        100, 0, false, true));
            }
        }
        // 失温升级（CB5）：直接命中与溅射伤害目标都附加失温，叠满 20 级（amplifier 19）附加永冻
        if (hasFlag(FLAG_HYPOTHERMIA) && e.isAlive()) {
            applyHypothermia(e);
        }
        if (hasFlag(FLAG_REMOVE_AI) && e instanceof Mob mob && e.isAlive()) {
            mob.setNoAi(true);
            if (level instanceof ServerLevel serverLevel) {
                // 重复命中时刷新恢复时间：以持久化数据记录最晚恢复刻，避免旧的定时任务提前恢复 AI。
                long restoreTick = serverLevel.getServer().getTickCount() + 40L;
                mob.getPersistentData().putLong("mekck:ai_restore_tick", restoreTick);
                final long scheduled = restoreTick;
                serverLevel.getServer().tell(new net.minecraft.server.TickTask((int) scheduled, () -> {
                    if (mob.isAlive()
                            && mob.getPersistentData().getLong("mekck:ai_restore_tick") <= serverLevel.getServer().getTickCount()) {
                        mob.setNoAi(false);
                    }
                }));
            }
        }
    }

    /** 失温每层持续时间（tick）：10 秒（命中刷新）。 */
    private static final int HYPOTHERMIA_DURATION = 20 * 10;
    /** 永冻持续时间（tick）：1 小时（命中刷新）。 */
    private static final int ETERNAL_FREEZE_DURATION = 20 * 60 * 60;

    /**
     * 失温：命中目标与溅射目标各附加 1 级失温（最多叠加 20 级 = amplifier 19，每层持续 10 秒），
     * 叠至 20 级时附加永冻（持续 1 小时，命中刷新）。
     */
    private void applyHypothermia(LivingEntity e) {
        if (e.level().isClientSide) return;
        MobEffectInstance existing = e.getEffect(cn.ism.mekck.registry.MekCkEffects.HYPOTHERMIA_EFFECT.get());
        int nextAmplifier = existing == null ? 0 : Math.min(19, existing.getAmplifier() + 1);
        e.addEffect(new MobEffectInstance(cn.ism.mekck.registry.MekCkEffects.HYPOTHERMIA_EFFECT.get(),
                HYPOTHERMIA_DURATION, nextAmplifier, false, true));
        if (nextAmplifier == 19) {
            e.addEffect(new MobEffectInstance(cn.ism.mekck.registry.MekCkEffects.ETERNAL_FREEZE_EFFECT.get(),
                    ETERNAL_FREEZE_DURATION, 0, false, true));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("IceDamage", getDamage());
        tag.putByte("IceFlags", this.entityData.get(DATA_FLAGS));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_DAMAGE, tag.getFloat("IceDamage"));
        this.entityData.set(DATA_FLAGS, tag.getByte("IceFlags"));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
