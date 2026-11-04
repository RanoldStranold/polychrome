package net.ranold.polychrome.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;

// diagnostic, not listed. our on/off made it into sodium's options (the mixin is require = 0, so it can fail silently)
public class SodiumOptionGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        String found = context.computeOnClient(client -> {
            try {
                Object config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
                Object option = config.getClass().getMethod("getOption", Identifier.class)
                        .invoke(config, Identifier.fromNamespaceAndPath("polychrome", "general.enabled"));
                return String.valueOf(option);
            } catch (ReflectiveOperationException e) {
                return "error " + e;
            }
        });
        System.out.printf("[polychrome-test] sodium option: %s%n", found);
        if (found.equals("null") || found.startsWith("error")) {
            throw new AssertionError("the Polychrome option is not in Sodium's options: " + found);
        }
    }
}
