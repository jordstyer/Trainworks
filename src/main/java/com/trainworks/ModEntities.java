package com.trainworks;

import com.trainworks.train.CarriageEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, TrainworksMod.MOD_ID);

    public static final RegistryObject<EntityType<CarriageEntity>> CARRIAGE = ENTITY_TYPES.register("carriage",
            () -> EntityType.Builder.<CarriageEntity>of(CarriageEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .build("carriage"));
}
