package net.singularitymc.sapi.internal.mc;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.gui.Form;
import net.singularitymc.sapi.internal.ApiRuntime;
import net.singularitymc.sapi.internal.Forms;

/**
 * {@link Form} as one of the game's dialogs. Each button sends the fields' texts back as a "custom click action"
 * named {@code singularity:form}, with the form's one-time id added; {@link #answer} takes it from there.
 */
public final class FormImpl implements Forms.Shower {
    private static final Identifier ACTION = Identifier.fromNamespaceAndPath("singularity", "form");
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Open(String id, Form form) {}

    /** The form each player has open. */
    private static final Map<UUID, Open> OPEN = new ConcurrentHashMap<>();

    @Override
    public void show(Form form, Player player) {
        ServerPlayer p = PlayerImpl.handle(player);
        if (p == null) {
            return;
        }
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        String id = HexFormat.of().formatHex(random);
        OPEN.put(p.getUUID(), new Open(id, form));

        List<DialogBody> body = new ArrayList<>();
        for (String paragraph : form.text()) {
            body.add(new PlainMessage(Component.literal(paragraph), 260));
        }
        List<Input> inputs = new ArrayList<>();
        for (Form.Field field : form.fields()) {
            inputs.add(new Input(field.key(), new TextInput(240, Component.literal(field.label()), true, "",
                    field.maxLength(), Optional.empty())));
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Form.Button button : form.buttons()) {
            CompoundTag extra = new CompoundTag();
            extra.putString("sapi_form", id);
            extra.putString("sapi_button", button.id());
            buttons.add(new ActionButton(new CommonButtonData(Component.literal(button.label()), 150),
                    Optional.of(new net.minecraft.server.dialog.action.CustomAll(ACTION, Optional.of(extra)))));
        }
        CommonDialogData common = new CommonDialogData(Component.literal(form.title()), Optional.empty(), form.closable(),
                false, DialogAction.CLOSE, body, inputs);
        Dialog dialog = new MultiActionDialog(common, buttons, Optional.empty(), Math.min(2, buttons.size()));
        p.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    @Override
    public void close(Player player) {
        ServerPlayer p = PlayerImpl.handle(player);
        if (p != null && OPEN.remove(p.getUUID()) != null) {
            p.connection.send(ClientboundClearDialogPacket.INSTANCE);
        }
    }

    static void forget(UUID player) {
        OPEN.remove(player);
    }

    /**
     * A custom click action arrived from {@code player} (server thread). Returns whether it was a form's answer
     * (also a stale or forged one: those are swallowed, not passed on).
     */
    static boolean answer(ServerPlayer player, Identifier action, Optional<Tag> payload) {
        if (!ACTION.equals(action)) {
            return false;
        }
        if (payload.isEmpty() || !(payload.get() instanceof CompoundTag data)) {
            return true;
        }
        Open open = OPEN.get(player.getUUID());
        if (open == null || !open.id().equals(data.getStringOr("sapi_form", ""))) {
            return true; // not the form this player has open: an old window, or made up
        }
        OPEN.remove(player.getUUID());
        String pressed = data.getStringOr("sapi_button", "");
        boolean known = false;
        for (Form.Button button : open.form().buttons()) {
            known |= button.id().equals(pressed);
        }
        if (!known) {
            return true;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Form.Field field : open.form().fields()) {
            String value = data.getStringOr(field.key(), "");
            values.put(field.key(), value.length() > field.maxLength() ? value.substring(0, field.maxLength()) : value);
        }
        try {
            open.form().handler().accept(new Form.Answer(PlayerImpl.of(player), pressed, values));
        } catch (RuntimeException e) {
            ApiRuntime.get().log().error("A form's answer handler failed (form '" + open.form().title() + "')", e);
        }
        return true;
    }
}
