package dev.uas.protocol;

/**
 * Thrown when a UAS frame or packet cannot be decoded.
 *
 * <p>Every decode path converts hostile input into this exception rather than
 * leaking an {@link IndexOutOfBoundsException} or allocating based on a
 * client-supplied length. Callers treat it as "drop the payload", never as a
 * server-fatal error.
 */
public class UasProtocolException extends RuntimeException {

    public UasProtocolException(String message) {
        super(message);
    }

    public UasProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public static UasProtocolException truncated(String field, int needed, int available) {
        return new UasProtocolException(
            "truncated " + field + ": needed " + needed + " bytes, " + available + " available");
    }

    public static UasProtocolException oversized(String field, long requested, long limit) {
        return new UasProtocolException(
            "declared " + field + " length " + requested + " exceeds the " + limit + "-byte limit");
    }
}
