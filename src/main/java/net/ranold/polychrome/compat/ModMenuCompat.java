package net.ranold.polychrome.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.ranold.polychrome.Settings;

// mod menu config button, the fallback toggle when sodium isnt there. only loaded by mod menu
public final class ModMenuCompat implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }

    private static final class SettingsScreen extends Screen {

        private final Screen parent;

        SettingsScreen(Screen parent) {
            super(Component.translatable("polychrome.options.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            addRenderableWidget(CycleButton.onOffBuilder(Settings.enabled())
                    .create(width / 2 - 100, height / 2 - 24, 200, 20, Component.translatable("polychrome.options.enabled"),
                            (button, value) -> Settings.setEnabled(value)));
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(width / 2 - 100, height / 2 + 8, 200, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            graphics.centeredText(font, title, width / 2, height / 2 - 50, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            minecraft.gui.setScreen(parent);
        }
    }
}
