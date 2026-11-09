package dom_lines;

/**
 * Which loaded copy of this indicator's jar is the current one.
 *
 * <p>When a new jar replaces the indicator, MotiveWave makes new study instances but does not call destroy() on the old
 * ones (seen 5 Oct 2026: after a reinstall every line change was synced twice; confirmed 9 Nov 2026 on another study). The
 * old instance's sync timer would keep writing DOM notes until the platform is restarted. A JVM-wide system property says
 * which copy of this class started last; a timer of an older copy sees that and stops.
 *
 * <p>Each jar has its own copy of this class (its own class loader), so {@link #ID} differs between copies.
 */
public final class LoadedCopy {

    static final String KEY = "com.zentrader.loaded." + LoadedCopy.class.getName();
    private static final String ID = Integer.toHexString(System.identityHashCode(LoadedCopy.class)) + "@" + System.nanoTime();

    private LoadedCopy() { }

    /** This copy takes over: instances of older copies stop at their next check. */
    public static void claim() { System.setProperty(KEY, ID); }

    /** False once a newer copy of the jar has started an instance. */
    public static boolean isCurrent() {
        String v = System.getProperty(KEY);
        return v == null || v.equals(ID);
    }
}
