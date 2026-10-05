package dom_lines;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.Settings;
import com.motivewave.platform.sdk.common.TextStyle;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.ColorDescriptor;
import com.motivewave.platform.sdk.common.desc.EnabledDependency;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

import dom_lines.audit.AuditLog;
import dom_lines.chart.ChartLines;
import dom_lines.sync.NoteSync;

/**
 * Chart Lines in DOM
 * ------------------
 * MotiveWave cannot show the lines drawn on a chart in the DOM (price ladder). This indicator reads the
 * chart's horizontal lines and keeps one DOM note per line, in the colour of the line, at its price.
 * The DOM shows notes in its "Notes" column (DOM "+" menu -> Notes) when it is linked to this chart.
 *
 * Display only: a plain Study with no access to orders. The notes are the SDK's own DataContext.addNote();
 * only reading the chart's drawings goes through MotiveWave internals (see chart/ChartLines).
 */
@StudyHeader(
        namespace = "com.zentrader",
        id = "DOM_LINES",
        rb = "dom_lines.nls.strings",
        name = "STUDY_NAME",
        desc = "STUDY_DESC",
        menu = "MENU_GENERAL",
        overlay = true,
        studyOverlay = true,
        requiresBarUpdates = true
)
public class DomLines extends Study {

    static final String VERSION = "0.1.0";

    private static final String ENABLED = "enabled";
    private static final String SHOW_PRICE = "showPrice";
    private static final String USE_LINE_COLOR = "useLineColor";
    private static final String COLOR = "color";

    private static final long PERIOD_MS = 300;

    private ChartLines reader;
    private NoteSync<Object> sync;
    private Timer timer;
    private volatile boolean destroyed;
    private boolean started, failureLogged;
    private volatile long settingsStamp;     // bumped when settings change: the next pass rebuilds every note
    private long appliedStamp = -1;
    private int lastLineCount = -1;

    @Override
    public void initialize(Defaults defaults) {
        var sd = createSD();
        var tab = sd.addTab(get("TAB_GENERAL"));
        var g = tab.addGroup(get("LBL_SYNC_GROUP"));
        g.addRow(new BooleanDescriptor(ENABLED, get("LBL_ENABLED"), true));
        g.addRow(new BooleanDescriptor(SHOW_PRICE, get("LBL_SHOW_PRICE"), true));
        g.addRow(new BooleanDescriptor(USE_LINE_COLOR, get("LBL_USE_LINE_COLOR"), true),
                new ColorDescriptor(COLOR, get("LBL_COLOR"), new Color(90, 90, 90)));
        sd.addDependency(new EnabledDependency(false, USE_LINE_COLOR, COLOR));    // the fixed colour is only used when the line colour is off
        createRD();
    }

    // ==================== lifecycle ====================
    @Override
    protected void calculateValues(DataContext ctx) {
        start(ctx);
    }

    @Override
    public void onBarUpdate(DataContext ctx) {
        start(ctx);
    }

    @Override
    public void onSettingsUpdated(DataContext ctx) {
        super.onSettingsUpdated(ctx);
        settingsStamp++;
    }

    @Override
    public void destroy() {
        destroyed = true;
        stop();
        try {
            DataContext dc = getDataContext();
            if (dc != null) dc.removeAllNotes();
        } catch (RuntimeException ignored) { }
        super.destroy();
    }

    /** First data context seen: start the timer that keeps the DOM in step with the chart. */
    private synchronized void start(DataContext ctx) {
        if (started || ctx == null) return;
        // The platform only calls a live study, so a call after destroy() means it is being reused: start again.
        destroyed = false;
        sync = null;
        started = true;
        reader = new ChartLines(ctx);
        timer = new Timer("DomLines-sync", true);
        timer.schedule(new TimerTask() {
            @Override public void run() { onUi(DomLines.this::pass); }
        }, 500, PERIOD_MS);
        AuditLog.log("LIFECYCLE", "started v" + VERSION);
    }

    private synchronized void stop() {
        if (timer != null) timer.cancel();
        timer = null;
        started = false;
    }

    /** The platform's UI thread is where its chart and DOM objects live. */
    private static void onUi(Runnable r) {
        try {
            javafx.application.Platform.runLater(r);
        } catch (IllegalStateException | NoClassDefFoundError e) {
            r.run();            // no JavaFX toolkit: run on the timer thread
        }
    }

    // ==================== one pass ====================
    private void pass() {
        if (destroyed) return;
        try {
            DataContext dc = getDataContext();
            if (dc == null) return;
            Settings s = getSettings();
            Instrument instr = dc.getInstrument();
            if (sync == null || appliedStamp != settingsStamp) {
                // first pass, or settings changed: start from a clean slate (notes of an earlier instance too)
                dc.removeAllNotes();
                sync = new NoteSync<>(instr.getTickSize());
                appliedStamp = settingsStamp;
            }
            List<NoteSync.Want> wants = new ArrayList<>();
            if (s.getBoolean(ENABLED, true)) {
                boolean own = !s.getBoolean(USE_LINE_COLOR, true);
                Color fixed = s.getColor(COLOR, new Color(90, 90, 90));
                boolean showPrice = s.getBoolean(SHOW_PRICE, true);
                for (ChartLines.Line l : reader.read()) {
                    double price = instr.round(l.price());
                    wants.add(new NoteSync.Want(price, showPrice ? instr.format(price) : " ", own ? fixed : l.color()));
                }
            }
            if (wants.size() != lastLineCount) {
                lastLineCount = wants.size();
                AuditLog.log("SYNC", wants.size() + " line(s) on the chart " + instr.getSymbol());
            }
            var result = sync.sync(wants, new DomSink(dc));
            if (result.changed() || result.failed() > 0) {
                AuditLog.log("SYNC", "notes +" + result.added() + " ~" + result.updated() + " -" + result.removed()
                        + (result.failed() > 0 ? " failed=" + result.failed() : ""));
            }
            failureLogged = false;
        } catch (ChartLines.Unavailable e) {
            if (!failureLogged) {
                failureLogged = true;
                AuditLog.log("ERROR", "cannot read the chart's lines: " + e.getMessage());
            }
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                AuditLog.log("ERROR", "sync: " + e);
            }
        }
    }

    /** DOM notes through the SDK. The note's colour is its background; the text colour is picked to stay readable. */
    private static final class DomSink implements NoteSync.Sink<Object> {
        private final DataContext dc;

        DomSink(DataContext dc) { this.dc = dc; }

        @Override
        public Object add(double price, String text, Color color) {
            return dc.addNote((float) price, text, style(color));
        }

        @Override
        public boolean update(Object id, double price, String text, Color color) {
            return dc.updateNote(id, (float) price, text, style(color));
        }

        @Override
        public void remove(Object id) {
            dc.removeNote(id);
        }

        private static TextStyle style(Color bg) {
            double luma = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
            return new TextStyle((Font) null, luma > 150 ? Color.BLACK : Color.WHITE, new Color(bg.getRed(), bg.getGreen(), bg.getBlue()));
        }
    }
}
