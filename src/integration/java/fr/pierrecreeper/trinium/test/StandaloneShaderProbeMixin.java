// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.test;

import fr.pierrecreeper.trinium.shaders.StandaloneShaders;
import fr.pierrecreeper.trinium.ui.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.*;
import java.util.*;

/** Offline copied-world probe only. Never packaged in the released mod. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class StandaloneShaderProbeMixin {
    @Unique private int trinium$ticks,trinium$stage,trinium$checks,trinium$failureShotDelay,trinium$failureStopDelay;
    @Unique private boolean trinium$ready,trinium$done;
    @Unique private ShaderAccess trinium$access;
    @Unique private Map<String,ShaderAccess.Choice> trinium$choices;
    @Unique private java.util.concurrent.CompletableFuture<Void> trinium$reload;
    @Unique private long trinium$beforeReload,trinium$offFrames,trinium$completedFrames,trinium$screenChangeFrame;
    @Unique private Screen trinium$renderedScreen;
    @Inject(method="onGameLoadFinished",at=@At("TAIL"))
    private void trinium$ready(CallbackInfo ci){trinium$ready=true;}
    @Inject(method="renderFrame",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render()V",shift=At.Shift.AFTER))
    private void trinium$rendered(boolean render,CallbackInfo ci){
        trinium$completedFrames++;
        trinium$renderedScreen=((Minecraft)(Object)this).gui.screen();
    }
    @Unique private void trinium$check(boolean value,String reason){if(!value)throw new AssertionError(reason);trinium$checks++;}
    @Unique private void trinium$expectRenderedScreen(String simpleName,String reason){
        Screen screen=((Minecraft)(Object)this).gui.screen();
        trinium$check(trinium$completedFrames>trinium$screenChangeFrame,"A real UI frame completed after "+reason);
        trinium$check(screen!=null&&screen.getClass().getName().equals("fr.pierrecreeper.trinium.ui."+simpleName),
            "Destination after "+reason+" must be "+simpleName+"\n"+trinium$uiLabels(screen));
        trinium$check(trinium$renderedScreen==screen,"Destination was rendered after "+reason);
    }
    @Unique private void trinium$click(String prefix){
        Minecraft mc=(Minecraft)(Object)this;Screen screen=mc.gui.screen();
        Button b=trinium$findButton(screen,prefix,false);
        if(b==null)throw new AssertionError("Missing button "+prefix+"\n"+trinium$uiLabels(screen));
        trinium$clickButton(screen,b,prefix);
    }
    @Unique private Button trinium$findButton(Screen screen,String label,boolean exact){
        if(screen==null)return null;
        return screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c)
            .filter(b->b.visible && (exact?b.getMessage().getString().equals(label):b.getMessage().getString().startsWith(label)))
            .findFirst().orElse(null);
    }
    @Unique private void trinium$clickButton(Screen screen,Button b,String prefix){
        trinium$check(b.active,"Active: "+prefix);
        var event=new MouseButtonEvent(b.getX()+b.getWidth()/2.,b.getY()+b.getHeight()/2.,new MouseButtonInfo(1,0));
        trinium$check(screen.mouseClicked(event,false),"Click accepted: "+prefix);screen.mouseReleased(event);
    }
    @Unique private void trinium$clickPagedOption(String prefix){
        Minecraft mc=(Minecraft)(Object)this;Screen screen=mc.gui.screen();
        Button option=trinium$findButton(screen,prefix,false);
        if(option!=null){trinium$clickButton(screen,option,prefix);return;}
        // Use the actual pagination controls, including when a previous test left
        // the screen on a later page. Never reach into the screen's draft or state.
        Set<String> previousPages=new HashSet<>();
        for(int step=0;step<64;step++){
            screen=mc.gui.screen();Button previous=trinium$findButton(screen,"<",true);
            if(previous==null||!previous.active)break;
            trinium$check(previousPages.add(trinium$uiLabels(screen)),"Previous-page navigation must advance");
            trinium$clickButton(screen,previous,"Previous options page");
        }
        Set<String> visited=new LinkedHashSet<>();
        for(int page=0;page<64;page++){
            screen=mc.gui.screen();
            trinium$check(visited.add(trinium$uiLabels(screen)),"Next-page navigation must advance");
            option=trinium$findButton(screen,prefix,false);
            if(option!=null){trinium$clickButton(screen,option,prefix);return;}
            Button next=trinium$findButton(screen,">",true);
            if(next==null||!next.active)break;
            trinium$clickButton(screen,next,"Next options page");
        }
        throw new AssertionError("Missing paged option "+prefix+" after "+visited.size()+" pages\n"+String.join("\n--- page ---\n",visited));
    }
    @Unique private String trinium$uiLabels(Screen screen){
        if(screen==null)return "No screen";
        StringBuilder details=new StringBuilder(screen.getClass().getName()).append(" ").append(screen.width).append("x").append(screen.height);
        for(var child:screen.children())if(child instanceof Button b)
            details.append("\n").append(b.getMessage().getString()).append(" active=").append(b.active).append(" visible=").append(b.visible)
                .append(" bounds=").append(b.getX()).append(',').append(b.getY()).append(',').append(b.getWidth()).append(',').append(b.getHeight());
        return details.toString();
    }
    @Unique private void trinium$shot(String name){Minecraft mc=(Minecraft)(Object)this;Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),1,m->{});}
    @Unique private void trinium$zip(String name,String fragment)throws Exception{
        String vertex="#version 120\nvarying vec2 uv;void main(){uv=gl_MultiTexCoord0.xy;gl_Position=ftransform();}";
        trinium$zipFiles(name,Map.of("shaders/final.vsh",vertex,"shaders/final.fsh",fragment));
    }
    @Unique private void trinium$zipFiles(String name,Map<String,String> files)throws Exception{
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(trinium$access.directory().resolve(name)))){
            for(var file:files.entrySet()){
                zip.putNextEntry(new java.util.zip.ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
    }
    @Unique private void trinium$offWithBlockedSettings()throws Exception{
        Minecraft mc=(Minecraft)(Object)this;
        Path game=mc.gameDirectory.toPath().toRealPath();
        trinium$check(FabricLoader.getInstance().isModLoaded("trinium-test-harness")&&mc.getSingleplayerServer()!=null
            &&Files.isDirectory(game.resolve("harness-classes"))&&Files.isRegularFile(game.resolve("mods/trinium-test-harness.jar")),
            "Settings fault is confined to the copied offline harness directory");
        Path settings=game.resolve("config/trinium/shaders.properties");
        trinium$check(Files.isRegularFile(settings,LinkOption.NOFOLLOW_LINKS)&&settings.toRealPath().startsWith(game),
            "Fault target is the copied regular shader configuration");
        byte[] original=Files.readAllBytes(settings);
        Path backup=settings.resolveSibling("shaders.properties.off-fault-backup");
        Path marker=settings.resolve("trinium-test-write-blocker");
        Files.move(settings,backup); // No replacement: preserve any prior failure evidence.
        boolean directoryCreated=false;
        try{
            Files.createDirectory(settings);directoryCreated=true;
            Files.writeString(marker,"Owned shader settings failure fixture.\n",StandardOpenOption.CREATE_NEW);
            ShaderAccess.Result result=trinium$access.apply(null,Map.of());
            trinium$check(!result.success(),"Off reports the failed persistence operation");
            trinium$check(result.message().startsWith("Shaders off for this session; settings could not be saved."),
                "Off explicitly distinguishes session state from unsaved settings");
            trinium$check(!trinium$access.state().enabled()&&!trinium$access.state().loaded(),
                "Off closes the runtime pipeline even when settings cannot be written");
            trinium$offFrames=StandaloneShaders.instance().renderedFrames();
        }finally{
            try{if(directoryCreated){Files.deleteIfExists(marker);Files.delete(settings);}}
            finally{Files.move(backup,settings);}
        }
        trinium$check(Arrays.equals(Files.readAllBytes(settings),original),"Fault fixture restores the original copied configuration bytes");
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void trinium$run(CallbackInfo ci){
        Minecraft mc=(Minecraft)(Object)this;
        if(trinium$done){
            if(trinium$failureShotDelay>0&&--trinium$failureShotDelay==0){
                trinium$failureStopDelay=100;
                try{Screenshot.grab(mc.gameDirectory,"standalone-failure.png",mc.gameRenderer.mainRenderTarget(),1,m->mc.execute(mc::stop));}
                catch(Throwable captureError){captureError.printStackTrace();mc.stop();}
            }else if(trinium$failureStopDelay>0&&--trinium$failureStopDelay==0)mc.stop();
            return;
        }
        if(!trinium$ready||mc.player==null||mc.gui.overlay()!=null)return;
        if(++trinium$ticks%30!=0)return;
        try{
            switch(trinium$stage++){
                case 0->{
                    trinium$check(!FabricLoader.getInstance().isModLoaded("iris")&&!FabricLoader.getInstance().isModLoaded("sodium"),"Neither Iris nor Sodium installed");
                    mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                    mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);
                    trinium$access=ShaderAccess.create();trinium$check(trinium$access instanceof StandaloneShaderAccess,"Independent backend selected");
                    trinium$check(trinium$access.packs().contains("Trinium-Clear.zip"),"Clear pack discovered");
                    if(Boolean.getBoolean("trinium.testShaderRestart")){
                        trinium$check("Trinium-Clear.zip".equals(trinium$access.state().activePack()),"Saved shader automatically restored after restart");
                        trinium$check(StandaloneShaders.instance().renderedFrames()>0,"Restored shader executes real world draws");
                        trinium$check(trinium$access.apply(null,Map.of()).success(),"Reset restored shader before UI tests");
                    }
                    trinium$shot("standalone-before");mc.gui.setScreen(new ShaderLibraryScreen(null));
                }
                case 1->{trinium$click("Trinium-Clear");trinium$check(!trinium$access.state().enabled(),"Selection does not load");trinium$click("Apply");}
                case 2->{
                    trinium$check("Trinium-Clear.zip".equals(trinium$access.state().activePack()),"Apply loads independently");
                    trinium$check(StandaloneShaders.instance().renderedFrames()>0,"Real world shader frames observed");
                    trinium$shot("standalone-library");trinium$choices=trinium$access.settings().choices();
                    trinium$check(trinium$choices.size()==3,"All Clear options discovered");trinium$click("Shader settings");trinium$screenChangeFrame=trinium$completedFrames;
                }
                case 3->{
                    trinium$expectRenderedScreen("ShaderOptionsScreen","opening shader settings");
                    trinium$clickPagedOption("Exposure:");
                    trinium$check(trinium$access.settings().choices().equals(trinium$choices),"Draft does not mutate active shader");
                    trinium$click("Cancel");trinium$screenChangeFrame=trinium$completedFrames;
                }
                case 4->{
                    // Cancel and reopen must be distinct UI actions on distinct
                    // rendered frames. A wrong destination fails; it is not retried.
                    trinium$expectRenderedScreen("ShaderLibraryScreen","cancelling the option draft");
                    trinium$check(trinium$access.settings().choices().equals(trinium$choices),"Cancel discards draft");
                    trinium$click("Shader settings");trinium$screenChangeFrame=trinium$completedFrames;
                }
                case 5->{
                    trinium$expectRenderedScreen("ShaderOptionsScreen","reopening shader settings");
                    trinium$clickPagedOption("Exposure:");trinium$click("Apply");
                }
                case 6->{
                    trinium$check(!trinium$access.settings().choices().get("EXPOSURE").current().equals(trinium$choices.get("EXPOSURE").current()),"Apply changes actual shader option");
                    trinium$shot("standalone-settings");trinium$click("Back");trinium$click("Done");
                }
                case 7->{
                    trinium$shot("standalone-clear");
                    String vertex="#version 120\nvarying vec2 uv;void main(){uv=gl_MultiTexCoord0.xy;gl_Position=ftransform();}";
                    // Five real HDR outputs, deliberately nonsequential logical
                    // targets. The next pass flips only target 3. Final reads ALL
                    // five buffers: clipped HDR/negative values, wrong routing or
                    // an incorrect flip breaks the unchanged (32,128,191) oracle.
                    String composite="#version 120\n/* RENDERTARGETS:4,2,1,3,0 */\n"
                        +"/* const int colortex0Format=RGBA16F;\nconst int colortex1Format=RGBA16F;\n"
                        +"const int colortex2Format=RGBA16F;\nconst int colortex3Format=RGBA16F;\nconst int colortex4Format=RGBA16F; */\n"
                        +"void main(){gl_FragData[0]=vec4(2.0);gl_FragData[1]=vec4(-.5);gl_FragData[2]=vec4(.75);gl_FragData[3]=vec4(1.5);gl_FragData[4]=vec4(4.0);}";
                    String composite1="#version 120\n/* DRAWBUFFERS:3 */\nvarying vec2 uv;uniform sampler2D colortex3;\n"
                        +"void main(){gl_FragData[0]=texture2D(colortex3,uv)+vec4(.5);}";
                    String finalFragment="#version 120\nvarying vec2 uv;uniform sampler2D colortex0,colortex1,colortex2,colortex3,colortex4;\n"
                        +"void main(){gl_FragColor=vec4(texture2D(colortex4,uv).r*texture2D(colortex0,uv).r/64.0,"
                        +"texture2D(colortex2,uv).r+texture2D(colortex3,uv).r*.5,texture2D(colortex1,uv).r,1.0);}";
                    trinium$zipFiles("Trinium-pixel-probe.zip",Map.of("shaders/composite.vsh",vertex,"shaders/composite.fsh",composite,
                        "shaders/composite1.vsh",vertex,"shaders/composite1.fsh",composite1,"shaders/final.vsh",vertex,"shaders/final.fsh",finalFragment));
                    trinium$check(trinium$access.apply("Trinium-pixel-probe.zip",Map.of()).success(),"Independent five-target MRT/HDR probe chain applied");
                }
                case 8->{
                    trinium$shot("standalone-pixels");
                    trinium$zip("Trinium-broken-probe.zip","#version 120\nnot valid GLSL");
                    trinium$check(!trinium$access.apply("Trinium-broken-probe.zip",Map.of()).success(),"Broken shader rejected");
                    trinium$check("Trinium-pixel-probe.zip".equals(trinium$access.state().activePack()),"Failed compile keeps previous GPU pipeline");
                    trinium$check(!trinium$access.apply("ComplementaryReimagined_r5.9.3.zip",Map.of()).success(),"Terrain/shadow pack explicitly refused");
                    trinium$check("Trinium-pixel-probe.zip".equals(trinium$access.state().activePack()),"Unsupported pack keeps previous shader");
                    trinium$offWithBlockedSettings();
                }
                case 9->{
                    trinium$check(StandaloneShaders.instance().renderedFrames()==trinium$offFrames,
                        "Unsaved Off stops actual shader draws across game frames");
                    trinium$check(trinium$access.apply("Trinium-pixel-probe.zip",Map.of()).success(),
                        "Pixel pipeline can be reactivated after the settings fault is removed");
                }
                case 10->{
                    trinium$check(StandaloneShaders.instance().renderedFrames()>trinium$offFrames&&trinium$access.state().loaded(),
                        "Reactivated pixel pipeline executes before the normal Off UI test");
                    mc.gui.setScreen(new ShaderLibraryScreen(null));trinium$click("Shaders off");trinium$click("Apply");
                }
                case 11->{trinium$check(!trinium$access.state().enabled(),"Off disables independent renderer");trinium$offFrames=StandaloneShaders.instance().renderedFrames();trinium$click("Done");}
                case 12->{
                    trinium$check(StandaloneShaders.instance().renderedFrames()==trinium$offFrames,"Off stops shader draws");trinium$shot("standalone-off");
                    trinium$check(trinium$access.apply("Trinium-Clear.zip",Map.of()).success(),"Clear reactivation");
                    trinium$check(trinium$access.settings().choices().get("EXPOSURE").current().equals("1.1"),"Pack settings survive switching to another shader");
                    trinium$check(trinium$access.apply("Trinium-Clear.zip",Map.of("EXPOSURE","1.0")).success(),"Restore exposure");
                    trinium$beforeReload=StandaloneShaders.instance().renderedFrames();trinium$reload=mc.reloadResourcePacks();
                }
                case 13->{
                    if(!trinium$reload.isDone()){trinium$stage--;break;}
                    trinium$check(!trinium$reload.isCompletedExceptionally(),"Resource reload succeeds");
                    trinium$check("Trinium-Clear.zip".equals(trinium$access.state().activePack()),"Shader survives texture reload");
                    trinium$check(StandaloneShaders.instance().renderedFrames()>trinium$beforeReload,"Shader rendering resumes after reload");
                    trinium$shot("standalone-after-reload");
                    org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(),1100,650);
                }
                case 14->{
                    trinium$check(mc.gameRenderer.mainRenderTarget().width==1100&&mc.gameRenderer.mainRenderTarget().height==650,"Main target resized");
                    trinium$check(trinium$access.state().loaded(),"Shader survives framebuffer resize");trinium$shot("standalone-resized");
                    trinium$check(StandaloneShaders.instance().lastError().isEmpty(),"No renderer fallback error");
                    trinium$check(fr.pierrecreeper.trinium.Trinium.rendererLine().equals("Renderer: Trinium 3D Render"),"Truthful Rust renderer label");
                    trinium$finish(null);
                }
            }
        }catch(Throwable error){trinium$finish(error);}
    }
    @Unique private void trinium$finish(Throwable error){
        Minecraft mc=(Minecraft)(Object)this;trinium$done=true;
        if(error!=null){
            error.printStackTrace();
            String details="Stage "+trinium$stage+", tick "+trinium$ticks+"\n"+error+"\n"+trinium$uiLabels(mc.gui.screen());
            System.err.println(details);
            try{Files.writeString(mc.gameDirectory.toPath().resolve("standalone-failure-ui.txt"),details+"\n");}
            catch(Exception diagnosticError){diagnosticError.printStackTrace();}
        }
        try{Files.writeString(mc.gameDirectory.toPath().resolve("standalone-result.json"),"{\"failed\":"+(error!=null)+",\"checks\":"+trinium$checks+",\"shaderFrames\":"+StandaloneShaders.instance().renderedFrames()+",\"iris\":false,\"sodium\":false}\n");}catch(Exception e){throw new RuntimeException(e);}
        if(error==null)mc.stop();
        else trinium$failureShotDelay=4; // Render the failed page before capturing it.
    }
}
