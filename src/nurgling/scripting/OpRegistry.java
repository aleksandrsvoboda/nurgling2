package nurgling.scripting;

import nurgling.scripting.api.ApiError;
import nurgling.scripting.api.ApiOp;
import nurgling.scripting.api.Args;
import nurgling.scripting.api.OpContext;
import nurgling.scripting.ops.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/** All ops, discovered from the @ApiOp methods of the op classes listed here. */
public final class OpRegistry {

    /** Every class holding ops. The order is the order of namespaces in the docs. */
    public static final List<Class<?>> OP_CLASSES = Collections.unmodifiableList(Arrays.<Class<?>>asList(
            CoreOps.class,
            BotOps.class,
            PlayerOps.class,
            GobOps.class,
            InvOps.class,
            EquipOps.class,
            WindowOps.class,
            AreaOps.class,
            MapOps.class,
            ChatOps.class,
            ActionOps.class,
            CraftOps.class,
            WaitOps.class,
            CombatOps.class,
            FormOps.class,
            OverlayOps.class,
            RawOps.class,
            JavaOps.class
    ));

    public static final class OpDef {
        public final ApiOp op;
        public final Method method;

        OpDef(ApiOp op, Method method) {
            this.op = op;
            this.method = method;
        }

        /** Invokes the handler, unwrapping reflection so interrupts and API errors pass through unchanged. */
        public Object invoke(OpContext ctx, Args args) throws InterruptedException {
            try {
                return method.invoke(null, ctx, args);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof InterruptedException) throw (InterruptedException) c;
                if (c instanceof RuntimeException) throw (RuntimeException) c;
                if (c instanceof Error) throw (Error) c;
                throw new ApiError(nurgling.scripting.api.Errors.INTERNAL, String.valueOf(c));
            } catch (IllegalAccessException e) {
                throw new ApiError(nurgling.scripting.api.Errors.INTERNAL, "op " + op.name() + " is not accessible");
            }
        }
    }

    private static Map<String, OpDef> ops;

    public static synchronized Map<String, OpDef> all() {
        if (ops == null) ops = scan();
        return ops;
    }

    public static OpDef get(String name) {
        return all().get(name);
    }

    private static Map<String, OpDef> scan() {
        Map<String, OpDef> res = new LinkedHashMap<>();
        for (Class<?> cl : OP_CLASSES) {
            for (OpDef d : opsOf(cl)) {
                if (res.put(d.op.name(), d) != null)
                    throw new IllegalStateException("duplicate scripting op " + d.op.name());
            }
        }
        return res;
    }

    /** The ops of one class, sorted by name; validates handler signatures. */
    public static List<OpDef> opsOf(Class<?> cl) {
        List<OpDef> res = new ArrayList<>();
        for (Method m : cl.getDeclaredMethods()) {
            ApiOp a = m.getAnnotation(ApiOp.class);
            if (a == null) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())
                    || pt.length != 2 || pt[0] != OpContext.class || pt[1] != Args.class)
                throw new IllegalStateException("op " + a.name() + " (" + cl.getName() + "." + m.getName()
                        + ") must be public static Object x(OpContext, Args)");
            res.add(new OpDef(a, m));
        }
        res.sort(Comparator.comparing(d -> d.op.name()));
        return res;
    }

    private OpRegistry() {}
}
