package me.neznamy.tab.shared.placeholders;

import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

import me.neznamy.tab.shared.features.types.Refreshable;
import me.neznamy.tab.shared.player.TabPlayer;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.api.placeholder.RelationalPlaceholder;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;

/**
 * Implementation of RelationalPlaceholder interface
 */
public class RelationalPlaceholderImpl extends TabPlaceholder implements RelationalPlaceholder {

    /** Placeholder function returning fresh output on request */
    private final BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, Object> function;

    /** Last known values for each online player duo after applying replacements and nested placeholders */
    private final WeakHashMap<me.neznamy.tab.api.TabPlayer, WeakHashMap<me.neznamy.tab.api.TabPlayer, String>> lastValues = new WeakHashMap<>();

    /**
     * Constructs new instance with given parameters
     *
     * @param   identifier
     *          placeholder identifier, must start with {@code %rel_} and end with {@code %}
     * @param   refresh
     *          refresh interval in milliseconds, must be divisible by {@link TabConstants.Placeholder#MINIMUM_REFRESH_INTERVAL}
     *          or equal to -1 to disable automatic refreshing
     * @param   function
     *          refresh function which returns new up-to-date output on request
     */
    public RelationalPlaceholderImpl(String identifier, int refresh, BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, Object> function) {
        super(identifier, refresh);
        if (!identifier.startsWith("%rel_")) throw new IllegalArgumentException("Relational placeholder identifiers must start with \"rel_\"");
        this.function = function;
    }
    
    /**
     * Updates value for given players and returns true if value changed, false if not
     *
     * @param   viewer
     *          viewer of the placeholder
     * @param   target
     *          target who is the text displayed on
     * @return  true if value changed, false if not
     */
    public boolean update(TabPlayer viewer, TabPlayer target) {
        Object output = request(viewer, target);
        if (output == null) return false; //bridge placeholders, they are updated using updateValue method
        String newValue = getReplacements().findReplacement(String.valueOf(output));
        if (!lastValues.computeIfAbsent(viewer, v -> new WeakHashMap<>()).containsKey(target) || !lastValues.get(viewer).get(target).equals(newValue)) {
            lastValues.get(viewer).put(target, newValue);
            updateParents(viewer);
            updateParents(target);
            return true;
        }
        return false;
    }

    /**
     * Internal method with an additional parameter {@code force}, which, if set to true,
     * features using the placeholder will refresh despite placeholder seemingly not
     * changing output, which is caused by nested placeholder changing value.
     *
     * @param   viewer
     *          viewer of the placeholder
     * @param   target
     *          target who is the text displayed on
     * @param   value
     *          new placeholder output
     * @param   force
     *          whether refreshing should be forced or not
     */
    private void updateValue(TabPlayer viewer, TabPlayer target, Object value, boolean force) {
        String s = getReplacements().findReplacement(String.valueOf(value));
        if (lastValues.computeIfAbsent(viewer, v -> new WeakHashMap<>()).containsKey(target) && lastValues.get(viewer).get(target).equals(s) && !force) return;
        lastValues.get(viewer).put(target, s);
        Set<Refreshable> usage = TAB.getInstance().getPlaceholderManager().getPlaceholderUsage().get(identifier);
        if (usage == null) return;
        for (Refreshable f : usage) {
            long time = System.nanoTime();
            f.refresh(viewer, true);
            f.refresh(target, true);
            TAB.getInstance().getCPUManager().addTime(f.getFeatureName(), f.getRefreshDisplayName(), System.nanoTime()-time);
        }
        updateParents(viewer);
        updateParents(target);
    }

    @Override
    public void updateValue(me.neznamy.tab.api.TabPlayer viewer, me.neznamy.tab.api.TabPlayer target, Object value) {
        updateValue((TabPlayer) viewer, (TabPlayer) target, value, false);
    }

    /**
     * Returns last known value for given players. First player is viewer,
     * second player is target.
     *
     * @param   viewer
     *          viewer of the placeholder
     * @param   target
     *          target who is the text displayed on
     * @return  last known value for entered player duo
     */
    public String getLastValue(TabPlayer viewer, TabPlayer target) {
        if (!lastValues.computeIfAbsent(viewer, v -> new WeakHashMap<>()).containsKey(target)) update(viewer, target);
        return setPlaceholders(replacements.findReplacement(EnumChatFormat.color(lastValues.computeIfAbsent(viewer, v -> new WeakHashMap<>()).get(target))), target);
    }

    @Override
    public String getLastValue(TabPlayer p) {
        return identifier;
    }

    @Override
    public void updateFromNested(TabPlayer player) {
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            updateValue(player, all, request(player, all), true);
        }
    }

    /**
     * Calls the placeholder request function and returns the output.
     * If the placeholder threw an exception, it is logged in {@code placeholder-errors.log}
     * file and "ERROR" is returned.
     *
     * @param   viewer
     *          player looking at output of the placeholder
     * @param   target
     *          player the placeholder is displayed on
     * @return  value placeholder returned or "ERROR" if it threw an error
     */
    public Object request(TabPlayer viewer, TabPlayer target) {
        try {
            return function.apply(viewer, target);
        } catch (Throwable t) {
            TAB.getInstance().getErrorManager().placeholderError("Relational placeholder " + identifier + " generated an error when setting for players " + viewer.getName() + " and " + target.getName(), t);
            return "ERROR";
        }
    }
}