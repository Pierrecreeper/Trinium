// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import java.lang.instrument.Instrumentation;

/** Test-only premain agent. It installs no transformers and changes no game classes. */
public final class ClimateObjectSizeAgent {
    private static Instrumentation instrumentation;
    public static void premain(String arguments,Instrumentation value) {instrumentation=value;}
    public static long sizeOf(Object value) {
        if(instrumentation==null)throw new IllegalStateException("Climate object-size agent was not supplied at JVM startup");
        return instrumentation.getObjectSize(value);
    }
}
