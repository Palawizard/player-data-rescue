package fr.palawi.playerdatarescue.core.data;

import java.util.Set;

/**
 * Top-level keys of a player file that the game or a loader writes itself, on any Tier A version.
 * Taken from the save methods of {@code Entity}, {@code LivingEntity}, {@code Player},
 * {@code ServerPlayer}, {@code Abilities} and {@code FoodData} of 1.20.1, 1.21.1 and 26.3, and
 * checked against files saved by those servers (ARCHITECTURE.md, Salvage). Any other key belongs
 * to a mod and is an opaque unit.
 */
final class VanillaKeys {
    private static final Set<String> KEYS = Set.of(
            // Entity
            "Air",
            "CustomName",
            "CustomNameVisible",
            "FallDistance",
            "fall_distance",
            "Fire",
            "Glowing",
            "HasVisualFire",
            "Invulnerable",
            "invulnerable_time",
            "Motion",
            "NoGravity",
            "OnGround",
            "Passengers",
            "PortalCooldown",
            "Pos",
            "Rotation",
            "Silent",
            "Tags",
            "TicksFrozen",
            "UUID",
            "data",
            "DataVersion",
            // LivingEntity
            "AbsorptionAmount",
            "ActiveEffects",
            "active_effects",
            "Attributes",
            "attributes",
            "Brain",
            "DeathTime",
            "FallFlying",
            "Health",
            "HurtByTimestamp",
            "HurtTime",
            "SleepingX",
            "SleepingY",
            "SleepingZ",
            "sleeping_pos",
            "equipment",
            "last_hurt_by_mob",
            "last_hurt_by_player",
            "last_hurt_by_player_memory_time",
            "locator_bar_icon",
            "ticks_since_last_hurt_by_mob",
            "current_explosion_impact_pos",
            "current_impulse_context_reset_grace_time",
            "ignore_fall_damage_from_current_explosion",
            // Player
            "EnderItems",
            "Inventory",
            "LastDeathLocation",
            "Score",
            "SelectedItemSlot",
            "ShoulderEntityLeft",
            "ShoulderEntityRight",
            "SleepTimer",
            "XpLevel",
            "XpP",
            "XpSeed",
            "XpTotal",
            "abilities",
            "foodExhaustionLevel",
            "foodLevel",
            "foodSaturationLevel",
            "foodTickTimer",
            "warden_spawn_tracker",
            // ServerPlayer
            "Dimension",
            "RootVehicle",
            "SpawnAngle",
            "SpawnDimension",
            "SpawnForced",
            "SpawnX",
            "SpawnY",
            "SpawnZ",
            "enteredNetherPosition",
            "entered_nether_pos",
            "ender_pearls",
            "last_explosion_impact_pos",
            "playerGameType",
            "post_effects",
            "previousPlayerGameType",
            "raid_omen_position",
            "recipeBook",
            "respawn",
            "seenCredits",
            "spawn_extra_particles_on_fall",
            // Loaders: Forge capabilities, persistent data and update flag, NeoForge attachments and
            // persistent data (Entity save patches of Forge 47, 52, 66 and NeoForge 21.1, 26.3)
            "CanUpdate",
            "ForgeCaps",
            "ForgeData",
            "neoforge:attachments",
            "NeoForgeData");

    private VanillaKeys() {}

    static boolean isKnown(String key) {
        return KEYS.contains(key);
    }
}
