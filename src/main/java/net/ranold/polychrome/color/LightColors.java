package net.ranold.polychrome.color;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.ranold.polychrome.Polychrome;
import net.ranold.polychrome.field.HueField;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Function;

// block state -> light color, from assets/<ns>/polychrome/light_colors/*.json.
// keys: block id, state id[prop=value], or #tag. most specific wins (state > block > tag).
// later packs win key by key, replace drops what came before, sets resolve after all packs merge
public final class LightColors {

    public static final String DIRECTORY = "polychrome/light_colors";
    public static final int WHITE = 0xFFFFFF;

    // rule before resolving against the block registry
    private record Rule(String key, int rgb) {
    }

    // one dimension's rules, sets and textures already worked out. tint -1 = none
    public record Palette(List<Rule> rules, List<Rule> items, List<Rule> entities, int tint, double tintAmount) {
    }

    public static final double DEFAULT_TINT_AMOUNT = 0.35;

    // what a reload read. dimensions only has the ones a pack wrote something for, the rest use base
    public record Loaded(Palette base, Map<String, Palette> dimensions, int sets, int textures) {
    }

    // for warnings
    private record Written(ColorSpec spec, String where) {
    }

    // what the files say before sets are resolved: the base, or one dimension's section
    private static final class Sections {
        final Map<String, Written> colors = new HashMap<>(), items = new HashMap<>(), entities = new HashMap<>(),
                sets = new HashMap<>();
        // mixed into every color, e.g. a purple end
        @Nullable Written tint;
        double tintAmount = DEFAULT_TINT_AMOUNT;

        void read(JsonObject root, String where) {
            readSection(root, "sets", where, sets);
            readSection(root, "colors", where, colors);
            readSection(root, "items", where, items);
            readSection(root, "entities", where, entities);
            try {
                if (root.has("tint")) {
                    tint = new Written(ColorSpec.parse(root.get("tint")), where);
                }
                if (root.has("tint_amount")) {
                    tintAmount = Math.clamp(root.get("tint_amount").getAsDouble(), 0, 1);
                }
            } catch (ColorSpec.BadColor | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
                Polychrome.LOGGER.warn("Bad light tint in {}: {}", where, e.getMessage());
            }
        }

        // this dimension's keys on top of the base's
        Sections over(Sections base) {
            Sections merged = new Sections();
            List<Map<String, Written>> from = List.of(base.colors, base.items, base.entities, base.sets);
            List<Map<String, Written>> mine = List.of(colors, items, entities, sets);
            List<Map<String, Written>> into = List.of(merged.colors, merged.items, merged.entities, merged.sets);
            for (int i = 0; i < 4; i++) {
                into.get(i).putAll(from.get(i));
                into.get(i).putAll(mine.get(i));
            }
            merged.tint = tint != null ? tint : base.tint;
            merged.tintAmount = tint != null ? tintAmount : base.tintAmount;
            return merged;
        }
    }

    private static volatile Palette base = new Palette(List.of(), List.of(), List.of(), -1, 0);
    // color for lights with none written, white unless the dimension has a tint
    private static volatile int white = WHITE;
    private static volatile Map<String, Palette> dimensions = Map.of();
    // dimension the tables below were resolved for
    private static volatile @Nullable String resolvedFor;

    // per state id: HueField.source() packing, 0 if it doesnt emit
    private static volatile int @Nullable [] sourceByState;
    // per state id: color whether it emits or not (white if none). for held / dropped blocks
    private static volatile int @Nullable [] colorByState;
    // dynamic lights
    private static volatile Map<Item, Integer> colorByItem = Map.of();
    private static volatile Map<EntityType<?>, Integer> colorByEntity = Map.of();

    private LightColors() {
    }


