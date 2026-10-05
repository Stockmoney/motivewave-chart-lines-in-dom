package dom_lines.sync;

import java.awt.Color;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a set of DOM notes equal to a set of wanted ones with the fewest calls: adds what is new,
 * updates what changed colour or text, removes what is gone. One note per price; when several lines
 * sit on the same price the first one wins. Pure logic (no MotiveWave types) so it is unit-tested.
 *
 * @param <ID> whatever the platform returns to identify a note
 */
public final class NoteSync<ID> {

    /** What the DOM should show at one price. */
    public record Want(double price, String text, Color color) { }

    /** The platform's note operations. Any of them may throw; the sync survives and tries again later. */
    public interface Sink<ID> {
        ID add(double price, String text, Color color);
        boolean update(ID id, double price, String text, Color color);
        void remove(ID id);
    }

    /** What one {@link #sync} call did. */
    public record Result(int added, int updated, int removed, int failed) {
        public boolean changed() { return added + updated + removed > 0; }
    }

    private record Have<ID>(ID id, String text, Color color) { }

    // The price is the key: two decimals of a tick are enough, so a float round trip never splits one price in two.
    private final Map<Long, Have<ID>> notes = new LinkedHashMap<>();
    private final double tick;

    public NoteSync(double tick) {
        this.tick = tick > 0 ? tick : 0.01;
    }

    public int size() { return notes.size(); }

    private long key(double price) { return Math.round(price / tick); }

    public Result sync(Collection<Want> wanted, Sink<ID> sink) {
        Map<Long, Want> want = new LinkedHashMap<>();
        for (Want w : wanted) want.putIfAbsent(key(w.price()), w);

        int added = 0, updated = 0, removed = 0, failed = 0;
        for (Iterator<Map.Entry<Long, Have<ID>>> it = notes.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (want.containsKey(e.getKey())) continue;
            try { sink.remove(e.getValue().id()); removed++; } catch (RuntimeException ex) { failed++; }
            it.remove();                       // gone from the picture: forget it even if the platform refused
        }
        for (var e : want.entrySet()) {
            Want w = e.getValue();
            Have<ID> have = notes.get(e.getKey());
            try {
                if (have == null) {
                    ID id = sink.add(w.price(), w.text(), w.color());
                    if (id == null) { failed++; continue; }
                    notes.put(e.getKey(), new Have<>(id, w.text(), w.color()));
                    added++;
                } else if (!have.text().equals(w.text()) || !have.color().equals(w.color())) {
                    if (sink.update(have.id(), w.price(), w.text(), w.color())) {
                        notes.put(e.getKey(), new Have<>(have.id(), w.text(), w.color()));
                        updated++;
                    } else {                   // the platform no longer knows the note: make a new one next time
                        notes.remove(e.getKey());
                        failed++;
                    }
                }
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        return new Result(added, updated, removed, failed);
    }

    /** Removes every note this sync made (the study is going away or switched off). */
    public int clear(Sink<ID> sink) {
        int n = 0;
        for (var have : new HashMap<>(notes).values()) {
            try { sink.remove(have.id()); n++; } catch (RuntimeException ignored) { }
        }
        notes.clear();
        return n;
    }

    /** Forget everything without touching the platform (its notes were wiped from outside). */
    public void forget() { notes.clear(); }
}
