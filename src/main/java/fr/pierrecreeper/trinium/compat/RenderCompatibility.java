package fr.pierrecreeper.trinium.compat;

/** Resolve once, without making the standalone native/GPU tests depend on Fabric startup. */
public final class RenderCompatibility {
    public static final boolean SODIUM=loaded("sodium"), IRIS=loaded("iris");
    private static boolean loaded(String id) {
        try {return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id);}
        catch(RuntimeException|LinkageError e){return false;}
    }
    private RenderCompatibility(){}
}
