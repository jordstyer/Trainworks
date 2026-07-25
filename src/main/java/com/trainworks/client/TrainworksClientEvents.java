package com.trainworks.client;

import com.trainworks.ModBlockEntities;
import com.trainworks.ModEntities;
import com.trainworks.TrainworksMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only setup: registers entity and block entity renderers. */
@Mod.EventBusSubscriber(modid = TrainworksMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TrainworksClientEvents {

    private TrainworksClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.TRACK_SEGMENT.get(), TrackSegmentRenderer::new);
        event.registerEntityRenderer(ModEntities.CARRIAGE.get(), CarriageRenderer::new);
    }
}
