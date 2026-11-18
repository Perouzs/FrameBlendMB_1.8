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
 * Displayed frame = time-weighted mean of all REAL frames rendered in the last `window` ms.
 *
 * The window is split into N buckets, where N adapts to the current FPS so that each bucket
 * holds about ONE real frame (N = window / avgFrameTime, clamped to 2..MAX_N).
 *   255 FPS, window 16.7 ms -> N ~ 4  (1 frame per bucket)
 *   960 FPS, window 16.7 ms -> N = 8  (~2 frames per bucket)
 * Each bucket stores the exact dt-weighted mean of the frames inside it.
 *
 * Compose: walking from the newest bucket backwards, each bucket gets weight min(its time, time
 * still missing from the window). So the OLDEST bucket is cut partially and the window length is
 * exactly `window` at all times (no flicker from whole buckets entering/leaving).
 */
public final class BlurRenderer {
    private static final int MAX_N = 8;
    private static final int RING = MAX_N + 1; // +1 so a partially covered oldest bucket still exists

    private static final Framebuffer[] fb = new Framebuffer[RING]; // allocated lazily
    private static final double[] time = new double[RING];   // ms of real time stored in bucket, 0 = empty
    private static final double[] weight = new double[RING]; // ms of that bucket inside the window
    private static int cur = 0;
    private static long lastNanos = 0;
    private static double avgDt = 0;
    private static int bw = -1, bh = -1;

    /** For /motionblur info. */
    public static volatile int debugBuckets = 0;
    public static volatile double debugAvgFps = 0;

    private BlurRenderer() {}

    private static void reset() {
        for (int i = 0; i < RING; i++) { time[i] = 0; weight[i] = 0; }
        cur = 0;
        lastNanos = 0;
        avgDt = 0;
    }

    private static void freeBuffers() {
        for (int i = 0; i < RING; i++) {
            if (fb[i] != null) { fb[i].deleteFramebuffer(); fb[i] = null; }
        }
    }

    private static Framebuffer ensure(int i, int w, int h) {
        if (fb[i] == null) {
            fb[i] = new Framebuffer(w, h, false);
            fb[i].setFramebufferFilter(GL11.GL_LINEAR);
            fb[i].setFramebufferColor(0f, 0f, 0f, 1f);
        }
        return fb[i];
    }

    public static void render(boolean enabled, int strength) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || strength <= 0 || mc.theWorld == null || !OpenGlHelper.isFramebufferEnabled()) {
            reset();
            return;
        }

        Framebuffer main = mc.getFramebuffer();
        int w = main.framebufferWidth, h = main.framebufferHeight;
        if (w != bw || h != bh) {
            freeBuffers();
            bw = w; bh = h;
            reset();
        }

        long now = System.nanoTime();
        double dt = (lastNanos == 0) ? 1.0 : Math.min(100.0, (now - lastNanos) / 1.0e6);
        lastNanos = now;
        if (dt < 0.001) dt = 0.001;
        avgDt = (avgDt <= 0) ? dt : avgDt + 0.1 * (dt - avgDt);

        // strength 50 -> 16.7 ms window (= one 60 Hz frame, 360-degree shutter), 100 -> 33 ms
        double window = strength / 3.0;

        // adaptive bucket count: ~1 real frame per bucket, capped for GPU cost
        int n = (int) Math.round(window / avgDt);
        n = Math.max(2, Math.min(MAX_N, n));
        double slice = window / n;
        debugBuckets = n;
        debugAvgFps = 1000.0 / avgDt;

        // 1) move to a new bucket when adding this frame would make the current one overshoot
        //    (add the frame if that brings the bucket closer to `slice`, else rotate)
        if (time[cur] > 0 && time[cur] + 0.5 * dt >= slice) {
            cur = (cur + 1) % RING;
            time[cur] = 0;
        }

        setupState();

        // 2) add the real frame into the current bucket (exact dt-weighted running mean)
        Framebuffer curFb = ensure(cur, w, h);
        float a = (time[cur] <= 0) ? 1f : (float) (dt / (time[cur] + dt));
        draw(main, curFb, a);
        time[cur] += dt;

        // 3) per-bucket weights, newest -> oldest; the oldest used bucket is cut partially
        double remaining = window;
        for (int k = 0; k < RING; k++) {
            int idx = (cur - k + RING) % RING;
            if (time[idx] <= 0) { weight[idx] = 0; continue; }
            if (remaining <= 0) { time[idx] = 0; weight[idx] = 0; continue; } // fully outside: free it
            double wgt = Math.min(time[idx], remaining);
            weight[idx] = wgt;
            remaining -= wgt;
        }

        // 4) compose displayed frame, oldest -> newest
        int count = 0;
        for (int i = 0; i < RING; i++) {
            if (weight[i] > 0 && (i == cur || weight[i] >= window * 0.01)) count++;
        }
        boolean onlyThisFrame = (count == 1 && time[cur] <= dt + 1e-9);
        if (count > 0 && !onlyThisFrame) {
            double cum = 0;
            boolean first = true;
            for (int k = RING - 1; k >= 0; k--) {
                int idx = (cur - k + RING) % RING;
                double wgt = weight[idx];
                if (wgt <= 0 || (idx != cur && wgt < window * 0.01)) continue;
                cum += wgt;
                draw(fb[idx], main, first ? 1f : (float) (wgt / cum));
                first = false;
            }
        }
        // onlyThisFrame: the displayed frame is the real frame already in main, nothing to do

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
