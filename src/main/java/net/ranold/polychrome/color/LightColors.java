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
// keys: block id, state id[prop=value], or #tag. a higher pack always wins; inside one pack a dimension's
// keys beat the base's, then the most specific key wins (state > block > tag). an entry that fails to
// resolve falls back to the pack below. replace drops what came before, sets resolve after all packs merge.
// "rules": a light's color by its neighbors, first match wins (higher packs first), plain colors otherwise
public final class LightColors {

    public static final String DIRECTORY = "polychrome/light_colors";
    public static final int WHITE = 0xFFFFFF;

    // rule before resolving against the block registry
    // rank: pack position, see Written
    private record Rule(String key, int rgb, int rank) {
    }

    // one dimension's rules, sets and textures already worked out. tint -1 = none
    public record Palette(List<Rule> rules, List<Rule> items, List<Rule> entities, List<NeighborRule> neighborRules,
                          int tint, double tintAmount) {
    }

    // a "rules" entry before resolving against the block registry. sides: above, below, north, south, east, west,
    // touching -> keys any of which the neighbor there has to match
    public record NeighborRule(List<String> match, Map<String, List<String>> sides, int rgb, String where) {
    }

    private record WrittenNeighborRule(JsonObject json, String where, int rank) {
    }

    // resolved: states by id. sides indexed by Direction.ordinal(), null = no condition there
    private record CompiledRule(java.util.BitSet match, java.util.BitSet @Nullable [] sides, java.util.@Nullable BitSet touching, int rgb) {
    }

    private record RuleTable(List<CompiledRule> rules, java.util.BitSet ruled) {
    }

    // neighbor of a light, null if not known (not loaded, outside what the caller has)
    public interface Neighbors {
        @Nullable BlockState at(net.minecraft.core.Direction side);
    }

    private static final List<String> SIDES = List.of("above", "below", "north", "south", "east", "west", "touching");

    public static final double DEFAULT_TINT_AMOUNT = 0.35;

    // what a reload read. dimensions only has the ones a pack wrote something for, the rest use base
    public record Loaded(Palette base, Map<String, Palette> dimensions, int sets, int textures, Map<Identifier, Integer> auto) {
    }

    // rank: 2 per pack position (higher pack = higher), +1 inside a dimension section, 0 = automatic.
    // below: the same key from lower packs, highest first, used when this one does not resolve
    private record Written(ColorSpec spec, String where, int rank, @Nullable Written below) {
        Written on(@Nullable Written lower) {
            return new Written(this.spec, this.where, this.rank, lower);
        }
    }

    // two stacks of the same key into one, highest rank first
    private static @Nullable Written merge(@Nullable Written a, @Nullable Written b) {
        if (a == null || b == null) {
            return a != null ? a : b;
        }
        return a.rank() >= b.rank() ? a.on(merge(a.below(), b)) : b.on(merge(a, b.below()));
    }

    // what the files say before sets are resolved: the base, or one dimension's section
    private static final class Sections {
        final Map<String, Written> colors = new HashMap<>(), items = new HashMap<>(), entities = new HashMap<>(),
                sets = new HashMap<>();
        // highest pack first, file order inside a file
        final List<WrittenNeighborRule> neighborRules = new ArrayList<>();
        // mixed into every color, e.g. a purple end
        @Nullable Written tint;
        double tintAmount = DEFAULT_TINT_AMOUNT;

        void read(JsonObject root, String where, int rank) {
            readSection(root, "sets", where, rank, sets);
            readSection(root, "colors", where, rank, colors);
            readSection(root, "items", where, rank, items);
            readSection(root, "entities", where, rank, entities);
            if (root.has("rules")) {
                List<WrittenNeighborRule> mine = new ArrayList<>();
                try {
                    for (JsonElement rule : root.getAsJsonArray("rules")) {
                        mine.add(new WrittenNeighborRule(rule.getAsJsonObject(), where, rank));
                    }
                } catch (IllegalStateException | UnsupportedOperationException e) {
                    Polychrome.LOGGER.warn("Bad light color rules in {}: {}", where, e.getMessage());
                }
                neighborRules.addAll(0, mine);
            }
            try {
                if (root.has("tint")) {
                    tint = new Written(ColorSpec.parse(root.get("tint")), where, rank, null);
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
                Map<String, Written> target = into.get(i);
                target.putAll(from.get(i));
                mine.get(i).forEach((key, written) -> target.merge(key, written, LightColors::merge));
            }
            // by pack, then the dimension's before the base's
            merged.neighborRules.addAll(neighborRules);
            merged.neighborRules.addAll(base.neighborRules);
            merged.neighborRules.sort(Comparator.comparingInt(WrittenNeighborRule::rank).reversed());
            merged.tint = tint != null ? tint : base.tint;
            merged.tintAmount = tint != null ? tintAmount : base.tintAmount;
            return merged;
        }
    }

