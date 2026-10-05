package dom_lines;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import dom_lines.chart.ChartLines;
import dom_lines.sync.NoteSync;
import dom_lines.sync.NoteSync.Want;
import fake.figure.Line;
import fake.figure.Marker;
import x.dc;
import x.h;
import x.ui.draw.graph.l;

/** Plain-JVM tests (no MotiveWave, no framework); build.sh runs them on every build. */
public final class Tests {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        sync();
        reading();
        System.out.println("tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ NoteSync
    /** A fake DOM: remembers notes and what was done to them. */
    private static final class FakeDom implements NoteSync.Sink<Integer> {
        final java.util.Map<Integer, String> notes = new java.util.LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        int next = 1;
        boolean refuseAdd, refuseUpdate;

        @Override public Integer add(double price, String text, Color color) {
            calls.add("add " + price);
            if (refuseAdd) throw new IllegalStateException("no");
            int id = next++;
            notes.put(id, price + "/" + text + "/" + color.getRGB());
            return id;
        }
        @Override public boolean update(Integer id, double price, String text, Color color) {
            calls.add("update " + price);
            if (refuseUpdate) { notes.remove(id); return false; }     // the platform no longer knows this note
            if (!notes.containsKey(id)) return false;
            notes.put(id, price + "/" + text + "/" + color.getRGB());
            return true;
        }
        @Override public void remove(Integer id) {
            calls.add("remove " + id);
            notes.remove(id);
        }
    }

    private static Want want(double price, String text, Color c) { return new Want(price, text, c); }

    private static void sync() {
        var dom = new FakeDom();
        var sync = new NoteSync<Integer>(0.25);

        var r = sync.sync(List.of(want(31000, "31000.00", Color.RED), want(31010.25, "31010.25", Color.GREEN)), dom);
        check("first pass adds both", r.added() == 2 && r.updated() == 0 && r.removed() == 0 && dom.notes.size() == 2);

        dom.calls.clear();
        r = sync.sync(List.of(want(31000, "31000.00", Color.RED), want(31010.25, "31010.25", Color.GREEN)), dom);
        check("same picture: nothing is touched", !r.changed() && dom.calls.isEmpty());

        r = sync.sync(List.of(want(31000, "31000.00", Color.BLUE), want(31010.25, "31010.25", Color.GREEN)), dom);
        check("colour change updates one note", r.updated() == 1 && r.added() == 0 && dom.notes.size() == 2);

        dom.calls.clear();
        r = sync.sync(List.of(want(31000, "31000.00", Color.BLUE), want(31020, "31020.00", Color.GREEN)), dom);
        check("a moved line = old price removed, new price added", r.removed() == 1 && r.added() == 1 && dom.notes.size() == 2);

        r = sync.sync(List.of(), dom);
        check("no lines: everything removed", r.removed() == 2 && dom.notes.isEmpty() && sync.size() == 0);

        var d2 = new FakeDom();
        var s2 = new NoteSync<Integer>(0.25);
        r = s2.sync(List.of(want(100, "a", Color.RED), want(100.0, "b", Color.BLUE), want(100.1, "c", Color.GREEN)), d2);
        check("lines on one price: one note, the first wins", r.added() == 1 && d2.notes.size() == 1 && d2.notes.values().iterator().next().contains("/a/"));

        var d3 = new FakeDom();
        var s3 = new NoteSync<Integer>(0.25);
        d3.refuseAdd = true;
        r = s3.sync(List.of(want(50, "x", Color.RED)), d3);
        check("a refused add counts as failed and keeps no state", r.failed() == 1 && r.added() == 0 && s3.size() == 0);
        d3.refuseAdd = false;
        r = s3.sync(List.of(want(50, "x", Color.RED)), d3);
        check("...and is retried on the next pass", r.added() == 1 && s3.size() == 1);

        d3.refuseUpdate = true;
        r = s3.sync(List.of(want(50, "x", Color.BLUE)), d3);
        check("an update the platform no longer knows is dropped", r.failed() == 1 && s3.size() == 0);
        d3.refuseUpdate = false;
        r = s3.sync(List.of(want(50, "x", Color.BLUE)), d3);
        check("...and the note is recreated next time", r.added() == 1 && s3.size() == 1);

        int removed = s3.clear(d3);
        check("clear removes everything it made", removed == 1 && s3.size() == 0 && d3.notes.isEmpty());

        var s4 = new NoteSync<Integer>(0.25);
        s4.sync(List.of(want(10, "t", Color.RED)), new FakeDom());
        s4.forget();
        check("forget empties the state without calling the platform", s4.size() == 0);

        var d5 = new FakeDom();
        var s5 = new NoteSync<Integer>(0.25);
        s5.sync(List.of(want(31094.75, "t", Color.RED)), d5);
        r = s5.sync(List.of(want(31094.75000001, "t", Color.RED)), d5);
        check("a float round trip does not split a price in two", !r.changed());
    }

    // ------------------------------------------------------------------ ChartLines
    private static void reading() throws Exception {
        var chart = new l();
        Object ctx = new dc(new h(chart));

        chart.items.add("a study object");
        chart.items.add(new Line(1000, 31094.75, 5000, 31094.75, new Color(192, 192, 192), chart));
        chart.items.add(new Line(1000, 30975.5, 9000, 30975.5, Color.GREEN, chart));
        chart.items.add(new Line(1000, 31000.0, 5000, 31100.0, Color.RED, chart));        // sloped trend line
        chart.items.add(new Line(3000, 31000.0, 3000, 31200.0, Color.RED, chart));        // vertical line
        chart.items.add(new Line(1000, 30900.0, 5000, 30900.0, Color.BLUE, chart).destroyed());
        chart.items.add(new Marker());
        chart.other.add("unrelated");

        var reader = new ChartLines(ctx);
        List<ChartLines.Line> lines = reader.read();
        check("only the two horizontal lines are read (" + lines.size() + ")", lines.size() == 2);
        check("price of the first", Math.abs(lines.get(0).price() - 31094.75) < 1e-9);
        check("colour of the first", lines.get(0).color().equals(new Color(192, 192, 192)));
        check("price and colour of the second", Math.abs(lines.get(1).price() - 30975.5) < 1e-9 && lines.get(1).color().equals(Color.GREEN));

        chart.items.add(new Line(1000, 31255.0, 7000, 31255.0, Color.ORANGE, chart));
        check("a line added later is seen on the next read", reader.read().size() == 3);
        chart.items.remove(1);
        check("a removed line is gone on the next read", reader.read().size() == 2);

        var empty = new l();
        check("a chart with no drawings yields none, not an error", unavailable(new ChartLines(new dc(new h(empty)))));

        var broken = new ChartLines(new dc("nothing to find here"));
        check("no chart at all -> Unavailable", unavailable(broken));
    }

    private static boolean unavailable(ChartLines reader) {
        try {
            reader.read();
            return false;
        } catch (ChartLines.Unavailable e) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAILED: " + name); }
    }
}
