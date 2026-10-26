package dev.codewitness.storage;

/**
 * The stored history could not be reached for a reason that is not a defect in CodeWitness.
 *
 * <p>A read-only mount, a directory the process may not write, and a database locked by another run all mean
 * "this history cannot be used from here". They are statements about the environment, and reporting one as an
 * internal failure filed a self-issue nobody could ever fix by changing code. A corrupt database, a schema
 * that does not match the driver, or a mapper that throws are the opposite case: those are defects, they are
 * journalled, and this exception is deliberately not thrown for them.
 *
 * <p>It extends {@link StorageException} so that nothing which already catches that stops catching this.
 */
public class StorageUnavailableException extends StorageException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what could not be reached, and why
     */
    public StorageUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message what could not be reached, and why
     * @param cause   the driver failure behind it
     */
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * @param failure a driver failure
     * @return {@code true} when another writer holds the database, which waiting may resolve
     */
    public static boolean isBusy(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked")
                    || message.contains("SQLITE_LOCKED"))) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * @param failure a driver failure
     * @return {@code true} when it describes an environment restriction rather than a defect
     */
    public static boolean isUnavailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                if (message.contains("SQLITE_READONLY") || message.contains("attempt to write a readonly")
                        || message.contains("SQLITE_BUSY") || message.contains("database is locked")
                        || message.contains("SQLITE_CANTOPEN") || message.contains("unable to open database")) {
                    return true;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
