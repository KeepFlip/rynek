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
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class RynekMod implements ClientModInitializer {

    private static final int DELAY_TICKS = 40;
    private static final int JUMP_INTERVAL_TICKS = 2 * 60 * 20; // co 2 minuty
    private static final int JUMPS_PER_BURST = 3;

    private record Piece(Item item, int price,
                         RegistryKey<Enchantment> ench1, int lvl1,
                         RegistryKey<Enchantment> ench2, int lvl2) {}

    private static Piece armor(Item item, int price) {
        return new Piece(item, price, Enchantments.PROTECTION, 5, Enchantments.UNBREAKING, 5);
    }

    // KOLEJNOSC: helm -> klata -> spodnie -> buty -> miecz
    private static final List<Piece> PIECES = List.of(
            armor(Items.NETHERITE_HELMET, 19999),
            armor(Items.NETHERITE_CHESTPLATE, 14999),
            armor(Items.NETHERITE_LEGGINGS, 12999),
            armor(Items.NETHERITE_BOOTS, 14999),
            new Piece(Items.NETHERITE_SWORD, 23999, Enchantments.SHARPNESS, 6, Enchantments.FIRE_ASPECT, 2)
    );

    private static boolean running = false;
    private static int pointer = 0;
    private static int cooldown = 0;

    private static int jumpTimer = 0;
    private static int jumpsLeft = 0;
    private static int jumpWait = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("rynek-start").executes(ctx -> start(ctx)));
            dispatcher.register(ClientCommandManager.literal("rynek-stop").executes(ctx -> stop(ctx)));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!running) return;
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
                running = false;
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
            ctx.getSource().sendFeedback(Text.literal("§c[Rynek] Nie masz w eq zadnego itemu z wymaganymi enchantami - nie dziala."));
            return 0;
        }
        running = true;
        pointer = 0;
        cooldown = 0;
        jumpTimer = JUMP_INTERVAL_TICKS;
        jumpsLeft = 0;
        jumpWait = 0;
        ctx.getSource().sendFeedback(Text.literal("§a[Rynek] Start. Wylacz: /rynek-stop"));
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx) {
        running = false;
        jumpsLeft = 0;
        ctx.getSource().sendFeedback(Text.literal("§e[Rynek] Stop."));
        return 1;
    }

    /** Co 2 minuty: 3 skoki pod rzad. */
    private static void jumpTick(MinecraftClient mc) {
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
        if (mc.player.isOnGround()) {
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, 0.42, v.z);
            jumpsLeft--;
            jumpWait = 10;
        }
    }

    private static void step(MinecraftClient mc) {
        for (int i = 0; i < PIECES.size(); i++) {
            int idx = (pointer + i) % PIECES.size();
            Piece piece = PIECES.get(idx);
            int slot = findSlot(mc, piece);
            if (slot < 0) continue;

            holdSlot(mc, slot);
            mc.getNetworkHandler().sendChatCommand("wystaw 1 " + piece.price());

            pointer = (idx + 1) % PIECES.size();
            cooldown = DELAY_TICKS;
            return;
        }
        running = false;
        mc.player.sendMessage(Text.literal("§e[Rynek] Brak itemow w eq - zatrzymano."), false);
    }

    private static void holdSlot(MinecraftClient mc, int slot) {
        var player = mc.player;
        int hotbar = slot;
        if (slot >= 9) {
            hotbar = 0;
            mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot,
                    hotbar, SlotActionType.SWAP, player);
        }
        mc.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(hotbar));
    }

    private static boolean hasAnyPiece(MinecraftClient mc) {
        for (Piece p : PIECES) {
            if (findSlot(mc, p) >= 0) return true;
        }
        return false;
    }

    private static int findSlot(MinecraftClient mc, Piece piece) {
        if (mc.player == null || mc.world == null) return -1;
        Registry<Enchantment> reg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        var e1 = reg.getOrThrow(piece.ench1());
        var e2 = reg.getOrThrow(piece.ench2());
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isOf(piece.item())) continue;
            if (EnchantmentHelper.getLevel(e1, s) != piece.lvl1()) continue;
            if (EnchantmentHelper.getLevel(e2, s) != piece.lvl2()) continue;
            return i;
        }
        return -1;
    }
}
