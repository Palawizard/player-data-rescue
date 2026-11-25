package fr.palawi.playerdatarescue.mc;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.rescue.RescueTarget;
import fr.palawi.playerdatarescue.mixin.PlayerListAccess;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** A join on 1.20.1, from {@code ServerLoginPacketListenerImpl#handleAcceptedLogin}. */
public final class Join1201 implements GameJoin {
    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile profile;

    public Join1201(MinecraftServer server, Connection connection, GameProfile profile) {
        this.server = server;
        this.connection = connection;
        this.profile = profile;
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
        return new AttemptTarget(server, connection, profile, source);
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
    static void discard(PlayerList players, ServerPlayer player) {
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
            ItemStack item = ItemStack.of(GameNbt.toGame(stack));
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
                    && server.getProfilePermissions(operator.getGameProfile()) >= permissionLevel) {
                operator.sendSystemMessage(Notices.forOperators(incident, folder));
            }
        }
    }
}
