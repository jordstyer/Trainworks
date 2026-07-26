package com.trainworks.train;

import com.trainworks.track.BezierCurve;
import com.trainworks.track.Edge;
import com.trainworks.track.TrackGraph;
import com.trainworks.track.TrackGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A single assembled carriage: the blocks a player built around one or two
 * bogies, captured and moved out of the world into this entity. See
 * design/trains.md §2.2/§3.
 *
 * <p>{@code CapturedBlock.relativeOffset} is a fractional {@link Vec3}, not
 * an integer {@link BlockPos}: for a two-bogie carriage the entity's
 * position is the midpoint between the two bogies' curve positions, which
 * is generally not block-aligned, so offsets relative to it aren't either
 * (design/trains.md §3.3). A single-bogie carriage is the degenerate case
 * where that midpoint happens to sit at one specific point instead.</p>
 *
 * <p><strong>Movement (first-pass, unmanned test):</strong> if a track
 * reference was set ({@link #setTrackReference}), the server advances
 * {@code distance} along that edge by a fixed test speed every tick and
 * moves/rotates the entity accordingly -- no throttle/brake/player control
 * yet, just proving the core "follow the curve" mechanic in isolation
 * before wiring up driving on top of it.
 *
 * <p>The client interpolates toward each incoming position/rotation update
 * over {@link #lerpTo}'s given step count, the same pattern {@code
 * LivingEntity} and vehicle entities use. This turned out to matter: the
 * base {@code Entity.lerpTo} does no interpolation at all (just snaps), so
 * without overriding it here, movement looked jittery -- the client only
 * gets a position packet every few ticks, and was teleporting to each one
 * instead of easing toward it.</p>
 *
 * <p>The renderer applies {@code trackYaw() - assemblyYaw} as its rotation,
 * not the raw current yaw -- see {@code CarriageRenderer} for why the raw
 * angle was wrong (it double-counts the alignment already baked into how
 * the structure was built). At the moment of assembly the two are equal,
 * so the delta is zero and nothing visually rotates; as the carriage moves
 * to track positions with a different heading than where it was built,
 * the delta grows and the render correctly follows the curve.
 *
 * <p>{@code trackYaw} is its own {@link SynchedEntityData} field, not
 * {@code getYRot()}/the render-frame {@code entityYaw} parameter. Turned
 * out to matter: vanilla's generic rotation network sync only sends an
 * update once accumulated rotation change crosses roughly a 1.4° threshold
 * (compared against the last value it actually sent), gated by its own
 * periodic check -- fine for a mob that turns in noticeable increments,
 * but for a carriage advancing very gradually every tick, that threshold
 * could take a long time (or track sections with a gentle enough curve,
 * effectively never) to trip, which looked exactly like "doesn't rotate at
 * all" during testing. {@code SynchedEntityData} syncs on any actual value
 * change with no angle-sized gate, so it doesn't have that failure mode.</p>
 *
 * <p>Block states are network-synced (via {@link IEntityAdditionalSpawnData})
 * and saved to disk as raw block-state registry ids rather than the fuller
 * descriptive NBT format ({@code NbtUtils.writeBlockState}/{@code
 * readBlockState}) -- simpler to implement, at the cost of not being
 * resilient to block-state id renumbering across game/mod updates. Worth
 * revisiting before this is depended on for real saves.</p>
 */
public class CarriageEntity extends Entity implements IEntityAdditionalSpawnData {
    /** Test-only fixed speed for the unmanned movement proof -- ~1 block/second. */
    private static final double TEST_SPEED_BLOCKS_PER_TICK = 0.05;

    private static final EntityDataAccessor<Float> DATA_TRACK_YAW =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.FLOAT);

    public record CapturedBlock(Vec3 relativeOffset, BlockState state) {
    }

    private List<CapturedBlock> capturedBlocks = List.of();
    // Bounds of the captured blocks, relative to this entity's position -- used to build a
    // culling box that actually covers the rendered content (see getBoundingBoxForCulling).
    // The declared EntityType size (1x1x1) does not; leaving the default culling box would let
    // the renderer get skipped for any carriage taller/wider than that, with no error or crash,
    // just nothing drawn -- exactly what happened before this was added.
    private Vec3 boundsMin = Vec3.ZERO;
    private Vec3 boundsMax = Vec3.ZERO;

    // Track reference for movement -- server-side authority only, not synced to clients (they
    // only need the interpolated position/yaw from the standard entity-tracking sync).
    private long edgeId = -1L;
    private double distance;
    // Fixed at assembly time; see the class doc for why the renderer needs this alongside the
    // ever-changing current yaw rather than using either value alone.
    private float assemblyYaw;

    public CarriageEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public void setCapturedBlocks(List<CapturedBlock> blocks) {
        applyCapturedBlocks(blocks);
    }

    public List<CapturedBlock> capturedBlocks() {
        return capturedBlocks;
    }

    public void setTrackReference(long edgeId, double distance, float assemblyYaw) {
        this.edgeId = edgeId;
        this.distance = distance;
        this.assemblyYaw = assemblyYaw;
        this.entityData.set(DATA_TRACK_YAW, assemblyYaw);
    }

    public float assemblyYaw() {
        return assemblyYaw;
    }

    /** Current track yaw, synced via {@link SynchedEntityData} -- see the class doc for why. */
    public float trackYaw() {
        return this.entityData.get(DATA_TRACK_YAW);
    }

    // Client-side interpolation target, per the standard pattern LivingEntity/vehicles use --
    // see the class doc. The base Entity.lerpTo() has no interpolation at all (just snaps), which
    // is exactly why movement looked jittery before this was added: the client only gets a
    // position packet every few ticks, and without smoothing it teleports to each one instead of
    // easing toward it.
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = steps;
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide()) {
            if (lerpSteps > 0) {
                double nx = getX() + (lerpX - getX()) / lerpSteps;
                double ny = getY() + (lerpY - getY()) / lerpSteps;
                double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
                float nyRot = getYRot() + (float) Mth.wrapDegrees(lerpYRot - getYRot()) / lerpSteps;
                lerpSteps--;
                setPos(nx, ny, nz);
                setYRot(nyRot);
            }
            return;
        }

        if (edgeId == -1L) {
            return;
        }

        TrackGraph graph = TrackGraphSavedData.get((ServerLevel) level()).graph();
        Optional<Edge> edge = graph.getEdge(edgeId);
        if (edge.isEmpty()) {
            return;
        }

        BezierCurve curve = graph.curveOf(edge.get());
        distance = Math.min(distance + TEST_SPEED_BLOCKS_PER_TICK, curve.length());

        Vec3 pos = curve.positionAt(distance).subtract(0.5, 0.5, 0.5);
        float yaw = curve.yawAt(distance);
        setPos(pos.x, pos.y, pos.z);
        setYRot(yaw);
        this.entityData.set(DATA_TRACK_YAW, yaw);
    }

    private void applyCapturedBlocks(List<CapturedBlock> blocks) {
        this.capturedBlocks = List.copyOf(blocks);

        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (CapturedBlock block : this.capturedBlocks) {
            Vec3 pos = block.relativeOffset();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        this.boundsMin = new Vec3(minX, minY, minZ);
        this.boundsMax = new Vec3(maxX, maxY, maxZ);
    }

    /**
     * Implementing {@link IEntityAdditionalSpawnData} does nothing by itself --
     * this override is what actually makes the server send Forge's custom spawn
     * packet (carrying {@link #writeSpawnData}'s payload) instead of the plain
     * vanilla one. Without it, {@link #readSpawnData} is never called on the
     * client and {@code capturedBlocks} silently stays empty forever.
     */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(
                getX() + boundsMin.x, getY() + boundsMin.y, getZ() + boundsMin.z,
                getX() + boundsMax.x + 1, getY() + boundsMax.y + 1, getZ() + boundsMax.z + 1);
    }

    @Override
    protected void defineSynchedData() {
        // The captured block list is variable-length and syncs via IEntityAdditionalSpawnData
        // instead; trackYaw is the one small fixed-size value worth a real SynchedEntityData
        // field, since it needs reliable per-tick sync (see the class doc).
        this.entityData.define(DATA_TRACK_YAW, 0f);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ListTag list = tag.getList("Blocks", Tag.TAG_COMPOUND);
        List<CapturedBlock> blocks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Vec3 relative = new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(entry.getInt("State"));
            blocks.add(new CapturedBlock(relative, state));
        }
        applyCapturedBlocks(blocks);

        edgeId = tag.getLong("EdgeId");
        distance = tag.getDouble("Distance");
        assemblyYaw = tag.getFloat("AssemblyYaw");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CapturedBlock block : capturedBlocks) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", block.relativeOffset().x);
            entry.putDouble("Y", block.relativeOffset().y);
            entry.putDouble("Z", block.relativeOffset().z);
            entry.putInt("State", Block.BLOCK_STATE_REGISTRY.getId(block.state()));
            list.add(entry);
        }
        tag.put("Blocks", list);

        tag.putLong("EdgeId", edgeId);
        tag.putDouble("Distance", distance);
        tag.putFloat("AssemblyYaw", assemblyYaw);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeFloat(assemblyYaw);
        buffer.writeVarInt(capturedBlocks.size());
        for (CapturedBlock block : capturedBlocks) {
            Vec3 relative = block.relativeOffset();
            buffer.writeDouble(relative.x);
            buffer.writeDouble(relative.y);
            buffer.writeDouble(relative.z);
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        assemblyYaw = buffer.readFloat();
        int count = buffer.readVarInt();
        List<CapturedBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Vec3 relative = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buffer.readVarInt());
            blocks.add(new CapturedBlock(relative, state));
        }
        applyCapturedBlocks(blocks);
    }
}
