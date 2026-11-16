package fr.palawi.playerdatarescue.core.incident;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * An exception as kept in an incident: class, message, the first stack frames and the "Caused
 * by" chain (SPEC.md section 4.2).
 */
public record ErrorInfo(String exceptionClass, String message, List<String> frames, List<String> causes) {
    public static final int MAX_FRAMES = 15;

    public static ErrorInfo of(Throwable failure) {
        List<String> frames = new ArrayList<>();
        StackTraceElement[] trace = failure.getStackTrace();
        for (int i = 0; i < Math.min(MAX_FRAMES, trace.length); i++) {
            frames.add(trace[i].toString());
        }
        List<String> causes = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        seen.put(failure, true);
        for (Throwable cause = failure.getCause();
                cause != null && seen.put(cause, true) == null;
                cause = cause.getCause()) {
            StackTraceElement[] causeTrace = cause.getStackTrace();
            causes.add(cause.getClass().getName() + ": " + cause.getMessage()
                    + (causeTrace.length > 0 ? " (at " + causeTrace[0] + ")" : ""));
        }
        return new ErrorInfo(failure.getClass().getName(), String.valueOf(failure.getMessage()), frames, causes);
    }

    public String headline() {
        return exceptionClass + ": " + message;
    }
}
