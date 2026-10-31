// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import fr.pierrecreeper.trinium.shaders.StandaloneShaders;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The default backend when Iris is absent. No Sodium dependency. */
public final class StandaloneShaderAccess implements ShaderAccess {
    private final StandaloneShaders shaders = StandaloneShaders.instance();
    public boolean available() { return fr.pierrecreeper.trinium.Trinium.active(); }
    public String unavailableReason() { return available() ? "" : "Trinium is disabled. Shader rendering is unavailable."; }
    public Path directory() { return shaders.directory(); }
    public State state() { return shaders.state(); }
    public List<String> packs() { try { return shaders.packs(); } catch (IOException e) { throw new IllegalStateException(e); } }
    public Result apply(String pack, Map<String, String> options) { return shaders.apply(pack, options); }
    public Settings settings() { return shaders.settings(); }
}
