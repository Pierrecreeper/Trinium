// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.compat.RenderCompatibility;
import fr.pierrecreeper.trinium.mixin.ChestFrustumAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

/** Conservative main-camera filtering for the three known vanilla chest implementations. */
public final class ChestFrustumCulling {
    public static final boolean REQUESTED=!Boolean.getBoolean("trinium.disableBlockEntityCulling");
    public static boolean renderingEnabled=true;
    public static long tested,culled;
    private ChestFrustumCulling() { }

    public static boolean enabled() {
        return REQUESTED && renderingEnabled && Trinium.active()
            && !RenderCompatibility.IRIS && !RenderCompatibility.SODIUM;
    }

    public static boolean supported(BlockEntity entity,BlockEntityRenderDispatcher dispatcher,boolean offScreen) {
        return Checks.supported(entity,dispatcher,offScreen);
    }
    public static boolean finite(Frustum frustum) { return Checks.finite(frustum); }
    public static boolean finiteProjection(Matrix4fc projection) { return Checks.finiteProjection(projection); }
    public static boolean outside(Frustum frustum,BlockPos position) { return Checks.outside(frustum,position); }
    public static AABB bounds(BlockPos position) { return Checks.bounds(position); }

    // Counter-only diagnostics must also work without Minecraft on the CLI classpath.
    private static final class Checks {
        private static boolean supported(BlockEntity entity,BlockEntityRenderDispatcher dispatcher,boolean offScreen) {
            if(offScreen || entity==null || dispatcher==null || dispatcher.getClass()!=BlockEntityRenderDispatcher.class)return false;
            Class<?> type=entity.getClass();
            if(type!=ChestBlockEntity.class && type!=TrappedChestBlockEntity.class && type!=EnderChestBlockEntity.class)return false;
            var block=entity.getBlockState().getBlock();
            boolean known=type==ChestBlockEntity.class && block==Blocks.CHEST
                || type==TrappedChestBlockEntity.class && block==Blocks.TRAPPED_CHEST
                || type==EnderChestBlockEntity.class && block==Blocks.ENDER_CHEST;
            if(!known)return false;
            var renderer=dispatcher.getRenderer(entity);
            return renderer!=null && renderer.getClass()==ChestRenderer.class && !renderer.shouldRenderOffScreen();
        }

        private static boolean finite(Frustum frustum) {
            if(frustum==null || frustum.getClass()!=Frustum.class
                || !Double.isFinite(frustum.getCamX()) || !Double.isFinite(frustum.getCamY())
                || !Double.isFinite(frustum.getCamZ()))return false;
            // Once per extraction, only when a supported chest is encountered. The
            // actual game path reads existing matrix values and allocates nothing.
            if((Object)frustum instanceof ChestFrustumAccess access)return finiteProjection(access.trinium$chestMatrix());
            // Unmixed standalone CPU tests retain the public vanilla API as an oracle.
            for(var point:frustum.getFrustumPoints())
                if(!Float.isFinite(point.x) || !Float.isFinite(point.y)
                    || !Float.isFinite(point.z) || !Float.isFinite(point.w))return false;
            return true;
        }

        private static boolean finiteProjection(Matrix4fc matrix) {
            if(matrix==null || !matrix.isFinite())return false;
            float determinant=matrix.determinant();
            return Float.isFinite(determinant) && determinant!=0;
        }

        private static AABB bounds(BlockPos position) {
            double x=position.getX(),y=position.getY(),z=position.getZ();
            // Each horizontal neighbor is included for a double chest. The generous
            // vertical margin includes the lid/lock throughout their complete sweep.
            // Destruction draws the same model. Do not shrink this to the block cube.
            return new AABB(x-1,y-1,z-1,x+2,y+2,z+2);
        }

        private static boolean outside(Frustum frustum,BlockPos position) {
            tested++;
            if((Object)frustum instanceof ChestFrustumAccess access) {
                double x=position.getX(),y=position.getY(),z=position.getZ();
                int result=access.trinium$chestCube(x-1,y-1,z-1,x+2,y+2,z+2);
                if(result==-1 || result==-2)return false; // vanilla Frustum.isVisible
            } else if(frustum.isVisible(bounds(position)))return false;
            culled++;
            return true;
        }
    }
}
