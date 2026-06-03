package ch.uemasa.syncmatica.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the optional per-sub-region modifications of a placement.
 */
public final class SubRegionData {

    private boolean modified;
    private final Map<String, SubRegionModification> modifications = new LinkedHashMap<>();

    public boolean isModified() {
        return modified;
    }

    public Map<String, SubRegionModification> getModifications() {
        return modifications;
    }

    public void put(SubRegionModification mod) {
        modified = true;
        modifications.put(mod.name(), mod);
    }

    public void clear() {
        modified = false;
        modifications.clear();
    }
}
