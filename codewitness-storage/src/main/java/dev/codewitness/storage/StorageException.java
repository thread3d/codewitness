package dev.codewitness.storage;

/**
 * Thrown when a verification run cannot be persisted or read back.
 *
 * <p>Storage failures are unrecoverable for the caller: a report that was not written cannot be
 * presented as a stored result. The exception is therefore unchecked, and it always carries the
 * underlying database failure as its cause instead of hiding it.
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a storage failure.
     *
     * @param message description of the failed operation
     * @param cause   the underlying database or I/O failure
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a storage failure without an underlying cause.
     *
     * @param message description of the failed operation
     */
    public StorageException(String message) {
        super(message);
    }
}
