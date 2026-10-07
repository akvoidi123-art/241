package com.example.itemthrower;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ItemThrower (клиентский мод).
 *
 * 1. Возьмите в руку предмет, который нужно автоматически выбрасывать
 *    (например, изумруд), и нажмите кнопку "Назначить предмет".
 * 2. Нажмите кнопку "Вкл/выкл авто-выброс" — пока включено, мод каждый тик
 *    ищет этот предмет в вашем инвентаре и выбрасывает найденные стопки
 *    вперёд (ровно так же, как обычное нажатие Q по слоту) — летят в
 *    направлении взгляда, так что можно собирать воронками.
 *
 * Работает даже когда открыт ваш собственный инвентарь ИЛИ окно торговли
 * с жителем — мод не ломает блоки и не щёлкает мышью по экрану, а просто
 * отправляет игре то же самое действие "выбросить предмет из слота", что
 * происходит при обычном Q. Поэтому серверу ничего ставить не нужно.
 */
public class ItemThrowerClientMod implements ClientModInitializer {

    public static final String MOD_ID = "itemthrower";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Сколько тиков ждать между выбросами, чтобы не заспамить сервер пачкой пакетов разом. */
    private static final int THROW_INTERVAL = 4;

    private KeyMapping toggleKey;
    private KeyMapping setItemKey;

    private Item targetItem;
    private boolean enabled = false;
    private int throwCooldown;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.itemthrower.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        setItemKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.itemthrower.set_item", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("ItemThrower (клиент) загружен");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("itemthrower")
                .then(ClientCommands.literal("on").executes(ctx -> {
                    if (!enableIfReady(ctx.getSource().getClient())) {
                        ctx.getSource().sendError(Component.literal("Сначала назначьте предмет (держите его в руке и нажмите клавишу, либо /itemthrower item)"));
                        return 0;
                    }
                    ctx.getSource().sendFeedback(Component.literal("§aАвто-выброс включён"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    enabled = false;
                    ctx.getSource().sendFeedback(Component.literal("§eАвто-выброс выключен"));
                    return 1;
                }))
                .then(ClientCommands.literal("item").executes(ctx -> {
                    setItemFromHand(ctx.getSource().getClient());
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(
                            "Предмет: " + (targetItem == null ? "не назначен" : nameOf(targetItem))
                                    + " | " + (enabled ? "§aвключено" : "§eвыключено")));
                    return 1;
                }))
            )
        );
    }

    private void onClientTick(Minecraft mc) {
        while (setItemKey.consumeClick()) {
            setItemFromHand(mc);
        }
        while (toggleKey.consumeClick()) {
            if (enabled) {
                enabled = false;
                showOverlay("§eАвто-выброс выключен");
            } else if (enableIfReady(mc)) {
                showOverlay("§aАвто-выброс включён: " + nameOf(targetItem));
            } else {
                showOverlay("§cСначала назначьте предмет (держите его в руке и нажмите кнопку)");
            }
        }

        if (!enabled || mc.player == null) {
            return;
        }

        if (throwCooldown-- > 0) {
            return;
        }
        if (throwNextMatch(mc, mc.player)) {
            throwCooldown = THROW_INTERVAL;
        }
    }

    private boolean enableIfReady(Minecraft mc) {
        if (targetItem == null) {
            return false;
        }
        enabled = true;
        return true;
    }

    private void setItemFromHand(Minecraft mc) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            showOverlay("§cВ руке должен быть предмет");
            return;
        }
        targetItem = held.getItem();
        showOverlay("§aНазначен предмет: " + nameOf(targetItem));
    }

    /**
     * Ищет в текущем открытом меню игрока (или в обычном инвентаре, если экран не открыт —
     * containerMenu всегда указывает на что-то валидное) первый слот с назначенным предметом
     * и выбрасывает его целиком тем же действием, что и обычное нажатие Q по слоту.
     */
    private boolean throwNextMatch(Minecraft mc, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || mc.gameMode == null) {
            return false;
        }
        Inventory inventory = player.getInventory();

        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                continue; // не трогаем слоты торговли/печи/сундука — только собственный инвентарь игрока
            }
            if (slot.getContainerSlot() >= 36) {
                continue; // пропускаем броню и дополнительный слот — только основной инвентарь и хотбар
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(targetItem)) {
                continue;
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 1, ClickType.THROW, player);
            return true;
        }
        return false;
    }

    private static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    private static void showOverlay(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(text), false);
        }
    }
}
