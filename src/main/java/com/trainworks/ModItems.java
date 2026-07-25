package com.trainworks;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Registry for all items (including BlockItems for anything in ModBlocks).
 * First entries land in Phase 1 (track anchor item, linking tool) per
 * design/track-graph.md — empty until then.
 */
public class ModItems {
    public static final DeferredRegister<net.minecraft.world.item.Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TrainworksMod.MOD_ID);
}
