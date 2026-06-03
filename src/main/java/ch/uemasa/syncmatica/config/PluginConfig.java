package ch.uemasa.syncmatica.config;

import ch.uemasa.syncmatica.Reference;
import ch.uemasa.syncmatica.comm.Feature;
import ch.uemasa.syncmatica.comm.FeatureSet;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Typed view over {@code config.yml}.
 */
public final class PluginConfig {

    private final String protocolVersion;
    private final FeatureSet featureSet;
    private final boolean quotaEnabled;
    private final long quotaLimit;
    private final boolean permissionsEnforce;
    private final boolean debugLogging;

    private PluginConfig(String protocolVersion, FeatureSet featureSet, boolean quotaEnabled,
                         long quotaLimit, boolean permissionsEnforce, boolean debugLogging) {
        this.protocolVersion = protocolVersion;
        this.featureSet = featureSet;
        this.quotaEnabled = quotaEnabled;
        this.quotaLimit = quotaLimit;
        this.permissionsEnforce = permissionsEnforce;
        this.debugLogging = debugLogging;
    }

    public static PluginConfig load(FileConfiguration cfg) {
        String version = cfg.getString("protocol-version", Reference.DEFAULT_MOD_VERSION);

        Set<Feature> features = EnumSet.noneOf(Feature.class);
        List<String> names = cfg.getStringList("features");
        if (names.isEmpty()) {
            features = defaultFeatures();
        } else {
            for (String n : names) {
                Feature f = Feature.fromString(n);
                if (f != null) {
                    features.add(f);
                }
            }
        }
        // CORE is always implied.
        features.add(Feature.CORE);

        boolean quotaEnabled = cfg.getBoolean("quota.enabled", false);
        long quotaLimit = cfg.getLong("quota.limit", 40_000_000L);
        boolean enforce = cfg.getBoolean("permissions.enforce", false);
        boolean debugLogging = cfg.getBoolean("debug.doPackageLogging", false);

        return new PluginConfig(version, new FeatureSet(features), quotaEnabled, quotaLimit, enforce, debugLogging);
    }

    private static Set<Feature> defaultFeatures() {
        // Upstream advertises the whole enum; match it so the negotiated feature string is identical.
        return EnumSet.allOf(Feature.class);
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public FeatureSet getFeatureSet() {
        return featureSet;
    }

    public boolean isQuotaEnabled() {
        return quotaEnabled;
    }

    public long getQuotaLimit() {
        return quotaLimit;
    }

    public boolean isPermissionsEnforced() {
        return permissionsEnforce;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }
}
