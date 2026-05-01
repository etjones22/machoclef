package baritone.altoclef;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class AltoClefSettings {
    private static final AltoClefSettings INSTANCE = new AltoClefSettings();

    private final Object breakMutex = new Object();
    private final Object placeMutex = new Object();
    private final Object propertiesMutex = new Object();
    private final Object globalHeuristicMutex = new Object();

    private final HashSet<BlockPos> blocksToAvoidBreaking = new HashSet<>();
    private final List<Predicate<BlockPos>> breakAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> placeAvoiders = new ArrayList<>();
    private final List<Item> protectedItems = new ArrayList<>();
    private final List<Predicate<BlockPos>> forceWalkOnPredicates = new ArrayList<>();
    private final List<Predicate<BlockPos>> forceAvoidWalkThroughPredicates = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> forceUseToolPredicates = new ArrayList<>();
    private final List<BiFunction<Double, BlockPos, Double>> globalHeuristics = new ArrayList<>();

    private boolean flowingWaterPassAllowed;
    private boolean interactionPaused;
    private boolean placeBucketButDontFall;
    private boolean swimThroughLava;

    private AltoClefSettings() {
    }

    public static AltoClefSettings getInstance() {
        return INSTANCE;
    }

    public Object getBreakMutex() {
        return breakMutex;
    }

    public Object getPlaceMutex() {
        return placeMutex;
    }

    public Object getPropertiesMutex() {
        return propertiesMutex;
    }

    public Object getGlobalHeuristicMutex() {
        return globalHeuristicMutex;
    }

    public HashSet<BlockPos> getBlocksToAvoidBreaking() {
        return blocksToAvoidBreaking;
    }

    public List<Predicate<BlockPos>> getBreakAvoiders() {
        return breakAvoiders;
    }

    public List<Predicate<BlockPos>> getPlaceAvoiders() {
        return placeAvoiders;
    }

    public List<Item> getProtectedItems() {
        return protectedItems;
    }

    public List<Predicate<BlockPos>> getForceWalkOnPredicates() {
        return forceWalkOnPredicates;
    }

    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() {
        return forceAvoidWalkThroughPredicates;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() {
        return forceUseToolPredicates;
    }

    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() {
        return globalHeuristics;
    }

    public void avoidBlockBreak(Predicate<BlockPos> predicate) {
        breakAvoiders.add(predicate);
    }

    public void avoidBlockPlace(Predicate<BlockPos> predicate) {
        placeAvoiders.add(predicate);
    }

    public boolean shouldAvoidBreaking(BlockPos pos) {
        return blocksToAvoidBreaking.contains(pos) || breakAvoiders.stream().anyMatch(predicate -> predicate.test(pos));
    }

    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        return placeAvoiders.stream().anyMatch(predicate -> predicate.test(pos));
    }

    public boolean isFlowingWaterPassAllowed() {
        return flowingWaterPassAllowed;
    }

    public void setFlowingWaterPass(boolean flowingWaterPassAllowed) {
        this.flowingWaterPassAllowed = flowingWaterPassAllowed;
    }

    public boolean isInteractionPaused() {
        return interactionPaused;
    }

    public void setInteractionPaused(boolean interactionPaused) {
        this.interactionPaused = interactionPaused;
    }

    public void configurePlaceBucketButDontFall(boolean placeBucketButDontFall) {
        this.placeBucketButDontFall = placeBucketButDontFall;
    }

    public boolean shouldPlaceBucketButDontFall() {
        return placeBucketButDontFall;
    }

    public void allowSwimThroughLava(boolean swimThroughLava) {
        this.swimThroughLava = swimThroughLava;
    }

    public boolean canSwimThroughLava() {
        return swimThroughLava;
    }
}
