package fr.palawi.playerdatarescue.core.restore;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.attribution.Confidence;
import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.EntryStatus;
import fr.palawi.playerdatarescue.core.incident.Incident;
import fr.palawi.playerdatarescue.core.incident.IncidentFiles;
import fr.palawi.playerdatarescue.core.incident.IncidentRecorder;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.incident.Trigger;
import fr.palawi.playerdatarescue.core.io.AtomicFiles;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.NbtValue;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A world with S1Bot's player file (fixtures/README.md: slot 17 holds 12 bricks renamed to
 * pdrtest_removed:brass_ingot) and rescues that set that slot aside, for the restore tests. A join
 * here is what the game side does: settle a past record, merge the queue, write the data, report
 * where the join ended.
 */
final class RestoreBench {
    static final UUID PLAYER = UUID.fromString("b6fb3f6f-1d7c-3f14-9b45-4a1f2b0b6a11");
    static final String ITEM = "pdrtest_removed:brass_ingot";

    final Path world;
    final Path root;
    final Path playerFile;
    private final NbtCompound saved;
    private int rescues;

    RestoreBench(Path world) throws IOException {
        this(world, "1.20.1-fabric/S1Bot.dat");
    }

    /** A world whose player file is the fixture {@code fixture}. */
    RestoreBench(Path world, String fixture) throws IOException {
        this.world = world;
        this.root = world.resolve(IncidentStore.FOLDER);
        this.saved = Fixtures.read(fixture);
        this.playerFile = world.resolve("playerdata").resolve(PLAYER + ".dat");
        Files.createDirectories(playerFile.getParent());
        Files.copy(Fixtures.path(fixture), playerFile);
    }

    /** A rescue that set slot 17 aside; the player was saved without it. Returns the incident. */
    String rescueSlot17() throws IOException {
        String incident = rescue("inv.17");
        NbtCompound data = NbtIo.read(playerFile);
        NbtList inventory = data.list("Inventory").orElseThrow();
        for (int i = inventory.size() - 1; i >= 0; i--) {
            if (((NbtCompound) inventory.get(i)).string("id").equals(Optional.of(ITEM))) {
                inventory.remove(i);
            }
        }
        write(data);
        return incident;
    }

    /** A rescue that set the unit {@code unitId} of the fixture aside, the data left as it is. */
    String rescue(String unitId) throws IOException {
        Unit unit = PlayerData.of(saved).units().stream()
                .filter(u -> u.id().equals(unitId))
                .findFirst()
                .orElseThrow();
        Path folder = new IncidentStore(world).create(PLAYER, LocalDateTime.of(2026, 11, 24, 12, 0, rescues++));
        List<String> originals = IncidentStore.backup(folder, List.of(playerFile));
        Exception failure = new IllegalStateException("pdrtest: test failure");
        RescueResult result = new RescueResult(
                Trigger.T1,
                Outcome.RESCUED,
                List.of(new RescueResult.SetAside(
                        unit,
                        failure,
                        List.of(new Suspect("pdrtest", "the mod's data failed to load", Confidence.HIGH)))),
                List.of(),
                List.of(),
                failure,
                2);
        IncidentRecorder.record(
                context(folder, originals), result, saved.number("DataVersion").map(Number::intValue));
        return folder.getFileName().toString();
    }

    IncidentRecorder.Context context(Path folder, List<String> originals) {
        return new IncidentRecorder.Context(
                PLAYER,
                "S1Bot",
                folder,
                Instant.parse("2026-11-24T11:00:00Z"),
                new Incident.Versions("1.20.1", "fabric", "0.20.1", "0.1.0"),
                originals);
    }

    /** The game side before the read: settles a past record, then merges and writes the queue. */
    Optional<RestoreJoin.Prepared> beforeRead() throws IOException {
        RestoreJoin.recover(root, PLAYER, sha());
        Optional<RestoreJoin.Prepared> prepared = RestoreJoin.prepare(
                root, context(world, List.of()), NbtIo.read(playerFile), List.of(playerFile), sha(), "ForgeCaps");
        if (prepared.isPresent()) {
            write(prepared.get().data());
        }
        return prepared;
    }

    /** A join that ends with the player in the world without a rescue; returns the stacks dropped. */
    List<NbtCompound> join() throws IOException {
        beforeRead();
        return end(RestoreJoin.End.joined());
    }

    List<NbtCompound> end(RestoreJoin.End end) throws IOException {
        RestoreJoin.Delivery delivery = RestoreJoin.afterJoin(root, PLAYER, end);
        RestoreJoin.delivered(root, PLAYER);
        return delivery.drops();
    }

    String sha() throws IOException {
        return AtomicFiles.sha256(Files.readAllBytes(playerFile));
    }

    void write(NbtCompound data) throws IOException {
        Files.write(playerFile, NbtIo.write(data, true));
    }

    /** Fills every main inventory slot with stone, so a restored stack has no free slot. */
    void fillInventory() throws IOException {
        NbtCompound data = NbtIo.read(playerFile);
        NbtList inventory = new NbtList(NbtTag.COMPOUND);
        for (int slot = 0; slot < RestoreMerge.INVENTORY_SLOTS; slot++) {
            inventory.add(new NbtCompound()
                    .put("Slot", NbtValue.ofByte((byte) slot))
                    .put("id", NbtValue.ofString("minecraft:stone"))
                    .put("Count", NbtValue.ofByte((byte) 1)));
        }
        data.put("Inventory", inventory);
        write(data);
    }

    /** The stacks of the restored item in the player file, as the game would read them. */
    List<NbtCompound> restoredStacks() throws IOException {
        return NbtIo.read(playerFile).list("Inventory").orElseThrow().elements().stream()
                .map(NbtCompound.class::cast)
                .filter(stack -> stack.string("id").equals(Optional.of(ITEM)))
                .toList();
    }

    EntryStatus status(String incident) throws IOException {
        return entry(incident).status();
    }

    IncidentFiles.Summary summary(String incident) throws IOException {
        return IncidentFiles.read(root.resolve(PLAYER.toString()).resolve(incident));
    }

    IncidentFiles.Entry entry(String incident) throws IOException {
        return IncidentFiles.read(root.resolve(PLAYER.toString()).resolve(incident))
                .entries()
                .get(0);
    }

    long incidents() throws IOException {
        return IncidentFiles.incidents(root, PLAYER).size();
    }
}
