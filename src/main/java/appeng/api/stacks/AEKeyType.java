package appeng.api.stacks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * AE2 v9 (1.17.1) compatibility shim for the v10+ {@code appeng.api.stacks.AEKeyType}
 * API — the "type" of a key (item / fluid). Only the surface used by the VM core and
 * its offline bench harness exists; v9's real type system is the storage channel.
 */
public abstract class AEKeyType {

    private final ResourceLocation id;
    private final Class<? extends AEKey> keyClass;
    private final Component description;

    protected AEKeyType(ResourceLocation id, Class<? extends AEKey> keyClass, Component description) {
        this.id = id;
        this.keyClass = keyClass;
        this.description = description;
    }

    /** The type's registry id. */
    public final ResourceLocation getId() {
        return id;
    }

    /** The key class this type produces. */
    public final Class<? extends AEKey> getKeyClass() {
        return keyClass;
    }

    /** Human-readable type name. */
    public final Component getDescription() {
        return description;
    }

    /**
     * How many units of this type make up one byte of storage (v10+ API; the VM's
     * addStackBytes byte accounting divides by this). Items are 8 items = 1 byte
     * in crafting storage terms.
     */
    public int getAmountPerByte() {
        return 8;
    }
}
