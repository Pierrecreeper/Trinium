// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** CPU-only geometry/degeneracy checks; no window, world, GL context or renderer bootstrap. */
public final class ChestFrustumCullingTest {
    private static int checks;
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        var origin=new BlockPos(0,0,0);
        AABB bounds=ChestFrustumCulling.bounds(origin);
        var projection=new Matrix4f().perspective((float)Math.toRadians(90),1,.1f,100);
        var front=new Frustum(new Matrix4f(),projection);front.prepare(.5,.5,.5);
        check(ChestFrustumCulling.finite(front),"ordinary perspective accepted");
        check(ChestFrustumCulling.finiteProjection(projection),"production matrix check accepts perspective");
        check(!ChestFrustumCulling.finiteProjection(new Matrix4f().zero()),"production matrix check rejects singular projection");
        check(!ChestFrustumCulling.finiteProjection(new Matrix4f().m22(Float.NaN)),"production matrix check rejects NaN projection");
        check(!ChestFrustumCulling.finiteProjection(new Matrix4f().m11(Float.POSITIVE_INFINITY)),"production matrix check rejects infinite projection");
        check(!ChestFrustumCulling.outside(front,new BlockPos(0,0,-10)),"front-facing chest retained");
        check(ChestFrustumCulling.outside(front,new BlockPos(0,0,10)),"chest fully behind camera rejected");
        check(!ChestFrustumCulling.outside(front,origin),"camera inside chest envelope retained");
        check(!ChestFrustumCulling.outside(front,new BlockPos(10,0,-10)),"envelope crossing screen edge retained");
        check(ChestFrustumCulling.outside(front,new BlockPos(30,0,-10)),"fully outside side plane rejected");
        check(!ChestFrustumCulling.outside(front,new BlockPos(0,0,-1)),"near-plane crossing retained");
        var reverse=new Frustum(new Matrix4f().rotationY((float)Math.PI),projection);reverse.prepare(.5,.5,.5);
        check(!ChestFrustumCulling.outside(reverse,new BlockPos(0,0,10)),"rotation immediately reveals previous rear chest");
        check(ChestFrustumCulling.outside(reverse,new BlockPos(0,0,-10)),"rotation immediately rejects previous front chest");
        front.prepare(Double.NaN,0,0);
        check(!ChestFrustumCulling.finite(front),"NaN camera fails open");
        front.prepare(0,Double.POSITIVE_INFINITY,0);
        check(!ChestFrustumCulling.finite(front),"infinite camera fails open");
        check(!ChestFrustumCulling.finite(null),"absent frustum fails open");
        check(!ChestFrustumCulling.finite(new Frustum(new Matrix4f(),new Matrix4f().zero())),"singular projection fails open");
        check(!ChestFrustumCulling.finite(new Frustum(new Matrix4f(),new Matrix4f().m00(Float.NaN))),"NaN projection fails open");
        check(!ChestFrustumCulling.finite(new Frustum(new Matrix4f(),projection) { }),"custom frustum class fails open");
        AABB far=ChestFrustumCulling.bounds(new BlockPos(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE));
        check(far.maxX==(double)Integer.MAX_VALUE+2 && far.minY==(double)Integer.MIN_VALUE-1,"bounds arithmetic never overflows integer coordinates");

        // Independent vertex oracle from the local 26.3 ChestModel layer definitions.
        // Vary the entire lid/lock revolution, beyond the ordinary 0..90-degree range,
        // and all horizontal facings. Both double halves include their outer corners.
        float[][] single={{1,0,1,14,10,14,0,0,0,0},{1,0,0,14,5,14,0,9,1,1},{7,-2,14,2,4,1,0,9,1,1}};
        float[][] left={{0,0,1,15,10,14,0,0,0,0},{0,0,0,15,5,14,0,9,1,1},{0,-2,14,1,4,1,0,9,1,1}};
        float[][] right={{1,0,1,15,10,14,0,0,0,0},{1,0,0,15,5,14,0,9,1,1},{15,-2,14,1,4,1,0,9,1,1}};
        for(float[][] model:new float[][][]{single,left,right})for(float[] part:model)
            for(int facing=0;facing<4;facing++)for(int step=0;step<=360;step++)for(int corner=0;corner<8;corner++) {
                double x=(part[0]+((corner&1)==0?0:part[3]))/16.0;
                double y=(part[1]+((corner&2)==0?0:part[4]))/16.0;
                double z=(part[2]+((corner&4)==0?0:part[5]))/16.0;
                double a=part[9]==0?0:Math.toRadians(step),cos=Math.cos(a),sin=Math.sin(a);
                double rotatedY=y*cos-z*sin,rotatedZ=y*sin+z*cos;
                x+=part[6]/16.0;y=rotatedY+part[7]/16.0;z=rotatedZ+part[8]/16.0;
                a=facing*Math.PI/2;cos=Math.cos(a);sin=Math.sin(a);
                double rotatedX=(x-.5)*cos-(z-.5)*sin+.5;
                rotatedZ=(x-.5)*sin+(z-.5)*cos+.5;
                check(contains(bounds,rotatedX,y,rotatedZ),"all known chest model vertices stay in envelope");
            }
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx==0 || dz==0)
            for(int corner=0;corner<8;corner++)
                check(contains(bounds,dx+((corner&1)==0?0:1),((corner&2)==0?0:1),dz+((corner&4)==0?0:1)),"double chest neighbor block retained");
        System.out.println("Chest frustum CPU geometry: "+checks+" checks passed");
    }
    private static boolean contains(AABB box,double x,double y,double z) {
        return x>=box.minX && x<=box.maxX && y>=box.minY && y<=box.maxY && z>=box.minZ && z<=box.maxZ;
    }
}
