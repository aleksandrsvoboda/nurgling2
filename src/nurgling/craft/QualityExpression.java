package nurgling.craft;

import java.util.*;
import java.util.function.Function;

/** Small bounded arithmetic AST. No script engine, reflection or executable wiki content. */
public final class QualityExpression {
    private final String op;
    private final double number;
    private final List<QualityExpression> args;
    private QualityExpression(String op, double number, QualityExpression... args) {
        this.op = op; this.number = number; this.args = Arrays.asList(args);
    }
    public static String key(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    public Set<String> variables() {
        Set<String> ret = new LinkedHashSet<>();
        if(op.startsWith("$")) ret.add(op.substring(1));
        for(QualityExpression a : args) ret.addAll(a.variables());
        return ret;
    }
    public QualityExpression bind(Function<String, String> names) {
        if(op.startsWith("$")) {
            String name = names.apply(op.substring(1));
            if(name == null) throw new IllegalArgumentException("Unknown variable: " + op.substring(1));
            return new QualityExpression("$" + name, 0);
        }
        return new QualityExpression(op, number, args.stream().map(a -> a.bind(names)).toArray(QualityExpression[]::new));
    }
    public double evaluate(Function<String, Double> values) {
        double result;
        if(op.equals("#")) return number;
        if(op.startsWith("$")) {
            Double value = values.apply(op.substring(1));
            if(value == null || !Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Missing value: " + op.substring(1));
            return value;
        }
        double[] a = args.stream().mapToDouble(n -> n.evaluate(values)).toArray();
        switch(op) {
        case "+": result = a[0] + a[1]; break;
        case "-": result = a[0] - a[1]; break;
        case "*": result = a[0] * a[1]; break;
        case "/": if(a[1] == 0) throw new IllegalArgumentException("Division by zero"); result = a[0] / a[1]; break;
        case "^": case "pow": result = Math.pow(a[0], a[1]); break;
        case "neg": result = -a[0]; break;
        case "sqrt": result = Math.sqrt(a[0]); break;
        case "floor": result = Math.floor(a[0]); break;
        case "min": result = Arrays.stream(a).min().orElseThrow(); break;
        case "max": result = Arrays.stream(a).max().orElseThrow(); break;
        case "mean": result = Arrays.stream(a).average().orElseThrow(); break;
        case "geomean": result = Arrays.stream(a).anyMatch(v -> v == 0) ? 0 : Math.exp(Arrays.stream(a).map(Math::log).average().orElseThrow()); break;
        case "softcap": result = a[1] < a[0] ? (a[0] + a[1]) / 2 : a[0]; break;
        default: throw new IllegalArgumentException("Unknown operator");
        }
        if(!Double.isFinite(result)) throw new IllegalArgumentException("Non-finite result");
        return result;
    }
    public String toString() {
        if(op.equals("#")) return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        if(op.startsWith("$")) return op.substring(1);
        if(op.equals("neg")) return "(-" + args.get(0) + ")";
        if(Set.of("+", "-", "*", "/", "^").contains(op)) return "(" + args.get(0) + op + args.get(1) + ")";
        return op + "(" + String.join(",", args.stream().map(Object::toString).toArray(String[]::new)) + ")";
    }
    /** Human labels are supplied by the recipe; the arithmetic AST remains the source of truth. */
    public String toTex(Function<String,String> names) {
        return toTex(names,0);
    }
    private String toTex(Function<String,String> names, int parentPrecedence) {
        if(op.equals("#")) return toString();
        if(op.startsWith("$")) return names.apply(op.substring(1));
        if(op.equals("+") || op.equals("-") || op.equals("*")) {
            int precedence=op.equals("*") ? 20 : 10;
            String body=args.get(0).toTex(names,precedence) + (op.equals("*") ? "\\cdot " : op)
                + args.get(1).toTex(names,precedence+(op.equals("-") ? 1 : 0));
            return precedence < parentPrecedence ? "\\left("+body+"\\right)" : body;
        }
        List<String> a = new ArrayList<>(); for(QualityExpression child : args) a.add(child.toTex(names));
        switch(op) {
        case "/": return "\\frac{" + a.get(0) + "}{" + a.get(1) + "}";
        case "sqrt": return "\\sqrt{" + a.get(0) + "}";
        case "^": case "pow":
            if(args.get(1).variables().isEmpty()) {
                double exponent=args.get(1).evaluate(k -> null), degree=1/exponent;
                if(degree>=2 && degree<=16 && Math.abs(degree-Math.rint(degree))<1e-9)
                    return "\\sqrt"+(degree==2 ? "" : "["+(int)Math.rint(degree)+"]")+"{"+a.get(0)+"}";
            }
            return "\\left(" + a.get(0) + "\\right)^{" + a.get(1) + "}";
        case "mean": return "\\frac{" + String.join("+", a) + "}{" + a.size() + "}";
        case "geomean": return "\\sqrt" + (a.size()==2 ? "" : "["+a.size()+"]") + "{"
            + String.join("\\cdot ", args.stream().map(child -> child.toTex(names,20)).toArray(String[]::new)) + "}";
        case "neg": return "-\\left(" + a.get(0) + "\\right)";
        default: return "\\operatorname{" + op + "}\\left(" + String.join(",", a) + "\\right)";
        }
    }
    public static QualityExpression parse(String text) {
        if(text.length() > 4096) throw new IllegalArgumentException("Expression too long");
        Parser p = new Parser(text); QualityExpression ret = p.expression(0); p.space();
        if(p.pos != text.length()) throw new IllegalArgumentException("Unexpected text at " + p.pos);
        ret.validate(0);
        return ret;
    }
    private void validate(int depth) {
        if(depth > 48) throw new IllegalArgumentException("Expression too deep");
        for(QualityExpression a : args) a.validate(depth + 1);
    }
    private static final class Parser {
        final String text; int pos;
        Parser(String text) { this.text = text; }
        void space() { while(pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        boolean take(char c) { space(); if(pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void need(char c) { if(!take(c)) throw new IllegalArgumentException("Expected " + c + " at " + pos); }
        QualityExpression expression(int depth) {
            if(depth > 48) throw new IllegalArgumentException("Expression too deep");
            QualityExpression n = term(depth + 1);
            while(true) {
                if(take('+')) n = new QualityExpression("+", 0, n, term(depth + 1));
                else if(take('-')) n = new QualityExpression("-", 0, n, term(depth + 1)); else return n;
            }
        }
        QualityExpression term(int d) {
            QualityExpression n = power(d);
            while(true) {
                if(take('*')) n = new QualityExpression("*", 0, n, power(d));
                else if(take('/')) n = new QualityExpression("/", 0, n, power(d)); else return n;
            }
        }
        QualityExpression power(int d) {
            if(d > 48) throw new IllegalArgumentException("Expression too deep");
            if(take('-')) return new QualityExpression("neg", 0, power(d + 1));
            if(take('+')) return power(d + 1);
            QualityExpression n = atom(d + 1);
            return take('^') ? new QualityExpression("^", 0, n, power(d + 1)) : n;
        }
        QualityExpression atom(int d) {
            if(take('(')) { QualityExpression n = expression(d); need(')'); return n; }
            space(); int start = pos;
            if(pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                while(pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
                double value = Double.parseDouble(text.substring(start, pos));
                if(!Double.isFinite(value)) throw new IllegalArgumentException("Invalid number");
                return new QualityExpression("#", value);
            }
            while(pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || "_ '".indexOf(text.charAt(pos)) >= 0)) pos++;
            String id = key(text.substring(start, pos));
            if(id.isEmpty()) throw new IllegalArgumentException("Expected value at " + pos);
            if(take('(')) {
                if(!Set.of("sqrt", "pow", "min", "max", "mean", "geomean", "floor", "softcap").contains(id)) throw new IllegalArgumentException("Unknown function: " + id);
                List<QualityExpression> a = new ArrayList<>();
                do { a.add(expression(d + 1)); } while(take(',')); need(')');
                int count = a.size();
                if(((id.equals("sqrt") || id.equals("floor")) && count != 1) || ((id.equals("pow") || id.equals("softcap")) && count != 2))
                    throw new IllegalArgumentException("Wrong function arity");
                return new QualityExpression(id, 0, a.toArray(new QualityExpression[0]));
            }
            return new QualityExpression("$" + id, 0);
        }
    }
    public static QualityExpression tex(String text) {
        text = text.replaceAll("_\\s*\\{q\\}", "").replaceAll("_q(?=\\b|[A-Z])", "")
            .replace("\\left", "").replace("\\right", "").replace("\\cdot", "*").replace("\\times", "*")
            .replace("\\operatorname", "").replace("\\mathrm", "").replace("\\text", "");
        return parse(texParts(text, 0).replace('{', '(').replace('}', ')'));
    }
    private static String texParts(String text, int depth) {
        if(depth > 32) throw new IllegalArgumentException("TeX too deep");
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < text.length();) {
            boolean frac = text.startsWith("\\frac", i), sqrt = text.startsWith("\\sqrt", i);
            if(frac || sqrt) {
                i += 5; while(i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                String degree = "2";
                if(sqrt && i < text.length() && text.charAt(i) == '[') {
                    int end = text.indexOf(']', i); if(end < 0) throw new IllegalArgumentException("Invalid root");
                    degree = text.substring(i + 1, end); i = end + 1;
                }
                while(i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                int end = groupEnd(text, i); String a = texParts(text.substring(i + 1, end), depth + 1); i = end + 1;
                if(frac) {
                    while(i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
                    end = groupEnd(text, i); String b = texParts(text.substring(i + 1, end), depth + 1); i = end + 1;
                    out.append("((").append(a).append(")/(").append(b).append("))");
                } else out.append("pow((").append(a).append("),1/(").append(degree).append("))");
            } else out.append(text.charAt(i++));
        }
        return out.toString();
    }
    private static int groupEnd(String text, int at) {
        if(at >= text.length() || text.charAt(at) != '{') throw new IllegalArgumentException("Expected TeX group");
        int depth = 1;
        for(int i = at + 1; i < text.length(); i++) {
            if(text.charAt(i) == '{') depth++;
            if(text.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unclosed TeX group");
    }
}
