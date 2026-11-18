package com.example.framemotionblur;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = FrameMotionBlur.MODID, name = "Frame Blending Motion Blur", version = "1.2.0",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class FrameMotionBlur {
    public static final String MODID = "framemotionblur";
    public static final String VERSION = "1.2.0";

    /** 0..100. 0 = off-ish, 100 = very strong ghosting. */
    public static volatile int strength = 50;
    public static volatile boolean enabled = true;

    private static final KeyBinding TOGGLE =
            new KeyBinding("Toggle Motion Blur", Keyboard.KEY_NONE, "Motion Blur");

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(TOGGLE);
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new BlurCommand());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (TOGGLE.isPressed()) {
            enabled = !enabled;
            say("Motion blur: " + (enabled ? "ON" : "OFF"));
        }
    }

    /** Fires once per frame after world + hand are rendered, before the HUD is drawn. */
    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Pre e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        BlurRenderer.render(enabled, strength);
    }

    static void say(String s) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[MotionBlur] " + s));
    }

    public static class BlurCommand extends CommandBase {
        @Override public String getCommandName() { return "motionblur"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/motionblur <0-100|on|off>"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) throws CommandException {
            if (args.length != 1) {
                say("v" + VERSION + " | " + (enabled ? "ON" : "OFF") + " | strength " + strength
                        + " | window " + String.format("%.1f", strength / 3.0) + " ms"
                        + " | buckets " + BlurRenderer.debugBuckets
                        + " | fps~" + (int) BlurRenderer.debugAvgFps);
                say("Usage: " + getCommandUsage(sender));
                return;
            }
            String a = args[0].toLowerCase();
            if (a.equals("on")) { enabled = true; say("ON"); }
            else if (a.equals("off")) { enabled = false; say("OFF"); }
            else {
                try {
                    strength = Math.max(0, Math.min(100, Integer.parseInt(a)));
                    enabled = true;
                    say("Strength = " + strength);
                } catch (NumberFormatException ex) {
                    say("Usage: " + getCommandUsage(sender));
                }
            }
        }
    }
}
