package fr.pierrecreeper.trinium.test;

import fr.pierrecreeper.trinium.ui.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.*;
import java.util.*;

/** Offline UI test only. Never packaged with the user's mod. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class UiProbeMixin {
    @Unique private boolean trinium$ready,trinium$done;
    @Unique private int trinium$ticks,trinium$stage,trinium$checks,trinium$initialDistance,trinium$alphaFixtureChecks;
    @Unique private OptionsScreen trinium$options;
    @Unique private IrisAccess trinium$iris;
    @Unique private String trinium$pack;
    @Unique private Map<String,IrisAccess.Choice> trinium$originalChoices;
    @Inject(method="onGameLoadFinished",at=@At("TAIL"))
    private void trinium$ready(CallbackInfo ci){trinium$ready=true;}
    @Unique private void trinium$check(boolean value,String reason){if(!value)throw new AssertionError(reason);trinium$checks++;}
    @Unique private Button trinium$button(String prefix){
        Minecraft mc=(Minecraft)(Object)this;
        return mc.gui.screen().children().stream().filter(c->c instanceof Button)
            .map(c->(Button)c).filter(b->b.getMessage().getString().startsWith(prefix)).findFirst().orElseThrow(()->new AssertionError("Missing button "+prefix));
    }
    @Unique private void trinium$click(String prefix){
        Minecraft mc=(Minecraft)(Object)this;Screen screen=mc.gui.screen();Button b=trinium$button(prefix);
        trinium$check(b.active,"Inactive button "+prefix);
        MouseButtonEvent event=new MouseButtonEvent(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,new MouseButtonInfo(1,0));
        System.out.println("TRINIUM_UI_CLICK "+prefix+" at "+event.x()+","+event.y()+" target="+screen.getChildAt(event.x(),event.y()).map(c->c instanceof AbstractWidget w?w.getMessage().getString():c.toString()).orElse("none"));
        trinium$check(screen.mouseClicked(event,false),"Mouse click accepted: "+prefix);screen.mouseReleased(event);
    }
    @Unique private void trinium$shot(String name){Minecraft mc=(Minecraft)(Object)this;Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),1,m->{});}
    @Unique private void trinium$bounds(){Minecraft mc=(Minecraft)(Object)this;Screen screen=mc.gui.screen();for(var child:screen.children())if(child instanceof AbstractWidget w&&w.visible)trinium$check(w.getX()>=0&&w.getY()>=0&&w.getRight()<=screen.width&&w.getBottom()<=screen.height,"Widget bounds: "+w.getMessage().getString());}
    @Unique private AbstractWidget trinium$setting(String prefix) {
        Minecraft mc=(Minecraft)(Object)this;
        return mc.gui.screen().children().stream().filter(c->c instanceof AbstractWidget)
            .map(c->(AbstractWidget)c).filter(w->w.getMessage().getString().startsWith(prefix+": "))
            .findFirst().orElseThrow(()->new AssertionError("Missing setting "+prefix));
    }
    @Unique private void trinium$expandedVideo() {
        Minecraft mc=(Minecraft)(Object)this;
        int fps=mc.options.framerateLimit().get(),distance=mc.options.renderDistance().get(),simulation=mc.options.simulationDistance().get();
        boolean vsync=mc.options.enableVsync().get();
        String[] tabs={"Display","Quality","Textures","Effects"};int[] counts={13,10,6,8};
        for(int i=0;i<tabs.length;i++) {
            trinium$click(tabs[i]);
            long count=mc.gui.screen().children().stream().filter(c->c instanceof AbstractWidget w&&w.getMessage().getString().contains(": ")).count();
            trinium$check(count==counts[i],"Expanded controls in "+tabs[i]);trinium$bounds();
        }
        for(String name:new String[]{"FOV effects","Darkness effects","Damage tilt","Enchantment glint speed","Enchantment glint strength"})
            trinium$check(trinium$setting(name) instanceof AbstractSliderButton,"Small numeric slider: "+name);
        double originalGlint=mc.options.glintSpeed().get();
        AbstractWidget glint=trinium$setting("Enchantment glint speed");
        var glintClick=new MouseButtonEvent(glint.getX()+glint.getWidth()*(originalGlint<0.5?0.75:0.25),glint.getY()+glint.getHeight()/2.0,new MouseButtonInfo(1,0));
        trinium$check(mc.gui.screen().mouseClicked(glintClick,false),"New effect slider press");
        trinium$check(mc.options.glintSpeed().get()==originalGlint,"New effect waits for release");
        mc.gui.screen().mouseReleased(glintClick);
        trinium$check(mc.options.glintSpeed().get()!=originalGlint,"New effect slider commits");
        mc.options.glintSpeed().set(originalGlint);
        trinium$click("Display");
        trinium$check(mc.options.glintSpeed().get()==originalGlint,"Untouched slider does not overwrite external restoration on exit");
        Screen screen=mc.gui.screen();
        AbstractWidget render=trinium$setting("Render distance");int viewportTop=render.getY();
        trinium$check(!trinium$setting("Panorama speed").visible,"Overflow controls start hidden");
        for(int i=0;i<20;i++)screen.mouseScrolled(render.getX()+5,viewportTop+5,0,-1);
        AbstractWidget last=trinium$setting("Panorama speed");
        trinium$check(last.visible&&!render.visible,"Mouse wheel reaches final setting");trinium$bounds();
        screen.setFocused(last);last.setFocused(true);
        trinium$check(screen.keyPressed(new KeyEvent(InputConstants.KEY_HOME,InputConstants.KEY_HOME,0)),"Home scrolls to first setting");
        trinium$check(render.visible&&!last.visible,"Home viewport");
        trinium$check(screen.keyPressed(new KeyEvent(InputConstants.KEY_END,InputConstants.KEY_END,0)),"End scrolls to final setting");
        trinium$check(last.visible,"End viewport");
        screen.keyPressed(new KeyEvent(InputConstants.KEY_HOME,InputConstants.KEY_HOME,0));
        int viewportBottom=screen.children().stream().filter(c->c instanceof AbstractWidget w&&w.visible&&w.getMessage().getString().contains(": ")).map(c->(AbstractWidget)c).mapToInt(AbstractWidget::getBottom).max().orElseThrow()+1;
        double barX=trinium$button("Done").getRight()+1;
        var barPress=new MouseButtonEvent(barX,viewportBottom-1,new MouseButtonInfo(1,0));
        trinium$check(screen.mouseClicked(barPress,false),"Scrollbar accepts click");
        trinium$check(last.visible,"Scrollbar reaches bottom");
        var barDrag=new MouseButtonEvent(barX,viewportTop,new MouseButtonInfo(1,0));
        trinium$check(screen.mouseDragged(barDrag,0,viewportTop-viewportBottom),"Scrollbar accepts drag");screen.mouseReleased(barDrag);
        trinium$check(render.visible,"Scrollbar returns to top");
        AbstractWidget scale=trinium$setting("Interface scale");
        trinium$check(scale instanceof AbstractSliderButton,"Interface scale is a numeric slider");
        int originalScale=mc.options.guiScale().get();
        var scaleClick=new MouseButtonEvent(originalScale==0?scale.getRight()-4:scale.getX()+4,scale.getY()+scale.getHeight()/2.0,new MouseButtonInfo(1,0));
        trinium$check(screen.mouseClicked(scaleClick,false),"GUI scale slider press");screen.mouseReleased(scaleClick);
        trinium$check(mc.options.guiScale().get()!=originalScale,"GUI scale callback safely rebuilds screen");trinium$bounds();
        mc.options.guiScale().set(originalScale);
        trinium$check(mc.gui.screen()==screen,"Scale restoration preserves video screen");
        int originalWidth=screen.width,originalHeight=screen.height;
        for(int[] size:new int[][]{{320,240},{480,270},{960,540}}) {
            screen.resize(size[0],size[1]);trinium$bounds();
            trinium$check(trinium$button("Done").visible,"Done remains reachable after layout resize");
            trinium$check(trinium$setting("Render distance").visible,"First row remains reachable after resize");
        }
        screen.resize(originalWidth,originalHeight);
        trinium$check(mc.options.framerateLimit().get()==fps&&mc.options.renderDistance().get()==distance
            &&mc.options.simulationDistance().get()==simulation&&mc.options.enableVsync().get()==vsync,"Extra navigation preserves FPS and quality settings");
        trinium$click("Textures");
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void trinium$run(CallbackInfo ci){
        Minecraft mc=(Minecraft)(Object)this;
        if(!trinium$ready||trinium$done||mc.gui.overlay()!=null)return;
        if(Boolean.getBoolean("trinium.testUiWorld")&&mc.player==null)return;
        if(mc.gui.screen()!=null)for(var child:mc.gui.screen().children())if(child instanceof AbstractWidget w)w.setTooltipDelay(java.time.Duration.ofSeconds(60));
        if(++trinium$ticks%25!=0)return;
        try{
            switch(trinium$stage++){
                case 0->{
                    mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                    trinium$initialDistance=mc.options.renderDistance().get();
                    trinium$options=new OptionsScreen(mc.gui.screen(),mc.options);mc.gui.setScreen(trinium$options);
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(trinium$options,mc,mc.options));
                    org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(mc.getWindow().handle(),8f,8f);
                    trinium$check(mc.gui.screen() instanceof VideoSettingsScreen,"Video entry interception");
                    trinium$check(mc.options.renderDistance().get()==trinium$initialDistance,"Menu silently changed distance");
                }
                case 1->{
                    trinium$bounds();trinium$shot("ui-video");
                    boolean before=mc.options.enableVsync().get();trinium$click("Vertical sync");
                    trinium$check(mc.options.enableVsync().get()!=before,"VSync toggle");trinium$click("Vertical sync");
                    trinium$check(mc.options.enableVsync().get()==before,"VSync restore");
                    var gamma=mc.gui.screen().children().stream().filter(c->c instanceof AbstractSliderButton w&&w.getMessage().getString().startsWith("Brightness")).map(c->(AbstractSliderButton)c).findFirst().orElseThrow();
                    double oldGamma=mc.options.gamma().get();
                    var press=new MouseButtonEvent(gamma.getX()+gamma.getWidth()*0.3,gamma.getY()+gamma.getHeight()/2.0,new MouseButtonInfo(1,0));
                    var release=new MouseButtonEvent(gamma.getX()+gamma.getWidth()*0.7,gamma.getY()+gamma.getHeight()/2.0,new MouseButtonInfo(1,0));
                    trinium$check(mc.gui.screen().mouseClicked(press,false),"Slider mouse press accepted");
                    trinium$check(mc.gui.screen().mouseDragged(release,gamma.getWidth()*0.4,0),"Slider drag accepted");
                    trinium$check(mc.options.gamma().get()==oldGamma,"Slider waits until release");
                    mc.gui.screen().mouseReleased(release);
                    trinium$check(mc.options.gamma().get()!=oldGamma,"Slider release commits actual option");mc.options.gamma().set(oldGamma);
                    var slider=mc.gui.screen().children().stream().filter(c->c instanceof AbstractSliderButton w&&w.getMessage().getString().startsWith("Render distance")).map(c->(AbstractSliderButton)c).findFirst().orElseThrow();
                    slider.setFocused(true);if(!slider.keyPressed(new KeyEvent(80,80,0)))slider.keyPressed(new KeyEvent(40,40,0));
                    for(int i=0;i<12;i++)slider.keyPressed(new KeyEvent(80,80,0));
                    trinium$check(mc.options.renderDistance().get()!=trinium$initialDistance,"Keyboard slider changes actual option");
                    trinium$click("Quality");
                }
                case 2->{trinium$bounds();trinium$shot("ui-quality");boolean before=mc.options.entityShadows().get();trinium$click("Entity shadows");trinium$check(mc.options.entityShadows().get()!=before,"Entity shadow toggle");trinium$click("Entity shadows");trinium$click("Textures");}
                case 3->{trinium$bounds();trinium$shot("ui-textures");trinium$expandedVideo();trinium$alphaFixtureChecks=UiAlphaRegressionProbe.run(mc);trinium$click("Shaders");trinium$check(mc.gui.screen() instanceof ShaderLibraryScreen,"Shader navigation");}
                case 4->{
                    trinium$bounds();trinium$shot("ui-shaders");trinium$iris=new IrisAccess();
                    if(!trinium$iris.available()){
                        trinium$check(!trinium$button("Apply").active,"No Iris has disabled Apply");trinium$check(trinium$button("Open folder").active,"Folder remains available");
                        trinium$stage=12;
                    }else{
                        trinium$pack=trinium$iris.state().activePack();trinium$check(trinium$pack!=null,"Iris current pack loaded");
                        trinium$check(trinium$iris.packs().contains(trinium$pack),"Pack listed");
                        trinium$originalChoices=trinium$iris.settings().choices();trinium$check(!trinium$originalChoices.isEmpty(),"Real shader options");
                        trinium$click("Shader settings");
                    }
                }
                case 5->{
                    trinium$bounds();trinium$shot("ui-shader-options");
                    Button control=mc.gui.screen().children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().contains(": ")).map(c->(Button)c).findFirst().orElse(null);
                    if(control==null){trinium$click("Defaults");}else trinium$click(control.getMessage().getString());
                    trinium$check(trinium$iris.settings().choices().equals(trinium$originalChoices),"Unapplied settings remain unchanged");
                    mc.gui.screen().onClose();trinium$check(mc.gui.screen() instanceof ShaderLibraryScreen,"Settings cancel returns to library");
                    trinium$check(trinium$iris.settings().choices().equals(trinium$originalChoices),"Cancel discards draft");
                    trinium$click("Shaders off");trinium$click("Apply");
                }
                case 6->{trinium$check(!trinium$iris.state().enabled(),"Disable shaders");trinium$shot("ui-shaders-off");trinium$click("Complementary");trinium$click("Apply");}
                case 7->{trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Re-enable shader");
                    Path bad=trinium$iris.directory().resolve("Trinium-corrupt-test.zip");Files.writeString(bad,"not a ZIP archive");
                    try{var result=trinium$iris.apply(bad.getFileName().toString(),Map.of());trinium$check(!result.success(),"Invalid shader rejected");trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Failed shader restores previous pack");}finally{Files.deleteIfExists(bad);}
                }
                case 8->{
                    for(int i=0;i<36;i++){
                        Path file=trinium$iris.directory().resolve(String.format(Locale.ROOT,"Trinium-list-test-%02d.zip",i));
                        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){
                            zip.putNextEntry(new java.util.zip.ZipEntry("shaders/shaders.properties"));zip.closeEntry();
                        }
                    }
                    trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Shader active before resize");
                    Screen list=mc.gui.screen();list.resize(list.width,list.height);
                    trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Resize does not apply shader");
                    trinium$check(trinium$iris.packs().size()>=37,"Long shader list discovered");
                    for(int i=0;i<50;i++)list.mouseScrolled(list.width/2.0,list.height/2.0,0,-1);
                    trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Scrolling does not apply shader");
                    trinium$click("Trinium-list-test-35");
                    trinium$check(Objects.equals(trinium$pack,trinium$iris.state().activePack()),"Selecting a row does not compile/apply");
                    trinium$check(trinium$button("Apply").active,"New selection enables Apply");
                    list.keyPressed(new KeyEvent(74,74,0));
                    trinium$check(trinium$button("Shaders off").active,"Home reaches first row");
                    for(int i=0;i<36;i++)Files.deleteIfExists(trinium$iris.directory().resolve(String.format(Locale.ROOT,"Trinium-list-test-%02d.zip",i)));
                    list.resize(list.width,list.height);
                    trinium$check(!trinium$button("Apply").active,"Deleted draft restores active selection");
                    trinium$stage=12;
                }
                case 12->{trinium$click("Done");trinium$check(mc.gui.screen() instanceof VideoSettingsScreen,"Library returns to video");trinium$click("Done");trinium$check(mc.gui.screen()==trinium$options,"Video returns to Options");}
                case 13->{String options=Files.readString(mc.gameDirectory.toPath().resolve("options.txt"));trinium$check(options.contains("renderDistance:"+mc.options.renderDistance().get()+"\n"),"Changes saved");trinium$finish(null);}
            }
        }catch(Throwable error){error.printStackTrace();trinium$finish(error);}
    }
    @Unique private void trinium$finish(Throwable error){
        Minecraft mc=(Minecraft)(Object)this;trinium$done=true;
        if(trinium$iris!=null&&trinium$iris.available())for(int i=0;i<36;i++)try{Files.deleteIfExists(trinium$iris.directory().resolve(String.format(Locale.ROOT,"Trinium-list-test-%02d.zip",i)));}catch(Exception ignored){}
        try{String message=error==null?"":error.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ");Files.writeString(mc.gameDirectory.toPath().resolve("ui-result.json"),"{\"checks\":"+trinium$checks+",\"alphaFixtureChecks\":"+trinium$alphaFixtureChecks+",\"failed\":"+(error!=null)+",\"error\":\""+message+"\"}\n");}catch(Exception e){e.printStackTrace();}mc.stop();
    }
}
