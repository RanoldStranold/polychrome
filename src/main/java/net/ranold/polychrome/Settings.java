package net.ranold.polychrome;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// config/polychrome.properties. sodium's general page and the mod menu screen both go through here so
// they stay in sync
public final class Settings {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("polychrome.properties");

    private Settings() {
    }

    static boolean loadEnabled() {
        if (!Files.exists(FILE)) {
            return true;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            props.load(reader);
        } catch (IOException e) {
            Polychrome.LOGGER.warn("Could not read {}: {}", FILE, e.getMessage());
        }
        return !"false".equals(props.getProperty("enabled"));
    }

    public static boolean enabled() {
        return Polychrome.tintEnabled();
    }

    public static void setEnabled(boolean enabled) {
        Polychrome.setTintEnabled(enabled);
        Properties props = new Properties();
        props.setProperty("enabled", Boolean.toString(enabled));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                props.store(writer, "Polychrome");
            }
        } catch (IOException e) {
            Polychrome.LOGGER.warn("Could not save {}: {}", FILE, e.getMessage());
        }
    }
}
