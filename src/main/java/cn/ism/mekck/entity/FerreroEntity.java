package cn.ism.mekck.entity;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * 费列罗巧克力实体：巧克力大炮的攻击弹药。
 * <p>
 * 外观为费列罗巧克力物品（{@link cn.ism.mekck.client.FerreroRenderer} 渲染），
 * 与冰块实体相同的下坠方式（约 10 倍重力）。接触实体或方块时：
 * 产生一次不破坏地形且无爆炸伤害的爆炸特效（音效 + 爆炸粒子），
 * 并对范围内（默认 3×3×3，霸王死神升级 7×7×7）实体造成爆炸类型伤害。
 * 伤害判定在受击时：目标生命值低于伤害值直接死亡，否则正常结算爆炸伤害。
 * </p>
 * <p>
 * 升级效果由巧克力大炮生成时打包进 flags：
 * 脆心升级命中后移除目标 2 秒 AI（机制同女王冷萃）；
 * 超重力场升级伤害类型从爆炸变为铁砧，且被杀死的实体不播放死亡动画。
 * </p>
 */
public class FerreroEntity extends ThrowableItemProjectile {

    public static final byte FLAG_CRISPY = 1;
    public static final byte FLAG_GRAVITY = 1 << 1;
    public static final byte FLAG_OVERLORD = 1 << 2;

    private static final EntityDataAccessor<Float> DATA_DAMAGE = SynchedEntityData.defineId(FerreroEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(FerreroEntity.class, EntityDataSerializers.BYTE);

    /** 基础伤害（未安装任何费列罗升级时的 0 档默认值，与 {@link cn.ism.mekck.util.FerreroUpgradeProfile#damageOf(int) damageOf(0)} 一致）。 */
    public static final float BASE_DAMAGE = 250.0F;

    @Nullable
    private LivingEntity lastContact;

    /** 发射本弹的巧克力大炮方块坐标。null 表示非大炮发射（如指令生成），不会进行伤害预留结算。 */
    @Nullable
    private BlockPos ownerPos;

    @Nullable
    public BlockPos getOwnerPos() {
        return ownerPos;
    }

    public FerreroEntity(EntityType<? extends FerreroEntity> type, Level level) {
        super(type, level);
    }

    /** 便捷生成方法：在目标上方生成费列罗，并在生成位置与机器位置播放传送特效与音效。 */
    public static FerreroEntity spawn(Level level, double x, double y, double z, float damage, byte flags, @Nullable BlockPos machinePos) {
        FerreroEntity entity = new FerreroEntity(UniversalCuttingMachine.FERRERO_ENTITY.get(), level);
        entity.setPos(x, y, z);
        entity.setDeltaMovement(0, -0.05, 0);
        entity.entityData.set(DATA_DAMAGE, damage);
        entity.entityData.set(DATA_FLAGS, flags);
        entity.ownerPos = machinePos;
        // 仅当实体成功加入世界才视为发射成功；失败返回 null，发射方不得扣弹药或登记预留。
        if (!level.addFreshEntity(entity)) {
            return null;
        }
        // 传送特效：弹体生成位置 + 巧克力大炮位置（与末影珍珠传送一致的 PORTAL 粒子 + 末影人传送音效）
        cn.ism.mekck.util.TeleportFxUtil.play(level, x, y, z, machinePos);
        return entity;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DAMAGE, BASE_DAMAGE);
        this.entityData.define(DATA_FLAGS, (byte) 0);
    }

    @Override
    protected Item getDefaultItem() {
        // 发射物用"裸巧克力球"载体（物品栏那颗是带金箔的 ferrero_chocolate）
        return UniversalCuttingMachine.FERRERO_PROJECTILE_ITEM.get();
    }

    private float getDamage() {
        return this.entityData.get(DATA_DAMAGE);
    }

    private boolean hasFlag(byte flag) {
        return (this.entityData.get(DATA_FLAGS) & flag) != 0;
    }

    /** 实际爆炸范围（边长，方块）：默认 3×3×3，霸王死神升级 7×7×7。大炮索敌与弹体结算共用此处，避免在索敌代码中写死。 */
    public static float getExplosionRange(byte flags) {
        return (flags & FLAG_OVERLORD) != 0 ? 7.0F : 3.0F;
    }

    /**
     * 判断实体是否会落入以精确坐标 {@code centerVec} 为中心的爆炸范围（与 {@link #impact()} 的命中判定几何完全一致）。
     * 几何定义：以 {@code centerVec} 为中心、边长 {@code range} 的长方体 AABB，命中判定为“实体包围盒与该长方体相交”。
     * 索敌预测、预留覆盖、生成前复核与真实爆炸 {@code impact()} 必须共用这一个定义，避免“预测用方块中心、实际用精确坐标”造成边缘误判。
     */
    public static boolean isEntityInBlast(Vec3 centerVec, float range, Entity entity) {
        if (entity == null || centerVec == null) return false;
        AABB area = AABB.ofSize(centerVec, range, range, range);
        return entity.getBoundingBox().intersects(area);
    }

    @Override
    public void tick() {
        Level level = this.level();
        if (level.isClientSide) {
            super.tick();
            return;
        }
        // 10 倍重力：原版下落每刻 -0.04，这里总计约 -0.40（与冰块实体一致）
        Vec3 d = this.getDeltaMovement();
        this.setDeltaMovement(d.x, d.y - 0.40, d.z);

        // 接触实体检测：隔 tick 查一次（理由同冰块实体：每次只下落 0.40 格 < 碰撞箱高度，不会漏判）
        if ((this.tickCount & 1) == 0) {
            AABB box = this.getBoundingBox();
            for (Entity e : level.getEntities(this, box, ent -> ent instanceof LivingEntity && ent.isAlive())) {
                this.lastContact = (LivingEntity) e;
                impact();
                return;
            }
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.onGround() || this.horizontalCollision) {
            impact();
            return;
        }
        if (this.getY() < level.getMinBuildHeight() - 1) {
            // 落出世界：不再爆炸，释放本弹的伤害预留
            cn.ism.mekck.util.ChocolateCannonReservations.release(this);
            this.discard();
            return;
        }
        this.baseTick();
    }

