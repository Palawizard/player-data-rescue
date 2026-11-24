package fr.palawi.playerdatarescue.mc;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.rescue.RescueTarget;
import fr.palawi.playerdatarescue.mixin.PlayerListAccess;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A join on 1.21.1, from {@code ServerConfigurationPacketListenerImpl#handleConfigurationFinished}.
 * Each attempt builds its player with {@code PlayerList#getPlayerForLogin}, which on this version
 * only calls the constructor (ADR-PDR-005), and places it with a cookie made by the listener.
 */
public final class Join1211 implements GameJoin {
    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile profile;
    private final ClientInformation clientInformation;
    private final Supplier<CommonListenerCookie> cookie;

    public Join1211(
            MinecraftServer server,
            Connection connection,
            GameProfile profile,
            ClientInformation clientInformation,
            Supplier<CommonListenerCookie> cookie) {
        this.server = server;
        this.connection = connection;
        this.profile = profile;
        this.clientInformation = clientInformation;
        this.cookie = cookie;
    }

    @Override
    public UUID id() {
        return profile.getId();
    }

    @Override
    public String name() {
        return profile.getName();
    }

    @Override
    public Path worldDirectory() {
        return server.getWorldPath(LevelResource.ROOT);
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public DataSource source() {
        CompoundTag host = server.getWorldData().getLoadedPlayerTag();
        if (server.isSingleplayerOwner(profile) && host != null) {
            return new HostData(server.getWorldPath(LevelResource.LEVEL_DATA_FILE), host);
        }
        return new DataSource.PlayerFile(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id() + ".dat"));
    }

    @Override
    public RescueTarget attempts(DataSource source) {
        return new RescueTarget() {
            @Override
            public void loadOnly(NbtCompound data) {
                source.write(data);
                server.getPlayerList().load(server.getPlayerList().getPlayerForLogin(profile, clientInformation));
            }

            @Override
            public void place(NbtCompound data) {
                source.write(data);
                InboundReset.beforePlacement(connection);
                PlayerList players = server.getPlayerList();
                ServerPlayer player = players.getPlayerForLogin(profile, clientInformation);
                HeldPackets.hold(connection);
                try {
                    players.placeNewPlayer(connection, player, cookie.get());
                } catch (RuntimeException | LinkageError e) {
                    discard(players, player);
                    HeldPackets.drop(connection);
                    throw e;
                }
                HeldPackets.release(connection);
            }
        };
    }

    @Override
    public CoreValues.Spawn spawn() {
        BlockPos pos = server.overworld().getSharedSpawnPos();
        return new CoreValues.Spawn(
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                "minecraft:overworld",
                server.levelKeys().stream()
                        .map(key -> key.location().toString())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Override
    public void discardHalfPlaced() {
        ServerPlayer half = server.getPlayerList().getPlayer(id());
        if (half != null) {
            discard(server.getPlayerList(), half);
        }
        HeldPackets.drop(connection);
    }

    /** Removes a half-placed player as the game does when a player leaves, without saving it. */
    private void discard(PlayerList players, ServerPlayer player) {
        RescueService.markFailed(player);
        if (players.getPlayers().contains(player)) {
            players.remove(player);
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
    public void tellOperators(Incident incident, String folder, int permissionLevel) {
        for (ServerPlayer operator : server.getPlayerList().getPlayers()) {
            if (!operator.getUUID().equals(id())
                    && server.getProfilePermissions(operator.getGameProfile()) >= permissionLevel) {
                operator.sendSystemMessage(Notices.forOperators(incident, folder));
            }
        }
    }
}
