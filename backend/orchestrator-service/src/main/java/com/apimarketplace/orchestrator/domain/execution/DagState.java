package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * State of a single DAG (trigger) across all its epochs.
 *
 * <p>Each trigger in a workflow has its own DagState tracking:
 * <ul>
 *   <li>{@code currentEpoch} - which epoch is currently executing (most recent)</li>
 *   <li>{@code currentSpawn} - rerun counter within the current epoch</li>
 *   <li>{@code fireCount} - total number of times this trigger has fired</li>
 *   <li>{@code epochs} - per-epoch execution state (historical + active)</li>
 *   <li>{@code activeEpochs} - epochs currently in flight (running/ready, not yet closed)</li>
 * </ul>
 *
 * <p>Parallel epochs: Multiple epochs can be active simultaneously when the trigger
 * fires multiple times concurrently. {@code activeEpochs} tracks which epochs are
 * still executing. {@code currentEpoch} always points to the most recently created epoch.
 *
 * <p>Immutable - all mutations return new instances.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DagState {

    private final int currentEpoch;
    private final int currentSpawn;
    private final int fireCount;
    private final Map<Integer, EpochState> epochs;
    private final Set<Integer> activeEpochs;

    @JsonCreator
    public DagState(
            @JsonProperty("currentEpoch") int currentEpoch,
            @JsonProperty("currentSpawn") int currentSpawn,
            @JsonProperty("fireCount") int fireCount,
            @JsonProperty("epochs") Map<Integer, EpochState> epochs,
            @JsonProperty("activeEpochs") Set<Integer> activeEpochs) {
        this.currentEpoch = currentEpoch;
        this.currentSpawn = currentSpawn;
        this.fireCount = fireCount;
        this.epochs = epochs != null ? Map.copyOf(epochs) : Map.of();
        this.activeEpochs = activeEpochs != null ? Set.copyOf(activeEpochs) : Set.of();
    }

    /**
     * Backward-compatible constructor (no activeEpochs).
     * Infers activeEpochs from currentEpoch if epochs exist.
     */
    public DagState(int currentEpoch, int currentSpawn, int fireCount,
                    Map<Integer, EpochState> epochs) {
        this(currentEpoch, currentSpawn, fireCount, epochs,
             epochs != null && epochs.containsKey(currentEpoch)
                 ? Set.of(currentEpoch) : Set.of());
    }

    /**
     * Create an initial DagState (before any trigger fire).
     */
    public static DagState initial() {
        return new DagState(0, 0, 0, Map.of(), Set.of());
    }

    /**
     * Get the EpochState for the current (most recent) epoch.
     * Returns a fresh EpochState if the current epoch hasn't been initialized.
     */
    public EpochState currentEpochState() {
        return epochs.getOrDefault(currentEpoch, EpochState.fresh());
    }

    /**
     * Get the EpochState for a specific epoch.
     * Returns null if that epoch doesn't exist.
     */
    public EpochState getEpochState(int epoch) {
        return epochs.get(epoch);
    }

    /**
     * Advance to the next epoch. Creates a fresh EpochState for the new epoch
     * and adds it to activeEpochs. Previous epoch data is kept.
     */
    public DagState advanceEpoch(int newGlobalEpoch) {
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        newEpochs.put(newGlobalEpoch, EpochState.fresh());
        Set<Integer> newActive = new HashSet<>(activeEpochs);
        newActive.add(newGlobalEpoch);
        return new DagState(newGlobalEpoch, 0, fireCount + 1, newEpochs, newActive);
    }

    /**
     * Prepare for next trigger cycle WITHOUT creating an active epoch.
     *
     * <p>Unlike {@link #advanceEpoch(int)}, this does NOT add the epoch to
     * {@code activeEpochs}. It only creates an EpochState entry with the trigger
     * in its readyNodeIds, so that the flat view shows the trigger as ready.
     * The actual activation happens later via {@link #openEpoch(int)} when the
     * trigger fires.
     *
     * <p>This prevents "phantom epochs" - active epochs that never execute,
     * which block the run from transitioning to WAITING_TRIGGER.
     *
     * @param newGlobalEpoch the epoch number to prepare
     * @param readyNodeId the trigger node to mark as ready
     * @return new DagState with the epoch prepared but NOT active
     */
    public DagState prepareNextCycle(int newGlobalEpoch, String readyNodeId) {
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        EpochState readyState = EpochState.fresh().addReadyNode(readyNodeId);
        newEpochs.put(newGlobalEpoch, readyState);
        // NOT added to activeEpochs - epoch is prepared but dormant
        return new DagState(newGlobalEpoch, 0, fireCount, newEpochs, activeEpochs);
    }

    /**
     * Open an epoch: add it to activeEpochs and create its EpochState if absent.
     * Unlike advanceEpoch(), this does NOT reset the EpochState if it already exists.
     * Increments fireCount to stay in sync with metadata dagFireCount.
     * Used to register the epoch as active before execution begins.
     */
    public DagState openEpoch(int epoch) {
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        newEpochs.putIfAbsent(epoch, EpochState.fresh());
        Set<Integer> newActive = new HashSet<>(activeEpochs);
        newActive.add(epoch);
        return new DagState(Math.max(currentEpoch, epoch), currentSpawn, fireCount + 1, newEpochs, newActive);
    }

    /**
     * Close and prune an epoch: remove it from activeEpochs AND from the epochs map.
     * The epoch's full state lives in workflow_epochs table (header row).
     * This keeps StateSnapshot JSONB at constant size (only active epochs).
     */
    public DagState closeAndPruneEpoch(int epoch) {
        Set<Integer> newActive = new HashSet<>(activeEpochs);
        newActive.remove(epoch);
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        newEpochs.remove(epoch);
        return new DagState(currentEpoch, currentSpawn, fireCount, newEpochs, newActive);
    }

    /**
     * Close and prune ALL active epochs at once.
     * Used when SBS mode auto-closes previous epochs before opening a new one.
     * Returns the set of epochs that were closed (for caller to do dual-write).
     */
    public DagState closeAllActiveEpochs() {
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        for (int epoch : activeEpochs) {
            newEpochs.remove(epoch);
        }
        return new DagState(currentEpoch, currentSpawn, fireCount, newEpochs, Set.of());
    }

    /**
     * Advance the spawn counter (rerun within same epoch).
     */
    public DagState advanceSpawn() {
        return new DagState(currentEpoch, currentSpawn + 1, fireCount, epochs, activeEpochs);
    }

    /**
     * Replace the EpochState for a specific epoch.
     */
    public DagState withEpochState(int epoch, EpochState state) {
        Map<Integer, EpochState> newEpochs = new HashMap<>(epochs);
        newEpochs.put(epoch, state);
        return new DagState(currentEpoch, currentSpawn, fireCount, newEpochs, activeEpochs);
    }

    /**
     * Replace the current epoch's state.
     */
    public DagState withCurrentEpochState(EpochState state) {
        return withEpochState(currentEpoch, state);
    }

    /**
     * Initialize the current epoch with a fresh state if it doesn't exist yet.
     */
    public DagState ensureCurrentEpochInitialized() {
        if (epochs.containsKey(currentEpoch)) {
            return this;
        }
        return withEpochState(currentEpoch, EpochState.fresh());
    }

    /**
     * Re-add the current epoch to activeEpochs if it was closed.
     * Used by rerun: the epoch was closed after SBS completion, but the user
     * wants to re-execute a node within it - the epoch must be active again
     * so that flat views (computeFlatSet) include its state and
     * closeEpochIfCompleteForSbs can detect completion.
     */
    public DagState reactivateCurrentEpoch() {
        if (activeEpochs.contains(currentEpoch)) {
            return this; // already active
        }
        Set<Integer> newActive = new HashSet<>(activeEpochs);
        newActive.add(currentEpoch);
        return new DagState(currentEpoch, currentSpawn, fireCount, epochs, newActive);
    }

    /**
     * Get the EpochStates for all active (non-closed) epochs.
     * JsonIgnore: computed helper - must not be serialized to avoid
     * Jackson deserialization crash on nested EpochState properties.
     */
    @JsonIgnore
    public Map<Integer, EpochState> getActiveEpochStates() {
        Map<Integer, EpochState> result = new HashMap<>();
        for (int active : activeEpochs) {
            EpochState es = epochs.get(active);
            if (es != null) {
                result.put(active, es);
            }
        }
        return result;
    }

    /**
     * Check if there are any active epochs.
     */
    public boolean hasActiveEpochs() {
        return !activeEpochs.isEmpty();
    }

    // Getters
    public int getCurrentEpoch() { return currentEpoch; }
    public int getCurrentSpawn() { return currentSpawn; }
    public int getFireCount() { return fireCount; }
    public Map<Integer, EpochState> getEpochs() { return epochs; }
    public Set<Integer> getActiveEpochs() { return activeEpochs; }
}
