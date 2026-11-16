package fr.palawi.playerdatarescue.core.attribution;

import fr.palawi.playerdatarescue.core.ModInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Ranks suspected mods from failing ids and stack frames (SPEC.md section 5). */
public final class Attribution {
    public static final int MAX_SUSPECTS = 3;

    /** Mods that are never the cause: this mod, the loaders and the game itself. */
    private static final Set<String> IGNORED =
            Set.of(ModInfo.MOD_ID, "minecraft", "java", "forge", "neoforge", "fabricloader", "fml", "mixinextras");

    private Attribution() {}

    /**
     * Every frame of the failure and of its causes, innermost cause first: that is where the
     * failure started, while the outer exceptions only show where it was caught and wrapped.
     */
    public static List<Frame> frames(Throwable failure, ModResolver mods) {
        List<Throwable> chain = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = failure; t != null && seen.put(t, true) == null; t = t.getCause()) {
            chain.add(0, t);
        }
        List<Frame> frames = new ArrayList<>();
        for (Throwable t : chain) {
            for (StackTraceElement element : t.getStackTrace()) {
                frames.add(new Frame(
                        element.getClassName(),
                        element.getMethodName(),
                        mods.modOfClass(element.getClassName()),
                        mods.mixinOwner(element)));
            }
        }
        return frames;
    }

    /**
     * Suspects for one failure. {@code namespaces} are the namespaces of the failing unit ids, if
     * any are known. {@code frames} come from {@link #frames}.
     */
    public static List<Suspect> rank(Collection<String> namespaces, List<Frame> frames, ModResolver mods) {
        Map<String, Suspect> suspects = new LinkedHashMap<>();
        for (String namespace : namespaces) {
            if (!IGNORED.contains(namespace)) {
                String reason = mods.isInstalled(namespace) ? Suspect.DATA_FAILED : Suspect.NOT_INSTALLED;
                suspects.putIfAbsent(namespace, new Suspect(namespace, reason, Confidence.HIGH));
            }
        }
        firstModFrame(frames)
                .ifPresent(
                        mod -> suspects.putIfAbsent(mod, new Suspect(mod, Suspect.IN_STACK_TRACE, Confidence.MEDIUM)));
        for (Frame frame : frames) {
            Optional<String> owner = frame.mixinModId().filter(mod -> !IGNORED.contains(mod));
            owner.ifPresent(
                    mod -> suspects.putIfAbsent(mod, new Suspect(mod, Suspect.MIXIN_IN_STACK_TRACE, Confidence.LOW)));
        }
        return suspects.values().stream().limit(MAX_SUSPECTS).toList();
    }

    /** Merges ranked lists, keeping the strongest reason per mod and at most three mods. */
    public static List<Suspect> merge(Collection<List<Suspect>> lists) {
        Map<String, Suspect> best = new LinkedHashMap<>();
        for (List<Suspect> list : lists) {
            for (Suspect suspect : list) {
                best.merge(suspect.modId(), suspect, (a, b) -> a.confidence().compareTo(b.confidence()) <= 0 ? a : b);
            }
        }
        return best.values().stream()
                .sorted((a, b) -> a.confidence().compareTo(b.confidence()))
                .limit(MAX_SUSPECTS)
                .toList();
    }

    private static Optional<String> firstModFrame(List<Frame> frames) {
        for (Frame frame : frames) {
            Optional<String> mod = frame.modId().filter(id -> !IGNORED.contains(id));
            if (mod.isPresent()) {
                return mod;
            }
        }
        return Optional.empty();
    }
}
