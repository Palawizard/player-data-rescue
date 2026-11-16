package fr.palawi.playerdatarescue.core.incident;

import fr.palawi.playerdatarescue.core.attribution.Suspect;
import fr.palawi.playerdatarescue.core.data.Unit;
import fr.palawi.playerdatarescue.core.nbt.NbtCompound;
import fr.palawi.playerdatarescue.core.nbt.NbtList;
import fr.palawi.playerdatarescue.core.nbt.NbtTag;
import fr.palawi.playerdatarescue.core.nbt.Snbt;
import fr.palawi.playerdatarescue.core.rescue.RescueResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Turns what a rescue did into the files of its incident folder. */
public final class IncidentRecorder {
    private IncidentRecorder() {}

    /** Who and what the incident is about, known before the rescue runs. */
    public record Context(
            UUID playerId,
            String playerName,
            Path folder,
            Instant time,
            Incident.Versions versions,
            List<String> originals) {}

    /** Writes the payloads and reports of a rescue or diagnosis, and returns the incident. */
    public static Incident record(Context context, RescueResult result, Optional<Integer> dataVersion)
            throws IOException {
        List<QuarantineEntry> entries = new ArrayList<>();
        List<String> payloadNames = new ArrayList<>();
        for (RescueResult.SetAside aside : result.aside()) {
            Unit unit = aside.unit();
            Optional<String> payload =
                    IncidentStore.writePayload(context.folder(), unit.id(), unit.payload(), dataVersion, payloadNames);
            entries.add(new QuarantineEntry(
                    unit.id(),
                    unit.section(),
                    unit.slot(),
                    describe(unit),
                    payload.isPresent() ? Snbt.write(unit.payload()) : "",
                    ErrorInfo.of(aside.failure()),
                    aside.suspects(),
                    EntryStatus.QUARANTINED,
                    payload,
                    payload.isEmpty()));
        }
        Incident incident = new Incident(
                context.playerId(),
                context.playerName(),
                context.folder().getFileName().toString(),
                context.time(),
                context.versions(),
                result.trigger(),
                result.outcome(),
                context.originals(),
                entries,
                result.coreChanges(),
                result.suspects(),
                result.lastFailure() == null ? null : ErrorInfo.of(result.lastFailure()));
        IncidentStore.writeReports(context.folder(), incident);
        return incident;
    }

    /** Writes the reports of an unreadable file (T2), which sets nothing aside. */
    public static Incident recordUnreadable(Context context, Outcome outcome, Throwable parseError) throws IOException {
        Incident incident = new Incident(
                context.playerId(),
                context.playerName(),
                context.folder().getFileName().toString(),
                context.time(),
                context.versions(),
                Trigger.T2,
                outcome,
                context.originals(),
                List.of(),
                List.of(),
                List.<Suspect>of(),
                ErrorInfo.of(parseError));
        IncidentStore.writeReports(context.folder(), incident);
        return incident;
    }

    /** A unit in plain words, for example "Inventory slot 17: 12 x create:brass_ingot". */
    static String describe(Unit unit) {
        if (unit.id().startsWith("section.")) {
            return wholeSection(unit);
        }
        NbtTag payload = unit.payload();
        String slot = unit.slot().orElse("?");
        return switch (unit.section()) {
            case INVENTORY -> "Inventory slot " + slot + ": " + stack(payload);
            case ENDER_CHEST -> "Ender chest slot " + slot + ": " + stack(payload);
            case EQUIPMENT -> "Equipment " + slot + ": " + stack(payload);
            case EFFECTS -> "Effect " + unit.id().substring("effect.".length());
            case ATTACHMENTS -> "Attachment " + unit.id().substring("attachment.".length());
            case VEHICLE -> "Vehicle" + vehicle(payload);
            case RECIPE_BOOK -> "Recipe book";
            case UNKNOWN_KEY -> "Mod data under the key " + unit.id().substring("key.".length());
            case CORE -> "Core values";
        };
    }

    /** A section set aside whole, by the minimal profile. */
    private static String wholeSection(Unit unit) {
        String key = unit.id().substring("section.".length());
        int size = unit.payload() instanceof NbtList list
                ? list.size()
                : unit.payload() instanceof NbtCompound compound ? compound.size() : 1;
        return switch (unit.section()) {
            case INVENTORY -> "Whole inventory: " + count(size, "item");
            case ENDER_CHEST -> "Whole ender chest: " + count(size, "item");
            case EQUIPMENT -> "All equipment: " + count(size, "item");
            case EFFECTS -> "All effects: " + count(size, "effect");
            case ATTACHMENTS -> "All mod attachments (" + key + ")";
            case VEHICLE -> "Vehicle" + vehicle(unit.payload());
            case RECIPE_BOOK -> "Recipe book";
            case UNKNOWN_KEY, CORE -> "Saved data under the key " + key;
        };
    }

    private static String count(int size, String noun) {
        return size + " " + noun + (size == 1 ? "" : "s");
    }

    private static String stack(NbtTag payload) {
        if (payload instanceof NbtCompound stack) {
            String id = stack.string("id").orElse("unknown item");
            int count = stack.number("count")
                    .or(() -> stack.number("Count"))
                    .map(Number::intValue)
                    .orElse(1);
            return count + " x " + id;
        }
        return payload.toString();
    }

    private static String vehicle(NbtTag payload) {
        if (payload instanceof NbtCompound vehicle) {
            return vehicle.compound("Entity")
                    .flatMap(entity -> entity.string("id"))
                    .map(id -> " " + id)
                    .orElse("");
        }
        return "";
    }
}
