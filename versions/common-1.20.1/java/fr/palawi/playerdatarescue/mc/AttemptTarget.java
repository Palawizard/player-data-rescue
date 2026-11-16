package fr.palawi.playerdatarescue.mc;

import com.mojang.authlib.GameProfile;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.rescue.RescueTarget;
import fr.palawi.playerdatarescue.mixin.ConnectionAccess;
import io.netty.channel.ChannelPipeline;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * One attempt of the 1.20.1 login flow with candidate data: a new {@code ServerPlayer} built with
 * the constructor {@code PlayerList#getPlayerForLogin} uses, without its kick of a player with the
 * same UUID (ADR-PDR-005), then the game's own load or placement.
 */
final class AttemptTarget implements RescueTarget {
    private static final List<String> PACKET_FILTERS = List.of("forge:vanilla_filter", "forge:forge_fixes");

    private final MinecraftServer server;
    private final Connection connection;
    private final GameProfile profile;
    private final DataSource source;

    AttemptTarget(MinecraftServer server, Connection connection, GameProfile profile, DataSource source) {
        this.server = server;
        this.connection = connection;
        this.profile = profile;
        this.source = source;
    }

    @Override
    public void loadOnly(NbtCompound data) {
        source.write(data);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile);
        server.getPlayerList().load(player);
    }

    @Override
    public void place(NbtCompound data) {
        source.write(data);
        forgetPacketFilters();
        PlayerList players = server.getPlayerList();
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile);
        try {
            players.placeNewPlayer(connection, player);
        } catch (RuntimeException e) {
            Join1201.discard(players, player);
            throw e;
        }
    }

    /**
     * Forge 47 adds two packet filters to the channel when it places a player
     * ({@code NetworkFilters#injectIfNecessary}) and throws when they are already there, so a
     * placement after a failed one would always fail. They are removed first; the placement adds
     * them again. Fabric has no such handlers, and there this does nothing.
     */
    private void forgetPacketFilters() {
        ChannelPipeline pipeline =
                ((ConnectionAccess) connection).playerdatarescue$channel().pipeline();
        for (String name : PACKET_FILTERS) {
            if (pipeline.get(name) != null) {
                pipeline.remove(name);
            }
        }
    }
}
