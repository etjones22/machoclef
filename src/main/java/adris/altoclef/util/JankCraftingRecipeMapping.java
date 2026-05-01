package adris.altoclef.util;

import net.minecraft.item.Item;
import net.minecraft.recipe.NetworkRecipeId;

import java.util.Optional;

/**
 * Recipe book network IDs changed substantially after 1.20.
 * Keep the old call surface compiling while manual crafting remains available.
 */
public class JankCraftingRecipeMapping {
    public static Optional<NetworkRecipeId> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output) {
        return Optional.empty();
    }
}
