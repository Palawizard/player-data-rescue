package fr.palawi.playerdatarescue.core.attribution;

/** A mod that most likely caused a failure (SPEC.md section 5). */
public record Suspect(String modId, String reason, Confidence confidence) {
    public static final String NOT_INSTALLED = "mod not installed";
    public static final String DATA_FAILED = "the mod's data failed to load";
    public static final String IN_STACK_TRACE = "its code is in the stack trace";
    public static final String MIXIN_IN_STACK_TRACE = "its mixin changed a game method in the stack trace";
}
