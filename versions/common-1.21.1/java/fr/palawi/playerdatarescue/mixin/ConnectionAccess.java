package fr.palawi.playerdatarescue.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The connection's netty channel, to put its pipeline back before another placement attempt. */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("channel")
    Channel playerdatarescue$channel();
}
