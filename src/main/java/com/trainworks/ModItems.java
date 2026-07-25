package com.trainworks;

import com.trainworks.track.TrackHammerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for all items (including BlockItems for anything in ModBlocks).
 */
public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TrainworksMod.MOD_ID);

    public static final RegistryObject<Item> TRACK_ANCHOR = ITEMS.register("track_anchor",
            () -> new BlockItem(ModBlocks.TRACK_ANCHOR.get(), new Item.Properties()));

    public static final RegistryObject<Item> TRACK_HAMMER = ITEMS.register("track_hammer",
            () -> new TrackHammerItem(new Item.Properties().stacksTo(1)));
}
