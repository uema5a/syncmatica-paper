package ch.uemasa.syncmatica.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * In-memory registry of placements with JSON persistence ({@code placements.json}). Saves run on every
 * add/remove and on shutdown, with a {@code .new}/{@code .bak} rotation so a crash mid-write cannot
 * corrupt the live file.
 */
public final class SyncmaticManager {

    private static final String KEY_PLACEMENTS = "placements";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, ServerPlacement> placements = new LinkedHashMap<>();
    private final Path file;
    private final PlayerIdentifierProvider players;
    private final Logger logger;

    public SyncmaticManager(Path file, PlayerIdentifierProvider players, Logger logger) {
        this.file = file;
        this.players = players;
        this.logger = logger;
    }

    public ServerPlacement get(UUID id) {
        return placements.get(id);
    }

    public boolean contains(UUID id) {
        return placements.containsKey(id);
    }

    public Collection<ServerPlacement> getAll() {
        return new ArrayList<>(placements.values());
    }

    public int size() {
        return placements.size();
    }

    public void addPlacement(ServerPlacement placement) {
        placements.put(placement.getId(), placement);
        save();
    }

    public ServerPlacement removePlacement(UUID id) {
        ServerPlacement removed = placements.remove(id);
        if (removed != null) {
            save();
        }
        return removed;
    }

    // ---- persistence ----

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has(KEY_PLACEMENTS)) {
                return;
            }
            JsonArray arr = root.getAsJsonArray(KEY_PLACEMENTS);
            for (var elem : arr) {
                ServerPlacement p = fromJson(elem.getAsJsonObject());
                if (p != null) {
                    placements.put(p.getId(), p);
                }
            }
            logger.info("Loaded " + placements.size() + " placement(s).");
        } catch (Exception e) {
            logger.severe("Failed to load placements.json: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            for (ServerPlacement p : placements.values()) {
                arr.add(toJson(p));
            }
            JsonObject root = new JsonObject();
            root.add(KEY_PLACEMENTS, arr);

            Path tmp = file.resolveSibling(file.getFileName() + ".new");
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, writer);
            }
            if (Files.exists(file)) {
                Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Failed to save placements.json: " + e.getMessage());
        }
    }

    // Vanilla Rotation/Mirror enum names by ordinal, so the JSON matches upstream's placements.json
    // without pulling in NMS enums (we keep rotation/mirror as ints internally).
    private static final String[] ROTATIONS = {"NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"};
    private static final String[] MIRRORS = {"NONE", "LEFT_RIGHT", "FRONT_BACK"};

    private static String rotationName(int ordinal) {
        return ordinal >= 0 && ordinal < ROTATIONS.length ? ROTATIONS[ordinal] : ROTATIONS[0];
    }

    private static String mirrorName(int ordinal) {
        return ordinal >= 0 && ordinal < MIRRORS.length ? MIRRORS[ordinal] : MIRRORS[0];
    }

    private static int ordinalOf(String[] names, com.google.gson.JsonElement element) {
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        String name = element.getAsString();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }

    private JsonObject toJson(ServerPlacement p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getId().toString());
        o.addProperty("hash", p.getHash().toString());
        o.addProperty("file_name", p.getFileName());
        o.addProperty("display_name", p.getDisplayName());
        o.add("owner", playerJson(p.getOwner()));
        if (!p.getOwner().equals(p.getLastModifiedBy())) {
            o.add("lastModifiedBy", playerJson(p.getLastModifiedBy()));
        }
        o.add("origin", originJson(p.getOrigin()));
        o.addProperty("rotation", rotationName(p.getRotation()));
        o.addProperty("mirror", mirrorName(p.getMirror()));
        if (p.getLitematicVersion() > -1) {
            o.addProperty("litematicVersion", p.getLitematicVersion());
        }
        if (p.getDataVersion() > -1) {
            o.addProperty("dataVersion", p.getDataVersion());
        }
        if (p.getSubRegionData().isModified()) {
            JsonArray subs = new JsonArray();
            for (SubRegionModification m : p.getSubRegionData().getModifications().values()) {
                JsonObject s = new JsonObject();
                s.addProperty("name", m.name());
                s.add("position", xyzJson(m.x(), m.y(), m.z()));
                s.addProperty("rotation", rotationName(m.rotation()));
                s.addProperty("mirror", mirrorName(m.mirror()));
                subs.add(s);
            }
            o.add("subregionData", subs);
        }
        return o;
    }

    private ServerPlacement fromJson(JsonObject o) {
        try {
            UUID id = UUID.fromString(o.get("id").getAsString());
            UUID hash = UUID.fromString(o.get("hash").getAsString());
            String fileName = o.get("file_name").getAsString();
            String displayName = o.has("display_name") ? o.get("display_name").getAsString() : fileName;
            PlayerIdentifier owner = playerFromJson(o.getAsJsonObject("owner"));
            ServerPlacement p = new ServerPlacement(id, hash, fileName, displayName, owner);
            if (o.has("lastModifiedBy")) {
                p.setLastModifiedBy(playerFromJson(o.getAsJsonObject("lastModifiedBy")));
            }
            p.setOrigin(originFromJson(o.getAsJsonObject("origin")));
            p.setRotation(ordinalOf(ROTATIONS, o.get("rotation")));
            p.setMirror(ordinalOf(MIRRORS, o.get("mirror")));
            if (o.has("litematicVersion")) {
                p.setLitematicVersion(o.get("litematicVersion").getAsInt());
            }
            if (o.has("dataVersion")) {
                p.setDataVersion(o.get("dataVersion").getAsInt());
            }
            JsonArray subs = o.has("subregionData") ? o.getAsJsonArray("subregionData")
                    : o.has("subRegions") ? o.getAsJsonArray("subRegions") : null;
            if (subs != null) {
                SubRegionData srd = new SubRegionData();
                for (var elem : subs) {
                    JsonObject s = elem.getAsJsonObject();
                    JsonArray pos = s.getAsJsonArray("position");
                    srd.put(new SubRegionModification(
                            s.get("name").getAsString(),
                            pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                            ordinalOf(ROTATIONS, s.get("rotation")), ordinalOf(MIRRORS, s.get("mirror"))));
                }
                p.setSubRegionData(srd);
            }
            return p;
        } catch (Exception e) {
            logger.warning("Skipping malformed placement entry: " + e.getMessage());
            return null;
        }
    }

    private JsonObject playerJson(PlayerIdentifier id) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", id.uuid.toString());
        o.addProperty("name", id.getName());
        return o;
    }

    private PlayerIdentifier playerFromJson(JsonObject o) {
        if (o == null) {
            return PlayerIdentifierProvider.MISSING_PLAYER;
        }
        return players.createOrGet(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString());
    }

    private JsonObject originJson(ServerPosition pos) {
        JsonObject o = new JsonObject();
        o.add("position", xyzJson(pos.x(), pos.y(), pos.z()));
        o.addProperty("dimension", pos.dimension());
        return o;
    }

    private ServerPosition originFromJson(JsonObject o) {
        JsonArray pos = o.getAsJsonArray("position");
        return new ServerPosition(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                o.get("dimension").getAsString());
    }

    private JsonArray xyzJson(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    public List<String> debugList() {
        List<String> out = new ArrayList<>();
        for (ServerPlacement p : placements.values()) {
            out.add(p.getId() + " " + p.getDisplayName());
        }
        return out;
    }
}
