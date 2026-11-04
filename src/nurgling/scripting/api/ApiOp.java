package nurgling.scripting.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method as a scripting API operation.
 *
 * The annotation is the single source of truth for the API: the schema exporter turns it into
 * {@code api-schema.json}, which generates the Python SDK and the reference documentation.
 *
 * An op method must have the signature
 * {@code public static Object name(OpContext ctx, Args args) throws InterruptedException}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ApiOp {
    /** Wire name, "namespace.verb", e.g. "gobs.find". */
    String name();

    /** One-paragraph description (Markdown). The first sentence is the summary. */
    String doc();

    /** API version that introduced the op. */
    String since() default "1.0";

    Kind kind() default Kind.QUERY;

    Exec exec() default Exec.BOT;

    ApiParam[] params() default {};

    /** Return type in schema notation, e.g. "Gob", "list[Item]", "bool", "null". */
    String returns() default "null";

    String returnsDoc() default "";

    /** Error types the op raises besides the universal ones (Stopped, BadArgs, Internal). */
    String[] errors() default {};

    /** A short Python usage example (the client object is called {@code nc}). */
    String example() default "";

    /** True for ops with no stability guarantee (layer 4). */
    boolean unstable() default false;

    /** Set when an op is deprecated; the text names the replacement. */
    String deprecated() default "";

    enum Kind {
        /** Reads state only; retried automatically while resources are loading. */
        QUERY,
        /** Changes game state. */
        ACTION,
        /** Blocks until a condition holds. */
        WAIT,
        /** Connection, session and event plumbing. */
        CONTROL
    }

    enum Exec {
        /** Runs on the script's bot thread, bound to its session (the default; required for anything touching the game). */
        BOT,
        /** Runs on the connection's reader thread; must not touch game state. */
        CONNECTION
    }
}
