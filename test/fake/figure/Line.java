package fake.figure;

import java.awt.Color;

import bj.e;
import bj.s;
import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.LineInfo;

/** Stand-in for a drawn trend/horizontal line: two anchors, a nested holder, a LineInfo with the colour. */
public class Line {
    e start, end;
    s nested;
    LineInfo mainLine;
    Object chartItBelongsTo;          // a figure points back to its chart: reading must not follow it
    boolean destroyed;

    public Line(long t1, double p1, long t2, double p2, Color color, Object chart) {
        start = new e(new Coordinate(t1, p1));
        end = new e(new Coordinate(t2, p2));
        nested = new s(start);
        mainLine = new LineInfo(color);
        chartItBelongsTo = chart;
    }

    public boolean isDestroyed() { return destroyed; }
    public Line destroyed() { destroyed = true; return this; }
}
