package com.trainworks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainworks.track.TrackSegmentBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws a {@link TrackSegmentBlockEntity}'s stored local polyline as a flat
 * textured ribbon instead of a static cube model (see
 * {@code TrackSegmentBlock.getRenderShape} -- it reports INVISIBLE so this is
 * the only thing that ever draws the block). This is what makes a whole edge
 * read as one continuous curve rather than a staircase of discrete blocks.
 *
 * <p>Quads are emitted in both vertex-winding orders. Without a running
 * client to visually confirm which winding this render type expects front-
 * facing, doubling the geometry is a deliberate, cheap way to guarantee the
 * ribbon is visible from any angle rather than risk it being invisible from
 * "the wrong side" due to backface culling.</p>
 */
public class TrackSegmentRenderer implements BlockEntityRenderer<TrackSegmentBlockEntity> {
    private static final ResourceLocation TOP_TEXTURE = new ResourceLocation("trainworks", "block/track_segment_top");
    private static final float HALF_WIDTH = 0.5f;

    public TrackSegmentRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(TrackSegmentBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        List<Vec3> points = blockEntity.localPoints();
        if (points.size() < 2) {
            return;
        }

        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(TOP_TEXTURE);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.cutout());
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalMat = poseStack.last().normal();

        float u0 = sprite.getU(0);
        float u1 = sprite.getU(16);
        double cumulative = 0;

        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 p0 = points.get(i);
            Vec3 p1 = points.get(i + 1);
            double segLength = p0.distanceTo(p1);

            double hx = p1.x - p0.x;
            double hz = p1.z - p0.z;
            double horizLen = Math.sqrt(hx * hx + hz * hz);
            if (horizLen < 1.0e-6) {
                cumulative += segLength;
                continue;
            }

            double nx = -hz / horizLen * HALF_WIDTH;
            double nz = hx / horizLen * HALF_WIDTH;
            Vec3 perp = new Vec3(nx, 0, nz);

            Vec3 a = p0.add(perp);
            Vec3 b = p0.subtract(perp);
            Vec3 c = p1.subtract(perp);
            Vec3 d = p1.add(perp);

            float v0 = sprite.getV(wrap16(cumulative));
            float v1 = sprite.getV(wrap16(cumulative + segLength));

            quad(consumer, pose, normalMat, a, b, c, d, u0, v0, u1, v1, packedLight);

            cumulative += segLength;
        }
    }

    private static float wrap16(double distanceInBlocks) {
        float pixels = (float) ((distanceInBlocks * 16.0) % 16.0);
        return pixels < 0 ? pixels + 16f : pixels;
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose, Matrix3f normalMat,
                              Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                              float u0, float v0, float u1, float v1, int light) {
        Vec3 normal = b.subtract(a).cross(d.subtract(a)).normalize();

        vertex(consumer, pose, normalMat, a, u0, v0, normal, light);
        vertex(consumer, pose, normalMat, b, u1, v0, normal, light);
        vertex(consumer, pose, normalMat, c, u1, v1, normal, light);
        vertex(consumer, pose, normalMat, d, u0, v1, normal, light);

        vertex(consumer, pose, normalMat, d, u0, v1, normal, light);
        vertex(consumer, pose, normalMat, c, u1, v1, normal, light);
        vertex(consumer, pose, normalMat, b, u1, v0, normal, light);
        vertex(consumer, pose, normalMat, a, u0, v0, normal, light);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normalMat,
                                Vec3 pos, float u, float v, Vec3 normal, int light) {
        consumer.vertex(pose, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normalMat, (float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
    }
}
