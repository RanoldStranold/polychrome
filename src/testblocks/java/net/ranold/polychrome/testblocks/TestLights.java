package net.ranold.polychrome.testblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;

// dev only, never shipped. level 15 rainbow blocks + white in their own tab, colored by our own light_colors file like a pack would
public class TestLights implements ModInitializer {

    public static final String MOD_ID = "polychrome-testblocks";
    private static final String[] COLORS = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta", "white"};

    @Override
    public void onInitialize() {
        List<Item> items = new ArrayList<>();
        for (String color : COLORS) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, color + "_test_light");
            Block block = new Block(BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, id))
                    .strength(0.3f)
                    .sound(SoundType.GLASS)
                    .lightLevel(state -> 15));
            Registry.register(BuiltInRegistries.BLOCK, id, block);
            items.add(Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, id))
                    .useBlockDescriptionPrefix())));
        }
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(MOD_ID, "test_lights"),
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup." + MOD_ID))
                        .icon(() -> new ItemStack(items.getFirst()))
                        .displayItems((parameters, output) -> items.forEach(output::accept))
                        .build());
    }
}
