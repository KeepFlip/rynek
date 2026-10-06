package pl.rynek;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;

public class RynekMod implements ClientModInitializer {

    private static final int DELAY_TICKS = 40;
    private static final int JUMP_INTERVAL_TICKS = 3 * 60 * 20; // co 3 minuty
    private static final int JUMPS_PER_BURST = 3;

    private record Piece(Item item, int price) {}

    private static final List<Piece> PIECES = List.of(
            new Piece(Items.DIAMOND_HELMET, 399),
            new Piece(Items.DIAMOND_CHESTPLATE, 349),
            new Piece(Items.DIAMOND_LEGGINGS, 349),
            new Piece(Items.DIAMOND_BOOTS, 349)
    );

    private static boolean running = false;
    private static int pointer = 0;
    private static int cooldown = 0;

    private static int jumpTimer = 0;
    private static int jumpsLeft = 0;
    private static int jumpWait = 0;
    private static boolean jumpHeld = false;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("rynek-start").executes(ctx -> start(ctx)));
            dispatcher.register(ClientCommandManager.literal("rynek-stop").executes(ctx -> stop(ctx)));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!running) return;
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
                haltAll(client);
                return;
            }

            jumpTick(client);

            if (cooldown > 0) {
                cooldown--;
                return;
            }
            step(client);
        });
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!hasAnyPiece(mc)) {
            ctx.getSource().sendFeedback(Text.literal("§c[Rynek] Nie masz w eq zadnej diamentowej zbroi z Ochrona 4 i Niezniszczalnosc 3 - nie dziala."));
            return 0;
        }
        running = true;
        pointer = 0;
        cooldown = 0;
        jumpTimer = JUMP_INTERVAL_TICKS;
        jumpsLeft = 0;
        jumpWait = 0;
        jumpHeld = false;
        ctx.getSource().sendFeedback(Text.literal("§a[Rynek] Start. Wylacz: /rynek-stop"));
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx) {
        haltAll(MinecraftClient.getInstance());
        ctx.getSource().sendFeedback(Text.literal("§e[Rynek] Stop."));
        return 1;
    }

    private static void haltAll(MinecraftClient mc) {
        running = false;
        jumpsLeft = 0;
        jumpHeld = false;
        if (mc != null && mc.options != null) {
            mc.options.jumpKey.setPressed(false);
        }
    }

    /** Co 3 minuty: 3 skoki pod rzad. */
    private static void jumpTick(MinecraftClient mc) {
        if (jumpHeld) {
            mc.options.jumpKey.setPressed(false);
            jumpHeld = false;
            jumpWait = 8;
            return;
        }
        if (jumpWait > 0) {
            jumpWait--;
            return;
        }
        if (jumpsLeft == 0) {
            if (jumpTimer > 0) {
                jumpTimer--;
            } else {
                jumpsLeft = JUMPS_PER_BURST;
                jumpTimer = JUMP_INTERVAL_TICKS;
            }
            return;
        }
        if (mc.currentScreen == null && mc.player.isOnGround()) {
            mc.options.jumpKey.setPressed(true);
            jumpHeld = true;
            jumpsLeft--;
        }
    }

    private static void step(MinecraftClient mc) {
        for (int i = 0; i < PIECES.size(); i++) {
            int idx = (pointer + i) % PIECES.size();
            Piece piece = PIECES.get(idx);
            int slot = findSlot(mc, piece.item());
            if (slot < 0) continue;

            holdSlot(mc, slot);
            mc.getNetworkHandler().sendChatCommand("wystaw 1 " + piece.price());

            pointer = (idx + 1) % PIECES.size();
            cooldown = DELAY_TICKS;
            return;
        }
        haltAll(mc);
        mc.player.sendMessage(Text.literal("§e[Rynek] Brak zbroi w eq - zatrzymano."), false);
    }

    private static void holdSlot(MinecraftClient mc, int slot) {
        var player = mc.player;
        if (slot < 9) {
            player.getInventory().selectedSlot = slot;
            mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
        } else {
            mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                    player.getInventory().selectedSlot, SlotActionType.SWAP, player);
        }
    }

    private static boolean hasAnyPiece(MinecraftClient mc) {
        for (Piece p : PIECES) {
            if (findSlot(mc, p.item()) >= 0) return true;
        }
        return false;
    }

    private static int findSlot(MinecraftClient mc, Item item) {
        if (mc.player == null || mc.world == null) return -1;
        Registry<Enchantment> reg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        var prot = reg.getOrThrow(Enchantments.PROTECTION);
        var unb = reg.getOrThrow(Enchantments.UNBREAKING);
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isOf(item)) continue;
            if (EnchantmentHelper.getLevel(prot, s) != 4) continue;
            if (EnchantmentHelper.getLevel(unb, s) != 3) continue;
            return i;
        }
        return -1;
    }
}
