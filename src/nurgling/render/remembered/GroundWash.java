package nurgling.render.remembered;

import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Function.PDir.*;
import static haven.render.sl.Type.*;

/**
 * Takes part of the colour out of remembered ground, so that what is
 * remembered reads apart from what is seen. A shader state rather than a
 * sheet laid over the ground: blending can only tint every fragment toward
 * one colour, while greying needs each fragment's own channels. Installed
 * on the remembered ground's slots, it reaches every tileset material below.
 */
public class GroundWash extends State {
    public static final Slot<GroundWash> slot = new Slot<>(Slot.Type.DRAW, GroundWash.class);
    private static final Uniform uamount = new Uniform(FLOAT, p -> p.get(slot).amount, slot);

    private static final Function wash = new Function.Def(VEC4) {{
	Expression col = param(IN, VEC4).ref();
	Expression amt = param(IN, FLOAT).ref();
	Expression lum = code.local(FLOAT, dot(pick(col, "rgb"), vec3(l(0.2126), l(0.7152), l(0.0722)))).ref();
	code.add(new Return(vec4(mix(pick(col, "rgb"), vec3(lum, lum, lum), amt), pick(col, "a"))));
    }};

    private static final ShaderMacro shader = prog ->
	FragColor.fragcol(prog.fctx).mod(in -> wash.call(in, uamount.ref()), 1000);

    /** How far toward grey, 0 to 1. */
    public final float amount;

    public GroundWash(float amount) {
	this.amount = amount;
    }

    public ShaderMacro shader() {return(shader);}

    public void apply(Pipe p) {p.put(slot, this);}
}
