package gg.moonrise.hawkeye.core.service.dev.fake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.jetbrains.annotations.NotNull;

/**
 * Weighted values for fake data: block states with every variant of their properties, item materials,
 * entity types. A value is picked by its weight, then one of its variants uniformly.
 */
public final class FakePalette {

    private final String[][] variants;
    private final long[] cumulative;

    private FakePalette(@NotNull List<String[]> variants, @NotNull List<Integer> weights) {
        this.variants = variants.toArray(String[][]::new);
        this.cumulative = new long[weights.size()];
        long total = 0;
        for (int i = 0; i < weights.size(); i++) {
            total += weights.get(i);
            cumulative[i] = total;
        }
    }

    /**
     * Starts a palette.
     *
     * @return a builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Picks a value.
     *
     * @param random the random source
     * @return a namespaced value, e.g. {@code minecraft:oak_stairs[facing=east,half=bottom,...]}
     */
    public @NotNull String pick(@NotNull SplittableRandom random) {
        long ticket = random.nextLong(cumulative[cumulative.length - 1]);
        int index = Arrays.binarySearch(cumulative, ticket + 1);
        String[] options = variants[index < 0 ? -index - 1 : index];
        return options.length == 1 ? options[0] : options[random.nextInt(options.length)];
    }

    /**
     * The distinct values the palette can produce.
     *
     * @return the count
     */
    public int distinct() {
        return Arrays.stream(variants).mapToInt(options -> options.length).sum();
    }

    /**
     * Every value the palette can produce.
     *
     * @return the values, in the order they were added
     */
    public @NotNull List<String> values() {
        return Arrays.stream(variants).flatMap(Arrays::stream).toList();
    }

    /** Collects weighted values. */
    public static final class Builder {

        private final List<String[]> variants = new ArrayList<>();
        private final List<Integer> weights = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds a value with its properties, each written {@code name=a|b|c} in alphabetical order of the
         * names (Minecraft's own order), so every combination is one variant.
         *
         * @param name       the id without namespace
         * @param weight     the relative frequency, at least 1
         * @param properties the properties and their values
         * @return this builder
         */
        public @NotNull Builder add(@NotNull String name, int weight, @NotNull String... properties) {
            if (weight < 1) {
                throw new IllegalArgumentException("a weight must be at least 1: " + name);
            }
            List<String> combinations = List.of("");
            for (String property : properties) {
                String[] parts = property.split("=", 2);
                List<String> next = new ArrayList<>();
                for (String prefix : combinations) {
                    for (String value : parts[1].split("\\|")) {
                        next.add(prefix + (prefix.isEmpty() ? "" : ",") + parts[0] + "=" + value);
                    }
                }
                combinations = next;
            }
            String id = "minecraft:" + name;
            variants.add(combinations.stream().map(suffix -> suffix.isEmpty() ? id : id + "[" + suffix + "]")
                    .toArray(String[]::new));
            weights.add(weight);
            return this;
        }

        /**
         * Builds the palette.
         *
         * @return the palette
         * @throws IllegalStateException when no value was added
         */
        public @NotNull FakePalette build() {
            if (variants.isEmpty()) {
                throw new IllegalStateException("a palette needs at least one value");
            }
            return new FakePalette(variants, weights);
        }
    }
}
