package dev.uas.api;

/**
 * Thrown when a declarative asset declaration is internally inconsistent.
 *
 * <p>Declarations are validated eagerly at build time so that a broken pack is
 * rejected before it ever reaches a client, rather than producing a silently
 * mangled resource pack.
 */
public class UasValidationException extends IllegalArgumentException {

    public UasValidationException(String message) {
        super(message);
    }

    public UasValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public static UasValidationException of(String subject, String problem) {
        return new UasValidationException(subject + ": " + problem);
    }
}
