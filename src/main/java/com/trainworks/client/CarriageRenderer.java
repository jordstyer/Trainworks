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
 * <p><strong>No rotation is applied here</strong> -- a conceptual correction
 * after testing showed structures rotating away from correct alignment, not
 * toward it. The captured relative offsets were extracted directly from the
 * world, where the player necessarily built <em>already aligned with the
 * physical track</em> (that's what "coincident with the track" means) --
 * so they're already in the correct final orientation. Rotating them again
 * by the track's absolute yaw double-counts that alignment. Rotation would
 * only become meaningful once a carriage can move to a point on the curve
 * with a different heading than where it was assembled -- and even then,
 * what's needed is the *delta* between assembly-time yaw and current yaw,
 * not the raw absolute angle applied here before. No movement exists yet,
 * so for this static-only phase the correct answer is no rotation at all.
 *
 * <p>The entity's own yaw (set at assembly time from the track's direction
 * at the bogie -- design/trains.md §3.3) is still stored on the entity for
 * that future delta-based use; it's just not consulted for rendering yet.</p>
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
