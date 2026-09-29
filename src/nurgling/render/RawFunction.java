package nurgling.render;

import haven.render.sl.*;

/*
 * A shader function written as plain GLSL, callable from the shader
 * DSL like a builtin. Effects are much easier to write and tune as
 * GLSL than as DSL expression trees.
 *
 * The source is a complete top-level definition (it may define
 * helpers before the function itself). It must be valid in both
 * GLSL 1.40 and Vulkan GLSL 4.50 (use texture(), not texture2D()),
 * and no line of it may start with "in ", "out " or "uniform ", which
 * the Vulkan backend reserves for declarations. Pass samplers and
 * uniforms in as arguments.
 */
public class RawFunction extends Function.Builtin {
    public final String src;

    public RawFunction(Type ret, String name, int nargs, String src) {
	super(ret, new Symbol.Fix(name), nargs);
	this.src = src;
    }

    private class Def extends Toplevel {
	public void walk(Walker w) {}

	public void output(Output out) {
	    out.write(src);
	    out.write("\n");
	}

	RawFunction fn() {return(RawFunction.this);}
    }

    /* Must be called on each shader context that calls the
     * function, before its main function is constructed. */
    public void define(Context ctx) {
	for(Toplevel tl : ctx.fundefs) {
	    if((tl instanceof Def) && (((Def)tl).fn() == this))
		return;
	}
	ctx.fundefs.add(new Def());
    }
}
