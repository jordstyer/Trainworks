package com.trainworks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.trainworks.train.CarriageEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a {@link CarriageEntity}'s captured blocks each at their stored
 * relative offset, using the same "just render this BlockState with the
 * current PoseStack transform" call vanilla item/GUI block rendering uses
 * ({@code BlockRenderDispatcher.renderSingleBlock}) -- not full in-world
 * ambient occlusion/tesselation (design/trains.md §6), but simple and
 * reliable for a first pass.
 *
 * <p>Rotation is the <em>delta</em> between the entity's current track yaw
 * and its assembly-time yaw ({@code entity.trackYaw() - entity.assemblyYaw()}),
 * not the raw current angle. Captured offsets come straight from the world,
 * where the player necessarily built already aligned with the physical
 * track -- so at the exact moment of assembly (current yaw == assembly
 * yaw) the delta is zero and nothing rotates, matching how it must render
 * when stationary. Applying the raw current yaw instead double-counts that
 * alignment (confirmed by an earlier in-game test: it rotated a structure
 * built in line with straight track to perpendicular). As the carriage
 * moves to points on the curve with a different heading than where it was
 * built, the delta grows and the render correctly follows along.</p>
 *
 * <p>{@code entity.trackYaw()} (a {@code SynchedEntityData} field), not the
 * {@code entityYaw} render parameter (the entity's generic, network-
 * threshold-gated rotation) -- see {@code CarriageEntity}'s class doc for
 * why the generic one wasn't reliable for our very gradual per-tick
 * rotation.</p>
 *
 * <p>The angle is still negated ({@code -delta}), same handedness reasoning
 * as before: Minecraft yaw increases clockwise viewed from above (matches
 * {@code Direction.fromYRot}), but {@code Axis.YP.rotationDegrees} is a
 * standard right-handed (counter-clockwise) rotation -- opposite senses.</p>
 */
public class CarriageRenderer extends EntityRenderer<CarriageEntity> {
    private final BlockRenderDispatcher blockRenderDispatcher;

    public CarriageRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderDispatcher = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(CarriageEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight) {
        float deltaYaw = entity.trackYaw() - entity.assemblyYaw();

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-deltaYaw));

        for (CarriageEntity.CapturedBlock block : entity.capturedBlocks()) {
            Vec3 relative = block.relativeOffset();
            poseStack.pushPose();
            poseStack.translate(relative.x, relative.y, relative.z);
            blockRenderDispatcher.renderSingleBlock(block.state(), poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarriageEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
