package fr.palawi.playerdatarescue.core.rescue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.playerdatarescue.core.Fixtures;
import fr.palawi.playerdatarescue.core.attribution.ModResolver;
import fr.palawi.playerdatarescue.core.data.CoreValues;
import fr.palawi.playerdatarescue.core.data.PlayerData;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.incident.IncidentStore;
import fr.palawi.playerdatarescue.core.incident.Outcome;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The part of SPEC.md scenario S8 that does not need a game client: the singleplayer host's data
 * in a real {@code level.dat} (see fixtures/README.md), its backup and its rescue.
 */
class HostDataTest {
    @TempDir
    Path world;

    @ParameterizedTest
    @ValueSource(strings = {"1.20.1-fabric", "1.21.1-fabric"})
    void theHostDataInLevelDatIsBackedUpAndRescuedLikeAPlayerFile(String band) throws Exception {
        Path levelDat = world.resolve("level.dat");
        Files.copy(Fixtures.path(band + "/level.dat"), levelDat);
        Files.copy(Fixtures.path(band + "/level.dat_old"), world.resolve("level.dat_old"));

        NbtCompound host = SourceFiles.hostPlayer(NbtIo.read(levelDat)).orElseThrow();
        List<String> keys = PlayerData.of(host).units().stream().map(Unit::id).toList();
        assertTrue(keys.contains("key.pdrtest:broken"), keys.toString());

        Path incident = new IncidentStore(world).create(UUID.randomUUID(), LocalDateTime.of(2026, 11, 16, 15, 0));
        List<String> copied = IncidentStore.backup(incident, List.of(levelDat, SourceFiles.old(levelDat)));
        assertEquals(List.of("level.dat", "level.dat_old"), copied);
        assertEquals(-1L, Files.mismatch(levelDat, incident.resolve("original/level.dat")));

        RescueTarget target = new RescueTarget() {
            @Override
            public void loadOnly(NbtCompound data) {
                if (data.contains("pdrtest:broken")) {
                    throw new IllegalStateException("pdrtest: deliberate failure: reading attachment pdrtest:broken");
                }
            }

            @Override
            public void place(NbtCompound data) {}
        };
        ModResolver mods = new ModResolver() {
            @Override
            public Optional<String> modOfClass(String className) {
                return Optional.empty();
            }

            @Override
            public Optional<String> mixinOwner(StackTraceElement frame) {
                return Optional.empty();
            }

            @Override
            public boolean isInstalled(String modId) {
                return false;
            }
        };
        RescueFlow flow = new RescueFlow(target, mods, new CoreValues.Spawn(0.5, 64, 0.5, "minecraft:overworld"));

        RescueResult result = flow.run(host, flow.classify(host), new IllegalStateException("join failed"));

        assertEquals(Outcome.RESCUED, result.outcome());
        assertEquals(
                List.of("key.pdrtest:broken"),
                result.aside().stream().map(a -> a.unit().id()).toList());
    }
}
