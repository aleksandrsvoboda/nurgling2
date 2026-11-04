package nurgling.scripting.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One parameter of an {@link ApiOp}. */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ApiParam {
    String name();

    /**
     * Type in schema notation: int, float, bool, str, any, dict, Gob, Item, Inventory, Window,
     * Widget, Area, Coord, Tile, Rect, Alias, list[T], or a union such as "Gob|Coord".
     */
    String type();

    String doc();

    boolean optional() default false;

    /** Default value as a JSON literal ("10", "true", "\"main\""); documentation only. */
    String def() default "";

    /** Allowed values for an enumerated string parameter. */
    String[] values() default {};
}
