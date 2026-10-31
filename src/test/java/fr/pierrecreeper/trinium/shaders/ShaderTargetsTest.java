// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.nio.file.*;
import java.util.*;

/** CPU contracts for target routing, allocation bounds, directives and refusal. */
public final class ShaderTargetsTest {
    private static int checks;
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); checks++; }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); throw new AssertionError("Accepted " + reason); }
        catch (IllegalArgumentException | ArithmeticException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        var routed = ShaderTargets.parse("composite", "", "/* RENDERTARGETS:4, 2, 1, 3, 0 */\n"
            + "/* const int colortex4Format = RGBA16F; */\nconst int gnormalFormat = RG16F;\n"
            + "const bool gaux1MipmapEnabled = true;\nconst bool colortex3Clear = false;\n"
            + "const vec4 colortex3ClearColor = vec4 ( .1f, +.2, 3e-1, 1.0 );\n");
        check(routed.outputs().equals(List.of(4,2,1,3,0)), "Ordered render targets");
        check(routed.formats().get(4)==ShaderTargets.Format.RGBA16F, "Format declarations in block comments");
        check(routed.formats().get(2)==ShaderTargets.Format.RG16F, "Legacy buffer alias");
        check(routed.mipmaps().equals(Set.of(4)), "Pass-local mipmap request");
        check(routed.clearColors().get(3).equals(new ShaderTargets.Color(.1f,.2f,.3f,1)), "Float suffix and literal clear color");
        var layout=ShaderTargets.layout(List.of(routed),Set.of(0,1,2,3,4));
        check(layout.size()==5 && !layout.containsKey(5), "Allocate only used targets");
        check(!layout.get(3).clear() && layout.get(3).written(), "Persistent auxiliary history");
        check(layout.get(1).clearColor().equals(new ShaderTargets.Color(1,1,1,1)), "Buffer 1 default white");
        check(layout.get(2).clearColor().equals(new ShaderTargets.Color(0,0,0,0)), "Auxiliary default black");
        var one=ShaderTargets.layout(List.of(ShaderTargets.parse("final","","")),Set.of(0));
        check(ShaderTargets.imageBytes(one,16,8,false)==512, "Identity needs one image, not eight target pairs");
        var mip=ShaderTargets.layout(List.of(ShaderTargets.parse("composite","","/* DRAWBUFFERS:3 */\nconst int colortex3Format=RGBA16F;\nconst bool colortex3MipmapEnabled=true;")),Set.of());
        check(ShaderTargets.imageBytes(mip,3,5,true)==3*5*8+(15+2+1)*8*2, "Exact odd-size mip chain and ping-pong memory");
        check(ShaderTargets.imageBytes(layout,16384,16384,true)>ShaderTargets.MAX_IMAGE_BYTES, "Large allocations are detectable before GL");
        rejects(()->ShaderTargets.imageBytes(layout,Integer.MAX_VALUE,Integer.MAX_VALUE,true),"size overflow");
        rejects(()->ShaderTargets.parse("composite","","/* DRAWBUFFERS:11 */"),"duplicate targets");
        rejects(()->ShaderTargets.parse("composite","","/* RENDERTARGETS:8 */"),"target above supported range");
        rejects(()->ShaderTargets.parse("final","","/* DRAWBUFFERS:01 */"),"multiple final outputs");
        rejects(()->ShaderTargets.parse("composite","","#if 0\nconst int colortex1Format=RGBA32F;\n#endif"),"conditional metadata");
        rejects(()->ShaderTargets.parse("composite","","const int colortex1Format=RGBA32UI;"),"unsupported integer format");
        rejects(()->ShaderTargets.parse("composite","","const bool colortex1Clear=EXPRESSION;"),"nonliteral metadata");
        rejects(()->ShaderTargets.parse("composite","","const vec4 colortex1ClearColor=vec4(1e999);"),"nonfinite clear");
        rejects(()->ShaderTargets.parse("composite","","/* DRAWBUFFERS:12 */\n/* RENDERTARGETS:2,1 */"),"conflicting routes");
        rejects(()->ShaderTargets.layout(List.of(ShaderTargets.parse("composite","","const bool colortex0Clear=false;")),Set.of()),"false scene history");
        rejects(()->ShaderTargets.layout(List.of(ShaderTargets.parse("composite","","const int colortex1Format=R16F;"),ShaderTargets.parse("final","","const int colortex1Format=R32F;")),Set.of()),"conflicting global formats");
        var commented=ShaderTargets.parse("composite","","// /* DRAWBUFFERS:45 */\n// const int colortex1Format=RGBA32UI;\n/* DRAWBUFFERS:21 */");
        check(commented.outputs().equals(List.of(2,1)) && commented.formats().isEmpty(),"Line comments do not declare metadata");
        String stripped=ShaderTargets.stripConstants("const int colortex1Format = RGBA16F;\nvoid main(){}\n");
        check(!stripped.contains("RGBA16F") && stripped.lines().count()==2,"Remove active loader declaration and retain line numbers");
        Path root=Files.createTempDirectory("trinium-targets-cpu-");
        try {
            String f="/* DRAWBUFFERS:41 */\nconst int colortex4Format=RGBA16F;\nvoid main(){gl_FragData[0]=vec4(2.0);gl_FragData[1]=vec4(.5);}";
            StandalonePackTest.zip(root.resolve("mrt.zip"),Map.of("shaders/composite.vsh",StandalonePackTest.VERTEX,"shaders/composite.fsh",f));
            var pass=new StandalonePack(root,"mrt.zip").passes("0",Map.of()).getFirst();
            check(pass.targets().outputs().equals(List.of(4,1)),"Pack reader carries output routing to renderer");
            check(pass.fragment().contains("layout(location=1) out vec4 trinium_FragColor1") && !pass.fragment().contains("gl_FragData"),"Real multiple fragment outputs generated");
            check(!pass.fragment().contains("const int colortex4Format"),"Loader format enum is not sent to GLSL");
        } finally { try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
        System.out.println("PASS standalone shader targets CPU: "+checks+" checks");
    }
}
