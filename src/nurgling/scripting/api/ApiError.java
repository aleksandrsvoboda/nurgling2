package nurgling.scripting.api;

/**
 * An error reported to the script as a typed exception.
 *
 * Unchecked on purpose: op handlers throw it through existing client code. It never wraps
 * {@link InterruptedException}; a stop always propagates as the interrupt itself.
 */
public class ApiError extends RuntimeException {
    public final String type;

    public ApiError(String type, String msg) {
        super(msg);
        this.type = type;
    }

    public static ApiError badArgs(String msg) { return new ApiError(Errors.BAD_ARGS, msg); }
    public static ApiError notFound(String msg) { return new ApiError(Errors.NOT_FOUND, msg); }
    public static ApiError failed(String msg) { return new ApiError(Errors.ACTION_FAILED, msg); }
    public static ApiError notSupported(String msg) { return new ApiError(Errors.NOT_SUPPORTED, msg); }
}
