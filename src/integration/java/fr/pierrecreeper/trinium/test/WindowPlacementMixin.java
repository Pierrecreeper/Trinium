package fr.pierrecreeper.trinium.test;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;

/** Test windows only: choose a physical monitor before creating/showing the window. */
@Mixin(value=Window.class,remap=false)
public abstract class WindowPlacementMixin {
    @Redirect(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
    private long trinium$hidden(com.mojang.renderpearl.api.device.GpuBackend backend,String title,int width,int height,long flags) {
        // A window-manager maximize/restore can otherwise change the number of
        // rendered pixels halfway through an unattended comparison.
        long fixedFlags=(flags & ~SDLVideo.SDL_WINDOW_MAXIMIZED & ~SDLVideo.SDL_WINDOW_RESIZABLE)
                | SDLVideo.SDL_WINDOW_HIDDEN;
        if (Boolean.getBoolean("trinium.testBorderless")) fixedFlags |= SDLVideo.SDL_WINDOW_BORDERLESS;
        return backend.createWindow(title,width,height,fixedFlags);
    }
    @Redirect(method="setWindowSizeAndPosition",at=@At(value="INVOKE",target="Lorg/lwjgl/sdl/SDLVideo;SDL_SetWindowPosition(JII)Z"))
    private boolean trinium$place(long window,int originalX,int originalY) {
        int x=originalX,y=originalY;
        int requested=Integer.getInteger("trinium.testMonitor",1);
        var displays=SDLVideo.SDL_GetDisplays();
        var bounds=new ArrayList<int[]>();
        if(displays!=null)try {
            for(int i=0;i<displays.remaining();i++)try(var rect=SDL_Rect.calloc()) {
                int display=displays.get(i);
                if(SDLVideo.SDL_GetDisplayBounds(display,rect))bounds.add(new int[]{rect.x(),rect.y(),rect.w(),rect.h(),display});
            }
        }finally{SDLStdinc.SDL_free(displays);}
        bounds.sort(Comparator.<int[]>comparingInt(a->a[0]).thenComparingInt(a->a[1]));
        if(requested>=1 && requested<=bounds.size()) {
            int[] b=bounds.get(requested-1);x=b[0]+Math.max(0,(b[2]-Integer.getInteger("trinium.testWidth",960))/2);y=b[1]+Math.max(0,(b[3]-Integer.getInteger("trinium.testHeight",540))/2);
            System.out.println("TRINIUM_TEST_MONITOR "+requested+" "+SDLVideo.SDL_GetDisplayName(b[4])+" x="+x+" y="+y);
        } else if(requested>1)throw new IllegalStateException("Requested test monitor unavailable: "+requested);
        SDLVideo.SDL_SetWindowResizable(window,false);
        if((SDLVideo.SDL_GetWindowFlags(window)&SDLVideo.SDL_WINDOW_MAXIMIZED)!=0)
            SDLVideo.SDL_RestoreWindow(window);
        if(!SDLVideo.SDL_SetWindowSize(window,Integer.getInteger("trinium.testWidth",960),Integer.getInteger("trinium.testHeight",540)))
            throw new IllegalStateException("Could not size test window: "+SDLError.SDL_GetError());
        boolean positioned=SDLVideo.SDL_SetWindowPosition(window,x,y);
        if(!positioned)throw new IllegalStateException("Could not place test window: "+SDLError.SDL_GetError());
        SDLVideo.SDL_ShowWindow(window);return true;
    }
}
