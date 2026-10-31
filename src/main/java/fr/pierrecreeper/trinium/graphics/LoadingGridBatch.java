// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/** One ordered element for the loading grid, avoiding quadratic GUI overlap searches.
 * Captures the game's own immutable rectangles, including pose, colors and clipping.
 * It never changes world loading, status reads, generation or the readiness condition.
 */
public final class LoadingGridBatch implements AutoCloseable {
    private static final ThreadLocal<LoadingGridBatch> CURRENT = new ThreadLocal<>();
    private static final int MAX_RECTANGLES = 16384;
    public static long batches, rectangles;
    private final GuiGraphicsExtractor extractor;
    private final LoadingGridBatch parent;
    private final List<ColoredRectangleRenderState> pending = new ArrayList<>();
    private GuiRenderState target;
    private boolean flushing;

    public LoadingGridBatch(GuiGraphicsExtractor extractor) {
        this.extractor = extractor;
        parent = CURRENT.get();
        if (parent != null) parent.flush();
        CURRENT.set(this);
    }

    public static boolean capture(GuiGraphicsExtractor extractor, GuiRenderState state,
                                  GuiElementRenderState element) {
        LoadingGridBatch batch = CURRENT.get();
        if (batch == null || batch.flushing || batch.extractor != extractor) return false;
        if (!(element instanceof ColoredRectangleRenderState rectangle)) {
            batch.flush();
            return false;
        }
        // Invisible rectangles are discarded by the original layer builder as well.
        if (rectangle.bounds() == null) return true;
        if (!batch.pending.isEmpty()) {
            var first = batch.pending.getFirst();
            if (state != batch.target || first.pipeline() != rectangle.pipeline()
                    || !Objects.equals(first.textureSetup(), rectangle.textureSetup())
                    || !Objects.equals(first.scissorArea(), rectangle.scissorArea())
                    || batch.pending.size() == MAX_RECTANGLES) batch.flush();
        }
        batch.target = state;
        batch.pending.add(rectangle);
        return true;
    }

    /** Keep foreign submissions and layer changes in their original extraction order. */
    public static void flushBeforeOtherSubmission(GuiRenderState state) {
        LoadingGridBatch batch = CURRENT.get();
        if (batch != null && !batch.flushing && batch.target == state) batch.flush();
    }

    private void flush() {
        if (pending.isEmpty() || flushing) return;
        flushing = true;
        try {
            if (pending.size() == 1) target.addGuiElement(pending.getFirst());
            else {
                target.addGuiElement(new Element(List.copyOf(pending)));
                batches++;
                rectangles += pending.size();
            }
            pending.clear();
            target = null;
        } finally {
            // addGuiElement itself calls up() while resolving overlapping layers.
            flushing = false;
        }
    }

    @Override public void close() {
        try { flush(); }
        finally { if (parent == null) CURRENT.remove(); else CURRENT.set(parent); }
    }

    public static final class Element implements GuiElementRenderState {
        private final List<ColoredRectangleRenderState> parts;
        private final ScreenRectangle bounds;
        public Element(List<ColoredRectangleRenderState> parts) {
            this.parts = List.copyOf(parts);
            if (parts.isEmpty()) throw new IllegalArgumentException("Empty loading grid");
            int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
            for (var part : parts) {
                ScreenRectangle b = Objects.requireNonNull(part.bounds());
                left = Math.min(left, b.left()); top = Math.min(top, b.top());
                right = Math.max(right, b.right()); bottom = Math.max(bottom, b.bottom());
            }
            bounds = new ScreenRectangle(left, top, right - left, bottom - top);
        }
        public void buildVertices(VertexConsumer consumer) {
            for (var part : parts) part.buildVertices(consumer);
        }
        public RenderPipeline pipeline() { return parts.getFirst().pipeline(); }
        public TextureSetup textureSetup() { return parts.getFirst().textureSetup(); }
        public ScreenRectangle scissorArea() { return parts.getFirst().scissorArea(); }
        public ScreenRectangle bounds() { return bounds; }
    }
}
