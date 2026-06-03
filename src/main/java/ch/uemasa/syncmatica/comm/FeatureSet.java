package ch.uemasa.syncmatica.comm;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A set of negotiated {@link Feature}s, serialized as their {@link Feature#name()}s joined by {@code '\n'}.
 */
public final class FeatureSet {

    private final Set<Feature> features;

    public FeatureSet(Set<Feature> features) {
        this.features = EnumSet.noneOf(Feature.class);
        this.features.addAll(features);
    }

    public boolean hasFeature(Feature f) {
        return features.contains(f);
    }

    public Set<Feature> getFeatures() {
        return Collections.unmodifiableSet(features);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Feature f : features) {
            if (!first) {
                sb.append('\n');
            }
            sb.append(f.name());
            first = false;
        }
        return sb.toString();
    }

    public static FeatureSet fromString(String s) {
        Set<Feature> set = EnumSet.noneOf(Feature.class);
        if (s != null && !s.isEmpty()) {
            for (String part : s.split("\n")) {
                Feature f = Feature.fromString(part);
                if (f != null) {
                    set.add(f);
                }
            }
        }
        return new FeatureSet(set);
    }
}
