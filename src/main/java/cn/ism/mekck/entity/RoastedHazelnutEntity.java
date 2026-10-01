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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import cn.ism.mekck.registry.MekCkEntities;
import cn.ism.mekck.registry.MekCkItems;

/**
 * 炒榛子实体：坚果爆炒机的攻击弹药。
 * <p>
 * 从机器位置射出，不受重力影响，以恒定速度（默认 2 格/tick）直线飞向目标；
 * 命中生物时造成 {@link #DAMAGE} 点弹射物伤害并消失，命中方块时直接消失；
 * 飞行时间达到上限（射出 tick 数 × 2 = 机器设置射程，速度 2 格/tick）后自动移除。
 * </p>
 */
public class RoastedHazelnutEntity extends ThrowableItemProjectile {

    /** 命中生物造成的弹射物伤害（可在常量处调整）。 */
    public static final float DAMAGE = 40.0F;
    /** 飞行速度（格 / tick）。 */
    public static final double SPEED = 2.0D;

    private static final EntityDataAccessor<Float> DATA_DAMAGE = SynchedEntityData.defineId(RoastedHazelnutEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_MAX_AGE = SynchedEntityData.defineId(RoastedHazelnutEntity.class, EntityDataSerializers.INT);

    private int age;

    /** 发射源方块的坐标：射线碰撞跳过该方块，避免炒榛子在机器内部生成时立即与机器碰撞消失。 */
    @Nullable
    private BlockPos spawnBlockPos;

    public RoastedHazelnutEntity(EntityType<? extends RoastedHazelnutEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 从机器位置朝目标方向发射一枚炒榛子。
     *
     * @param machinePos  发射源机器的坐标（射线碰撞跳过该方块）；可为 null
     * @param maxAgeTicks 存活 tick 上限（射程 / 2，速度 2 格/tick 下恰好飞满射程）
     */
    public static RoastedHazelnutEntity spawn(Level level, Vec3 from, LivingEntity target, float damage, int maxAgeTicks, @Nullable BlockPos machinePos) {
        RoastedHazelnutEntity entity = new RoastedHazelnutEntity(MekCkEntities.ROASTED_HAZELNUT_ENTITY.get(), level);
        entity.setPos(from.x, from.y, from.z);
        entity.spawnBlockPos = machinePos;
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
        Vec3 dir = aim.subtract(from);
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0, 1, 0);
        }
        entity.setDeltaMovement(dir.normalize().scale(SPEED));
        entity.entityData.set(DATA_DAMAGE, damage);
        entity.entityData.set(DATA_MAX_AGE, Math.max(1, maxAgeTicks));
        level.addFreshEntity(entity);
        return entity;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DAMAGE, DAMAGE);
        this.entityData.define(DATA_MAX_AGE, 8);
    }

    @Override
    protected Item getDefaultItem() {
        return MekCkItems.ROASTED_HAZELNUT_ITEM.get();
    }

    private float getDamage() {
        return this.entityData.get(DATA_DAMAGE);
    }

    @Override
    public void tick() {
        Level level = this.level();
        if (level.isClientSide) {
            super.tick();
            return;
        }
        // 飞行时间上限：射出 tick × 2 = 机器设置射程（速度 2 格/tick）
        if (++this.age > this.entityData.get(DATA_MAX_AGE)) {
            this.discard();
            return;
        }

        Vec3 start = this.position();
        Vec3 end = start.add(this.getDeltaMovement());

        // 方块射线（2 格/tick 的速度必须走射线检测，避免穿墙）；命中发射源方块时跳过并沿方向前进继续检测，
        // 使炒榛子可以穿过自己出生的坚果爆炒机（生成点在机器方块内部，否则会立即与机器碰撞消失）
        Vec3 clipStart = start;
        BlockHitResult blockHit = BlockHitResult.miss(end, net.minecraft.core.Direction.UP, net.minecraft.core.BlockPos.containing(end));
        for (int i = 0; i < 4; i++) {
            BlockHitResult hit = level.clip(new ClipContext(clipStart, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() != HitResult.Type.MISS && this.spawnBlockPos != null && hit.getBlockPos().equals(this.spawnBlockPos)) {
                clipStart = hit.getLocation().add(this.getDeltaMovement().normalize().scale(0.05D));
                continue;
            }
            blockHit = hit;
            break;
        }
        Vec3 hitPos = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;

        // 实体射线（取射线上最近的生物，且不早于方块命中点）
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, this, start, hitPos,
                this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(0.3D),
                e -> e instanceof LivingEntity && e.isAlive() && e != this);
        boolean entityFirst = entityHit != null
                && (blockHit.getType() == HitResult.Type.MISS
                || entityHit.getLocation().distanceToSqr(start) <= hitPos.distanceToSqr(start));

        this.setPos(hitPos.x, hitPos.y, hitPos.z);
        if (entityFirst && entityHit != null) {
            onHitEntity((LivingEntity) entityHit.getEntity());
            return;
        }
        if (blockHit.getType() != HitResult.Type.MISS) {
            onHitBlock();
            return;
        }
        this.baseTick();
    }

    private void onHitEntity(LivingEntity target) {
        Level level = this.level();
        target.invulnerableTime = 0;
        // 火焰伤害（炒榛子是刚出锅的，烫嘴）
        target.hurt(level.damageSources().inFire(), getDamage());
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.CRIT, this.getX(), this.getY(), this.getZ(), 4, 0.1, 0.1, 0.1, 0.02);
        }
        this.discard();
    }

    private void onHitBlock() {
        Level level = this.level();
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.POOF, this.getX(), this.getY(), this.getZ(), 3, 0.1, 0.1, 0.1, 0.01);
        }
        this.discard();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("RoastedDamage", getDamage());
        tag.putInt("RoastedMaxAge", this.entityData.get(DATA_MAX_AGE));
        tag.putInt("RoastedAge", this.age);
        if (this.spawnBlockPos != null) {
            tag.putLong("RoastedSpawnBlock", this.spawnBlockPos.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_DAMAGE, tag.getFloat("RoastedDamage"));
        this.entityData.set(DATA_MAX_AGE, Math.max(1, tag.getInt("RoastedMaxAge")));
        this.age = tag.getInt("RoastedAge");
        if (tag.contains("RoastedSpawnBlock")) {
            this.spawnBlockPos = BlockPos.of(tag.getLong("RoastedSpawnBlock"));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
