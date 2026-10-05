package dom_lines.chart;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.LineInfo;

/**
 * Reads the horizontal lines the trader has drawn on a chart.
 *
 * The SDK has no access to a chart's drawings, so this reaches into MotiveWave's own objects with
 * reflection. Nothing here depends on an obfuscated NAME of a field or method: the chart is found by the
 * data context holding it, the drawings by the collection that holds figure objects, a horizontal line by
 * its shape (two anchor points on one price, different in time) and its colour by its LineInfo.
 * Whatever does not fit is skipped; if the chart cannot be reached at all {@link Unavailable} says why,
 * and the caller must leave the DOM alone.
 */
public final class ChartLines {

    /** A drawn horizontal line. */
    public record Line(double price, Color color) { }

    /** The platform's internals are not what this code expects (a different MotiveWave build). */
    public static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;
        public Unavailable(String message) { super(message); }
    }

    /** The chart object's class ends with this (the platform's composite chart). */
    static final String CHART_SUFFIX = "ui.draw.graph.l";

    private final Object dataContext;
    private Object chart;
    private Method figures;

    public ChartLines(Object dataContext) {
        this.dataContext = dataContext;
    }

    /** Reads the lines now. Cheap enough to call several times a second. */
    public List<Line> read() throws Unavailable {
        try {
            if (chart == null) chart = findChart(dataContext);
            if (chart == null) throw new Unavailable("the chart object was not found");
            if (figures == null) figures = findFigureList(chart);
            if (figures == null) throw new Unavailable("the chart's list of drawings was not found");
            Object all = figures.invoke(chart);
            if (!(all instanceof Collection<?> c)) throw new Unavailable("the chart's list of drawings is not a collection");
            return linesIn(new ArrayList<>(c));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            chart = null;                      // look again next time: the chart may have been rebuilt
            figures = null;
            throw new Unavailable(e.toString());
        }
    }

    // ------------------------------------------------------------------ finding the chart

    /** Breadth-first through the fields of the data context for the chart object (a few steps deep). */
    static Object findChart(Object root) {
        var seen = new IdentityHashMap<Object, Boolean>();
        var queue = new ArrayList<Object>();
        var depth = new ArrayList<Integer>();
        queue.add(root);
        depth.add(0);
        seen.put(root, true);
        for (int i = 0; i < queue.size() && i < 4000; i++) {
            Object o = queue.get(i);
            int d = depth.get(i);
            if (o.getClass().getName().endsWith(CHART_SUFFIX)) return o;
            if (d >= 3) continue;
            for (Field f : fieldsOf(o.getClass())) {
                if (f.getType().isPrimitive()) continue;
                Object v = value(f, o);
                if (v == null || seen.containsKey(v) || !isPlatform(v)) continue;
                seen.put(v, true);
                queue.add(v);
                depth.add(d + 1);
            }
        }
        return null;
    }

    private static boolean isPlatform(Object v) {
        String n = v.getClass().getName();
        return n.startsWith("com.motivewave") || n.matches("[a-z]{1,3}\\..*");
    }

    /** The chart method that returns the collection holding the drawn figures. */
    private static Method findFigureList(Object chart) throws ReflectiveOperationException {
        for (Class<?> c = chart.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                if (!Collection.class.isAssignableFrom(m.getReturnType())) continue;
                m.setAccessible(true);
                Object r;
                try { r = m.invoke(chart); } catch (ReflectiveOperationException | RuntimeException e) { continue; }
                if (r instanceof Collection<?> col && col.stream().anyMatch(ChartLines::isFigure)) return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ reading figures

    private static boolean isFigure(Object o) {
        return o != null && o.getClass().getName().contains(".figure.");
    }

    /** The horizontal lines among the chart's elements (studies and other drawings are ignored). */
    public static List<Line> linesIn(Collection<?> elements) {
        var out = new ArrayList<Line>();
        for (Object e : elements) {
            if (!isFigure(e) || destroyed(e)) continue;
            double price = horizontalPrice(e);
            if (Double.isNaN(price)) continue;
            Color color = lineColor(e);
            out.add(new Line(price, color != null ? color : Color.GRAY));
        }
        return out;
    }

    /**
     * The price of a figure that is a horizontal line, NaN otherwise: it has at least two anchor
     * points, all on one price, not all at the same moment. Anchors are looked for in the figure's own
     * fields and in the anchor objects those point to (one more level), never in the chart it belongs to.
     */
    static double horizontalPrice(Object figure) {
        var coords = new IdentityHashMap<Coordinate, Boolean>();
        collect(figure, 0, coords, new IdentityHashMap<>());
        if (coords.size() < 2) return Double.NaN;
        double price = Double.NaN;
        long firstTime = 0;
        boolean timesDiffer = false, first = true;
        for (Coordinate c : coords.keySet()) {
            if (first) { price = c.getValue(); firstTime = c.getTime(); first = false; continue; }
            if (Math.abs(c.getValue() - price) > 1e-9) return Double.NaN;
            if (c.getTime() != firstTime) timesDiffer = true;
        }
        return timesDiffer && price > 0 ? price : Double.NaN;
    }

    private static void collect(Object o, int depth, Map<Coordinate, Boolean> out, Map<Object, Boolean> seen) {
        if (o == null || depth > 2 || seen.put(o, true) != null) return;
        for (Field f : fieldsOf(o.getClass())) {
            if (f.getType().isPrimitive()) continue;
            Object v = value(f, o);
            if (v == null) continue;
            if (v instanceof Coordinate c) { out.put(c, true); continue; }
            // follow only the platform's small "anchor" objects, never the chart the figure sits in
            if (depth < 2 && isAnchorLike(v)) collect(v, depth + 1, out, seen);
        }
    }

    /** An object that can hold a point of a drawing: a platform class that is not a figure or a graph. */
    private static boolean isAnchorLike(Object v) {
        String n = v.getClass().getName();
        return n.matches("[a-z]{1,3}\\.[a-z0-9]{1,3}") && !n.startsWith("bl.") && !n.startsWith("k.");
    }

    private static Color lineColor(Object figure) {
        for (Field f : fieldsOf(figure.getClass())) {
            if (!LineInfo.class.isAssignableFrom(f.getType())) continue;
            Object v = value(f, figure);
            if (v instanceof LineInfo li && li.getLineColor() != null) return li.getLineColor();
        }
        return null;
    }

    private static boolean destroyed(Object figure) {
        try {
            Method m = figure.getClass().getMethod("isDestroyed");
            return Boolean.TRUE.equals(m.invoke(figure));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    private static Field[] fieldsOf(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            var list = new ArrayList<Field>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try { f.setAccessible(true); list.add(f); } catch (RuntimeException ignored) { }
                }
            }
            return list.toArray(new Field[0]);
        });
    }

    private static Object value(Field f, Object o) {
        try { return f.get(o); } catch (IllegalAccessException | RuntimeException e) { return null; }
    }
}
