package ch.uemasa.syncmatica.command;

import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.config.PluginConfig;
import ch.uemasa.syncmatica.data.PlayerIdentifier;
import ch.uemasa.syncmatica.data.PlayerIdentifierProvider;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.data.ServerPosition;
import ch.uemasa.syncmatica.util.Checksum;
import ch.uemasa.syncmatica.util.FileNameSanitizer;
import ch.uemasa.syncmatica.util.LitematicPeek;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code /syncmatica} admin command tree: {@code list}, {@code remove}, {@code load}, {@code reload}.
 */
public final class SyncmaticaCommand {

    private final SyncmaticaContext context;

    public SyncmaticaCommand(SyncmaticaContext context) {
        this.context = context;
    }

    public void register(JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var root = Commands.literal("syncmatica")
                    .requires(src -> permitted(src, "syncmatica.command"))
                    .then(Commands.literal("list").executes(this::list))
                    .then(Commands.literal("reload").executes(this::reload))
                    .then(Commands.literal("load").executes(this::loadAll))
                    .then(Commands.literal("remove")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .suggests(this::suggestPlacementIds)
                                    .executes(this::remove)));
            event.registrar().register(root.build(), "Syncmatica server management.");
        });
    }

    /** Completes the {@code remove} id argument with known placement ids, tooltipped by display name. */
    private CompletableFuture<Suggestions> suggestPlacementIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        for (ServerPlacement p : context.syncManager.getAll()) {
            String id = p.getId().toString();
            if (id.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(id, new LiteralTooltip(p.getDisplayName()));
            }
        }
        return builder.buildFuture();
    }

    /** A plain-text Brigadier suggestion tooltip. */
    private record LiteralTooltip(String text) implements com.mojang.brigadier.Message {
        @Override
        public String getString() {
            return text;
        }
    }

    private boolean permitted(CommandSourceStack src, String node) {
        return !context.config.isPermissionsEnforced() || src.getSender().hasPermission(node);
    }

    private int list(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        var placements = context.syncManager.getAll();
        sender.sendMessage(Component.text("Shared syncmatics: " + placements.size(), NamedTextColor.AQUA));
        for (ServerPlacement p : placements) {
            sender.sendMessage(Component.text(" - " + p.getId() + "  " + p.getDisplayName()
                    + "  (" + p.getOwner().getName() + ")", NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        context.plugin.reloadConfig();
        PluginConfig fresh = PluginConfig.load(context.plugin.getConfig());
        context.applyConfig(fresh);
        ctx.getSource().getSender().sendMessage(
                Component.text("Syncmatica config reloaded.", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int remove(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        String raw = StringArgumentType.getString(ctx, "id");
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Invalid UUID: " + raw, NamedTextColor.RED));
            return 0;
        }
        ServerPlacement removed = context.syncManager.removePlacement(id);
        if (removed == null) {
            sender.sendMessage(Component.text("No placement with id " + id, NamedTextColor.RED));
            return 0;
        }
        context.comms().releaseModifyLock(id);
        try {
            context.fileStorage.delete(removed);
        } catch (Exception e) {
            context.logger.warning("Failed to delete file for " + id + ": " + e.getMessage());
        }
        context.comms().broadcastRemove(id);
        sender.sendMessage(Component.text("Removed " + id, NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int loadAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var sender = source.getSender();
        Path importDir = context.plugin.getDataFolder().toPath().resolve("import");
        ServerPosition origin = originOf(source.getLocation());
        PlayerIdentifier owner = source.getExecutor() instanceof Player player
                ? context.players.createOrGet(player.getUniqueId(), player.getName())
                : PlayerIdentifierProvider.MISSING_PLAYER;

        int count = 0;
        try {
            Files.createDirectories(importDir);
            Files.createDirectories(context.fileStorage.getFolder());
            try (DirectoryStream<Path> files = Files.newDirectoryStream(importDir, "*.litematic")) {
                for (Path file : files) {
                    UUID hash;
                    try (InputStream in = Files.newInputStream(file)) {
                        hash = Checksum.ofStream(in);
                    }
                    String fileName = FileNameSanitizer.normalize(file.getFileName().toString());
                    String fallback = fileName.endsWith(".litematic")
                            ? fileName.substring(0, fileName.length() - ".litematic".length())
                            : fileName;
                    LitematicPeek.Info info = null;
                    try {
                        info = LitematicPeek.read(file);
                    } catch (Exception e) {
                        context.logger.warning("Could not read litematic metadata from " + fileName + ": " + e.getMessage());
                    }
                    String display = info != null && info.name() != null && !info.name().isBlank()
                            ? info.name() : fallback;
                    ServerPlacement placement = new ServerPlacement(UUID.randomUUID(), hash, fileName, display, owner);
                    if (info != null) {
                        placement.setLitematicVersion(info.litematicVersion());
                        placement.setDataVersion(info.dataVersion());
                    }
                    placement.setOrigin(origin);
                    Files.copy(file, context.fileStorage.getFile(placement), StandardCopyOption.REPLACE_EXISTING);
                    context.comms().publishPlacement(placement);
                    count++;
                }
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text("Load failed: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text("Loaded " + count + " syncmatic file(s) from import/.",
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private ServerPosition originOf(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return new ServerPosition(0, 0, 0, "minecraft:overworld");
        }
        return new ServerPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                loc.getWorld().getKey().toString());
    }
}
