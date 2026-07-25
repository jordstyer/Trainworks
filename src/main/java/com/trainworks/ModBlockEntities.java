package com.trainworks;

import com.trainworks.track.TrackAnchorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TrainworksMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<TrackAnchorBlockEntity>> TRACK_ANCHOR =
            BLOCK_ENTITIES.register("track_anchor", () -> BlockEntityType.Builder.of(
                    TrackAnchorBlockEntity::new, ModBlocks.TRACK_ANCHOR.get()).build(null));
}
