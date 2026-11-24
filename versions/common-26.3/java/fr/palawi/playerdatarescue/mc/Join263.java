package fr.palawi.playerdatarescue.mc;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.rescue.RescueTarget;
import fr.palawi.playerdatarescue.mixin.PlayerListAccess;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A join on 26.3, from {@code ServerConfigurationPacketListenerImpl#handleConfigurationFinished}.
 * A placement attempt calls {@code PrepareSpawnTask#spawnPlayer} again: the task stays ready after
 * a spawn, so the spawn chosen in configuration is reused, and each call builds a new player and
 * reads the data file again (ADR-PDR-005). The host's data is a player file too on this version.
 */
public final class Join263 implements GameJoin {
    private static final Logger LOGGER = LoggerFactory.getLogger(Join263.class);

    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile profile;
    private final ClientInformation clientInformation;
    private final Supplier<CommonListenerCookie> cookie;
    private final Supplier<PrepareSpawnTask> spawnTask;

    public Join263(
            MinecraftServer server,
            Connection connection,
            GameProfile profile,
            ClientInformation clientInformation,
            Supplier<CommonListenerCookie> cookie,
            Supplier<PrepareSpawnTask> spawnTask) {
        this.server = server;
        this.connection = connection;
        this.profile = profile;
        this.clientInformation = clientInformation;
        this.cookie = cookie;
        this.spawnTask = spawnTask;
    }

    @Override
    public UUID id() {
        return profile.id();
    }

    @Override
    public String name() {
        return profile.name();
    }

    @Override
    public Path worldDirectory() {
        return server.getWorldPath(LevelResource.ROOT);
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public DataSource source() {
        return source(server, new NameAndId(profile));
    }

    /** Where 26.3 reads a player's data: the player file, which is also the host's. */
    public static DataSource source(MinecraftServer server, NameAndId player) {
        UUID owner = server.getWorldData().getSinglePlayerUUID();
        UUID file = server.isSingleplayerOwner(player) && owner != null ? owner : player.id();
        return new DataSource.PlayerFile(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(file + ".dat"));
    }

    @Override
    public RescueTarget attempts(DataSource source) {
        return new RescueTarget() {
            @Override
            public void loadOnly(NbtCompound data) {
                source.write(data);
                ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, clientInformation);
                try (ProblemReporter.ScopedCollector reporter =
                        new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
                    Optional<CompoundTag> tag = server.getPlayerList().loadPlayerData(new NameAndId(profile));
                    tag.map(t -> TagValueInput.create(reporter, server.registryAccess(), t))
                            .ifPresent(player::load);
                    PlayerLoadingEvent.fire(server, player, tag.isPresent());
                }
            }

            @Override
            public void place(NbtCompound data) {
                source.write(data);
                InboundReset.beforePlacement(connection);
                try {
                    spawnTask.get().spawnPlayer(connection, cookie.get());
                } catch (RuntimeException e) {
                    discardHalfPlaced();
                    throw e;
                }
            }
        };
    }

    @Override
    public CoreValues.Spawn spawn() {
        LevelData.RespawnData respawn = server.getRespawnData();
        BlockPos pos = respawn.pos();
        return new CoreValues.Spawn(
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                respawn.dimension().identifier().toString());
    }

    @Override
    public void discardHalfPlaced() {
        PlayerList players = server.getPlayerList();
        ServerPlayer half = players.getPlayer(id());
        if (half != null) {
            RescueService.markFailed(half);
            players.remove(half);
        }
    }

    @Override
    public boolean placed() {
        return server.getPlayerList().getPlayer(id()) != null;
    }

    @Override
    public void save() {
        ServerPlayer player = server.getPlayerList().getPlayer(id());
        if (player != null) {
            ((PlayerListAccess) server.getPlayerList()).playerdatarescue$save(player);
        }
    }

    @Override
    public void tellPlayer(Outcome outcome) {
        ServerPlayer player = server.getPlayerList().getPlayer(id());
        if (player != null) {
            player.sendSystemMessage(Notices.forPlayer(outcome));
        }
    }

    @Override
    public void tellRestored(int entries) {
        ServerPlayer player = server.getPlayerList().getPlayer(id());
        if (player != null) {
            player.sendSystemMessage(Notices.restored(entries));
        }
    }

    @Override
    public void dropAtFeet(java.util.List<fr.palawi.playerdatarescue.core.nbt.NbtCompound> stacks) {
        ServerPlayer player = server.getPlayerList().getPlayer(id());
        if (player == null) {
            return;
        }
        for (fr.palawi.playerdatarescue.core.nbt.NbtCompound stack : stacks) {
            ItemStack item = ItemStack.CODEC
                    .parse(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), GameNbt.toGame(stack))
                    .result()
                    .orElse(ItemStack.EMPTY);
            if (item.isEmpty()) {
                continue;
            }
            ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), item);
            entity.setNoPickUpDelay();
            player.level().addFreshEntity(entity);
        }
    }

    @Override
    public void tellOperators(Incident incident, String folder, int permissionLevel) {
        for (ServerPlayer operator : server.getPlayerList().getPlayers()) {
            if (!operator.getUUID().equals(id())
                    && server.getProfilePermissions(operator.nameAndId())
                                    .level()
                                    .id()
                            >= permissionLevel) {
                operator.sendSystemMessage(Notices.forOperators(incident, folder));
            }
        }
    }

    @Override
    public int tellOperatorsRecurring(
            fr.palawi.playerdatarescue.core.recurring.RecurringIssues.Issue issue, String file, int permissionLevel) {
        int told = 0;
        for (ServerPlayer operator : server.getPlayerList().getPlayers()) {
            if (!operator.getUUID().equals(id())
                    && server.getProfilePermissions(operator.nameAndId())
                                    .level()
                                    .id()
                            >= permissionLevel) {
                operator.sendSystemMessage(Notices.recurring(issue, file));
                told++;
            }
        }
        return told;
    }
}
