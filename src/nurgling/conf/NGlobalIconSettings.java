package nurgling.conf;

import haven.GobIcon;
import haven.HashDirCache;
import haven.Message;
import haven.MessageBuf;
import haven.ResCache;
import haven.Resource;
import haven.Utils;
import haven.Warning;
import nurgling.NUtils;
import nurgling.tools.NFileUtils;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-global map-icon settings.
 *
 * Vanilla keys GobIcon settings on "data/mm-icons-2/&lt;world&gt;/&lt;account&gt;", so every
 * world and every account starts from an empty icon list and has to be set up by hand, and a
 * world reset throws the lot away. This keeps one authoritative copy for the whole machine
 * instead.
 *
 * It is stored in the client's own settings format rather than JSON, which buys two things.
 * The file can be handed straight back to {@link GobIcon.Settings#load(Message)}, so restoring
 * it goes through the same asynchronous Loader the server path uses - that is what makes an
 * icon the character has never encountered resolve, list and draw correctly. And it round-trips
 * sub-ids, notification sounds and marker flags without a second serialisation format to keep
 * in step.
 *
 * The per-world file is still written. The server's catalog version ("tag") is genuinely
 * per-world handshake state and must not be shared; it is the one thing that stays world-scoped.
 *
 * Writes merge rather than overwrite. Nurgling runs several sessions in one process, each with
 * its own Settings instance, and a whole-map write from a session holding a stale view would
 * erase another session's changes. Each session remembers what the store held when it last
 * synced ({@link GobIcon.Settings#globalsync}) and pushes only the entries it actually changed.
 *
 * Existing setups are not thrown away: the first time a given world and account is seen, its
 * old per-world file is folded in, so the store grows to the union of everything already set
 * up by hand as those characters get logged into.
 */
public class NGlobalIconSettings {
    public static final String FILENAME = "icon_settings.nurgling.dat";
    private static final String LEGACY_PREFIX = "data/mm-icons-2/";
    private static final String LEGACY_RINGS = "nurgling-icon-rings.json";

    private NGlobalIconSettings() {}

    /* One icon's stored preferences, plus enough resource identity to rebuild it. */
    private static class Entry {
        final String resnm;
        int resver;
        final byte[] resdata;
        final Object[] sub;
        boolean show, defshow, notify, ring, mark, markset;
        String resns, filens;

        Entry(String resnm, int resver, byte[] resdata, Object[] sub) {
            this.resnm = resnm;
            this.resver = resver;
            this.resdata = resdata;
            this.sub = sub;
        }
    }

    private static class Store {
        final Map<GobIcon.Setting.ID, Entry> entries = new HashMap<>();
        /* Per-world files already folded in. Absorbing one twice would resurrect icons the
         * user has since switched off, since the fold ORs flags together. */
        final Set<String> absorbed = new LinkedHashSet<>();
        boolean notify = false;

        void put(Entry ent) {
            entries.put(new GobIcon.Setting.ID(ent.resnm, ent.sub), ent);
        }
    }

    /* What the store held when a given session last synced with it, so that session can tell
     * which of its own settings it has since changed. Stashed opaquely on Settings. */
    private static class Sync {
        final Map<GobIcon.Setting.ID, String> sigs;
        final boolean notify;

        Sync(Map<GobIcon.Setting.ID, String> sigs, boolean notify) {
            this.sigs = sigs;
            this.notify = notify;
        }

        static Sync of(Store store) {
            Map<GobIcon.Setting.ID, String> sigs = new HashMap<>();
            for(Map.Entry<GobIcon.Setting.ID, Entry> ent : store.entries.entrySet())
                sigs.put(ent.getKey(), sig(ent.getValue()));
            return(new Sync(sigs, store.notify));
        }
    }

    /* ------------------------------------------------------------------ entry points */

    /**
     * Overlays the machine-global settings onto a freshly loaded per-world configuration,
     * first folding in this world and account's old per-world file if it hasn't been seen
     * before. The world's server catalog tag is preserved.
     */
    public static synchronized void apply(GobIcon.Settings conf) {
        Store store = read();
        boolean changed = absorb(store, conf.filename);
        byte[] raw = encode(store);
        if(changed)
            write(raw);
        if(!store.entries.isEmpty()) {
            int tag = conf.tag;
            try {
                conf.load(new MessageBuf(raw));
            } catch(Message.BinError e) {
                new Warning(e, "could not apply global icon settings").issue();
            }
            /* load() reads a tag out of the blob; ours is a placeholder, and the real one is
             * per-world handshake state that has to survive. */
            conf.tag = tag;
        }
        conf.globalsync = Sync.of(store);
    }

    /**
     * Writes back whatever this session has changed since it last synced, leaving every other
     * entry in the store as it stands.
     */
    public static synchronized void push(GobIcon.Settings conf) {
        Sync prev = (conf.globalsync instanceof Sync) ? (Sync)conf.globalsync : null;
        Store store = read();

        Map<GobIcon.Setting.ID, String> sigs = new HashMap<>();
        boolean dirty = false;
        for(GobIcon.Setting set : new ArrayList<>(conf.settings.values())) {
            Entry ent = entry(set);
            if(ent == null)
                continue;
            String sig = sig(ent);
            sigs.put(set.id, sig);
            if((prev == null) || !sig.equals(prev.sigs.get(set.id))) {
                store.put(ent);
                dirty = true;
            }
        }
        if(((prev == null) || (conf.notify != prev.notify)) && (store.notify != conf.notify)) {
            store.notify = conf.notify;
            dirty = true;
        }

        if(dirty)
            write(store);
        conf.globalsync = new Sync(sigs, conf.notify);
    }

    /* ------------------------------------------------------------------ change detection */

    /* The user's choices only. defshow is the server's default rather than a preference, and
     * it legitimately differs between worlds, so including it would make every world switch
     * look like a change and rewrite the whole store. */
    private static String sig(Entry ent) {
        StringBuilder buf = new StringBuilder();
        buf.append(ent.show ? '1' : '0');
        buf.append(ent.notify ? '1' : '0');
        buf.append(ent.ring ? '1' : '0');
        buf.append(ent.markset ? (ent.mark ? '2' : '1') : '0');
        buf.append('\0').append((ent.resns == null) ? "" : ent.resns);
        buf.append('\0').append((ent.filens == null) ? "" : ent.filens);
        return(buf.toString());
    }

    private static Entry entry(GobIcon.Setting set) {
        Resource.Saved res = (set.from != null) ? set.from.res : set.res;
        if(res == null)
            return(null);
        byte[] data = (set.from != null) ? set.from.data : new byte[0];
        Entry ret = new Entry(res.name, res.savever(), data, set.id.sub);
        ret.show = set.show;
        ret.defshow = set.defshow;
        ret.notify = set.notify;
        ret.ring = set.ring;
        ret.mark = set.mark;
        ret.markset = set.markset;
        ret.resns = set.resns;
        ret.filens = (set.filens == null) ? null : set.filens.toString();
        return(ret);
    }

    /* ------------------------------------------------------------------ codec */

    private static void encodeset(Map<Object, Object> buf, Entry ent) {
        if(ent.show)    buf.put("s", 1);
        if(ent.defshow) buf.put("d", 1);
        if(ent.notify)  buf.put("n", 1);
        if(ent.ring)    buf.put("r", 1);
        if(ent.markset) buf.put("m", ent.mark ? 1 : 0);
        if(ent.resns != null)  buf.put("R", ent.resns);
        if(ent.filens != null) buf.put("W", ent.filens);
    }

    private static void parseset(Entry ent, Map<Object, Object> data) {
        ent.show    = Utils.bv(data.getOrDefault("s", 0));
        ent.defshow = Utils.bv(data.getOrDefault("d", 0));
        ent.notify  = Utils.bv(data.getOrDefault("n", 0));
        ent.ring    = Utils.bv(data.getOrDefault("r", 0));
        ent.resns   = (String)data.getOrDefault("R", null);
        ent.filens  = (String)data.getOrDefault("W", null);
        if(data.containsKey("m")) {
            ent.markset = true;
            ent.mark = Utils.bv(data.get("m"));
        }
    }

    private static byte[] encode(Store store) {
        /* Entries are grouped by the resource they came from, which is what the format keys on. */
        Map<String, List<Entry>> byres = new LinkedHashMap<>();
        for(Entry ent : store.entries.values())
            byres.computeIfAbsent(ent.resnm + "\0" + Arrays.toString(ent.resdata), k -> new ArrayList<>()).add(ent);

        List<Object> abuf = new ArrayList<>();
        for(List<Entry> group : byres.values()) {
            Entry first = group.get(0);
            int ver = first.resver;
            for(Entry ent : group)
                ver = Math.max(ver, ent.resver);
            Map<Object, Object> rbuf = new HashMap<>();
            if(first.resdata.length == 0)
                rbuf.put("res", new Object[] {first.resnm, ver});
            else
                rbuf.put("res", new Object[] {first.resnm, ver, first.resdata});
            Collection<Object> sub = new ArrayList<>();
            for(Entry ent : group) {
                if(ent.sub.length == 0) {
                    encodeset(rbuf, ent);
                } else {
                    Map<Object, Object> sbuf = new HashMap<>();
                    sbuf.put("id", ent.sub);
                    encodeset(sbuf, ent);
                    sub.add(Utils.mapencn(sbuf));
                }
            }
            if(!sub.isEmpty())
                rbuf.put("sub", sub.toArray(new Object[0]));
            abuf.add(Utils.mapencn(rbuf));
        }

        Map<Object, Object> buf = new HashMap<>();
        /* Placeholder: the real tag is per-world and stays in the per-world file. Written only
         * so the blob stays loadable by the stock reader, which requires the key. */
        buf.put("tag", -1);
        if(store.notify)
            buf.put("notify", 1);
        if(!store.absorbed.isEmpty())
            buf.put("nabsorbed", store.absorbed.toArray(new Object[0]));
        buf.put("icons", abuf.toArray(new Object[0]));

        MessageBuf dst = new MessageBuf();
        dst.addbytes(GobIcon.Settings.sig);
        dst.adduint8(3);
        dst.addlist(Utils.mapencn(buf));
        return(dst.fin());
    }

    /** @return the decoded store, or null if the blob could not be read. */
    private static Store decode(byte[] raw) {
        try {
            Message blob = new MessageBuf(raw);
            if(!Arrays.equals(blob.bytes(GobIcon.Settings.sig.length), GobIcon.Settings.sig))
                throw(new Message.FormatError("Invalid signature"));
            int ver = blob.uint8();
            if(ver != 3)
                throw(new Message.FormatError("Unknown version: " + ver));
            Map<Object, Object> root = Utils.mapdecn(blob.tto());
            Store ret = new Store();
            ret.notify = Utils.bv(root.getOrDefault("notify", 0));
            if(root.containsKey("nabsorbed")) {
                for(Object nm : (Object[])root.get("nabsorbed"))
                    ret.absorbed.add((String)nm);
            }
            for(Object eicon : (Object[])root.get("icons")) {
                Map<Object, Object> icon = Utils.mapdecn(eicon);
                Object[] eres = (Object[])icon.get("res");
                String nm = (String)eres[0];
                int rver = Utils.iv(eres[1]);
                byte[] data = (eres.length > 2) ? (byte[])eres[2] : new byte[0];
                Entry top = new Entry(nm, rver, data, GobIcon.Icon.nilid);
                parseset(top, icon);
                ret.put(top);
                if(icon.containsKey("sub")) {
                    for(Object esub : (Object[])icon.get("sub")) {
                        Map<Object, Object> sub = Utils.mapdecn(esub);
                        Entry ent = new Entry(nm, rver, data, (Object[])sub.get("id"));
                        parseset(ent, sub);
                        ret.put(ent);
                    }
                }
            }
            return(ret);
        } catch(Message.BinError | ClassCastException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            new Warning(e, "could not decode icon settings").issue();
            return(null);
        }
    }

    /* ------------------------------------------------------------------ storage */

    private static String path() {
        return(NUtils.getDataFile(FILENAME));
    }

    private static Store read() {
        byte[] raw = NFileUtils.readBytesWithBackupFallback(path(), GobIcon.Settings.sig);
        Store ret = (raw == null) ? null : decode(raw);
        return((ret == null) ? new Store() : ret);
    }

    private static void write(Store store) {
        write(encode(store));
    }

    private static void write(byte[] raw) {
        try {
            NFileUtils.writeAtomically(path(), raw);
        } catch(IOException e) {
            new Warning(e, "could not store global icon settings").issue();
        }
    }

    /* ------------------------------------------------------------------ migration */

    /**
     * Folds one per-world/per-account settings file into the global store, the first time that
     * world and account is seen. Each character therefore keeps whatever it was already set up
     * with, and the store accumulates the union as the other accounts get logged into.
     *
     * The file is addressed by name rather than found by searching: cache filenames are hashes
     * of the logical name, so enumerating them means opening and reading a header for every
     * cached file - measured at over a minute on a well-used cache, on the thread that draws
     * the game.
     *
     * @return true if the store changed and needs writing back.
     */
    private static boolean absorb(Store store, String legacy) {
        if((legacy == null) || !legacy.startsWith(LEGACY_PREFIX))
            return(false);
        if(!store.absorbed.add(legacy))
            return(false);

        int icons = store.entries.size();
        byte[] raw = fetch(legacy);
        if(raw != null) {
            Store one = decode(raw);
            if(one != null)
                union(store, one);
        }
        absorbrings(store, legacy);
        System.out.println("[icon-settings] folded " + legacy + " into the global store ("
                           + icons + " -> " + store.entries.size() + " icons)");
        return(true);
    }

    /* Absorb the settings IconRingConfig used to keep for this world, so its rings aren't lost
     * along with it. It stored them per-genus, keyed by icon resource name. */
    private static void absorbrings(Store store, String legacy) {
        String[] parts = legacy.substring(LEGACY_PREFIX.length()).split("/");
        if(parts.length < 1)
            return;
        if(!(ResCache.global instanceof HashDirCache))
            return;
        Path file = ((HashDirCache)ResCache.global).base.resolve(parts[0]).resolve(LEGACY_RINGS);
        if(!Files.exists(file))
            return;
        try {
            String content = NFileUtils.readWithBackupFallback(file.toString());
            if((content == null) || content.isEmpty())
                return;
            JSONObject json = new JSONObject(content);
            for(Entry ent : store.entries.values()) {
                if(json.optBoolean(ent.resnm, false))
                    ent.ring = true;
            }
        } catch(org.json.JSONException e) {
            new Warning(e, "could not read legacy icon-ring config").issue();
        }
    }

    private static byte[] fetch(String name) {
        if(ResCache.global == null)
            return(null);
        try(InputStream in = ResCache.global.fetch(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for(int n = in.read(chunk); n >= 0; n = in.read(chunk))
                buf.write(chunk, 0, n);
            return(buf.toByteArray());
        } catch(IOException e) {
            return(null);
        }
    }

    private static void union(Store into, Store from) {
        into.notify |= from.notify;
        for(Map.Entry<GobIcon.Setting.ID, Entry> ment : from.entries.entrySet()) {
            Entry ent = ment.getValue();
            Entry cur = into.entries.get(ment.getKey());
            if(cur == null) {
                into.entries.put(ment.getKey(), ent);
                continue;
            }
            cur.show    |= ent.show;
            cur.defshow |= ent.defshow;
            cur.notify  |= ent.notify;
            cur.ring    |= ent.ring;
            if(ent.markset && !cur.markset) {
                cur.markset = true;
                cur.mark = ent.mark;
            } else if(ent.markset) {
                cur.mark |= ent.mark;
            }
            if(cur.resns == null)  cur.resns = ent.resns;
            if(cur.filens == null) cur.filens = ent.filens;
            cur.resver = Math.max(cur.resver, ent.resver);
        }
    }
}
