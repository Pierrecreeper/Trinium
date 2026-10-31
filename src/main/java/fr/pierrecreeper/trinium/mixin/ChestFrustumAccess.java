// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Use the existing frustum math without constructing a temporary AABB per chest. */
@Mixin(value=Frustum.class,remap=false)
public interface ChestFrustumAccess {
    @Accessor("matrix") Matrix4f trinium$chestMatrix();
    @Invoker("cubeInFrustum") int trinium$chestCube(double minX,double minY,double minZ,double maxX,double maxY,double maxZ);
}
