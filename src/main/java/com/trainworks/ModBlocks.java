package com.trainworks;

import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Registry for all blocks. First entries land in Phase 1 (track anchor, track
 * segment) per design/track-graph.md — empty until then.
 */
public class ModBlocks {
    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TrainworksMod.MOD_ID);
}