    // reads every file and works out sets/textures/references. off thread during reload prep, no registry access
    public static Loaded read(ResourceManager manager) {
        // lowest pack first so higher packs win. inside one pack by file id so order is stable
        List<String> packOrder = manager.listPacks().map(PackResources::packId).toList();
        List<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>();
        manager.listResourceStacks(DIRECTORY, id -> id.getPath().endsWith(".json"))
                .forEach((id, stack) -> stack.forEach(resource -> ordered.add(Map.entry(id, resource))));
        ordered.sort(Comparator.<Map.Entry<Identifier, Resource>>comparingInt(e -> packOrder.indexOf(e.getValue().sourcePackId()))
                .thenComparing(e -> e.getKey().getNamespace())
                .thenComparing(e -> e.getKey().getPath()));
        Sections base = new Sections();
        Map<String, Sections> dimensions = new HashMap<>();
        for (Map.Entry<Identifier, Resource> file : ordered) {
            Resource resource = file.getValue();
            String where = file.getKey() + " (" + resource.sourcePackId() + ")";
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("replace") && root.get("replace").getAsBoolean()) {
                    base = new Sections();
                    dimensions.clear();
                }
                base.read(root, where);
                if (root.has("dimensions")) {
                    for (Map.Entry<String, JsonElement> dim : root.getAsJsonObject("dimensions").entrySet()) {
                        String id = Identifier.parse(dim.getKey()).toString();
                        dimensions.computeIfAbsent(id, k -> new Sections()).read(dim.getValue().getAsJsonObject(), where + " " + id);
                    }
                }
            } catch (Exception e) {
                Polychrome.LOGGER.error("Could not read light colors {}", where, e);
            }
        }

        Map<String, Integer> textures = new HashMap<>();
        Function<ColorSpec.Texture, Integer> texture = tex -> textures.computeIfAbsent(
                tex.id() + (tex.region() == null ? "" : java.util.Arrays.toString(tex.region())), k -> readTexture(manager, tex));
        // a dimension repeats the base's keys, so the same problem would be logged once per dimension
        java.util.Set<String> warned = new HashSet<>();
        Map<String, Palette> palettes = new HashMap<>();
        for (Map.Entry<String, Sections> dim : dimensions.entrySet()) {
            palettes.put(dim.getKey(), palette(dim.getValue().over(base), texture, warned));
        }
        return new Loaded(palette(base, texture, warned), Map.copyOf(palettes), base.sets.size(), textures.size());
    }

    private static Palette palette(Sections sections, Function<ColorSpec.Texture, Integer> texture, java.util.Set<String> warned) {
        Map<String, ColorSpec> setSpecs = new HashMap<>();
        sections.sets.forEach((name, written) -> setSpecs.put(name, written.spec()));
        Map<String, String> problems = new HashMap<>();
        ColorSpec.resolveSets(setSpecs, texture, problems);
        problems.forEach((name, problem) -> warn(warned, "Light color set '{}' in {}: {}", name, sections.sets.get(name).where(), problem));
        int tint = -1;
        if (sections.tint != null) {
            try {
                tint = ColorSpec.resolve(sections.tint.spec(), setSpecs, texture, new HashSet<>());
            } catch (ColorSpec.BadColor e) {
                warn(warned, "Light tint in {}: {}", sections.tint.where(), e.getMessage());
            }
        }
        return new Palette(resolveAll(sections.colors, setSpecs, texture, warned), resolveAll(sections.items, setSpecs, texture, warned),
                resolveAll(sections.entities, setSpecs, texture, warned), tint, sections.tintAmount);
    }

    // rgb moved toward tint by amount, brightest channel back to 255 (only hue counts)
    public static int mix(int rgb, int tint, double amount) {
        double r = ((rgb >> 16) & 255) * (1 - amount) + ((tint >> 16) & 255) * amount;
        double g = ((rgb >> 8) & 255) * (1 - amount) + ((tint >> 8) & 255) * amount;
        double b = (rgb & 255) * (1 - amount) + (tint & 255) * amount;
        double max = Math.max(r, Math.max(g, b));
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round(r * 255 / max) << 16 | (int) Math.round(g * 255 / max) << 8 | (int) Math.round(b * 255 / max);
    }

    private static void warn(java.util.Set<String> warned, String message, Object... args) {
        if (warned.add(message + java.util.Arrays.toString(args))) {
            Polychrome.LOGGER.warn(message, args);
        }
    }

    private static List<Rule> resolveAll(Map<String, Written> written, Map<String, ColorSpec> sets,
            Function<ColorSpec.Texture, Integer> texture, java.util.Set<String> warned) {
        List<Rule> loaded = new ArrayList<>();
        written.forEach((key, w) -> {
            try {
                loaded.add(new Rule(key, ColorSpec.resolve(w.spec(), sets, texture, new HashSet<>())));
            } catch (ColorSpec.BadColor e) {
                warn(warned, "Light color for '{}' in {}: {}", key, w.where(), e.getMessage());
            }
        });
        return List.copyOf(loaded);
    }

    private static void readSection(JsonObject root, String name, String where, Map<String, Written> into) {
        if (!root.has(name)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(name).entrySet()) {
            try {
                into.put(entry.getKey(), new Written(ColorSpec.parse(entry.getValue()), where));
            } catch (ColorSpec.BadColor | IllegalStateException | UnsupportedOperationException e) {
                Polychrome.LOGGER.warn("Bad light color '{}' in {}: {}", entry.getKey(), where, e.getMessage());
            }
        }
    }

    // "ns:block/torch" = assets/ns/textures/block/torch.png, full textures/...png path works too
    private static int readTexture(ResourceManager manager, ColorSpec.Texture tex) {
        Identifier id;
        try {
            id = Identifier.parse(tex.id());
        } catch (RuntimeException e) {
            throw new ColorSpec.BadColor("'" + tex.id() + "' is not a texture id");
        }
        String path = id.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path += ".png";
        }
        Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), path);
        Resource resource = manager.getResource(file).orElseThrow(() -> new ColorSpec.BadColor("no texture " + file));
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth(), height = image.getHeight();
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    argb[y * width + x] = image.getPixel(x, y);
                }
            }
            return ColorSpec.averageColor(argb, width, height, tex.region());
        } catch (IOException e) {
            throw new ColorSpec.BadColor("could not read texture " + file + ": " + e.getMessage());
        }
    }

    // main thread. call resolve() after while in a world
    public static void install(Loaded loaded) {
        base = loaded.base();
        dimensions = loaded.dimensions();
        sourceByState = null;
        resolvedFor = null;
        Polychrome.LOGGER.info("Loaded {} light colors, {} for items, {} for entities ({} sets, {} textures, {} dimensions)",
                base.rules().size(), base.items().size(), base.entities().size(), loaded.sets(), loaded.textures(),
                dimensions.size());
    }


    // needs block tags so only once in a world (tags come from the server), after reloads in a world,
    // and on a dimension change
    public static void resolve(String dimension) {
        Palette palette = dimensions.getOrDefault(dimension, base);
        List<Rule> rules = palette.rules(), itemRules = palette.items(), entityRules = palette.entities();
        int stateCount = Block.BLOCK_STATE_REGISTRY.size();
        int[] rgbByState = new int[stateCount];
        int[] specificity = new int[stateCount];
        java.util.Arrays.fill(rgbByState, WHITE);

        for (Rule rule : rules) {
            try {
                apply(rule, rgbByState, specificity);
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping light color '{}': {}", rule.key(), e.getMessage());
            }
        }
        java.util.function.IntUnaryOperator tinted = palette.tint() < 0 ? rgb -> rgb
                : rgb -> mix(rgb, palette.tint(), palette.tintAmount());
        for (int id = 0; id < stateCount; id++) {
            rgbByState[id] = tinted.applyAsInt(rgbByState[id]);
        }
        white = tinted.applyAsInt(WHITE);

        int[] sources = new int[stateCount];
        for (int id = 0; id < stateCount; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state != null) {
                sources[id] = HueField.source(state.getLightEmission(), rgbByState[id]);
            }
        }
        colorByState = rgbByState;
        sourceByState = sources;

        Map<Item, Integer> byItem = new HashMap<>();
        for (Rule rule : itemRules) {
            try {
                forEachNamed(BuiltInRegistries.ITEM, Registries.ITEM, rule.key(), item -> byItem.put(item, tinted.applyAsInt(rule.rgb())));
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping item light color '{}': {}", rule.key(), e.getMessage());
            }
        }
        colorByItem = byItem;
        Map<EntityType<?>, Integer> byEntity = new HashMap<>();
        for (Rule rule : entityRules) {
            try {
                forEachNamed(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, rule.key(), type -> byEntity.put(type, tinted.applyAsInt(rule.rgb())));
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping entity light color '{}': {}", rule.key(), e.getMessage());
            }
        }
        colorByEntity = byEntity;
        resolvedFor = dimension;
    }

    // one id, or every entry in #tag
    private static <T> void forEachNamed(Registry<T> registry, ResourceKey<? extends Registry<T>> registryKey, String key,
            Consumer<T> action) {
        if (key.startsWith("#")) {
            for (Holder<T> holder : registry.getTagOrEmpty(TagKey.create(registryKey, Identifier.parse(key.substring(1))))) {
                action.accept(holder.value());
            }
            return;
        }
        action.accept(registry.getOptional(Identifier.parse(key)).orElseThrow(() -> new IllegalArgumentException("unknown id " + key)));
    }

    private static void apply(Rule rule, int[] rgbByState, int[] specificity) {
        String key = rule.key();
        if (key.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(key.substring(1)));
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                for (BlockState state : holder.value().getStateDefinition().getPossibleStates()) {
                    set(state, rule.rgb(), 1, rgbByState, specificity);
                }
            }
            return;
        }
        int bracket = key.indexOf('[');
        String blockId = bracket < 0 ? key : key.substring(0, bracket);
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId))
                .orElseThrow(() -> new IllegalArgumentException("unknown block " + blockId));
        Map<String, String> wanted = bracket < 0 ? Map.of() : parseProperties(key.substring(bracket + 1, key.length() - 1));
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (matches(state, wanted)) {
                set(state, rule.rgb(), wanted.isEmpty() ? 2 : 3, rgbByState, specificity);
            }
        }
    }

    private static void set(BlockState state, int rgb, int level, int[] rgbByState, int[] specificity) {
        int id = Block.getId(state);
        if (level >= specificity[id]) {
            specificity[id] = level;
            rgbByState[id] = rgb;
        }
    }

    private static Map<String, String> parseProperties(String text) {
        Map<String, String> result = new HashMap<>();
        for (String pair : text.split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                result.put(parts[0].trim(), parts[1].trim());
            }
        }
        return result;
    }

    private static boolean matches(BlockState state, Map<String, String> wanted) {
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !entry.getValue().equals(valueName(state, property))) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }


    public static boolean isResolvedFor(String dimension) {
        return sourceByState != null && dimension.equals(resolvedFor);
    }

    // to tell if a reload changed anything
    public static int @Nullable [] table() {
        return sourceByState;
    }

    // white (or the dimension's tinted white) if none written
    public static int colorOf(BlockState state) {
        int[] table = colorByState;
        int id = Block.getId(state);
        return table != null && id >= 0 && id < table.length ? table[id] : white;
    }

    public static int white() {
        return white;
    }

    // written color, else its block's (held torch = placed torch), else white
    public static int colorOf(ItemStack stack) {
        Integer written = colorByItem.get(stack.getItem());
        if (written != null) {
            return written;
        }
        return stack.getItem() instanceof BlockItem block ? colorOf(block.getBlock().defaultBlockState()) : white;
    }

    // -1 if none
    public static int colorOf(EntityType<?> type) {
        Integer written = colorByEntity.get(type);
        return written == null ? -1 : written;
    }

    // HueField.source() packing or 0. any thread
    public static int sourceOf(BlockState state) {
        int[] table = sourceByState;
        if (table == null) {
            return 0;
        }
        int id = Block.getId(state);
        return id >= 0 && id < table.length ? table[id] : 0;
    }
}
