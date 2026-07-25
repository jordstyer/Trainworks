package com.trainworks;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Entry point. Registry population happens here; behavior lives in the
 * track / train / automation packages per design/*.md.
 */
@Mod(TrainworksMod.MOD_ID)
public class TrainworksMod {
    public static final String MOD_ID = "trainworks";

    public TrainworksMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
    }
}
