package io.github.foundationgames.automobility.entity;

import com.google.common.util.concurrent.AtomicDouble;
import io.github.foundationgames.automobility.automobile.AutomobileEngine;
import io.github.foundationgames.automobility.automobile.AutomobileFrame;
import io.github.foundationgames.automobility.automobile.AutomobileStats;
import io.github.foundationgames.automobility.automobile.AutomobileWheel;
import io.github.foundationgames.automobility.automobile.WheelBase;
import io.github.foundationgames.automobility.automobile.render.RenderableAutomobile;
import io.github.foundationgames.automobility.block.OffRoadBlock;
import io.github.foundationgames.automobility.block.Sloped;
import io.github.foundationgames.automobility.item.AutomobilityItems;
import io.github.foundationgames.automobility.util.AUtils;
import io.github.foundationgames.automobility.util.lambdacontrols.ControllerUtils;
import io.github.foundationgames.automobility.util.network.PayloadPackets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class AutomobileEntity extends Entity implements RenderableAutomobile {
    private AutomobileFrame frame = AutomobileFrame.REGISTRY.getOrDefault(null);
    private AutomobileWheel wheels = AutomobileWheel.REGISTRY.getOrDefault(null);
    private AutomobileEngine engine = AutomobileEngine.REGISTRY.getOrDefault(null);

    private final AutomobileStats stats = new AutomobileStats();

    @Environment(EnvType.CLIENT)
    private Model frameModel = null;
    @Environment(EnvType.CLIENT)
    private Model wheelModel = null;
    @Environment(EnvType.CLIENT)
    private Model engineModel = null;

    public static final int DRIFT_TURBO_TIME = 50;
    public static final float TERMINAL_VELOCITY = -1.2f;

    private boolean dirty = false;

    private float engineSpeed = 0;
    private float boostSpeed = 0;
    private float speedDirection = 0;
    private float lastBoostSpeed = boostSpeed;

    private int boostTimer = 0;
    private float boostPower = 0;

    private float hSpeed = 0;

    private float verticalSpeed = 0;
    private Vec3d addedVelocity = getVelocity();

    private float steering = 0;
    private float lastSteering = steering;

    private float wheelAngle = 0;
    private float lastWheelAngle = 0;

    private final Displacement displacement = new Displacement();

    private boolean drifting = false;
    private int driftDir = 0;
    private int driftTimer = 0;

    private float lockedViewOffset = 0;

    private boolean automobileOnGround = true;
    private boolean wasOnGround = automobileOnGround;
    private boolean isFloorDirectlyBelow = true;

    private Vec3d lastVelocity = Vec3d.ZERO;

    // Prevents jittering when going down slopes
    private int slopeStickingTimer = 0;

    private int suspensionBounceTimer = 0;
    private int lastSusBounceTimer = suspensionBounceTimer;

    private final Deque<Double> prevYDisplacements = new ArrayDeque<>();

    private boolean offRoad = false;
    private Vec3f debrisColor = new Vec3f();

    private int fallTicks = 0;

    public void writeSyncToClientData(PacketByteBuf buf) {
        buf.writeInt(boostTimer);
        buf.writeFloat(steering);
        buf.writeFloat(wheelAngle);
        buf.writeBoolean(drifting);
        buf.writeInt(driftTimer);
        this.displacement.toBuffer(buf);
        buf.writeByte(compactInputData());
    }

    public void readSyncToClientData(PacketByteBuf buf) {
        boostTimer = buf.readInt();
        steering = buf.readFloat();
        wheelAngle = buf.readFloat();
        drifting = buf.readBoolean();
        driftTimer = buf.readInt();
        this.displacement.fromBuffer(buf);
        readCompactedInputData(buf.readByte());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        setComponents(
                AutomobileFrame.REGISTRY.getOrDefault(Identifier.tryParse(nbt.getString("frame"))),
                AutomobileWheel.REGISTRY.getOrDefault(Identifier.tryParse(nbt.getString("wheels"))),
                AutomobileEngine.REGISTRY.getOrDefault(Identifier.tryParse(nbt.getString("engine")))
        );
        engineSpeed = nbt.getFloat("engineSpeed");
        boostSpeed = nbt.getFloat("boostSpeed");
        boostTimer = nbt.getInt("boostTimer");
        boostPower = nbt.getFloat("boostPower");
        speedDirection = nbt.getFloat("speedDirection");
        verticalSpeed = nbt.getFloat("verticalSpeed");
        hSpeed = nbt.getFloat("horizontalSpeed");
        addedVelocity = AUtils.v3dFromNbt(nbt.getCompound("addedVelocity"));
        lastVelocity = AUtils.v3dFromNbt(nbt.getCompound("lastVelocity"));
        steering = nbt.getFloat("steering");
        wheelAngle = nbt.getFloat("wheelAngle");
        drifting = nbt.getBoolean("drifting");
        driftDir = nbt.getInt("driftDir");
        driftTimer = nbt.getInt("driftTimer");
        accelerating = nbt.getBoolean("accelerating");
        braking = nbt.getBoolean("braking");
        steeringLeft = nbt.getBoolean("steeringLeft");
        steeringRight = nbt.getBoolean("steeringRight");
        holdingDrift = nbt.getBoolean("holdingDrift");
        fallTicks = nbt.getInt("fallTicks");
        displacement.fromNbt(nbt.getCompound("displacement"));

        updateModels = true;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("frame", frame.getId().toString());
        nbt.putString("wheels", wheels.getId().toString());
        nbt.putString("engine", engine.getId().toString());
        nbt.putFloat("engineSpeed", engineSpeed);
        nbt.putFloat("boostSpeed", boostSpeed);
        nbt.putInt("boostTimer", boostTimer);
        nbt.putFloat("boostPower", boostPower);
        nbt.putFloat("speedDirection", speedDirection);
        nbt.putFloat("verticalSpeed", verticalSpeed);
        nbt.putFloat("horizontalSpeed", hSpeed);
        nbt.put("addedVelocity", AUtils.v3dToNbt(addedVelocity));
        nbt.put("lastVelocity", AUtils.v3dToNbt(lastVelocity));
        nbt.putFloat("steering", steering);
        nbt.putFloat("wheelAngle", wheelAngle);
        nbt.putBoolean("drifting", drifting);
        nbt.putInt("driftDir", driftDir);
        nbt.putInt("driftTimer", driftTimer);
        nbt.putBoolean("accelerating", accelerating);
        nbt.putBoolean("braking", braking);
        nbt.putBoolean("steeringLeft", steeringLeft);
        nbt.putBoolean("steeringRight", steeringRight);
        nbt.putBoolean("holdingDrift", holdingDrift);
        nbt.putInt("fallTicks", fallTicks);
        nbt.put("displacement", displacement.toNbt(new NbtCompound()));
    }

    private boolean accelerating = false;
    private boolean braking = false;
    private boolean steeringLeft = false;
    private boolean steeringRight = false;
    private boolean holdingDrift = false;

    private boolean prevHoldDrift = holdingDrift;

    public byte compactInputData() {
        // yeah
        int r = ((((((((accelerating ? 1 : 0) << 1) | (braking ? 1 : 0)) << 1) | (steeringLeft ? 1 : 0)) << 1) | (steeringRight ? 1 : 0)) << 1) | (holdingDrift ? 1 : 0);
        return (byte) r;
    }

    public void readCompactedInputData(byte data) {
        // yup
        int d = data;
        holdingDrift = (1 & d) > 0;
        d = d >> 0b1;
        steeringRight = (1 & d) > 0;
        d = d >> 0b1;
        steeringLeft = (1 & d) > 0;
        d = d >> 0b1;
        braking = (1 & d) > 0;
        d = d >> 0b1;
        accelerating = (1 & d) > 0;
    }

    @Environment(EnvType.CLIENT)
    public boolean updateModels = true;

    public AutomobileEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    public AutomobileEntity(World world) {
        this(AutomobilityEntities.AUTOMOBILE, world);
    }

    @Override
    public void onSpawnPacket(EntitySpawnS2CPacket packet) {
        super.onSpawnPacket(packet);
        if (world.isClient()) {
            PayloadPackets.requestSyncAutomobileComponentsPacket(this);
        }
    }

    public AutomobileFrame getFrame() {
        return frame;
    }

    public AutomobileWheel getWheels() {
        return wheels;
    }

    public AutomobileEngine getEngine() {
        return engine;
    }

    @Override
    public float getSteering(float tickDelta) {
        return MathHelper.lerp(tickDelta, lastSteering, steering);
    }

    @Override
    public float getWheelAngle(float tickDelta) {
        return MathHelper.lerp(tickDelta, lastWheelAngle, wheelAngle);
    }

    public float getBoostSpeed(float tickDelta) {
        return MathHelper.lerp(tickDelta, lastBoostSpeed, boostSpeed);
    }

    @Override
    public float getSuspensionBounce(float tickDelta) {
        return MathHelper.lerp(tickDelta, lastSusBounceTimer, suspensionBounceTimer);
    }

    @Override
    public boolean engineRunning() {
        return hasPassengers();
    }

    @Override
    public int getDriftTimer() {
        return drifting ? driftTimer : 0;
    }

    @Override
    public long getWorldTime() {
        return world.getTime();
    }

    public float getHSpeed() {
        return hSpeed;
    }

    @Override
    public int getBoostTimer() {
        return boostTimer;
    }

    @Override
    public boolean automobileOnGround() {
        return automobileOnGround;
    }

    @Override
    public boolean debris() {
        return offRoad && hSpeed != 0;
    }

    @Override
    public Vec3f debrisColor() {
        return debrisColor;
    }

    public void setComponents(AutomobileFrame frame, AutomobileWheel wheel, AutomobileEngine engine) {
        this.frame = frame;
        this.wheels = wheel;
        this.engine = engine;
        this.updateModels = true;
        this.stepHeight = wheels.size();
        this.stats.from(frame, wheel, engine);
        this.displacement.applyWheelbase(frame.model().wheelBase());
        if (!world.isClient()) syncComponents();
    }

    private void forNearbyPlayers(int radius, boolean ignoreDriver, Consumer<ServerPlayerEntity> action) {
        for (PlayerEntity p : world.getPlayers()) {
            if (ignoreDriver && p == getFirstPassenger()) {
                continue;
            }
            if (p.getPos().distanceTo(getPos()) < radius && p instanceof ServerPlayerEntity player) {
                action.accept(player);
            }
        }
    }

    @Override
    public void baseTick() {
        super.baseTick();

        if (!this.hasPassengers()) {
            accelerating = false;
            braking = false;
            steeringLeft = false;
            steeringRight = false;
            holdingDrift = false;
        }
        collisionStateTick();
        steeringTick();
        driftingTick();
        movementTick();
        updateTrackedPosition(getX(), getY(), getZ());

        if (!world.isClient()) {
            if (dirty) {
                syncData();
                dirty = false;
            }
            forNearbyPlayers(400, true, player -> PayloadPackets.sendSyncAutomobilePosPacket(this, player));
            if (!this.hasPassengers()) {
                var touchingEntities = this.world.getOtherEntities(this, this.getBoundingBox().expand(0.2, 0, 0.2), EntityPredicates.canBePushedBy(this));
                for (Entity entity : touchingEntities) {
                    if (!entity.hasPassenger(this)) {
                        if (!entity.hasVehicle() && entity.getWidth() < this.getWidth() && entity instanceof MobEntity && !(entity instanceof WaterCreatureEntity)) {
                            entity.startRiding(this);
                        }
                    }
                }
            } else if (this.getFirstPassenger() instanceof MobEntity mob) {
                provideMobDriverInputs(mob);
            }
        }

        displacementTick();
    }

    public void markDirty() {
        dirty = true;
    }

    private void syncData() {
        forNearbyPlayers(200, true, player -> PayloadPackets.sendSyncAutomobileDataPacket(this, player));
    }

    private void syncComponents() {
        forNearbyPlayers(200, false, player -> PayloadPackets.sendSyncAutomobileComponentsPacket(this, player));
    }

    public ItemStack asItem() {
        var stack = new ItemStack(AutomobilityItems.AUTOMOBILE);
        var automobile = stack.getOrCreateSubNbt("Automobile");
        automobile.putString("frame", frame.getId().toString());
        automobile.putString("wheels", wheels.getId().toString());
        automobile.putString("engine", engine.getId().toString());
        return stack;
    }

    @Nullable
    @Override
    public ItemStack getPickBlockStack() {
        return asItem();
    }

    // making mobs drive automobiles
    // technically the mobs don't drive, instead the automobile
    // self-drives to the mob's destination...
    public void provideMobDriverInputs(MobEntity driver) {
        var path = driver.getNavigation().getCurrentPath();
        // checks if there is a current, incomplete path that the entity has targeted
        if (path != null && !path.isFinished() && path.getEnd() != null) {
            // determines the relative position to drive to, based on the end of the path
            var pos = path.getEnd().getPos().subtract(getPos());
            // determines the angle to that position
            double target = MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(pos.getX(), pos.getZ())));
            // determines another relative position, this time to the path's current node (in the case of the path directly to the end being obstructed)
            var fnPos = path.getCurrentNode().getPos().subtract(getPos());
            // determines the angle to that current node's position
            double fnTarget = MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(fnPos.getX(), fnPos.getZ())));
            // if the difference in angle between the end position and the current node's position is too great,
            // the automobile will drive to that current node under the assumption that the path directly to the
            // end is obstructed
            if (Math.abs(target - fnTarget) > 69) {
                pos = fnPos;
                target = fnTarget;
            }
            // fixes up the automobile's own yaw value
            float yaw = MathHelper.wrapDegrees(-getYaw());
            // finds the difference between the target angle and the yaw
            double offset = MathHelper.wrapDegrees(yaw - target);
            // whether the automobile should go in reverse
            boolean reverse = false;
            // a value to determine the threshold used to determine whether the automobile is moving
            // both slow enough and is at an extreme enough offset angle to incrementally move in reverse
            float mul = 0.5f + (MathHelper.clamp(hSpeed, 0, 1) * 0.5f);
            if (pos.length() < 20 * mul && Math.abs(offset) > 180 - (170 * mul)) {
                long time = world.getTime();
                // this is so that the automobile alternates between reverse and forward,
                // like a driver would do in order to angle their vehicle toward a target location
                reverse = (time % 80 <= 30);
            }
            // set the accel/brake inputs
            accelerating = !reverse;
            braking = reverse;
            // set the steering inputs, with a bit of a dead zone to prevent jittering
            if (offset < -7) {
                steeringRight = reverse;
                steeringLeft = !reverse;
            } else if (offset > 7) {
                steeringRight = !reverse;
                steeringLeft = reverse;
            }
            markDirty();
        } else {
            if (accelerating || steeringLeft || steeringRight) markDirty();
            accelerating = false;
            steeringLeft = false;
            steeringRight = false;
        }
    }

    // witness me fighting against minecraft's collision/physics
    public void movementTick() {
        // Handle the small suspension bounce effect
        if (lastSusBounceTimer != suspensionBounceTimer) markDirty();
        lastSusBounceTimer = suspensionBounceTimer;
        if (suspensionBounceTimer > 0) suspensionBounceTimer--;
        if (!wasOnGround && automobileOnGround) {
            suspensionBounceTimer = 3;
            markDirty();
        }

        // Handles boosting
        lastBoostSpeed = boostSpeed;
        if (boostTimer > 0) {
            boostTimer--;
            boostSpeed = Math.min(boostPower, boostSpeed + 0.09f);
            if (engineSpeed < stats.getComfortableSpeed()) {
                engineSpeed += 0.012f;
            }
            markDirty();
        } else {
            boostSpeed = AUtils.zero(boostSpeed, 0.09f);
        }

        // Get block below's friction
        var blockBelow = new BlockPos(getX(), getY() - 0.05, getZ());
        float grip = 1 - ((MathHelper.clamp((world.getBlockState(blockBelow).getBlock().getSlipperiness() - 0.6f) / 0.4f, 0, 1) * (1 - stats.getGrip() * 0.8f)));

        // Track the last position of the automobile
        var lastPos = getPos();

        // cumulative will be modified by the following code and then the automobile will be moved by it
        // Currently initialized with the value of addedVelocity (which is a general velocity vector applied to the automobile, i.e. for when it bumps into a wall and is pushed back)
        var cumulative = addedVelocity;
        if (lastWheelAngle != wheelAngle) markDirty();
        lastWheelAngle = wheelAngle;

        // Reduce gravity underwater
        cumulative = cumulative.add(0, (verticalSpeed * (isSubmergedInWater() ? 0.15f : 1)), 0);

        // This is the general direction the automobile will move, which is slightly offset to the side when drifting and delayed when on slippery surface
        this.speedDirection = MathHelper.lerp(grip, getYaw(), getYaw() - (drifting ? Math.min(driftTimer * 6, 43 + (-steering * 12)) * driftDir : 0));

        // Handle acceleration
        if (accelerating) {
            float speed = Math.max(this.engineSpeed, 0);
            // yeah ...
            this.engineSpeed +=
                    // The following conditions check whether the automobile should NOT receive normal acceleration
                    // It will not receive this acceleration if the automobile is steering or tight-drifting
                    (
                            (this.drifting && AUtils.haveSameSign(this.steering, this.driftDir)) ||
                            (!this.drifting && this.steering != 0 && hSpeed > 0.5)
                    ) ? (this.hSpeed < stats.getComfortableSpeed() ? 0.001 : 0) // This will supply a small amount of acceleration if the automobile is moving slowly only

                    // Otherwise, it will receive acceleration as normal
                    // It will receive this acceleration if the automobile is moving straight or wide-drifting (the latter slightly reduces acceleration)
                    : calculateAcceleration(speed, stats) * (drifting ? 0.86 : 1) * (engineSpeed > stats.getComfortableSpeed() ? 0.25f : 1) * grip;
        }
        // Handle braking/reverse
        if (braking) {
            this.engineSpeed = Math.max(this.engineSpeed - 0.15f, -0.25f);
        }
        // Handle when the automobile is rolling to a stop
        if (!accelerating && !braking) {
            this.engineSpeed = AUtils.zero(this.engineSpeed, 0.025f);
        }

        // Slow the automobile a bit while steering and moving fast
        if (!drifting && steering != 0 && hSpeed > 0.8) {
            engineSpeed -= engineSpeed * 0.00042f;
        }

        // Allows for the sticky slope effect to continue for a tick after not being on a slope
        // This prevents the automobile from randomly jumping if it's moving down a slope quickly
        var below = new BlockPos(Math.floor(getX()), Math.floor(getY() - 0.51), Math.floor(getZ()));
        var state = world.getBlockState(below);
        if (state.getBlock() instanceof Sloped slope && slope.isSticky()) {
            slopeStickingTimer = 1;
        } else {
            slopeStickingTimer = Math.max(0, slopeStickingTimer--);
        }

        // Handle being in off-road
        if (boostSpeed < 0.4f && world.getBlockState(getBlockPos()).getBlock() instanceof OffRoadBlock offRoad) {
            int layers = world.getBlockState(getBlockPos()).get(OffRoadBlock.LAYERS);
            float cap = stats.getComfortableSpeed() * (1 - ((float)layers / 3.5f));
            engineSpeed = Math.min(cap, engineSpeed);
            this.debrisColor = offRoad.color;
            this.offRoad = true;
        } else this.offRoad = false;

        // Set the horizontal speed
        hSpeed = engineSpeed + boostSpeed;

        // Sticking to sticky slopes
        double lowestPrevYDisp = 0;
        for (double d : prevYDisplacements) {
            lowestPrevYDisp = Math.min(d, lowestPrevYDisp);
        }
        if (slopeStickingTimer > 0 && automobileOnGround && lowestPrevYDisp <= 0) {
            cumulative = cumulative.add(0, -0.5, 0);
        }

        // Apply the horizontal speed to the cumulative movement
        float angle = (float) Math.toRadians(-speedDirection);
        cumulative = cumulative.add(Math.sin(angle) * hSpeed, 0, Math.cos(angle) * hSpeed);

        // Turn the wheels
        float wheelCircumference = (float)(2 * (wheels.model().radius() / 16) * Math.PI);
        if (hSpeed > 0) markDirty();
        wheelAngle += 300 * (hSpeed / wheelCircumference) + (hSpeed > 0 ? ((1 - grip) * 15) : 0); // made it a bit slower intentionally, also make it spin more when on slippery surface

        // Move the automobile by the cumulative vector
        this.move(MovementType.SELF, cumulative);
        if (world.isClient()) {
            this.lastRenderX = lastPos.x;
            this.lastRenderY = lastPos.y;
            this.lastRenderZ = lastPos.z;
        }
        lastVelocity = cumulative;

        // Damage and launch entities that are hit by a moving automobile
        if (hSpeed > 0.2) {
            var frontBox = getBoundingBox().offset(cumulative.multiply(0.5));
            var velAdd = cumulative.add(0, 0.1, 0).multiply(3);
            for (var entity : world.getEntitiesByType(TypeFilter.instanceOf(Entity.class), frontBox, entity -> entity != this && entity != getFirstPassenger())) {
                if (entity instanceof LivingEntity living) {
                    living.damage(AutomobilityEntities.AUTOMOBILE_DAMAGE_SOURCE, hSpeed * 10);
                }
                entity.addVelocity(velAdd.x, velAdd.y, velAdd.z);
            }
        }

        // ############################################################################################################
        // ############################################################################################################
        // ###########################################POST#MOVE#CODE###################################################
        // ############################################################################################################
        // ############################################################################################################

        // Reduce the values of addedVelocity incrementally
        addedVelocity = new Vec3d(
                AUtils.zero((float)addedVelocity.x, 0.1f),
                AUtils.zero((float)addedVelocity.y, 0.1f),
                AUtils.zero((float)addedVelocity.z, 0.1f)
        );

        // This code handles bumping into a wall, yes it is utterly horrendous
        var displacement = new Vec3d(getX(), 0, getZ()).subtract(lastPos.x, 0, lastPos.z);
        if (hSpeed > 0.1 && displacement.length() < hSpeed * 0.5 && addedVelocity.length() <= 0) {
            engineSpeed /= 3.6;
            float knockSpeed = ((-0.2f * hSpeed) - 0.5f);
            addedVelocity = addedVelocity.add(Math.sin(angle) * knockSpeed, 0, Math.cos(angle) * knockSpeed);
        }

        double yDisp = getPos().subtract(lastPos).getY();

        // Increment the falling timer
        if (!automobileOnGround && yDisp < 0) {
            fallTicks += 1;
        } else {
            fallTicks = 0;
        }

        // Handle launching off slopes
        double highestPrevYDisp = 0;
        for (double d : prevYDisplacements) {
            highestPrevYDisp = Math.max(d, highestPrevYDisp);
        }
        if (wasOnGround && !automobileOnGround && !isFloorDirectlyBelow) {
            verticalSpeed = (float)MathHelper.clamp(highestPrevYDisp, 0, hSpeed * 0.6f);
        }

        // Handles gravity
        verticalSpeed = Math.max(verticalSpeed - 0.08f, !automobileOnGround ? TERMINAL_VELOCITY : -0.01f);

        // Store previous y displacement to use when launching off slopes
        prevYDisplacements.push(yDisp);
        if (prevYDisplacements.size() > 2) {
            prevYDisplacements.removeLast();
        }

        // Handle setting the locked view offset
        if (hSpeed != 0) {
            float vOTarget = (drifting ? driftDir * -23 : steering * -5.6f);
            if (vOTarget == 0) lockedViewOffset = AUtils.zero(lockedViewOffset, 2.5f);
            else {
                if (lockedViewOffset < vOTarget) lockedViewOffset = Math.min(lockedViewOffset + 3.7f, vOTarget);
                else lockedViewOffset = Math.max(lockedViewOffset - 3.7f, vOTarget);
            }
        }

        // Turns the automobile based on steering/drifting
        if (hSpeed != 0) {
            float yawInc = (drifting ? (((this.steering + (driftDir)) * driftDir * 2.5f + 1.5f) * driftDir) * (((1 - stats.getGrip()) + 2) / 2.5f) : this.steering * ((4f * Math.min(hSpeed, 1)) + (hSpeed > 0 ? 2 : -3.5f))) * ((stats.getHandling() + 1) / 2);
            float prevYaw = getYaw();
            this.setYaw(getYaw() + yawInc);
            if (world.isClient) {
                var passenger = getFirstPassenger();
                if (passenger instanceof PlayerEntity player) {
                    if (inLockedViewMode()) {
                        player.setYaw(MathHelper.wrapDegrees(getYaw() + lockedViewOffset));
                        player.setBodyYaw(MathHelper.wrapDegrees(getYaw() + lockedViewOffset));
                    } else {
                        player.setYaw(MathHelper.wrapDegrees(player.getYaw() + yawInc));
                        player.setBodyYaw(MathHelper.wrapDegrees(player.getYaw() + yawInc));
                    }
                }
            } else {
                for (Entity e : getPassengerList()) {
                    if (e == getFirstPassenger()) continue;
                    e.setYaw(MathHelper.wrapDegrees(e.getYaw() + yawInc));
                    e.setBodyYaw(MathHelper.wrapDegrees(e.getYaw() + yawInc));
                }
            }
            if (world.isClient()) {
                this.prevYaw = prevYaw;
            }
        }
    }

    public void displacementTick() {
        this.displacement.preTick();
        if (this.automobileOnGround) {
            this.displacement.tick(this.world, this, this.getPos(), this.getYaw(), this.stepHeight);
        }
    }

    public Displacement getDisplacement() {
        return this.displacement;
    }

    public void collisionStateTick() {
        // scuffed ground check
        wasOnGround = automobileOnGround;
        automobileOnGround = false;
        isFloorDirectlyBelow = false;
        var b = getBoundingBox();
        var groundBox = new Box(b.minX, b.minY - 0.04, b.minZ, b.maxX, b.minY, b.maxZ);
        var wid = (b.getXLength() + b.getZLength()) * 0.5f;
        var floorBox = new Box(b.minX + (wid * 0.94), b.minY - 0.05, b.minZ + (wid * 0.94), b.maxX - (wid * 0.94), b.minY, b.maxZ - (wid * 0.94));
        var start = new BlockPos(b.minX - 0.1, b.minY - 0.2, b.minZ - 0.1);
        var end = new BlockPos(b.maxX + 0.1, b.maxY + 0.2, b.maxZ + 0.1);
        var groundCuboid = VoxelShapes.cuboid(groundBox);
        var floorCuboid = VoxelShapes.cuboid(floorBox);
        if (this.world.isRegionLoaded(start, end)) {
            var pos = new BlockPos.Mutable();
            for(int x = start.getX(); x <= end.getX(); ++x) {
                for(int y = start.getY(); y <= end.getY(); ++y) {
                    for(int z = start.getZ(); z <= end.getZ(); ++z) {
                        pos.set(x, y, z);
                        var state = this.world.getBlockState(pos);
                        var blockShape = state.getCollisionShape(this.world, pos, ShapeContext.of(this)).offset(pos.getX(), pos.getY(), pos.getZ());
                        automobileOnGround = automobileOnGround || VoxelShapes.matchesAnywhere(blockShape, groundCuboid, BooleanBiFunction.AND);
                        isFloorDirectlyBelow = isFloorDirectlyBelow || VoxelShapes.matchesAnywhere(blockShape, floorCuboid, BooleanBiFunction.AND);
                    }
                }
            }
        }
    }

    public void updatePositionAndAngles(double x, double y, double z, float yaw, float pitch) {
        prevYaw = getYaw();
        this.setPos(x, y, z);
        this.setYaw(yaw);
        this.setPitch(pitch);
        this.refreshPosition();
    }

    private float calculateAcceleration(float speed, AutomobileStats stats) {
        // A somewhat over-engineered function to accelerate the automobile, since I didn't want to add a hard speed cap
        return (1 / ((300 * speed) + (18.5f - (stats.getAcceleration() * 5.3f)))) * (0.9f * ((stats.getAcceleration() + 1) / 2));
    }

    @Environment(EnvType.CLIENT)
    public void provideClientInput(boolean fwd, boolean back, boolean left, boolean right, boolean space) {
        // Receives inputs client-side and sends them to the server
        if (!(
                fwd == accelerating &&
                back == braking &&
                left == steeringLeft &&
                right == steeringRight &&
                space == holdingDrift
        )) {
            setInputs(fwd, back, left, right, space);
            PayloadPackets.sendSyncAutomobileInputPacket(this, accelerating, braking, steeringLeft, steeringRight, holdingDrift);
        }
    }

    public void setInputs(boolean fwd, boolean back, boolean left, boolean right, boolean space) {
        this.prevHoldDrift = this.holdingDrift;
        this.accelerating = fwd;
        this.braking = back;
        this.steeringLeft = left;
        this.steeringRight = right;
        this.holdingDrift = space;
    }

    public void boost(float power, int time) {
        if (power > boostPower || time > boostTimer) {
            boostTimer = time;
            boostPower = power;
        }
    }

    private void steeringTick() {
        // Adjust the steering based on the left/right inputs
        this.lastSteering = steering;
        if (steeringLeft == steeringRight) {
            this.steering = AUtils.zero(this.steering, 0.42f);
        } else if (steeringLeft) {
            this.steering -= 0.42f;
            this.steering = Math.max(-1, this.steering);
        } else {
            this.steering += 0.42f;
            this.steering = Math.min(1, this.steering);
        }
    }

    private void driftingTick() {
        // Handles starting a drift
        if (steering != 0) {
            if (!drifting && !prevHoldDrift && holdingDrift && hSpeed > 0.4f && automobileOnGround) {
                drifting = true;
                driftDir = steering > 0 ? 1 : -1;
                // Reduce speed when a drift starts, based on how long the last drift was for
                // This allows you to do a series of short drifts without tanking all your speed, while still reducing your speed when you begin the drift(s)
                engineSpeed -= (0.028 * (Math.min(driftTimer, 20f) / 20)) * engineSpeed;
                driftTimer = 0;
            }
        }
        // Handles ending a drift and the drift timer (for drift turbos)
        if (drifting) {
            // Ending a drift successfully, giving you a turbo boost
            if (prevHoldDrift && !holdingDrift) {
                drifting = false;
                steering = 0;
                if (driftTimer > DRIFT_TURBO_TIME) {
                    boost(0.3f, 32);
                }
            // Ending a drift unsuccessfully, not giving you a boost
            } else if (hSpeed < 0.33f) {
                drifting = false;
                steering = 0;
            }
            if (automobileOnGround) driftTimer++;
        }
    }

    private static boolean inLockedViewMode() {
        return ControllerUtils.inControllerMode();
    }

    @Environment(EnvType.CLIENT)
    private void updateModels(EntityRendererFactory.Context ctx) {
        if (updateModels) {
            frameModel = frame.model().model().apply(ctx);
            wheelModel = wheels.model().model().apply(ctx);
            engineModel = engine.model().model().apply(ctx);
            updateModels = false;
        }
    }

    @Environment(EnvType.CLIENT)
    public Model getWheelModel(EntityRendererFactory.Context ctx) {
        updateModels(ctx);
        return wheelModel;
    }

    @Environment(EnvType.CLIENT)
    public Model getFrameModel(EntityRendererFactory.Context ctx) {
        updateModels(ctx);
        return frameModel;
    }

    @Environment(EnvType.CLIENT)
    public Model getEngineModel(EntityRendererFactory.Context ctx) {
        updateModels(ctx);
        return engineModel;
    }

    @Override
    public float getAutomobileYaw(float tickDelta) {
        return getYaw(tickDelta);
    }

    @Nullable
    @Override
    public Entity getPrimaryPassenger() {
        return getFirstPassenger();
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.getStackInHand(hand).isOf(AutomobilityItems.CROWBAR)) {
            var pos = getPos().add(0, 0.3, 0);
            if (!player.isCreative()) world.spawnEntity(new ItemEntity(world, pos.x, pos.y, pos.z, asItem()));
            this.remove(RemovalReason.KILLED);
            return ActionResult.success(world.isClient);
        }
        if (this.hasPassengers()) {
            if (!(this.getFirstPassenger() instanceof PlayerEntity)) {
                if (!world.isClient()) {
                    this.getFirstPassenger().stopRiding();
                }
                return ActionResult.success(world.isClient);
            }
            return ActionResult.PASS;
        }
        return ActionResult.success(player.startRiding(this));
    }

    @Override
    public double getMountedHeightOffset() {
        return ((wheels.model().radius() + frame.model().seatHeight() - 4) / 16) - (suspensionBounceTimer * 0.048f) + this.displacement.verticalTarget;
    }

    @Override
    public void updatePassengerPosition(Entity passenger) {
        super.updatePassengerPosition(passenger);
    }

    @Override
    public boolean collidesWith(Entity other) {
        return BoatEntity.canCollide(this, other);
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public boolean collides() {
        return !this.isRemoved();
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    protected void initDataTracker() {
    }

    @Override
    public Packet<?> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    public static final class Displacement {
        private float lastVertical = 0;
        private float lastAngularX = 0;
        private float lastAngularZ = 0;
        private float currAngularX = 0;
        private float currAngularZ = 0;
        private float verticalTarget = 0;
        private float angularXTarget = 0;
        private float angularZTarget = 0;
        private final List<Vec3d> scanPoints = new ArrayList<>();

        public void preTick() {
            this.lastAngularX = currAngularX;
            this.lastAngularZ = currAngularZ;
            this.lastVertical = verticalTarget;

            this.currAngularX = AUtils.shift(this.currAngularX, 9, this.angularXTarget);
            this.currAngularZ = AUtils.shift(this.currAngularZ, 9, this.angularZTarget);
        }

        public void tick(World world, AutomobileEntity entity, Vec3d centerPos, double yaw, double stepHeight) {
            yaw = 360 - yaw;
            Vec3d lowestDisplacementPos = null;
            Vec3d highestDisplacementPos = null;
            var scannedPoints = new ArrayList<Vec3d>();
            for (Vec3d scanPoint : scanPoints) {
                var downwardScanDist = (scanPoint.distanceTo(Vec3d.ZERO) * 1.4) + entity.getBoundingBox().getXLength();
                var upwardScanDist = (entity.getBoundingBox().getYLength() * 0.5) + stepHeight;
                var pointPos = scanPoint
                        .rotateY((float) Math.toRadians(yaw))
                        .add(centerPos);
                var collBoxes = new HashSet<VoxelShape>();

                int centerYBlock = (int) Math.floor(pointPos.y);
                for (var mpos = new BlockPos.Mutable(Math.floor(pointPos.x), centerYBlock + Math.ceil(upwardScanDist), Math.floor(pointPos.z));
                     mpos.getY() >= centerYBlock - Math.ceil(downwardScanDist); mpos.move(Direction.DOWN)) {
                    var shape = world.getBlockState(mpos).getCollisionShape(world, mpos);
                    if (!shape.isEmpty()) {
                        collBoxes.add(shape.offset(mpos.getX(), mpos.getY(), mpos.getZ()));
                    }
                }

                AtomicBoolean done = new AtomicBoolean(false);
                AtomicDouble yDisplacement = new AtomicDouble(0);
                for (double v = downwardScanDist; (v > -downwardScanDist && !done.get()); v -= 0.08) {
                    if (done.get()) break;
                    final double worldSpaceOffset = v + centerPos.y;
                    final Vec3d currentPointPos = pointPos;
                    collBoxes.forEach(shape -> {
                        if (done.get()) return;
                        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                            if (currentPointPos.x > minX && currentPointPos.x < maxX && currentPointPos.z > minZ && currentPointPos.z < maxZ) {
                                if (worldSpaceOffset <= maxY && worldSpaceOffset > minY) {;
                                    yDisplacement.set(maxY - centerPos.y);
                                    done.set(true);
                                }
                            }
                        });
                    });
                }

                pointPos = pointPos.add(0, yDisplacement.get(), 0);

                if (lowestDisplacementPos == null || pointPos.y < lowestDisplacementPos.y) {
                    lowestDisplacementPos = pointPos;
                }
                if (highestDisplacementPos == null || pointPos.y > highestDisplacementPos.y) {
                    highestDisplacementPos = pointPos;
                }

                scannedPoints.add(pointPos);
            }

            angularXTarget = 0;
            angularZTarget = 0;
            verticalTarget = 0;

            if (lowestDisplacementPos != null) {
                var displacementCenterPos = new Vec3d(centerPos.x, (lowestDisplacementPos.y + highestDisplacementPos.y) * 0.5, centerPos.z);

                var combinedNormals = Vec3d.ZERO;
                int normalCount = 0;
                Vec3d positiveXOffset = null;
                Vec3d negativeXOffset = null;
                Vec3d positiveZOffset = null;
                Vec3d negativeZOffset = null;

                for (var pointPos : scannedPoints) {
                    var pointOffset = pointPos.subtract(displacementCenterPos);
                    if (pointOffset.x > 0) {
                        if (positiveXOffset != null) {
                            var normal = positiveXOffset.crossProduct(pointOffset).normalize();
                            if (normal.y < 0) normal = normal.negate();
                            combinedNormals = combinedNormals.add(normal);
                            normalCount++;
                            positiveXOffset = null;
                        } else positiveXOffset = pointOffset;
                    } else if (pointOffset.x < 0) {
                        if (negativeXOffset != null) {
                            var normal = negativeXOffset.crossProduct(pointOffset).normalize();
                            if (normal.y < 0) normal = normal.negate();
                            combinedNormals = combinedNormals.add(normal);
                            normalCount++;
                            negativeXOffset = null;
                        } else negativeXOffset = pointOffset;
                    } else if (pointOffset.z > 0) {
                        if (positiveZOffset != null) {
                            var normal = positiveZOffset.crossProduct(pointOffset).normalize();
                            if (normal.y < 0) normal = normal.negate();
                            combinedNormals = combinedNormals.add(normal);
                            normalCount++;
                            positiveZOffset = null;
                        } else positiveZOffset = pointOffset;
                    } else if (pointOffset.z < 0) {
                        if (negativeZOffset != null) {
                            var normal = negativeZOffset.crossProduct(pointOffset).normalize();
                            if (normal.y < 0) normal = normal.negate();
                            combinedNormals = combinedNormals.add(normal);
                            normalCount++;
                            negativeZOffset = null;
                        } else negativeZOffset = pointOffset;
                    }
                }

                combinedNormals = normalCount > 0 ? combinedNormals.multiply(1f / normalCount) : new Vec3d(0, 1, 0);

                angularXTarget = MathHelper.wrapDegrees(90f - (float) Math.toDegrees(Math.atan2(combinedNormals.y, combinedNormals.z)));
                angularZTarget = MathHelper.wrapDegrees(270f + (float) Math.toDegrees(Math.atan2(combinedNormals.y, combinedNormals.x)));

                verticalTarget = (float) displacementCenterPos.subtract(centerPos).y;
            }
        }

        public void applyWheelbase(WheelBase wheelBase) {
            this.scanPoints.clear();
            for (WheelBase.WheelPos pos : wheelBase.wheels) {
                this.scanPoints.add(new Vec3d(pos.right() / 16, 0, pos.forward() / 16));
            }
        }

        public float getVertical(float tickDelta) {
            return MathHelper.lerp(tickDelta, lastVertical, verticalTarget);
        }

        public float getAngularX(float tickDelta) {
            return MathHelper.lerpAngleDegrees(tickDelta, lastAngularX, currAngularX);
        }

        public float getAngularZ(float tickDelta) {
            return MathHelper.lerpAngleDegrees(tickDelta, lastAngularZ, currAngularZ);
        }

        public NbtCompound toNbt(NbtCompound writeTo) {
            writeTo.putFloat("vertical", verticalTarget);
            writeTo.putFloat("angular_x", angularXTarget);
            writeTo.putFloat("angular_z", angularZTarget);
            return writeTo;
        }

        public void fromNbt(NbtCompound readFrom) {
            verticalTarget = readFrom.getFloat("vertical");
            angularXTarget = readFrom.getFloat("angular_x");
            angularZTarget = readFrom.getFloat("angular_z");
        }

        public void toBuffer(PacketByteBuf buf) {
            buf.writeFloat(verticalTarget);
            buf.writeFloat(currAngularX);
            buf.writeFloat(currAngularZ);
        }

        public void fromBuffer(PacketByteBuf buf) {
            verticalTarget = buf.readFloat();
            currAngularX = buf.readFloat();
            currAngularZ = buf.readFloat();
        }
    }
}
