package nurgling.scripting.ops;

import haven.*;
import nurgling.scripting.ScriptEvents;
import nurgling.scripting.api.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/**
 * Chat channels and on-screen messages.
 *
 * Also publishes the {@code chat.msg} event (hook in ChatUI.Channel.process).
 */
public final class ChatOps {

    @ApiOp(name = "chat.notify", kind = ACTION,
            doc = "Shows a message in the game's message area, the line above the hotbar where \"You found...\" messages appear. "
                    + "With `error=True` it is shown in red with the error sound. Only this player sees it; nothing is sent to the server.",
            params = {
                    @ApiParam(name = "text", type = "str", doc = "Message text."),
                    @ApiParam(name = "error", type = "bool", doc = "Show as an error.", optional = true, def = "false")},
            returns = "null",
            example = "nc.chat.notify(\"Done: 12 logs chopped\")")
    public static Object notify(OpContext c, Args a) {
        String t = a.str("text");
        if (a.b("error", false)) c.gui.error(t);
        else c.gui.msg(t);
        return null;
    }

    private static List<ChatUI.Channel> channels(OpContext c) {
        List<ChatUI.Channel> res = new ArrayList<>();
        if (c.gui.chat == null) return res;
        for (Widget w = c.gui.chat.child; w != null; w = w.next) {
            if (w instanceof ChatUI.Channel) res.add((ChatUI.Channel) w);
        }
        return res;
    }

    private static ChatUI.Channel channel(OpContext c, String name) {
        for (ChatUI.Channel ch : channels(c)) {
            if (name.equalsIgnoreCase(ch.name())) return ch;
        }
        throw ApiError.notFound("no chat channel '" + name + "'");
    }

    @ApiOp(name = "chat.channels", kind = QUERY,
            doc = "The names of the open chat channels: \"Area Chat\", \"Village\", \"Party\", private chats by the other player's name, "
                    + "and log channels such as \"System\".",
            returns = "list[str]",
            example = "print(nc.chat.channels())")
    public static Object listChannels(OpContext c, Args a) {
        List<String> res = new ArrayList<>();
        for (ChatUI.Channel ch : channels(c)) res.add(ch.name());
        return res;
    }

    @ApiOp(name = "chat.send", kind = ACTION,
            doc = "Sends a line to a chat channel, as if typed there. Other players see it. Log channels cannot be written to.",
            params = {
                    @ApiParam(name = "channel", type = "str", doc = "Channel name (case-insensitive), from `chat.channels`."),
                    @ApiParam(name = "text", type = "str", doc = "The line to send.")},
            returns = "null", errors = {Errors.NOT_FOUND, Errors.NOT_SUPPORTED},
            example = "nc.chat.send(\"Village\", \"Smelters are lit\")")
    public static Object send(OpContext c, Args a) {
        ChatUI.Channel ch = channel(c, a.str("channel"));
        if (!(ch instanceof ChatUI.EntryChannel)) throw ApiError.notSupported("channel '" + ch.name() + "' is read-only");
        String t = a.str("text");
        if (t.isEmpty()) throw ApiError.badArgs("text must not be empty");
        ((ChatUI.EntryChannel) ch).send(t);
        return null;
    }

    @ApiOp(name = "chat.history", kind = QUERY,
            doc = "The most recent lines of a channel that the client still holds (everything since the channel opened in this session), oldest first. "
                    + "`from` is the sender's name where the client knows it (kin list names; null for system lines and unknown senders), "
                    + "`time` is the client's clock in seconds when the line arrived. "
                    + "For new lines as they arrive, subscribe to the `chat.msg` event ({channel, from, sender, text}).",
            params = {
                    @ApiParam(name = "channel", type = "str", doc = "Channel name (case-insensitive)."),
                    @ApiParam(name = "limit", type = "int", doc = "At most this many lines (the newest).", optional = true, def = "50")},
            returns = "list[dict]", returnsDoc = "[{text, from, time}]", errors = {Errors.NOT_FOUND},
            example = "for line in nc.chat.history(\"Village\", limit=10):\n    print(line[\"from\"], line[\"text\"])\n"
                    + "for ev in nc.events(\"chat.msg\", channel=\"Village\"):\n    print(ev.data[\"from\"], ev.data[\"text\"])")
    public static Object history(OpContext c, Args a) {
        ChatUI.Channel ch = channel(c, a.str("channel"));
        int limit = Math.max(0, a.i("limit", 50));
        List<ChatUI.Channel.Message> msgs = new ArrayList<>();
        synchronized (ch.rmsgs) {
            int from = Math.max(0, ch.rmsgs.size() - limit);
            for (int i = from; i < ch.rmsgs.size(); i++) msgs.add(ch.rmsgs.get(i).msg);
        }
        JSONArray res = new JSONArray();
        for (ChatUI.Channel.Message m : msgs) {
            String text = null, sender = null;
            if (m instanceof ChatUI.MultiChat.NamedMessage) {
                ChatUI.MultiChat.NamedMessage nm = (ChatUI.MultiChat.NamedMessage) m;
                text = nm.text;
                sender = buddyName(c.gui, nm.from);
            } else if (m instanceof ChatUI.Channel.SimpleMessage) {
                text = ((ChatUI.Channel.SimpleMessage) m).text;
            }
            if (text == null) continue;
            res.put(new JSONObject().put("text", text).put("from", sender == null ? JSONObject.NULL : sender).put("time", m.time));
        }
        return res;
    }

    private static String buddyName(GameUI gui, int id) {
        if (gui == null || gui.buddies == null) return null;
        BuddyWnd.Buddy b = gui.buddies.find(id);
        return b == null ? null : b.name;
    }

    /**
     * Called by ChatUI.Channel for every incoming or echoed chat line, after the caller checked
     * {@code ScriptEvents.wanted(ui, "chat.msg")}. {@code sender} is the channel's sender tag:
     * "self", "chan" (system/log line), "u&lt;buddy id&gt;", "g&lt;gob id&gt;" (party) or "p&lt;buddy id&gt;" (private chat).
     */
    public static void publishLine(ChatUI.Channel ch, String text, String sender) {
        UI ui = ch.ui;
        GameUI gui = ch.getparent(GameUI.class);
        String from = null;
        if (sender != null) {
            if (sender.equals("self")) {
                from = (gui == null) ? null : gui.chrid;
            } else if ((sender.startsWith("u") || sender.startsWith("p")) && sender.length() > 1) {
                try {
                    from = buddyName(gui, Integer.parseInt(sender.substring(1)));
                } catch (NumberFormatException e) {
                    from = null;
                }
            }
        }
        JSONObject d = new JSONObject()
                .put("channel", ch.name())
                .put("from", from == null ? JSONObject.NULL : from)
                .put("sender", sender == null ? JSONObject.NULL : sender)
                .put("text", text == null ? JSONObject.NULL : text);
        ScriptEvents.publish(ui, "chat.msg", d);
    }

    private ChatOps() {}
}
