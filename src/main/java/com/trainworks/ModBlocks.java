package com.trainworks;

import com.trainworks.track.TrackAnchorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for all blocks. Track segment blocks (design/track-graph.md §4)
 * land in a later pass once the anchor/linking-tool flow is proven out.
 */
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TrainworksMod.MOD_ID);

    public static final RegistryObject<Block> TRACK_ANCHOR = BLOCKS.register("track_anchor",
            () -> new TrackAnchorBlock(BlockBehaviour.Properties.of()
                    .strength(3.0f, 6.0f)
                    .sound(SoundType.METAL)));
}
