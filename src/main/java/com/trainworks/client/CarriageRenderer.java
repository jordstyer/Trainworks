package com.trainworks.client;

import com.mojang.blaze3d.vertex.PoseStack;
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
 * <p><strong>No rotation is applied, even while moving.</strong> A
 * delta-yaw rotation ({@code trackYaw() - assemblyYaw()}, both synced
 * separately -- one via the spawn packet, one via {@code SynchedEntityData})
 * was tried for the unmanned movement proof and produced a real bug: a
 * carriage confirmed (via its debug hitbox, sitting correctly on the track)
 * to be in the right *position* rendered its visual blocks wildly displaced
 * -- almost certainly a synchronization-timing mismatch between those two
 * separately-synced yaw values, not a position/movement problem. Rather
 * than patch that interaction blind, rotation-while-moving is deliberately
 * out of scope here and deferred to when the real driving feature (control
 * stand + throttle) is built, where it can be designed more carefully.
 * Position-only movement (see {@code CarriageEntity.tick()}) is unaffected
 * and confirmed working -- the carriage will just keep its assembly-time
 * visual orientation as it slides along the track for now.</p>
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
        for (CarriageEntity.CapturedBlock block : entity.capturedBlocks()) {
            Vec3 relative = block.relativeOffset();
            poseStack.pushPose();
            poseStack.translate(relative.x, relative.y, relative.z);
            blockRenderDispatcher.renderSingleBlock(block.state(), poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarriageEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
