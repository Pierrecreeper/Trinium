// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.biome.Climate;

/** Defines the test helper with the signed game's lookup; no copied/modified game jar. */
public final class ClimateTreeTestLauncher {
    public static void main(String[] args) throws Throwable {
        var lookup=MethodHandles.privateLookupIn(Climate.class,MethodHandles.lookup());
        Path classes=Path.of(args[0]);
        String base="net/minecraft/world/level/biome/TriniumClimateTreeTest";
        lookup.defineClass(Files.readAllBytes(classes.resolve(base+"$Bounds.class")));
        Class<?> test=lookup.defineClass(Files.readAllBytes(classes.resolve(base+".class")));
        test.getMethod("main",String[].class).invoke(null,(Object)new String[0]);
    }
}