    /** 命中结算：爆炸特效（无地形破坏、无爆炸伤害本体）+ 范围伤害 + 处决判定。 */
    private void impact() {
        if (this.isRemoved() || this.level().isClientSide) return;
        Level level = this.level();
        double cx = this.getX(), cy = this.getY(), cz = this.getZ();
        float dmg = getDamage();
        float range = getExplosionRange(this.entityData.get(DATA_FLAGS));

        // 爆炸特效：仅音效与粒子，不破坏地形、不产生爆炸本体伤害（伤害由下方范围判定处理）
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.2, cz, 1, 0, 0, 0, 0);
            serverLevel.sendParticles(ParticleTypes.POOF, cx, cy, cz, 8, 0.3, 0.3, 0.3, 0.02);
        }

        // 范围伤害：默认 3×3×3，霸王死神升级 7×7×7（范围取自 flags，与大炮索敌共用 getExplosionRange）
        AABB area = AABB.ofSize(new Vec3(cx, cy, cz), range, range, range);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            applyHit(e, dmg);
        }

        // 让发射方大炮释放针对本弹的伤害预留（若本弹由大炮发射且对方仍在场）
        cn.ism.mekck.util.ChocolateCannonReservations.release(this);
        this.discard();
    }

    private void applyHit(LivingEntity e, float dmg) {
        Level level = this.level();
        boolean gravity = hasFlag(FLAG_GRAVITY);
        DamageSource src = gravity ? level.damageSources().anvil(null) : level.damageSources().explosion(null);
        // 处决白名单：创造模式玩家、试验假人、驯服生物不被秒杀，只走正常伤害结算（原版规则自然保护创造玩家）
        if (isExecutionWhitelisted(e)) {
            e.invulnerableTime = 0;
            e.hurt(src, dmg);
            if (e.isAlive() && hasFlag(FLAG_CRISPY) && e instanceof Mob mob) {
                removeAI(level, mob);
            }
            return;
        }
        // 受击时判定：目标生命值低于伤害值 → 先正常结算伤害，目标仍存活再处决；否则正常结算爆炸伤害
        if (e.getHealth() < dmg) {
            e.invulnerableTime = 0;
            // 1. 先造成伤害：保留受击音效/护甲减免/不死图腾等原版流程
            e.hurt(src, dmg);
            // 2. 目标仍存活（伤害被减免或图腾救下）→ 处决
            if (e.isAlive()) {
                e.setHealth(0.0F);
                e.die(src);
                if (e.isAlive() && !e.isRemoved()) {
                    // 兜底补刀：个别模组实体覆写 setHealth / isDeadOrDying 抵抗直接死亡（如试验假人式设计），
                    // 直接改血 + die() 无效时改用致命伤害强制击杀
                    e.hurt(src, Float.MAX_VALUE);
                }
            }
            if (gravity && !e.isAlive()) {
                // 超重力场升级：被杀死的实体不播放死亡动画（战利品已在 die 中掉落）
                e.discard();
            }
            return;
        }
        e.invulnerableTime = 0;
        e.hurt(src, dmg);
        if (e.isAlive() && hasFlag(FLAG_CRISPY) && e instanceof Mob mob) {
            removeAI(level, mob);
        }
    }

    /**
     * 处决白名单：命中时即使满足「生命值 < 伤害值」也不直接处决，只正常结算伤害。
     * 包含创造模式玩家（含旁观者）、试验假人 dummmmmmy:target_dummy、驯服的生物
     * （原版狼/猫/鹦鹉等 TamableAnimal 与马类，以及实现 OwnableEntity 的模组可驯养生物）。
     */
    public static boolean isExecutionWhitelisted(LivingEntity e) {
        if (e instanceof net.minecraft.world.entity.player.Player player) {
            return player.isCreative() || player.isSpectator();
        }
        if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                .toString().equals("dummmmmmy:target_dummy")) {
            return true;
        }
        return e instanceof net.minecraft.world.entity.OwnableEntity ownable
                && ownable.getOwnerUUID() != null;
    }

    /** 脆心升级：命中实体移除 2 秒 AI（机制同女王冷萃升级，含恢复时间刷新）。 */
    private static void removeAI(Level level, Mob mob) {
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

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("FerreroDamage", getDamage());
        tag.putByte("FerreroFlags", this.entityData.get(DATA_FLAGS));
        if (ownerPos != null) {
            tag.putLong("OwnerPos", ownerPos.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_DAMAGE, tag.getFloat("FerreroDamage"));
        this.entityData.set(DATA_FLAGS, tag.getByte("FerreroFlags"));
        if (tag.contains("OwnerPos", net.minecraft.nbt.Tag.TAG_LONG)) {
            this.ownerPos = BlockPos.of(tag.getLong("OwnerPos"));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /**
     * 兜底释放预留：所有走 {@link #discard()} 的移除路径（正常爆炸 discard、落出世界 discard、
     * 实体被销毁等）都会先释放本弹预留，保证不会因个别例外分支而遗留占坑的幽灵预留。
     * {@link ChocolateCannonReservations#release} 幂等，与 impact/落出世界 里的显式调用重复执行也安全。
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        cn.ism.mekck.util.ChocolateCannonReservations.release(this);
        super.remove(reason);
    }
}