    private static volatile Palette base = new Palette(List.of(), List.of(), List.of(), List.of(), -1, 0);
    // color for lights with none written, white unless the dimension has a tint
    private static volatile int white = WHITE;
    private static volatile Map<String, Palette> dimensions = Map.of();
    // block id -> color from its textures, for modded lights no pack wrote a color for
    private static volatile Map<Identifier, Integer> autoColors = Map.of();
    // dimension the tables below were resolved for
    private static volatile @Nullable String resolvedFor;

    // per state id: HueField.source() packing, 0 if it doesnt emit
    private static volatile int @Nullable [] sourceByState;
    // per state id: color whether it emits or not (white if none). for held / dropped blocks
    private static volatile int @Nullable [] colorByState;
    private static volatile @Nullable RuleTable ruleTable;
    // what the rule table was built from, to tell if a reload changed the rules
    private static volatile List<NeighborRule> resolvedRules = List.of();
    // dynamic lights
    private static volatile Map<Item, Integer> colorByItem = Map.of();
    private static volatile Map<EntityType<?>, Integer> colorByEntity = Map.of();

    private LightColors() {
    }


    // reads every file and works out sets/textures/references. off thread during reload prep, no tags
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
                int rank = 2 * (packOrder.indexOf(resource.sourcePackId()) + 1);
                base.read(root, where, rank);
                if (root.has("dimensions")) {
                    for (Map.Entry<String, JsonElement> dim : root.getAsJsonObject("dimensions").entrySet()) {
                        String id = Identifier.parse(dim.getKey()).toString();
                        dimensions.computeIfAbsent(id, k -> new Sections()).read(dim.getValue().getAsJsonObject(), where + " " + id, rank + 1);
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
        return new Loaded(palette(base, texture, warned), Map.copyOf(palettes), base.sets.size(), textures.size(), autoColors(manager));
    }

    // every modded block that gives light: glow average of the textures its model uses. vanilla ones
    // are all in vanilla.json. anything unreadable just stays white
    private static Map<Identifier, Integer> autoColors(ResourceManager manager) {
        Map<Identifier, Integer> auto = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                continue;
            }
            BlockState lit = null;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.getLightEmission() > 0) {
                    lit = state;
                    break;
                }
            }
            if (lit == null) {
                continue;
            }
            try {
                double[] sums = new double[3];
                for (Identifier texture : modelTextures(manager, id, lit)) {
                    int[] argb = pixels(manager, texture);
                    if (argb != null) {
                        ColorSpec.addGlow(argb, sums);
                    }
                }
                int rgb = ColorSpec.fromSums(sums);
                if (rgb >= 0) {
                    auto.put(id, rgb);
                }
            } catch (RuntimeException e) {
                Polychrome.LOGGER.debug("No automatic light color for {}: {}", id, e.getMessage());
            }
        }
        return Map.copyOf(auto);
    }

    // textures of the model(s) the lit state uses: matching variant, or every multipart piece
    private static java.util.Set<Identifier> modelTextures(ResourceManager manager, Identifier block, BlockState state) {
        JsonObject blockstate = readJson(manager, Identifier.fromNamespaceAndPath(block.getNamespace(), "blockstates/" + block.getPath() + ".json"));
        java.util.Set<Identifier> textures = new java.util.LinkedHashSet<>();
        if (blockstate == null) {
            return textures;
        }
        if (blockstate.has("variants")) {
            String model = null;
            for (Map.Entry<String, JsonElement> variant : blockstate.getAsJsonObject("variants").entrySet()) {
                boolean match = matches(state, parseProperties(variant.getKey()));
                if (model == null || match) {
                    model = modelOf(variant.getValue());
                }
                if (match) {
                    break;
                }
            }
            if (model != null) {
                collectTextures(manager, model, textures);
            }
        } else if (blockstate.has("multipart")) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                String model = modelOf(part.getAsJsonObject().get("apply"));
                if (model != null) {
                    collectTextures(manager, model, textures);
                }
            }
        }
        return textures;
    }

    // {"model": ...} or a weighted list of them, first one
    private static @Nullable String modelOf(@Nullable JsonElement element) {
        if (element != null && element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            element = element.getAsJsonArray().get(0);
        }
        return element != null && element.isJsonObject() && element.getAsJsonObject().has("model")
                ? element.getAsJsonObject().get("model").getAsString() : null;
    }

    // child textures win over parents, "#name" follows the map
    private static void collectTextures(ResourceManager manager, String modelId, java.util.Set<Identifier> into) {
        Map<String, String> textures = new HashMap<>();
        String current = modelId;
        for (int depth = 0; current != null && depth < 16; depth++) {
            Identifier id = Identifier.parse(current);
            if (id.getPath().startsWith("builtin/")) {
                break;
            }
            JsonObject model = readJson(manager, Identifier.fromNamespaceAndPath(id.getNamespace(), "models/" + id.getPath() + ".json"));
            if (model == null) {
                break;
            }
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> entry : model.getAsJsonObject("textures").entrySet()) {
                    JsonElement value = entry.getValue();
                    // newer models can write {"sprite": ...}
                    String sprite = !value.isJsonObject() ? value.getAsString()
                            : value.getAsJsonObject().has("sprite") ? value.getAsJsonObject().get("sprite").getAsString() : null;
                    if (sprite != null) {
                        textures.putIfAbsent(entry.getKey(), sprite);
                    }
                }
            }
            current = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        for (String value : textures.values()) {
            String resolved = value;
            for (int i = 0; resolved != null && resolved.startsWith("#") && i < 8; i++) {
                resolved = textures.get(resolved.substring(1));
            }
            if (resolved != null && !resolved.startsWith("#")) {
                into.add(Identifier.parse(resolved));
            }
        }
    }

    private static @Nullable JsonObject readJson(ResourceManager manager, Identifier file) {
        Resource resource = manager.getResource(file).orElse(null);
        if (resource == null) {
            return null;
        }
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // "ns:block/x" texture id, null if missing
    private static int @Nullable [] pixels(ResourceManager manager, Identifier texture) {
        Resource resource = manager.getResource(Identifier.fromNamespaceAndPath(texture.getNamespace(), "textures/" + texture.getPath() + ".png")).orElse(null);
        if (resource == null) {
            return null;
        }
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth(), height = image.getHeight();
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    argb[y * width + x] = image.getPixel(x, y);
                }
            }
            return argb;
        } catch (IOException e) {
            return null;
        }
    }

    private static Palette palette(Sections sections, Function<ColorSpec.Texture, Integer> texture, java.util.Set<String> warned) {
        // a set that does not resolve falls back to the same set from the pack below, like colors do
        Map<String, Written> chosen = new HashMap<>(sections.sets);
        Map<String, ColorSpec> setSpecs = new HashMap<>();
        Map<String, String> problems = new HashMap<>();
        while (true) {
            setSpecs.clear();
            chosen.forEach((name, written) -> setSpecs.put(name, written.spec()));
            problems.clear();
            ColorSpec.resolveSets(setSpecs, texture, problems);
            // a set only pointing at another broken set waits for that one, unless nothing else can move (a loop)
            List<String> broken = problems.keySet().stream().filter(name -> chosen.get(name).below() != null).toList();
            List<String> first = broken.stream().filter(name -> !(setSpecs.get(name) instanceof ColorSpec.SetRef ref
                    && problems.containsKey(ref.name()) && !ref.name().equals(name))).toList();
            List<String> step = first.isEmpty() ? broken : first;
            if (step.isEmpty()) {
                break;
            }
            for (String name : step) {
                Written written = chosen.get(name);
                warn(warned, "Light color set '{}' in {}: {}, using the pack below", name, written.where(), problems.get(name));
                chosen.put(name, written.below());
            }
        }
        problems.forEach((name, problem) -> warn(warned, "Light color set '{}' in {}: {}", name, chosen.get(name).where(), problem));
        int tint = -1;
        if (sections.tint != null) {
            try {
                tint = ColorSpec.resolve(sections.tint.spec(), setSpecs, texture, new HashSet<>());
            } catch (ColorSpec.BadColor e) {
                warn(warned, "Light tint in {}: {}", sections.tint.where(), e.getMessage());
            }
        }
        return new Palette(resolveAll(sections.colors, setSpecs, texture, warned), resolveAll(sections.items, setSpecs, texture, warned),
                resolveAll(sections.entities, setSpecs, texture, warned), neighborRules(sections.neighborRules, setSpecs, texture, warned),
                tint, sections.tintAmount);
    }

    // keys as a string or a list of strings
    private static List<String> keys(JsonElement element) {
        List<String> keys = new ArrayList<>();
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(key -> keys.add(key.getAsString()));
        } else {
            keys.add(element.getAsString());
        }
        return keys;
    }

    private static List<NeighborRule> neighborRules(List<WrittenNeighborRule> written, Map<String, ColorSpec> sets,
                                                    Function<ColorSpec.Texture, Integer> texture, java.util.Set<String> warned) {
        List<NeighborRule> rules = new ArrayList<>();
        for (WrittenNeighborRule rule : written) {
            JsonObject json = rule.json();
            try {
                if (!json.has("matchBlocks") || !json.has("color")) {
                    throw new ColorSpec.BadColor("a rule needs \"matchBlocks\" and \"color\"");
                }
                Map<String, List<String>> sides = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    if (SIDES.contains(entry.getKey())) {
                        sides.put(entry.getKey(), keys(entry.getValue()));
                    } else if (!entry.getKey().equals("matchBlocks") && !entry.getKey().equals("color")) {
                        throw new ColorSpec.BadColor("unknown key \"" + entry.getKey() + "\" (sides are " + String.join(", ", SIDES) + ")");
                    }
                }
                if (sides.isEmpty()) {
                    throw new ColorSpec.BadColor("a rule needs at least one side, else write it under \"colors\"");
                }
                int rgb = ColorSpec.resolve(ColorSpec.parse(json.get("color")), sets, texture, new HashSet<>());
                rules.add(new NeighborRule(keys(json.get("matchBlocks")), Map.copyOf(sides), rgb, rule.where()));
            } catch (ColorSpec.BadColor | IllegalStateException | UnsupportedOperationException e) {
                warn(warned, "Light color rule {} in {}: {}", json, rule.where(), e.getMessage());
            }
        }
        return List.copyOf(rules);
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
        written.forEach((key, top) -> {
            for (Written w = top; w != null; w = w.below()) {
                try {
                    loaded.add(new Rule(key, ColorSpec.resolve(w.spec(), sets, texture, new HashSet<>()), w.rank()));
                    return;
                } catch (ColorSpec.BadColor e) {
                    warn(warned, "Light color for '{}' in {}: {}", key, w.where(), e.getMessage());
                }
            }
        });
        return List.copyOf(loaded);
    }

    private static void readSection(JsonObject root, String name, String where, int rank, Map<String, Written> into) {
        if (!root.has(name)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(name).entrySet()) {
            try {
                into.put(entry.getKey(), new Written(ColorSpec.parse(entry.getValue()), where, rank, into.get(entry.getKey())));
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
        autoColors = loaded.auto();
        sourceByState = null;
        resolvedFor = null;
        Polychrome.LOGGER.info("Loaded {} light colors, {} for items, {} for entities, {} rules ({} sets, {} textures, {} dimensions), {} automatic",
                base.rules().size(), base.items().size(), base.entities().size(), base.neighborRules().size(), loaded.sets(), loaded.textures(),
                dimensions.size(), loaded.auto().size());
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

        // priority 0, anything written wins
        autoColors.forEach((id, rgb) -> BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                set(state, rgb, 0, rgbByState, specificity);
            }
        }));

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
        ruleTable = compileRules(palette.neighborRules(), tinted, stateCount);
        resolvedRules = palette.neighborRules();

        Map<Item, Integer> byItem = new HashMap<>();
        Map<Item, Integer> itemPriority = new HashMap<>();
        for (Rule rule : itemRules) {
            try {
                forEachNamed(BuiltInRegistries.ITEM, Registries.ITEM, rule.key(),
                        item -> put(byItem, itemPriority, item, tinted.applyAsInt(rule.rgb()), priority(rule.rank(), rule.key().startsWith("#") ? 1 : 2)));
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping item light color '{}': {}", rule.key(), e.getMessage());
            }
        }
        colorByItem = byItem;
        Map<EntityType<?>, Integer> byEntity = new HashMap<>();
        Map<EntityType<?>, Integer> entityPriority = new HashMap<>();
        for (Rule rule : entityRules) {
            try {
                forEachNamed(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, rule.key(),
                        type -> put(byEntity, entityPriority, type, tinted.applyAsInt(rule.rgb()), priority(rule.rank(), rule.key().startsWith("#") ? 1 : 2)));
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping entity light color '{}': {}", rule.key(), e.getMessage());
            }
        }
        colorByEntity = byEntity;
        resolvedFor = dimension;
    }

    private static @Nullable RuleTable compileRules(List<NeighborRule> rules, java.util.function.IntUnaryOperator tinted, int stateCount) {
        List<CompiledRule> compiled = new ArrayList<>();
        java.util.BitSet ruled = new java.util.BitSet(stateCount);
        for (NeighborRule rule : rules) {
            try {
                java.util.BitSet match = states(rule.match());
                java.util.BitSet[] sides = null;
                java.util.BitSet touching = null;
                for (Map.Entry<String, List<String>> side : rule.sides().entrySet()) {
                    java.util.BitSet allowed = states(side.getValue());
                    if (side.getKey().equals("touching")) {
                        touching = allowed;
                        continue;
                    }
                    if (sides == null) {
                        sides = new java.util.BitSet[6];
                    }
                    sides[direction(side.getKey()).ordinal()] = allowed;
                }
                // only lights have a color to change
                for (int id = match.nextSetBit(0); id >= 0; id = match.nextSetBit(id + 1)) {
                    BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                    if (state == null || state.getLightEmission() == 0) {
                        match.clear(id);
                    }
                }
                ruled.or(match);
                compiled.add(new CompiledRule(match, sides, touching, tinted.applyAsInt(rule.rgb())));
            } catch (RuntimeException e) {
                Polychrome.LOGGER.warn("Skipping light color rule in {}: {}", rule.where(), e.getMessage());
            }
        }
        return compiled.isEmpty() ? null : new RuleTable(List.copyOf(compiled), ruled);
    }

    private static net.minecraft.core.Direction direction(String side) {
        return switch (side) {
            case "above" -> net.minecraft.core.Direction.UP;
            case "below" -> net.minecraft.core.Direction.DOWN;
            default -> net.minecraft.core.Direction.byName(side);
        };
    }

    // every state the keys match, same key syntax as colors
    private static java.util.BitSet states(List<String> keys) {
        java.util.BitSet states = new java.util.BitSet();
        for (String key : keys) {
            if (key.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(key.substring(1)));
                for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                    holder.value().getStateDefinition().getPossibleStates().forEach(state -> states.set(Block.getId(state)));
                }
                continue;
            }
            int bracket = key.indexOf('[');
            String blockId = bracket < 0 ? key : key.substring(0, bracket);
            Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId))
                    .orElseThrow(() -> new IllegalArgumentException("unknown block " + blockId));
            Map<String, String> wanted = bracket < 0 ? Map.of() : parseProperties(key.substring(bracket + 1, key.length() - 1));
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (matches(state, wanted)) {
                    states.set(Block.getId(state));
                }
            }
        }
        return states;
    }

    // true if any pack has a rule. callers skip all neighbor work otherwise
    public static boolean anyRules() {
        return ruleTable != null;
    }

    public static boolean hasRules(BlockState state) {
        RuleTable table = ruleTable;
        return table != null && table.ruled().get(Block.getId(state));
    }

    // HueField.source() packing for a light with these neighbors: first matching rule, else sourceOf. any thread
    public static int sourceAt(BlockState state, Neighbors around) {
        RuleTable table = ruleTable;
        int id = Block.getId(state);
        if (table == null || !table.ruled().get(id)) {
            return sourceOf(state);
        }
        for (CompiledRule rule : table.rules()) {
            if (rule.match().get(id) && matches(rule, around)) {
                return HueField.source(state.getLightEmission(), rule.rgb());
            }
        }
        return sourceOf(state);
    }

    private static boolean matches(CompiledRule rule, Neighbors around) {
        net.minecraft.core.Direction[] directions = net.minecraft.core.Direction.values();
        if (rule.sides() != null) {
            for (int i = 0; i < 6; i++) {
                java.util.BitSet allowed = rule.sides()[i];
                if (allowed != null) {
                    BlockState neighbor = around.at(directions[i]);
                    if (neighbor == null || !allowed.get(Block.getId(neighbor))) {
                        return false;
                    }
                }
            }
        }
        if (rule.touching() != null) {
            for (net.minecraft.core.Direction side : directions) {
                BlockState neighbor = around.at(side);
                if (neighbor != null && rule.touching().get(Block.getId(neighbor))) {
                    return true;
                }
            }
            return false;
        }
        return true;
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
                    set(state, rule.rgb(), priority(rule.rank(), 1), rgbByState, specificity);
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
                set(state, rule.rgb(), priority(rule.rank(), wanted.isEmpty() ? 2 : 3), rgbByState, specificity);
            }
        }
    }

    // pack first, then specificity (1 tag, 2 block, 3 state). auto colors are 0
    private static int priority(int rank, int level) {
        return rank * 4 + level;
    }

    private static <T> void put(Map<T, Integer> colors, Map<T, Integer> priorities, T key, int rgb, int priority) {
        if (priority >= priorities.getOrDefault(key, 0)) {
            priorities.put(key, priority);
            colors.put(key, rgb);
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

    // rules part of the same check
    public static List<NeighborRule> rules() {
        return resolvedRules;
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
