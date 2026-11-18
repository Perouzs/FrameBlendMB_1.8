package com.example.framemotionblur;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * True frame blending (box filter over a time window), no exponential tail.
 *
 * Output frame = time-weighted mean of all REAL frames rendered in the last `window` ms.
 *
 * To avoid storing one FBO per frame (1000 FPS * 16 ms = 16 frames), the window is split into
 * BUCKETS time slices. Each bucket holds the exact running mean of the frames that fell into it
 * (dt-weighted). The displayed frame = time-weighted mean of the live buckets. Old buckets are
 * discarded as soon as they leave the window, so nothing older than the window can ever show.
 *
 * Every blend step uses alpha >= ~1/BUCKETS-ish values (never tiny), which also avoids the
 * 8-bit rounding "stuck pixels" that a small-alpha moving average suffers at very high FPS.
 */
public final class BlurRenderer {
    private static final int BUCKETS = 4;

    private static final Framebuffer[] fb = new Framebuffer[BUCKETS];
    private static final double[] time = new double[BUCKETS]; // ms of real time in each bucket, 0 = empty
    private static int cur = 0;
    private static long lastNanos = 0;
    private static int bw = -1, bh = -1;

    private BlurRenderer() {}

    private static void reset() {
        for (int i = 0; i < BUCKETS; i++) time[i] = 0;
        cur = 0;
        lastNanos = 0;
    }

    public static void render(boolean enabled, int strength) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || strength <= 0 || mc.theWorld == null || !OpenGlHelper.isFramebufferEnabled()) {
            reset();
            return;
        }

        Framebuffer main = mc.getFramebuffer();
        int w = main.framebufferWidth, h = main.framebufferHeight;
        if (fb[0] == null || w != bw || h != bh) {
            for (int i = 0; i < BUCKETS; i++) {
                if (fb[i] != null) fb[i].deleteFramebuffer();
                fb[i] = new Framebuffer(w, h, false);
                fb[i].setFramebufferFilter(GL11.GL_LINEAR);
                fb[i].setFramebufferColor(0f, 0f, 0f, 1f);
            }
            bw = w; bh = h;
            reset();
        }

        long now = System.nanoTime();
        double dt = (lastNanos == 0) ? 1.0 : Math.min(100.0, (now - lastNanos) / 1.0e6);
        lastNanos = now;
        if (dt < 0.001) dt = 0.001;

        // strength 50 -> 16.7 ms window (= one 60 Hz frame, 360-degree shutter), 100 -> 33 ms
        double window = strength / 3.0;
        double slice = window / BUCKETS;

        // 1) start a new bucket when the current one is full
        if (time[cur] >= slice) {
            cur = (cur + 1) % BUCKETS;
            time[cur] = 0;
        }

        // 2) drop buckets that are completely outside the window (oldest first)
        double total = dt;
        for (int i = 0; i < BUCKETS; i++) total += time[i];
        for (int k = 1; k < BUCKETS; k++) {
            int idx = (cur + k) % BUCKETS; // k=1 is the oldest
            if (time[idx] > 0 && total - time[idx] >= window) {
                total -= time[idx];
                time[idx] = 0;
            }
        }

        setupState();

        // 3) add the real frame to the current bucket (exact dt-weighted running mean)
        float a = (time[cur] <= 0) ? 1f : (float) (dt / (time[cur] + dt));
        draw(main, fb[cur], a);
        time[cur] += dt;

        // 4) compose displayed frame = time-weighted mean of live buckets, oldest -> newest
        int live = 0;
        for (int i = 0; i < BUCKETS; i++) if (time[i] > 0) live++;
        if (live > 1) {
            double cum = 0;
            boolean first = true;
            for (int k = 1; k <= BUCKETS; k++) {
                int idx = (cur + k) % BUCKETS; // ends with cur (newest)
                if (time[idx] <= 0) continue;
                cum += time[idx];
                draw(fb[idx], main, first ? 1f : (float) (time[idx] / cum));
                first = false;
            }
        }
        // live == 1: the displayed frame is the real frame already in main, nothing to do

        main.bindFramebuffer(true);
        restoreState();
    }

    private static void draw(Framebuffer src, Framebuffer dst, float alpha) {
        dst.bindFramebuffer(true);
        if (alpha >= 0.999f) {
            GlStateManager.disableBlend();
        } else {
            GlStateManager.enableBlend();
            // raw GL call on purpose: GlStateManager's cache can be out of sync with real GL state
            // (OptiFine/mods call GL directly), which would silently turn this into a plain copy.
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        }
        src.bindFramebufferTexture();
        GlStateManager.color(1f, 1f, 1f, alpha);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(0, 1, 0).tex(0, 0).endVertex();
        wr.pos(1, 1, 0).tex(1, 0).endVertex();
        wr.pos(1, 0, 0).tex(1, 1).endVertex();
        wr.pos(0, 0, 0).tex(0, 1).endVertex();
        t.draw();
    }

    private static void setupState() {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, 1, 1, 0, -1, 1);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static void restoreState() {
        GlStateManager.bindTexture(0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }
}
