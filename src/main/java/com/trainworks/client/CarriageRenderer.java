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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a {@link CarriageEntity}'s captured blocks each at their stored
 * relative offset, using the same "just render this BlockState with the
 * current PoseStack transform" call vanilla item/GUI block rendering uses
 * ({@code BlockRenderDispatcher.renderSingleBlock}) -- not full in-world
 * ambient occlusion/tesselation (design/trains.md §6), but simple and
 * reliable for a first pass.
 *
 * <p>The whole carriage is rotated once, by the entity's own yaw (set at
 * assembly time from the track's direction at the bogie -- design/
 * trains.md §3.3), before any per-block translation -- this rotates each
 * block's own facing along with its position, not just its position. The
 * rotation angle is applied directly as {@code entityYaw} with no sign
 * flip: this matches the same yaw convention already validated in-game via
 * the track anchor's facing indicator (blockstate "y" rotation), which
 * shares the same underlying rotation system as {@code Axis.YP}.</p>
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
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entityYaw));

        for (CarriageEntity.CapturedBlock block : entity.capturedBlocks()) {
            BlockPos relative = block.relativeOffset();
            poseStack.pushPose();
            poseStack.translate(relative.getX(), relative.getY(), relative.getZ());
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
