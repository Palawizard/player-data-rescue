package fr.palawi.playerdatarescue.mc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.palawi.playerdatarescue.core.ModInfo;
import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.recurring.RecurringIssues;
import fr.palawi.playerdatarescue.core.restore.RestorePending;
import fr.palawi.playerdatarescue.core.restore.RestoreQueue;
import fr.palawi.playerdatarescue.core.restore.Restorer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /pdrescue} (SPEC.md section 7). The incident files are the only state: every command
 * reads them again, so a command sees what the rescue and earlier commands wrote. Player
 * arguments take an online player's name, or the name or UUID of a player with incidents.
 */
public final class PdrCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);
    private static final String ROOT = "pdrescue";

    private PdrCommands() {}

    /** A player of the store: the UUID the incidents are filed under and the name to show. */
    private record Player(UUID id, String name, Optional<ServerPlayer> online) {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> Texts.allowed(source, RescueService.config().commandPermissionLevel()))
                .then(Commands.literal("list")
                        .executes(c -> list(c, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PLAYERS)
                                .executes(c -> list(c, StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("show").then(incidentArguments(PdrCommands::show)))
                .then(Commands.literal("restore")
                        .then(incidentArguments(
                                c -> restore(c, "all"),
                                entryArgument(c -> restore(c, StringArgumentType.getString(c, "entry"))))))
                .then(Commands.literal("dismiss")
                        .then(Commands.literal("recurring")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .suggests(RECURRING_KEYS)
                                        .executes(c -> guarded(c, PdrCommands::dismissRecurring))))
                        .then(incidentArguments(
                                c -> dismiss(c, "all"),
                                entryArgument(c -> dismiss(c, StringArgumentType.getString(c, "entry"))))))
                .then(Commands.literal("export").then(incidentArguments(PdrCommands::export))));
    }

    private interface Action {
        int run(CommandContext<CommandSourceStack> context) throws IOException;
    }

    /** {@code <player> <incident>}, running {@code action} when nothing follows. */
    private static ArgumentBuilder<CommandSourceStack, ?> incidentArguments(Action action) {
        return incidentArguments(action, null);
    }

    /** {@code <player> <incident> [next]}: {@code next} follows the incident. */
    private static ArgumentBuilder<CommandSourceStack, ?> incidentArguments(
            Action action, ArgumentBuilder<CommandSourceStack, ?> next) {
        ArgumentBuilder<CommandSourceStack, ?> incident = Commands.argument("incident", StringArgumentType.word())
                .suggests(INCIDENTS)
                .executes(c -> guarded(c, action));
        if (next != null) {
            incident.then(next);
        }
        return Commands.argument("player", StringArgumentType.word())
                .suggests(PLAYERS)
                .then(incident);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> entryArgument(Command<CommandSourceStack> command) {
        return Commands.argument("entry", StringArgumentType.greedyString())
                .suggests(ENTRIES)
                .executes(command);
    }

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (context, builder) -> {
        List<String> names = new ArrayList<>();
        context.getSource().getServer().getPlayerList().getPlayers().forEach(p -> names.add(p.getScoreboardName()));
        try {
            for (UUID id : IncidentFiles.players(root(context.getSource().getServer()))) {
                names.add(id.toString());
            }
        } catch (IOException e) {
            LOGGER.debug("[{}] no player suggestions", ModInfo.MOD_ID, e);
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> RECURRING_KEYS = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        try {
            RecurringService.issues(world(context.getSource().getServer()))
                    .all()
                    .forEach(i -> keys.add(i.key()));
        } catch (IOException e) {
            LOGGER.debug("[{}] no recurring key suggestions", ModInfo.MOD_ID, e);
        }
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> INCIDENTS = (context, builder) -> {
        List<String> folders = new ArrayList<>();
        try {
            Optional<Player> player = player(context, StringArgumentType.getString(context, "player"));
            if (player.isPresent()) {
                for (Path incident : IncidentFiles.incidents(
                        root(context.getSource().getServer()), player.get().id())) {
                    folders.add(incident.getFileName().toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[{}] no incident suggestions", ModInfo.MOD_ID, e);
        }
        return SharedSuggestionProvider.suggest(folders, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> ENTRIES = (context, builder) -> {
        List<String> ids = new ArrayList<>(List.of("all"));
        try {
            Optional<Player> player = player(context, StringArgumentType.getString(context, "player"));
            if (player.isPresent()) {
                Path folder = root(context.getSource().getServer())
                        .resolve(player.get().id().toString())
                        .resolve(StringArgumentType.getString(context, "incident"));
                IncidentFiles.read(folder).entries().forEach(e -> ids.add(e.id()));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[{}] no entry suggestions", ModInfo.MOD_ID, e);
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static int guarded(CommandContext<CommandSourceStack> context, Action action) {
        try {
            return action.run(context);
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[{}] /{} failed", ModInfo.MOD_ID, ROOT, e);
            context.getSource()
                    .sendFailure(Component.literal("The incident files could not be read; see the server log."));
            return 0;
        }
    }

    private static int list(CommandContext<CommandSourceStack> context, String argument) {
        return guarded(context, c -> {
            Path root = root(c.getSource().getServer());
            List<UUID> players;
            if (argument == null) {
                players = IncidentFiles.players(root);
                if (players.isEmpty()) {
                    say(c, Component.literal("No player has an incident."));
                    return 1;
                }
            } else {
                players = List.of(require(c, argument).id());
            }
            for (UUID id : players) {
                List<Path> incidents = IncidentFiles.incidents(root, id);
                if (incidents.isEmpty()) {
                    continue;
                }
                List<IncidentFiles.Summary> summaries = new ArrayList<>();
                for (Path incident : incidents) {
                    summaries.add(IncidentFiles.read(incident));
                }
                String name = summaries.get(summaries.size() - 1).playerName();
                long aside = summaries.stream()
                        .mapToLong(s -> s.count(EntryStatus.QUARANTINED))
                        .sum();
                int waiting = RestoreQueue.read(root, id).size();
                MutableComponent header = Component.literal(name + " (" + id + "): "
                                + count(incidents.size(), "incident")
                                + ", " + count(aside, "part") + " set aside"
                                + (waiting > 0 ? ", " + count(waiting, "entry") + " to restore at the next join" : ""))
                        .withStyle(ChatFormatting.GOLD);
                say(c, header);
                if (argument == null) {
                    continue;
                }
                for (IncidentFiles.Summary s : summaries) {
                    say(
                            c,
                            Component.literal("  " + s.folder() + " " + s.trigger() + ", "
                                            + s.outcome().replace('_', ' ') + ": " + statusCounts(s) + " ")
                                    .append(Texts.action("show", "/" + ROOT + " show " + id + " " + s.folder())));
                }
            }
            List<RecurringIssues.Issue> issues = new ArrayList<>();
            for (RecurringIssues.Issue issue :
                    RecurringService.issues(world(c.getSource().getServer())).all()) {
                if (argument == null || issue.players().containsKey(players.get(0))) {
                    issues.add(issue);
                }
            }
            if (!issues.isEmpty()) {
                say(c, Component.literal("Recurring issues: " + issues.size()).withStyle(ChatFormatting.GOLD));
                for (RecurringIssues.Issue issue : issues) {
                    say(
                            c,
                            Component.literal("  " + issue.key() + " ("
                                            + issue.suspect().orElse("no mod stands out") + "): "
                                            + count(issue.count(), "failed join") + ", "
                                            + String.join(", ", issue.players().values())
                                            + ", last " + issue.last() + " ")
                                    .append(Texts.action("dismiss", "/" + ROOT + " dismiss recurring " + issue.key())));
                }
            }
            if (argument == null) {
                say(c, Component.literal("Details: /" + ROOT + " list <player>"));
            }
            return players.size();
        });
    }

    private static int show(CommandContext<CommandSourceStack> context) throws IOException {
        Player player = require(context, StringArgumentType.getString(context, "player"));
        String incident = StringArgumentType.getString(context, "incident");
        Path folder = incidentFolder(context, player, incident);
        IncidentFiles.Summary s = IncidentFiles.read(folder);
        say(context, Component.literal(s.summary()).withStyle(ChatFormatting.GOLD));
        say(
                context,
                Component.literal("Incident " + s.folder() + " of " + s.playerName() + ", " + s.time() + ", trigger "
                        + s.trigger() + ", " + s.outcome().replace('_', ' ') + "."));
        Path root = root(context.getSource().getServer());
        List<RestoreQueue.Item> queue = RestoreQueue.read(root, player.id());
        Optional<RestorePending> pending = RestorePending.read(root, player.id());
        for (IncidentFiles.Entry e : s.entries()) {
            RestoreQueue.Item item = new RestoreQueue.Item(incident, e.id());
            boolean inFlight = pending.isPresent() && pending.get().holds(item);
            boolean waiting = queue.contains(item) || inFlight;
            String status = inFlight
                    ? "going back into the data at the current join"
                    : waiting
                            ? "to restore at the next join"
                            : e.status().json().replace('_', ' ');
            MutableComponent line = Component.literal("- " + e.id() + ": " + e.description() + " (" + status
                    + e.statusReason().map(r -> ": " + r).orElse("") + ") ");
            if (e.status().restorable() && !waiting && e.payload().isPresent()) {
                line.append(Texts.action(
                        "restore", "/" + ROOT + " restore " + player.id() + " " + incident + " " + e.id()));
            }
            if (e.status().dismissable() && !inFlight) {
                line.append(" ")
                        .append(Texts.action(
                                "dismiss", "/" + ROOT + " dismiss " + player.id() + " " + incident + " " + e.id()));
            }
            say(context, line);
        }
        say(context, Component.literal("Next step: " + s.nextStep()));
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> context, String entry) {
        return guarded(context, c -> {
            Player player = require(c, StringArgumentType.getString(c, "player"));
            String incident = StringArgumentType.getString(c, "incident");
            incidentFolder(c, player, incident);
            MinecraftServer server = c.getSource().getServer();
            Restorer.Queued queued = Restorer.queue(root(server), player.id(), incident, entry);
            if (!queued.queued().isEmpty()) {
                RestoreService.queued(world(server), player.id());
                say(
                        c,
                        Component.literal(count(queued.queued().size(), "entry") + " of " + player.name()
                                        + " will be restored at their next join: " + String.join(", ", queued.queued())
                                        + ".")
                                .withStyle(ChatFormatting.GREEN));
                LOGGER.warn(
                        "[{}] {} queued the restore of {} for {}: {}",
                        ModInfo.MOD_ID,
                        c.getSource().getTextName(),
                        incident,
                        player.name(),
                        queued.queued());
                if (!RescueService.active()) {
                    say(
                            c,
                            Component.literal("The mod is turned off in its config: the entries go back at the first"
                                            + " join after it is turned on again.")
                                    .withStyle(ChatFormatting.YELLOW));
                }
                player.online()
                        .ifPresent(online -> online.sendSystemMessage(Component.translatableWithFallback(
                                        "playerdatarescue.player.restore_queued",
                                        "An admin restored some of your saved data. Leave and join again to get it back.")
                                .withStyle(ChatFormatting.YELLOW)));
            }
            if (!queued.alreadyRestored().isEmpty()) {
                say(
                        c,
                        Component.literal("Already restored, nothing to do: "
                                + String.join(", ", queued.alreadyRestored()) + "."));
            }
            queued.notRestorable()
                    .forEach((id, reason) -> say(c, Component.literal("Cannot restore " + id + ": " + reason + ".")));
            if (queued.queued().isEmpty()
                    && queued.alreadyRestored().isEmpty()
                    && queued.notRestorable().isEmpty()) {
                say(c, Component.literal("Nothing to restore in " + incident + "."));
            }
            return queued.queued().size();
        });
    }

    private static int dismiss(CommandContext<CommandSourceStack> context, String entry) {
        return guarded(context, c -> {
            Player player = require(c, StringArgumentType.getString(c, "player"));
            String incident = StringArgumentType.getString(c, "incident");
            incidentFolder(c, player, incident);
            List<String> dismissed = Restorer.dismiss(root(c.getSource().getServer()), player.id(), incident, entry);
            say(
                    c,
                    Component.literal(
                            dismissed.isEmpty()
                                    ? "Nothing to dismiss in " + incident + "."
                                    : "Dismissed " + count(dismissed.size(), "entry") + " of " + player.name() + ": "
                                            + String.join(", ", dismissed)
                                            + ". The data stays in the incident folder."));
            return dismissed.size();
        });
    }

    /** Closes a recurring issue: its record moves to recurring/closed/ with its payloads. */
    private static int dismissRecurring(CommandContext<CommandSourceStack> context) throws IOException {
        String key = StringArgumentType.getString(context, "key");
        RecurringIssues issues =
                RecurringService.issues(world(context.getSource().getServer()));
        RecurringIssues.Issue issue =
                issues.find(key).orElseThrow(() -> new IllegalArgumentException("No recurring issue for " + key + "."));
        issues.close(issue, java.time.Instant.now());
        say(
                context,
                Component.literal("Closed the recurring issue " + key + ". Its record is kept in "
                        + IncidentStore.FOLDER + "/" + RecurringIssues.FOLDER + "/closed/."));
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> context) throws IOException {
        Player player = require(context, StringArgumentType.getString(context, "player"));
        Path folder = incidentFolder(context, player, StringArgumentType.getString(context, "incident"));
        say(
                context,
                Component.literal("Incident folder: " + folder.toAbsolutePath().normalize()));
        return 1;
    }

    private static Optional<Player> player(CommandContext<CommandSourceStack> context, String argument)
            throws IOException {
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(argument);
        if (online != null) {
            return Optional.of(new Player(online.getUUID(), online.getScoreboardName(), Optional.of(online)));
        }
        Path root = root(server);
        Optional<UUID> id = IncidentFiles.findPlayer(root, argument);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        List<Path> incidents = IncidentFiles.incidents(root, id.get());
        String name = incidents.isEmpty()
                ? argument
                : IncidentFiles.read(incidents.get(incidents.size() - 1)).playerName();
        return Optional.of(new Player(
                id.get(), name, Optional.ofNullable(server.getPlayerList().getPlayer(id.get()))));
    }

    private static Player require(CommandContext<CommandSourceStack> context, String argument) throws IOException {
        return player(context, argument)
                .orElseThrow(() -> new IllegalArgumentException("No incident for " + argument + "."));
    }

    private static Path incidentFolder(CommandContext<CommandSourceStack> context, Player player, String incident) {
        try {
            return Restorer.incidentFolder(root(context.getSource().getServer()), player.id(), incident);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(player.name() + " has no incident " + incident + ".", e);
        }
    }

    private static String statusCounts(IncidentFiles.Summary s) {
        List<String> parts = new ArrayList<>();
        for (EntryStatus status : EntryStatus.values()) {
            long n = s.count(status);
            if (n > 0) {
                parts.add(n + " " + status.json().replace('_', ' '));
            }
        }
        return parts.isEmpty() ? "nothing set aside" : String.join(", ", parts);
    }

    private static String count(long n, String noun) {
        String plural = noun.endsWith("y") ? noun.substring(0, noun.length() - 1) + "ies" : noun + "s";
        return n + " " + (n == 1 ? noun : plural);
    }

    private static void say(CommandContext<CommandSourceStack> context, Component message) {
        context.getSource().sendSuccess(() -> message, false);
    }

    private static Path world(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    private static Path root(MinecraftServer server) {
        return new IncidentStore(world(server)).root();
    }
}
