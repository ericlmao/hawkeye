package gg.moonrise.hawkeye.util.compat;

/**
 * The Paper API newer than the oldest supported server (1.21.1) that Hawkeye uses, with the version that
 * added each: a class name, or {@code Class#method} for a method added to an older class. A component that
 * needs one carries {@link ConditionalOnApi}, its fallback {@link ConditionalOnMissingApi}, both naming the
 * constant here. README's version table lists what each gap costs.
 */
public final class NewerApi {

    /** Paper 1.21.4: forced serialization of an entity in its death event (kill rollbacks). */
    public static final String ENTITY_SERIALIZATION_FLAG = "io.papermc.paper.entity.EntitySerializationFlag";

    /** Paper 1.21.7: dialogs with server-side button callbacks. */
    public static final String DIALOG = "io.papermc.paper.registry.data.dialog.action.DialogActionCallback";

    /** Minecraft 1.21.9: shelves, which hold items without a menu. */
    public static final String SHELF = "org.bukkit.block.Shelf";

    /** Paper 1.21.11: the crafted item with the count actually crafted. */
    public static final String ITEM_CRAFTED_EVENT = "io.papermc.paper.event.inventory.ItemCraftedEvent";

    /** Paper 26.1.2: the damage that destroyed a boat or minecart. */
    public static final String VEHICLE_DESTROY_DAMAGE_SOURCE =
            "org.bukkit.event.vehicle.VehicleDestroyEvent#getDamageSource";

    /** Paper 26.2: a golem or wither built from a block pattern, with the pattern's blocks. */
    public static final String ENTITY_CONSTRUCT_EVENT = "io.papermc.paper.event.entity.EntityConstructEvent";

    /** Paper 26.3: a block-attached entity other than a painting or item frame breaking (cushions). */
    public static final String ENTITY_BREAK_EVENT = "io.papermc.paper.event.entity.EntityBreakEvent";

    private NewerApi() {
    }
}
