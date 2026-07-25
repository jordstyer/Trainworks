package com.trainworks;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mod-bus event handlers (setup-time). Adds Trainworks items to a vanilla
 * creative tab until the mod has enough content to warrant its own.
 */
@Mod.EventBusSubscriber(modid = TrainworksMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TrainworksModBusEvents {

    private TrainworksModBusEvents() {
    }

    @SubscribeEvent
    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.TRACK_ANCHOR.get());
            event.accept(ModItems.TRACK_HAMMER.get());
            event.accept(ModItems.TRAIN_BOGIE.get());
        }
    }
}
