package nurgling;

import haven.*;
import nurgling.actions.bots.BuildCatalog;
import nurgling.pf.NHitBoxD;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Attribute hosted on the player that owns a heterogeneous set of ghost building gobs.
 * Used by the world blueprint editor to track manually placed ghosts independent of the
 * single-type BuildGhostPreview used by the area-fill bots.
 */
public class MixedGhostStore extends GAttrib {

    public static final class Entry {
        public final Gob gob;
        public final String type;     // catalog name, e.g. "Cupboard"
        public final String kind;     // "building" for now
        public int rotation;          // 0..3 quarter turns clockwise
        public Coord2d pos;           // world coords of gob center
        public final NHitBox unrotatedHitbox;  // for collision math only
        public final long gridId;     // grid id captured at placement time

        Entry(Gob gob, String type, String kind, int rotation, Coord2d pos, NHitBox unrotatedHitbox, long gridId) {
            this.gob = gob;
            this.type = type;
            this.kind = kind;
            this.rotation = rotation;
            this.pos = pos;
            this.unrotatedHitbox = unrotatedHitbox;
            this.gridId = gridId;
        }

        public double angleRadians() {
            return (rotation & 3) * Math.PI / 2.0;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Glob glob;

    public MixedGhostStore(Gob owner) {
        super(owner);
        this.glob = owner.glob;
    }

    public List<Entry> getEntries() { return Collections.unmodifiableList(entries); }

    /**
     * Try to place a building ghost at the given world position. Returns the new entry,
     * or null if it would overlap an existing real obstacle or another ghost.
     */
    public Entry tryPlaceBuilding(String type, Coord2d worldPos, int rotation) {
        BuildCatalog.BuildingDef def = BuildCatalog.get(type);
        if (def == null) return null;

        NHitBox hb = lookupHitbox(def);
        if (collides(hb, worldPos, rotation, null)) return null;

        try {
            Gob ghost = new Gob(glob, worldPos);
            ghost.setattr(new GhostAlpha(ghost));
            Indir<Resource> res = Resource.remote().load(def.resName);
            ghost.setattr(new ResDrawable(ghost, res, Message.nil));
            ghost.a = rotation * Math.PI / 2.0;
            glob.oc.add(ghost);
            long gridId = lookupGridIdFor(worldPos);
            Entry e = new Entry(ghost, type, "building", rotation, worldPos, hb, gridId);
            entries.add(e);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Look up the grid id at the given world position from the player's current view.
     * The user places ghosts while on the correct floor, so this captures floor-specific
     * grid IDs that survive even if the player later moves to a different floor.
     */
    private long lookupGridIdFor(Coord2d worldPos) {
        try {
            Coord chunk = worldPos.floor(MCache.tilesz).div(MCache.cmaps);
            MCache.Grid g = glob.map.grids.get(chunk);
            return g != null ? g.id : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Clear auto-populated hitbox so the ghost doesn't act as a pathfinder obstacle. */
    public void suppressHitboxes() {
        for (Entry e : entries) {
            if (e.gob != null && e.gob.ngob != null && e.gob.ngob.hitBox != null) {
                e.gob.ngob.hitBox = null;
            }
        }
    }

    /** Return the closest entry within `tolerance` world pixels of pos, or null. */
    public Entry findNear(Coord2d pos, double tolerance) {
        Entry best = null;
        double minDist = tolerance;
        for (Entry e : entries) {
            double d = e.pos.dist(pos);
            if (d < minDist) {
                minDist = d;
                best = e;
            }
        }
        return best;
    }

    /** Find the entry whose hitbox actually contains the given world point, or null. */
    public Entry findContaining(Coord2d pos) {
        for (Entry e : entries) {
            if (e.unrotatedHitbox == null) continue;
            NHitBoxD box = new NHitBoxD(e.unrotatedHitbox.begin, e.unrotatedHitbox.end, e.pos, e.angleRadians());
            // Build a tiny point-sized box at pos and use intersection
            NHitBoxD point = new NHitBoxD(pos.add(-0.5, -0.5), pos.add(0.5, 0.5));
            if (box.intersects(point, false)) return e;
        }
        return null;
    }

    public boolean remove(Entry e) {
        if (e == null) return false;
        if (!entries.remove(e)) return false;
        try { glob.oc.remove(e.gob); } catch (Exception ex) { /* ignore */ }
        return true;
    }

    /** Cycle the entry's rotation. Returns false if rotated footprint would overlap. */
    public boolean rotate(Entry e) {
        if (e == null) return false;
        int newRot = (e.rotation + 1) & 3;
        if (collides(e.unrotatedHitbox, e.pos, newRot, e)) return false;
        e.rotation = newRot;
        e.gob.move(e.pos, newRot * Math.PI / 2.0);
        return true;
    }

    public void clear() {
        for (Entry e : entries) {
            try { glob.oc.remove(e.gob); } catch (Exception ex) { /* ignore */ }
        }
        entries.clear();
    }

    @Override
    public void dispose() {
        clear();
    }

    /**
     * Look up an appropriate hitbox for the building: explicit custom hitbox if defined,
     * otherwise an axis-aligned box approximated from the catalog's tile footprint.
     */
    public static NHitBox lookupHitbox(BuildCatalog.BuildingDef def) {
        NHitBox h = NHitBox.findCustom(def.resName);
        if (h != null) return h;
        int hw = (int) (def.tileFootprint.x * MCache.tilesz.x);
        int hh = (int) (def.tileFootprint.y * MCache.tilesz.y);
        return new NHitBox(new Coord(-hw / 2, -hh / 2), new Coord(hw / 2, hh / 2));
    }

    /**
     * Check whether placing a hitbox at (pos, rot) would intersect any real obstacle in
     * the world or any other ghost in this store. Pass `ignore` to skip self when
     * checking a re-rotation in place.
     */
    public boolean collides(NHitBox hb, Coord2d pos, int rot, Entry ignore) {
        if (hb == null) return false;
        NHitBoxD testBox = new NHitBoxD(hb.begin, hb.end, pos, rot * Math.PI / 2.0);

        // Real-world obstacles
        try {
            Gob player = NUtils.player();
            long playerId = player != null ? player.id : -1;
            synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
                for (Gob other : NUtils.getGameUI().ui.sess.glob.oc) {
                    if (other == null || other.id == playerId) continue;
                    if (other.getattr(GhostAlpha.class) != null) continue; // skip ghosts (ours + other previews)
                    if (other instanceof OCache.Virtual) continue;
                    if (other.attr.isEmpty()) continue;
                    if (other.ngob == null || other.ngob.hitBox == null) continue;
                    if (other.getattr(Following.class) != null) continue;
                    NHitBoxD otherBox = new NHitBoxD(other);
                    if (otherBox.intersects(testBox, false)) return true;
                }
            }
        } catch (Exception ex) {
            // If we can't enumerate obstacles, fall through to ghost-vs-ghost check.
        }

        // Other ghosts in our own store
        for (Entry e : entries) {
            if (e == ignore) continue;
            if (e.unrotatedHitbox == null) continue;
            NHitBoxD eBox = new NHitBoxD(e.unrotatedHitbox.begin, e.unrotatedHitbox.end, e.pos, e.angleRadians());
            if (eBox.intersects(testBox, false)) return true;
        }
        return false;
    }
}
