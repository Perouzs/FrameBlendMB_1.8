package com.example.framemotionblur;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

/**
 * Frame blending via an exponential moving average:
 *   history = lerp(history, current, a)      a = 1 - exp(-dt / tau)
 * then history is copied back to the main framebuffer.
 */
public final class BlurRenderer {
    private static Framebuffer history;
    private static boolean needsReset = true;
    private static long lastNanos = 0;

    private BlurRenderer() {}

    public static void render(boolean enabled, int strength) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || strength <= 0 || mc.theWorld == null || !OpenGlHelper.isFramebufferEnabled()) {
            needsReset = true;
            lastNanos = 0;
            return;
        }

        Framebuffer main = mc.getFramebuffer();
        int w = main.framebufferWidth, h = main.framebufferHeight;

        if (history == null || history.framebufferWidth != w || history.framebufferHeight != h) {
            if (history != null) history.deleteFramebuffer();
            history = new Framebuffer(w, h, false);
            history.setFramebufferFilter(GL11.GL_LINEAR);
            history.setFramebufferColor(0f, 0f, 0f, 1f);
            needsReset = true;
        }

        long now = System.nanoTime();
        double dtMs = lastNanos == 0 ? 1000.0 : Math.min(100.0, (now - lastNanos) / 1.0e6);
        lastNanos = now;

        // tau in ms: strength 50 -> 30 ms, strength 100 -> 60 ms
        double tau = strength * 0.6;
        float alpha = needsReset ? 1f : (float) (1.0 - Math.exp(-dtMs / tau));
        alpha = Math.max(0.02f, Math.min(1f, alpha));

        // ---- set up a clean 0..1 ortho state ----
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

        // 1) history = lerp(history, main, alpha)
        history.bindFramebuffer(true);
        if (alpha >= 1f) {
            GlStateManager.disableBlend();
        } else {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        main.bindFramebufferTexture();
        quad(alpha);

        // 2) main = history (opaque copy)
        main.bindFramebuffer(true);
        GlStateManager.disableBlend();
        history.bindFramebufferTexture();
        quad(1f);

        // ---- restore state ----
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

        needsReset = false;
    }

    private static void quad(float a) {
        GlStateManager.color(1f, 1f, 1f, a);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(0, 1, 0).tex(0, 0).endVertex();
        wr.pos(1, 1, 0).tex(1, 0).endVertex();
        wr.pos(1, 0, 0).tex(1, 1).endVertex();
        wr.pos(0, 0, 0).tex(0, 1).endVertex();
        t.draw();
    }
}
