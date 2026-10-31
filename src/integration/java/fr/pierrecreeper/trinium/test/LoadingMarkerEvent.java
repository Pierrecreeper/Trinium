package fr.pierrecreeper.trinium.probe;

import java.lang.management.ManagementFactory;
import jdk.jfr.*;

/** Test-only phase boundaries; no game/account data or command-line arguments. */
@Name("trinium.LoadingMarker")
@Label("Trinium offline loading phase")
@Category("Trinium test")
@StackTrace(false)
public final class LoadingMarkerEvent extends Event {
    public String stage;
    public long uptimeMs;
    public long jvmStartEpochMs;
    private static final boolean ENABLED=Boolean.getBoolean("trinium.testLoadingMarkers");
    public static void mark(String stage) {
        if(!ENABLED)return;
        var event=new LoadingMarkerEvent();
        if(!event.isEnabled())return;
        var runtime=ManagementFactory.getRuntimeMXBean();
        event.stage=stage;event.uptimeMs=runtime.getUptime();
        event.jvmStartEpochMs=runtime.getStartTime();event.commit();
    }
}
