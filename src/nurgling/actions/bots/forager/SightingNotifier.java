package nurgling.actions.bots.forager;

import haven.ChatUI;
import haven.Gob;
import haven.OCache;
import haven.Widget;
import nurgling.NGameUI;
import nurgling.conf.NAreaRad;
import nurgling.conf.NDiscordNotification;
import nurgling.guarding.GuardingProfile;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Guarding profile's "Notify on sight": reports each matching gob once per run to Discord/chat, never stopping or pausing the bot. */
public class SightingNotifier {
    private final NGameUI gui;
    private final NAlias patterns;
    private final boolean discord;
    private final String chatChannel;
    private final String routeName;
    private final Set<Long> reported = new HashSet<>();

    private SightingNotifier(NGameUI gui, NAlias patterns, boolean discord, String chatChannel, String routeName) {
        this.gui = gui;
        this.patterns = patterns;
        this.discord = discord;
        this.chatChannel = chatChannel;
        this.routeName = routeName;
    }

    /** A notifier for the profile's sighting settings, or null when it has no patterns or nowhere to send them. */
    public static SightingNotifier create(NGameUI gui, GuardingProfile profile, String routeName) {
        List<String> keys = new ArrayList<>();
        for (String part : profile.sightingPatterns.split(",")) {
            if (!part.trim().isEmpty()) {
                keys.add(part.trim());
            }
        }
        String chat = profile.sightingChatChannel.trim();
        if (keys.isEmpty() || (!profile.sightingDiscord && chat.isEmpty())) {
            return null;
        }
        if (profile.sightingDiscord && !hasWebhook()) {
            gui.msg("Forager: Notify on sight is set to Discord, but no webhook is configured (Settings > Discord)");
        }
        return new SightingNotifier(gui, new NAlias(keys.toArray(new String[0])), profile.sightingDiscord, chat, routeName);
    }

    /** Reports every matching gob not reported yet this run; called repeatedly from the guard watcher thread. */
    public void scan() {
        Map<String, Integer> sighted = new LinkedHashMap<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob instanceof OCache.Virtual || gob.ngob == null || gob.ngob.name == null || reported.contains(gob.id)) {
                    continue;
                }
                // A corpse keeps its live res name - it's not a sighting.
                if (!NParser.checkName(gob.ngob.name, patterns) || NAreaRad.isDownOrDead(gob)) {
                    continue;
                }
                reported.add(gob.id);
                sighted.merge(shortName(gob.ngob.name), 1, Integer::sum);
            }
        }
        if (!sighted.isEmpty()) {
            send(sighted);
        }
    }

    private void send(Map<String, Integer> sighted) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sighted.entrySet()) {
            parts.add(e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey());
        }
        String message = gui.chrid + " spotted " + String.join(", ", parts) + " " + routeLeg()
                + " (Forager, route \"" + routeName + "\")";
        gui.msg("Forager: " + message);
        if (discord && hasWebhook()) {
            gui.msgToDiscord(NDiscordNotification.get("general"), message);
        }
        if (!chatChannel.isEmpty()) {
            ChatUI.Channel channel = findChatChannel(gui, chatChannel);
            if (channel instanceof ChatUI.EntryChannel) {
                ((ChatUI.EntryChannel) channel).send(message);
            }
        }
    }

    /** Where on the route the bot is, in the 1-based node numbers the route map shows. */
    private String routeLeg() {
        // The index of the waypoint being walked to, so the bot is on the leg from the one before it.
        int target = gui.activeBotWaypointIndex;
        if (target <= 0) {
            return "near node 1";
        }
        return "between nodes " + target + " and " + (target + 1);
    }

    private static boolean hasWebhook() {
        NDiscordNotification settings = NDiscordNotification.get("general");
        return settings != null && settings.webhookUrl != null && !settings.webhookUrl.isEmpty();
    }

    /** "gfx/kritter/caveangler/caveangler" -> "caveangler". */
    private static String shortName(String resName) {
        return resName.substring(resName.lastIndexOf('/') + 1);
    }

    /** The open chat channel with this name (case-insensitive), else null. */
    public static ChatUI.Channel findChatChannel(NGameUI gui, String channelName) {
        if (gui.chat == null) return null;
        for (Widget w = gui.chat.child; w != null; w = w.next) {
            if (w instanceof ChatUI.Channel && ((ChatUI.Channel) w).name().equalsIgnoreCase(channelName)) {
                return (ChatUI.Channel) w;
            }
        }
        return null;
    }
}
