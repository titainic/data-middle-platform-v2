package tech.qiantong.qdata.common.exception;

/** A safe, bounded dependency failure. Do not attach upstream messages containing credentials. */
public final class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) { super(message); }
}
