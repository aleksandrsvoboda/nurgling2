package nurgling.widgets;

import haven.*;
import haven.res.ui.obj.buddy.Buddy;
import nurgling.*;
import nurgling.overlays.map.MinimapChunkNavRenderer;
import nurgling.overlays.map.MinimapClaimRenderer;
import nurgling.overlays.map.MinimapDiscoveryRenderer;
import nurgling.overlays.map.MinimapExploredAreaRenderer;
import nurgling.tools.ExploredArea;
import nurgling.tools.NParser;

import java.awt.*;
import java.awt.image.BufferedImage;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;

public class
NMiniMap extends MiniMap {
    public static final Coord _sgridsz = new Coord(100, 100);
    public static final Coord VIEW_SZ = UI.scale(_sgridsz.mul(9).div(tilesz.floor()));
    public static final Color VIEW_EXPLORED_COLOR = new Color(255, 255, 0, 144); // Yellow semi-transparent for explored area (120 + 20% of 120 = 144)
    public static final Color VIEW_SESSION_COLOR = new Color(0, 255, 0, 160); // Green semi-transparent for session explored area
    public static final Color VIEW_BG_COLOR = new Color(255, 255, 255, 60);
    public static final Color VIEW_BORDER_COLOR = new Color(0, 0, 0, 128);
    public final ExploredArea exploredArea = new ExploredArea(this);

    private String currentTerrainName = null;

    // Cache for fish icon textures to avoid reloading every frame
    private final java.util.HashMap<String, TexI> fishIconCache = new java.util.HashMap<>();

    // Cache for tree icon textures to avoid reloading every frame
    private final java.util.HashMap<String, TexI> treeIconCache = new java.util.HashMap<>();

    /* Visibility of tree and fish icons, and of prospected sample marks, lives in NConfig so
     * that every minimap (corner and map window) shows the same thing and the choice survives
     * relogging. The Map Tools panel and the map-window toolbar buttons are two views of these. */
    public static boolean showTreeIcons() {
        Object val = NConfig.get(NConfig.Key.showTreeIcons);
        return !(val instanceof Boolean) || (Boolean) val;
    }

    public static void showTreeIcons(boolean val) {
        NConfig.set(NConfig.Key.showTreeIcons, val);
    }

    public static boolean showFishIcons() {
        Object val = NConfig.get(NConfig.Key.showFishIcons);
        return !(val instanceof Boolean) || (Boolean) val;
    }

    public static void showFishIcons(boolean val) {
        NConfig.set(NConfig.Key.showFishIcons, val);
    }

    public static nurgling.conf.ProspectMarkSettings prospectSettings() {
        Object val = NConfig.get(NConfig.Key.prospectMarks);
        if(val instanceof nurgling.conf.ProspectMarkSettings)
            return (nurgling.conf.ProspectMarkSettings) val;
        return null;
    }

    /** Whether a prospected sample mark passes the current kind/threshold filter. */
    public static boolean markVisible(LabeledMinimapMark mark) {
        nurgling.conf.ProspectMarkSettings settings = prospectSettings();
        if(settings == null)
            return true;
        return settings.shows(mark.kind, mark.quality);
    }

    // Cached waypoint number labels to avoid per-frame Text.render() allocations
    private static Text[] waypointNumCache = new Text[128];
    public static Text getWaypointLabel(int num) {
        int idx = num - 1;
        if(idx >= 0 && idx < waypointNumCache.length) {
            if(waypointNumCache[idx] == null)
                waypointNumCache[idx] = Text.render(String.valueOf(num));
            return waypointNumCache[idx];
        }
        return Text.render(String.valueOf(num));
    }

    private static final Coord2d sgridsz = new Coord2d(new Coord(100,100));
    public NMiniMap(Coord sz, MapFile file) {
        super(sz, file);
    }

    public NMiniMap(MapFile file) {
        super(file);
    }

    /**
     * Check if a TempMark is inside the player's visible area (81 tile zone).
     * Uses the same calculation as explored area and drawtempmarks for consistency.
     * 
     * @param cm the TempMark to check
     * @param pl player position (unused, kept for compatibility)
     * @return true if the mark is inside the visible area (should be removed), false otherwise
     */
    public boolean checktemp(TempMark cm, Coord2d pl) {
        return isInVisibleArea(cm.gc);
    }
    
    /**
     * Check if a tile coordinate (in global grid coords with session offset) is inside 
     * the player's 81-tile visible area.
     * 
     * @param gc the global grid coordinate to check (tile coords + sessloc.tc)
     * @return true if inside visible area, false otherwise
     */
    public boolean isInVisibleArea(Coord gc) {
        if(sessloc == null || dloc == null) {
            return false;
        }
        
        Gob player = NUtils.player();
        if(player == null) {
            return false;
        }
        
        // Calculate visible area boundaries (same as explored area and drawtempmarks)
        // This is the 81-tile visibility zone around the player
        Coord ul = player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz).floor(tilesz).add(sessloc.tc);
        Coord unscaledViewSize = _sgridsz.mul(9).div(tilesz.floor());
        Coord br = ul.add(unscaledViewSize).add(1, 1);
        
        // Check if the coordinate is inside the visible area
        return gc.x >= ul.x && gc.x < br.x &&
               gc.y >= ul.y && gc.y < br.y;
    }
    
    /**
     * Check if a tile coordinate is inside the inner zone (~71 tiles).
     * Objects that disappear inside this zone were likely collected/killed,
     * not just leaving the server's visible area.
     * 
     * @param gc the global grid coordinate to check (tile coords + sessloc.tc)
     * @return true if inside inner zone, false otherwise
     */
    public boolean isInInnerZone(Coord gc) {
        if(sessloc == null || dloc == null) {
            return false;
        }
        
        Gob player = NUtils.player();
        if(player == null) {
            return false;
        }
        
        // Calculate inner zone boundaries (~71 tiles instead of 81)
        // This is 5 tiles smaller on each side than the full visible area
        // 81 - 10 = 71 tiles
        Coord ul = player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz).floor(tilesz).add(sessloc.tc);
        Coord unscaledViewSize = _sgridsz.mul(9).div(tilesz.floor());
        Coord br = ul.add(unscaledViewSize).add(1, 1);
        
        // Shrink the area by 5 tiles on each side
        Coord innerUl = ul.add(5, 5);
        Coord innerBr = br.sub(5, 5);
        
        // Check if the coordinate is inside the inner zone
        return gc.x >= innerUl.x && gc.x < innerBr.x &&
               gc.y >= innerUl.y && gc.y < innerBr.y;
    }
    
    /**
     * Check if a world coordinate (rc) is inside the player's 81-tile visible area.
     * 
     * @param rc the world coordinate to check
     * @return true if inside visible area, false otherwise
     */
    public boolean isWorldCoordInVisibleArea(Coord2d rc) {
        if(sessloc == null) {
            return false;
        }
        Coord gc = rc.floor(tilesz).add(sessloc.tc);
        return isInVisibleArea(gc);
    }

    public static class TempMark {
        public String name;
        public long start;          // Time when the mark was "fixed" (object left visible area or disappeared)
        public long lastupdate;
        public long disappearedAt;  // Time when object disappeared from game (0 if still visible)
        public final long id;
        public Coord2d rc;
        public Coord gc;
        public TexI icon;
        public Color buddyColor;
        public boolean wasInsideVisibleArea;  // Track if object was inside visible area on last check
        public boolean objectExists;          // Track if object exists in game

        public MiniMap.Location loc;

        public TempMark(String name, MiniMap.Location loc, long id, Coord2d rc, Coord gc, BufferedImage icon) {
            start = System.currentTimeMillis();
            lastupdate = start;
            disappearedAt = 0;
            this.name = name;
            this.id = id;
            this.rc = rc;
            this.gc = gc;
            this.icon = new TexI(icon);
            this.loc = loc;
            this.buddyColor = null;
            this.wasInsideVisibleArea = true;  // Assume object starts inside visible area
            this.objectExists = true;          // Object exists when mark is created
        }
        
        public TempMark(String name, MiniMap.Location loc, long id, Coord2d rc, Coord gc, BufferedImage icon, Color buddyColor) {
            start = System.currentTimeMillis();
            lastupdate = start;
            disappearedAt = 0;
            this.name = name;
            this.id = id;
            this.rc = rc;
            this.gc = gc;
            this.icon = new TexI(icon);
            this.loc = loc;
            this.buddyColor = buddyColor;
            this.wasInsideVisibleArea = true;  // Assume object starts inside visible area
            this.objectExists = true;          // Object exists when mark is created
        }
    }

    @Override
    public void drawparts(GOut g) {
        if(NUtils.getGameUI()==null)
            return;
        drawmap(g);
        
        // Draw tile highlight overlay
        drawTileHighlightOverlay(g);

        // Render explored area overlay (yellow semi-transparent)
        MinimapExploredAreaRenderer.renderExploredArea(this, g);
        
        // Render claim overlays (personal, village, realm)
        MinimapClaimRenderer.renderClaims(this, g);

        // Render ChunkNav exploration overlay (checks config internally)
        MinimapChunkNavRenderer.renderChunkNav(this, g);

        // Render undiscovered-LP gob markers (shares NConfig.Key.lpassistent toggle with NLPassistant)
        MinimapDiscoveryRenderer.renderDiscoveryMarkers(this, g);

        boolean playerSegment = (sessloc != null) && ((curloc == null) || (sessloc.seg.id == curloc.seg.id));
        // Show grid when zoomed in enough (scale >= 0.25, i.e. not too far out)
        if(currentScale >= 0.25f && (Boolean) NConfig.get(NConfig.Key.showGrid)) {drawgrid(g);}
        // Show view box when zoomed in (scale >= 0.5)
        if(playerSegment && currentScale >= 0.5f && (Boolean)NConfig.get(NConfig.Key.showView)) {drawview(g);}
        drawmarkers(g);
        // Show icons on all zoom levels with high detail (data level 0 or 1)
        int dataLevel = getDataLevel();
        if(dataLevel <= 1)
            drawicons(g);
        drawparty(g);
        drawPeers(g);            // Players sharing this database, at any distance

        drawtempmarks(g);
        drawLabeledMarks(g);
        drawterrainname(g);
        drawResourceTimers(g);
        drawFishLocations(g);
        drawTreeLocations(g);
        drawQueuedWaypoints(g);  // Draw waypoint visualization
        drawForagerRecordingPath(g);  // Draw forager path being recorded
        drawMarkerLine(g);       // Draw line to selected marker
        drawPings(g);            // Draw chat map pings on top of everything else
    }

    /**
     * Players whose position came from the shared database - which is to say players at any distance
     * at all, kinned or not.
     *
     * <p>Membership is by database access, not by the in-game Kin list: anyone publishing to this
     * database is drawn. Kin group only decides the colour.
     *
     * <p>The client's own two mechanisms both stop short: {@code drawicons} needs the game server to
     * still be sending you the Gob, and {@code drawparty} needs an actual party. This draws everyone
     * the shared database knows about, using the same arrow as {@code drawparty} so a marker reads
     * as "a player" without anything new to learn.
     *
     * <p>Lives here rather than in a dedicated widget for the same reason as {@link #drawPings}: the
     * map window's view subclasses this class, so the corner minimap and the map window both get it
     * from one call.
     */
    private void drawPeers(GOut g) {
        if(sessloc == null || dloc == null)
            return;
        if(!(Boolean)NConfig.get(NConfig.Key.showPeerPositions))
            return;
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.peerPositionService == null)
            return;
        java.util.List<PeerPosition> peers = gui.peerPositionService.snapshot();
        if(peers.isEmpty())
            return;

        /* Names are only legible at high detail, and a crowded village at world zoom would be a wall
         * of overlapping text. The arrows stay at every zoom. */
        boolean names = getDataLevel() <= 1;

        for(PeerPosition kp : peers) {
            MiniMap.Location loc = kp.ref.loc();
            if(loc == null)
                continue;    // their grid is in no segment we have; nothing to draw against
            Coord c = xlate(loc);
            if(c == null)
                continue;    // resolved, but into a segment this map is not showing
            double alpha = kp.alpha();
            Color col = peercol(gui, kp.charName);
            boolean onmap = c.isect(Coord.z, sz);

            if(!onmap) {
                /* Off-map players get the same border arrow pings use. On a zoomed-in minimap that is
                 * most of them, and drawing nothing would hide the answer to "which way is Bjorn". */
                drawPingEdgeArrow(g, clampToEdge(c), c, col, alpha * 0.8, 0.0);
                continue;
            }

            // Same marker as drawparty, tinted by kin group where there is one, with a dark casing
            // so it stays readable over both snow and dark forest.
            g.chcolor(0, 0, 0, (int)(140 * alpha));
            g.rotimage(plp, c.add(UI.scale(1, 1)), plp.sz().div(2), -kp.angle - (Math.PI / 2));
            g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
            g.rotimage(plp, c, plp.sz().div(2), -kp.angle - (Math.PI / 2));
            g.chcolor();

            if(names) {
                String label = kp.stale() ? (kp.charName + " \u00b7 " + kp.agestr()) : kp.charName;
                Text txt = peerfnd.render(label, col);
                Coord tp = c.add(0, UI.scale(9));
                g.chcolor(0, 0, 0, (int)(150 * alpha));
                g.frect(tp.sub(txt.sz().x / 2 + UI.scale(2), 0), txt.sz().add(UI.scale(4), UI.scale(1)));
                g.chcolor(255, 255, 255, (int)(255 * alpha));
                g.aimage(txt.tex(), tp, 0.5, 0);
                g.chcolor();
            }
        }
    }

    /** Foundry for name labels; rendering one per player per frame would be needless garbage. */
    private static final Text.Foundry peerfnd = new Text.Foundry(Text.dfont, UI.scale(10)).aa(true);

    /** Neutral colour for a published character who is not on this client's kin list. */
    private static final Color PEER_DEFAULT = new Color(190, 190, 190);

    /**
     * Kin-group colour for a character name, or a neutral grey when they are not kinned here.
     *
     * <p>Colouring rather than filtering is the whole distinction between the two ideas: who is drawn
     * is decided by who shares this database, and only the colour is decided by the in-game Kin list.
     * Hiding someone who has not been added in-game yet would read as the feature being broken.
     */
    private static Color peercol(NGameUI gui, String name) {
        try {
            BuddyWnd bw = gui.buddies;
            if(bw != null && name != null) {
                for(BuddyWnd.Buddy b : bw) {
                    if(name.equals(b.name))
                        return((b.group >= 0 && b.group < BuddyWnd.gc.length)
                               ? BuddyWnd.gc[b.group] : PEER_DEFAULT);
                }
            }
        } catch(RuntimeException ignore) {
            /* The kin list is a live widget being mutated by the server; failing to read it must
             * cost a colour, never the marker. */
        }
        return(PEER_DEFAULT);
    }

    /**
     * Chat map pings (@Point). Drawn here rather than in a dedicated widget so the corner
     * minimap and the full map window both get them - MapWnd's view subclasses this class.
     * A ping in another segment simply has nowhere to go on this map and is skipped.
     */
    private void drawPings(GOut g) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.pingService == null)
            return;
        java.util.List<PingService.Ping> pings = gui.pingService.snapshot();
        if(pings.isEmpty())
            return;
        // Anchor the tethers on the character, the same way drawQueuedWaypoints anchors
        // its legs. Note this is NOT xlate(sessloc): sessloc is the segment tile of the
        // session's coordinate origin, so every tether would converge on one arbitrary
        // fixed point rather than on the player.
        Coord playerC = null;
        try {
            if(ui != null && ui.gui != null && ui.gui.map != null)
                playerC = p2c(new Coord2d(ui.gui.map.getcc()));
        } catch(Loading l) {
            playerC = null;
        }

        for(PingService.Ping p : pings) {
            MiniMap.Location loc = p.loc();
            if(loc == null)
                continue;
            Coord c = xlate(loc);
            if(c == null)
                continue;
            double alpha = p.alpha();
            if(alpha <= 0)
                continue;
            Color col = p.col;
            boolean onmap = c.isect(Coord.z, sz);
            Coord anchor = onmap ? c : clampToEdge(c);

            // Tether from the character to the ping - including our own pings, so a ping
            // we just sent reads the same as everyone else's. A ping near the edge of a
            // zoomed-in map otherwise gives no sense of which way it is; the dashes crawl
            // toward it so the direction reads without having to compare two dots.
            if(playerC != null) {
                double phase = Utils.rtime() * UI.scale(14);
                // Dark casing first: the map underneath ranges from dark forest to bright
                // snow, and a single thin coloured line disappears against half of it.
                g.chcolor(6, 18, 12, (int)(140 * alpha));
                dashLine(g, playerC, anchor, phase, 4);
                g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(190 * alpha));
                dashLine(g, playerC, anchor, phase, 2);
            }

            if(!onmap) {
                drawPingEdgeArrow(g, anchor, c, col, alpha, p.since());
                continue;
            }

            // Two expanding rings, staggered, each stroked twice so a 2 px outline reads
            // as a band rather than a hairline. Phase keys off the ping's own age so it
            // always starts at the marker.
            double life = p.since();
            for(int i = 0; i < 2; i++) {
                double t = (life - (i * (PING_PERIOD / 2))) / PING_PERIOD;
                if(t < 0)
                    continue;
                t = t % 1.0;
                int a = (int)(210 * alpha * Math.pow(1 - t, 1.7));
                if(a < 8)
                    continue;
                double u = 1 - t;
                int r = (int)(UI.scale(4) + ((1 - (u * u * u)) * UI.scale(15)));
                g.chcolor(6, 18, 12, (int)(a * 0.5));
                ringOutline(g, c, r, 4);
                g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
                ringOutline(g, c, r, 2);
            }

            // One-shot arrival burst, matching the world overlay.
            if(life < PING_BURST) {
                double t = life / PING_BURST;
                int a = (int)(230 * Math.pow(1 - t, 1.4));
                if(a >= 8) {
                    double u = 1 - t;
                    g.chcolor(255, 255, 255, a);
                    ringOutline(g, c, (int)(UI.scale(4) + ((1 - (u * u * u)) * UI.scale(22))), 2);
                }
            }

            // Marker: dark plate, colour body, hot core, breathing on the ring clock.
            double pulse = 1.0 + (0.20 * Math.sin(2 * Math.PI * life / PING_PERIOD));
            int r = (int)(UI.scale(5) * pulse);
            g.chcolor(6, 18, 12, (int)(220 * alpha));
            g.fellipse(c, new Coord(r + UI.scale(2), r + UI.scale(2)));
            g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
            g.fellipse(c, new Coord(r, r));
            g.chcolor(255, 255, 255, (int)(230 * alpha));
            g.fellipse(c, new Coord(Math.max(1, r - UI.scale(2)), Math.max(1, r - UI.scale(2))));
        }
        g.chcolor();
    }

    private static final double PING_PERIOD = 1.5;
    private static final double PING_BURST = 0.55;

    /** Where the ray from the map centre to an off-map point leaves the widget. */
    private Coord clampToEdge(Coord c) {
        Coord mid = sz.div(2);
        int dx = c.x - mid.x, dy = c.y - mid.y;
        if((dx == 0) && (dy == 0))
            return(mid);
        double hx = (sz.x / 2.0) - UI.scale(10), hy = (sz.y / 2.0) - UI.scale(10);
        double sc = Math.min((dx != 0) ? (hx / Math.abs(dx)) : Double.MAX_VALUE,
                             (dy != 0) ? (hy / Math.abs(dy)) : Double.MAX_VALUE);
        return(mid.add((int)Math.round(dx * sc), (int)Math.round(dy * sc)));
    }

    /**
     * Arrow pinned to the map border for a ping that is off the visible map. Without this
     * an off-map ping was simply not drawn at all, which on a zoomed-in minimap is most of
     * them - the ping existed but the player had no way to know.
     */
    private void drawPingEdgeArrow(GOut g, Coord at, Coord target, Color col, double alpha, double life) {
        Coord mid = sz.div(2);
        double ang = Math.atan2(target.y - mid.y, target.x - mid.x);
        double pulse = 1.0 + (0.28 * Math.sin(2 * Math.PI * (life % PING_PERIOD) / PING_PERIOD));
        int len = (int)(UI.scale(9) * pulse);
        Coord tip = at.add(Coord.sc(ang, len));
        Coord w1 = at.add(Coord.sc(ang + 2.5, len));
        Coord w2 = at.add(Coord.sc(ang - 2.5, len));
        g.chcolor(6, 18, 12, (int)(220 * alpha));
        g.line(w1, tip, 5);
        g.line(w2, tip, 5);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
        g.line(w1, tip, 2);
        g.line(w2, tip, 2);
    }

    /**
     * Broadcast a ping for the map position under a click. The map window can be scrolled
     * far outside the loaded grids, so the grid id comes from the map file's segment index
     * rather than from MCache. The file lock is only tried, never waited on - this runs on
     * the UI thread, and a missed ping costs nothing but another click.
     */
    private boolean sendPointPing(Coord sc) {
        MiniMap.Location loc = xlate(sc);
        if(loc == null)
            return false;
        if(!file.lock.readLock().tryLock())
            return false;
        Long gridId;
        try {
            gridId = loc.seg.map.get(loc.tc.div(cmaps));
        } finally {
            file.lock.readLock().unlock();
        }
        if(gridId == null)
            return false;
        return NMapView.sendPingToChat(gridId, loc.tc.mod(cmaps));
    }

    @Override
    public void drawparty(GOut g) {
        for(Party.Member m : ui.sess.glob.party.memb.values()) {
            try {
                Coord2d ppc = m.getc();
                if(ppc == null)
                    continue;
                Coord p2cppc = p2c(ppc);
                g.chcolor(m.col.getRed(), m.col.getGreen(), m.col.getBlue(), 255);
                g.rotimage(plp, p2cppc, plp.sz().div(2), -m.geta() - (Math.PI / 2));
                g.chcolor();
                
                // Draw party member names on minimap
                if((Boolean)NConfig.get(NConfig.Key.showPartyMemberNames)) {
                    String name = null;
                    if(NGameUI.gobIdToKinName.containsKey(m.gobid)) {
                        name = NGameUI.gobIdToKinName.get(m.gobid);
                    } else if(m.getgob() != null) {
                        Buddy buddyInfo = m.getgob().getattr(Buddy.class);
                        if(buddyInfo != null) {
                            name = buddyInfo.rnm;
                            if(name != null && !NGameUI.gobIdToKinName.containsKey(m.gobid)) {
                                NGameUI.gobIdToKinName.put(m.gobid, name);
                            }
                        }
                    }
                    if(name != null && !name.isEmpty()) {
                        Text nameText = NStyle.meter.render(name);
                        g.aimage(nameText.tex(), p2cppc.add(0, -UI.scale(15)), 0.5, 0.5);
                    }
                }
            } catch(Loading l) {}
        }
    }

    // Draw forager path being recorded or loaded
    protected void drawForagerRecordingPath(GOut g) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || sessloc == null || dloc == null) return;
        
        // Find a PathRecordable window (Forager or TrufflePigHunter)
        nurgling.widgets.bots.PathRecordable pathWnd = null;
        for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if(wdg instanceof nurgling.widgets.bots.PathRecordable) {
                pathWnd = (nurgling.widgets.bots.PathRecordable) wdg;
                break;
            }
        }

        // Get current path: from bot settings window, or from active bot execution
        nurgling.routes.ForagerPath recordingPath = null;
        if(pathWnd != null) {
            recordingPath = pathWnd.getCurrentLoadedPath();
        } else if((Boolean) nurgling.NConfig.get(nurgling.NConfig.Key.showBotPathOnMinimap) && gui.activeBotPath != null) {
            recordingPath = gui.activeBotPath;
        }
        if(recordingPath == null || recordingPath.waypoints.isEmpty()) {
            return;
        }
        
        Coord hsz = sz.div(2);
        
        // Draw lines connecting waypoints
        g.chcolor(0, 255, 0, 200); // Green color for recording path
        Coord prevC = null;
        
        for(nurgling.routes.ForagerWaypoint waypoint : recordingPath.waypoints) {
            // Only draw waypoints in current segment
            if(waypoint.seg != sessloc.seg.id) {
                continue;
            }
            
            // Convert tile coordinates to screen coordinates
            Coord waypointC = waypoint.tc.sub(dloc.tc).div(scalef()).add(hsz);
            
            // Only draw if within bounds
            if(waypointC.x >= 0 && waypointC.x < sz.x && waypointC.y >= 0 && waypointC.y < sz.y) {
                if(prevC != null && prevC.x >= 0 && prevC.x < sz.x && prevC.y >= 0 && prevC.y < sz.y) {
                    g.line(prevC, waypointC, 2);
                }
            }
            prevC = waypointC;
        }
        
        // Draw markers at each waypoint
        int num = 1;
        for(nurgling.routes.ForagerWaypoint waypoint : recordingPath.waypoints) {
            // Only draw waypoints in current segment
            if(waypoint.seg != sessloc.seg.id) continue;
            
            // Convert tile coordinates to screen coordinates
            Coord c = waypoint.tc.sub(dloc.tc).div(scalef()).add(hsz);
            
            // Only draw if within bounds
            if(c.x >= 0 && c.x < sz.x && c.y >= 0 && c.y < sz.y) {
                // Draw yellow circle
                g.chcolor(255, 255, 0, 220); // Yellow marker
                int radius = UI.scale(6); // Larger radius
                g.fellipse(c, new Coord(radius, radius));
                
                // Draw black number
                g.chcolor(0, 0, 0, 255); // Black text
                g.aimage(getWaypointLabel(num).tex(), c, 0.5, 0.5);
            }
            num++;
        }
        g.chcolor();
    }
    
    // Draw queued waypoints visualization
    protected void drawQueuedWaypoints(GOut g) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.waypointMovementService == null) return;
        if(sessloc == null || dloc == null) return;

        java.util.List<nurgling.WaypointMovementService.Waypoint> allWaypoints =
                gui.waypointMovementService.snapshot();
        if(allWaypoints.isEmpty()) return;

        // Get player's current position on the map for drawing the line
        Coord playerScreenPos = null;
        try {
            if(ui != null && ui.gui != null && ui.gui.map != null) {
                Coord2d playerWorld = new Coord2d(ui.gui.map.getcc());
                playerScreenPos = p2c(playerWorld);
            }
        } catch(Loading l) {
            // Fall back to sessloc if player position not available
            playerScreenPos = xlate(sessloc);
        }

        // Legs, as dashes crawling toward the next waypoint so direction is readable
        double phase = Utils.rtime() * UI.scale(16);
        Coord prevC = playerScreenPos;
        for(int i = 0; i < allWaypoints.size(); i++) {
            nurgling.WaypointMovementService.Waypoint waypoint = allWaypoints.get(i);
            if(waypoint.loc.seg.id != sessloc.seg.id)
                continue;

            Coord waypointC = xlate(waypoint.loc);
            if(prevC != null && waypointC != null) {
                Color lc = (i == 0) ? nurgling.overlays.NWaypointOverlay.activeColor()
                                    : nurgling.overlays.NWaypointOverlay.queuedColor();
                g.chcolor(lc.getRed(), lc.getGreen(), lc.getBlue(), 200);
                dashLine(g, prevC, waypointC, phase, 2);
            }
            prevC = waypointC;
        }

        // Nodes. The active one is bigger and pulses; the one being dragged or hovered
        // turns white so it is obvious which node the cursor has hold of.
        for(int i = 0; i < allWaypoints.size(); i++) {
            nurgling.WaypointMovementService.Waypoint waypoint = allWaypoints.get(i);
            if(waypoint.loc.seg.id != sessloc.seg.id)
                continue;

            Coord c = xlate(waypoint.loc);
            if(c == null || c.x < -UI.scale(12) || c.y < -UI.scale(12) ||
               c.x > sz.x + UI.scale(12) || c.y > sz.y + UI.scale(12))
                continue;

            boolean active = (i == 0);
            Color col = waypointColor(i, waypoint.id);

            if(active) {
                // expanding ping on the waypoint being run to
                double t = (Utils.rtime() % 1.3) / 1.3;
                int a = (int)(150 * (1 - t));
                if(a > 8) {
                    g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
                    ringOutline(g, c, (int)(UI.scale(6) + t * UI.scale(10)), 2);
                }
            }

            int radius = UI.scale(active ? 7 : 5);
            // Plate
            g.chcolor(0, 0, 0, 210);
            g.fellipse(c, new Coord(radius + 1, radius + 1));
            g.chcolor(col);
            g.fellipse(c, new Coord(radius, radius));
            // Number
            g.chcolor(10, 14, 16, 255);
            g.aimage(getWaypointLabel(i + 1).tex(), c, 0.5, 0.5);
        }
        drawWaypointDragGhost(g);
        g.chcolor();
    }

    /** Where a dragged waypoint was picked up from, plus a tether to where it is now. */
    private void drawWaypointDragGhost(GOut g) {
        if(wpGrab == null || wpDragOrigin == null || sessloc == null || dloc == null)
            return;
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.waypointMovementService == null)
            return;
        if(wpDragOrigin.seg.id != sessloc.seg.id)
            return;

        Coord org = xlate(wpDragOrigin);
        Coord cur = null;
        Location curLoc = null;
        for(nurgling.WaypointMovementService.Waypoint wp : gui.waypointMovementService.snapshot()) {
            if(wp.id == wpDragId && wp.loc.seg.id == sessloc.seg.id) {
                curLoc = wp.loc;
                cur = xlate(wp.loc);
            }
        }
        if(org == null)
            return;

        g.chcolor(255, 255, 255, 120);
        ringOutline(g, org, UI.scale(6), 1);
        if(cur != null && curLoc != null) {
            dashLine(g, org, cur, 0, 1);
            int tiles = (int)Math.round(wpDragOrigin.tc.dist(curLoc.tc));
            Text t = Text.render(tiles + " tiles");
            Coord mid = org.add(cur).div(2);
            g.chcolor(10, 14, 16, 190);
            g.frect(mid.sub(t.sz().x / 2 + UI.scale(2), t.sz().y / 2), t.sz().add(UI.scale(4), 0));
            g.chcolor(235, 240, 245, 255);
            g.aimage(t.tex(), mid, 0.5, 0.5);
            t.dispose();
        }
        g.chcolor();
    }

    /** Circle outline; GOut only offers filled ellipses. */
    private void ringOutline(GOut g, Coord c, int r, double w) {
        final int n = 20;
        Coord prev = null;
        for(int i = 0; i <= n; i++) {
            double ang = (2 * Math.PI * i) / n;
            Coord pt = c.add((int)Math.round(Math.cos(ang) * r), (int)Math.round(Math.sin(ang) * r));
            if(prev != null)
                g.line(prev, pt, w);
            prev = pt;
        }
    }

    /** Colour of a queued waypoint, shared with the world overlay. */
    private Color waypointColor(int idx, long id) {
        if(id == wpDragId)
            return(nurgling.overlays.NWaypointOverlay.dragColor());
        if(id == wpHoverId)
            return(nurgling.overlays.NWaypointOverlay.hoverColor());
        return((idx == 0) ? nurgling.overlays.NWaypointOverlay.activeColor()
                          : nurgling.overlays.NWaypointOverlay.queuedColor());
    }

    /**
     * Dashed line clipped to the widget, with the dash pattern offset by {@code phase}
     * so the dashes crawl from a toward b.
     */
    private void dashLine(GOut g, Coord a, Coord b, double phase, double w) {
        Coord2d[] cl = clipLineToRect(new Coord2d(a), new Coord2d(b), new Coord2d(sz));
        if(cl == null)
            return;
        Coord2d p1 = cl[0], p2 = cl[1];
        double len = p1.dist(p2);
        if(len < 1)
            return;
        Coord2d dir = p2.sub(p1).div(len);
        double dash = UI.scale(7), gap = UI.scale(5), period = dash + gap;
        // Dashes are laid out from the true start of the leg, so clipping doesn't make
        // them jump when the minimap scrolls.
        double skip = new Coord2d(a).dist(p1);
        for(double t = -((phase + skip) % period); t < len; t += period) {
            double s0 = Math.max(0, t), s1 = Math.min(len, t + dash);
            if(s1 <= s0)
                continue;
            g.line(p1.add(dir.mul(s0)).round(), p1.add(dir.mul(s1)).round(), w);
        }
    }

    /* Waypoint dragging ------------------------------------------------------
     * Queued waypoints (alt+click) can be picked up with the left mouse button
     * and dragged to a new spot. Dragging the waypoint the character is walking
     * to re-issues the move command, so he turns around and follows it live. */
    private UI.Grab wpGrab = null;
    private long wpDragId = -1;
    private long wpHoverId = -1;
    private Location wpDragOrigin = null;

    /** True while the user is dragging a queued waypoint on this minimap. */
    public boolean isDraggingWaypoint() {
        return(wpGrab != null);
    }

    /** Id of the queued waypoint under the given widget-local point, or -1. */
    protected long waypointAt(Coord c) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.waypointMovementService == null) return -1;
        if(sessloc == null || dloc == null) return -1;

        java.util.List<nurgling.WaypointMovementService.Waypoint> wps = gui.waypointMovementService.snapshot();
        long best = -1;
        double bestDist = UI.scale(9);
        for(nurgling.WaypointMovementService.Waypoint wp : wps) {
            if(wp.loc.seg.id != sessloc.seg.id)
                continue;
            Coord sc = xlate(wp.loc);
            if(sc == null)
                continue;
            double d = sc.dist(c);
            if(d <= bestDist) {
                bestDist = d;
                best = wp.id;
            }
        }
        return best;
    }

    /** Begin dragging the waypoint under c, if any. */
    protected boolean startWaypointDrag(Coord c) {
        long id = waypointAt(c);
        if(id < 0)
            return false;
        NGameUI gui = NUtils.getGameUI();
        wpDragOrigin = null;
        if(gui != null && gui.waypointMovementService != null) {
            for(nurgling.WaypointMovementService.Waypoint wp : gui.waypointMovementService.snapshot()) {
                if(wp.id == id)
                    wpDragOrigin = wp.loc;
            }
        }
        wpDragId = id;
        wpGrab = ui.grabmouse(this);
        return true;
    }

    private void dragWaypointTo(Coord c, boolean commit) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.waypointMovementService == null || sessloc == null)
            return;
        Location loc = xlate(c);
        if(loc == null || loc.seg.id != sessloc.seg.id)
            return;
        if(!gui.waypointMovementService.setWaypoint(wpDragId, loc, sessloc, commit))
            endWaypointDrag();
    }

    private void endWaypointDrag() {
        if(wpGrab != null) {
            wpGrab.remove();
            wpGrab = null;
        }
        wpDragId = -1;
        wpDragOrigin = null;
    }

    /* Press-and-hold steering -------------------------------------------------
     * With the hold-to-move setting on, keeping the left button down on the map keeps
     * re-sending the spot under the pointer as a move command. Only on maps where the
     * left button is not the pan handle (the corner minimap); on the big map window
     * dragging still scrolls the map. */
    private final HoldToMove holdMove = new HoldToMove();
    private UI.Grab holdGrab = null;

    /** Arm hold-to-move on a plain left press, if this map allows it. */
    protected void startHoldSteer(Coord c) {
        if(!HoldToMove.enabled() || (holdGrab != null) || (wpGrab != null) || dragp(1))
            return;
        if((sessloc == null) || (dloc == null))
            return;
        // A press on an icon walks to that object; re-sampling would cancel it, so
        // steering there waits until the pointer actually moves.
        holdMove.arm(c, iconat(c) != null);
        holdGrab = ui.grabmouse(this);
    }

    private void steerHold(Coord c) {
        if((ui.modflags() != 0) || !holdMove.due())
            return;
        NGameUI gui = NUtils.getGameUI();
        if((gui == null) || (gui.map == null) || (sessloc == null))
            return;
        // Dragging past the edge keeps steering towards that edge rather than stopping dead.
        Coord cc = new Coord(Utils.clip(c.x, 0, sz.x - 1), Utils.clip(c.y, 0, sz.y - 1));
        Location loc;
        try {
            loc = xlate(cc);
        } catch(Loading l) {
            return;
        }
        if((loc == null) || (loc.seg.id != sessloc.seg.id))
            return;
        if(!holdMove.accept(new Coord2d(loc.tc), 1.0))
            return;
        if(gui.waypointMovementService != null)
            gui.waypointMovementService.setSteerPaused(true);
        mvclick(gui.map, null, loc, null, 1);
    }

    @Override
    public void destroy() {
        // Never leave the movement queue paused because the map went away mid-steer.
        if(holdGrab != null)
            endHoldSteer();
        super.destroy();
    }

    private void endHoldSteer() {
        boolean steered = holdMove.steered();
        if(holdGrab != null) {
            holdGrab.remove();
            holdGrab = null;
        }
        holdMove.disarm();
        if(steered) {
            NGameUI gui = NUtils.getGameUI();
            if((gui != null) && (gui.waypointMovementService != null))
                gui.waypointMovementService.setSteerPaused(false);
        }
    }

    // Clip a line to a rectangle boundary using Liang-Barsky algorithm
    private Coord2d[] clipLineToRect(Coord2d p1, Coord2d p2, Coord2d rectSize) {
        double x1 = p1.x, y1 = p1.y;
        double x2 = p2.x, y2 = p2.y;
        double dx = x2 - x1;
        double dy = y2 - y1;

        double t0 = 0.0, t1 = 1.0;

        // Check all four edges
        double[] pArr = {-dx, dx, -dy, dy};
        double[] qArr = {x1, rectSize.x - x1, y1, rectSize.y - y1};

        for(int i = 0; i < 4; i++) {
            if(pArr[i] == 0) {
                // Line is parallel to this edge
                if(qArr[i] < 0) {
                    return null; // Line is outside
                }
            } else {
                double r = qArr[i] / pArr[i];
                if(pArr[i] < 0) {
                    // Entering edge
                    if(r > t1) return null; // Line is outside
                    if(r > t0) t0 = r;
                } else {
                    // Exiting edge
                    if(r < t0) return null; // Line is outside
                    if(r < t1) t1 = r;
                }
            }
        }

        // Line is at least partially inside
        Coord2d newP1 = new Coord2d(x1 + t0 * dx, y1 + t0 * dy);
        Coord2d newP2 = new Coord2d(x1 + t1 * dx, y1 + t1 * dy);
        return new Coord2d[] {newP1, newP2};
    }

    // Draw line from player to selected marker
    protected void drawMarkerLine(GOut g) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || !(gui.map instanceof NMapView)) return;
        NMapView mapView = (NMapView) gui.map;

        // Draw gold line to selected marker icon (follows player)
        if(mapView.selectedMarkerTileCoords != null && sessloc != null && dloc != null) {
            try {
                // Get player's current position on the minimap
                Coord playerScreenPos = null;
                if(ui != null && ui.gui != null && ui.gui.map != null) {
                    Coord2d playerWorld = new Coord2d(ui.gui.map.getcc());
                    playerScreenPos = p2c(playerWorld);
                } else {
                    playerScreenPos = xlate(sessloc);
                }

                if(playerScreenPos != null) {
                    // Get marker position on minimap
                    Coord hsz = sz.div(2);
                    Coord markerScreenPos = mapView.selectedMarkerTileCoords.sub(dloc.tc).div(scalef()).add(hsz);

                    // Clip line to map bounds
                    Coord2d[] clipped = clipLineToRect(new Coord2d(playerScreenPos), new Coord2d(markerScreenPos), new Coord2d(sz));
                    if(clipped != null) {
                        // Draw gold line from player to marker
                        g.chcolor(255, 215, 0, 220); // Gold color for marker path
                        g.line(clipped[0].floor(), clipped[1].floor(), 3); // Thicker line for visibility
                        g.chcolor();
                    }
                }
            } catch(Exception e) {
                // Ignore errors
            }
        }

        // Draw directional vectors (fixed rays, don't follow player)
        if(!mapView.directionalVectors.isEmpty() && dloc != null) {
            Coord hsz = sz.div(2);

            for(nurgling.tools.DirectionalVector vector : mapView.directionalVectors) {
                try {
                    // Convert tile coordinates to minimap screen coordinates
                    Coord originScreenPos = vector.originTileCoords.sub(dloc.tc).div(scalef()).add(hsz);

                    // Calculate a far point along the vector direction
                    double rayLength = 10000; // Tiles (effectively infinite on map scale)
                    Coord2d farPointTiles = vector.getTilePointAt(rayLength);
                    Coord farScreenPos = new Coord((int)farPointTiles.x, (int)farPointTiles.y).sub(dloc.tc).div(scalef()).add(hsz);

                    // Clip the vector line to map bounds
                    Coord2d[] clipped = clipLineToRect(new Coord2d(originScreenPos), new Coord2d(farScreenPos), new Coord2d(sz));
                    if(clipped != null && vector.color != null) {
                        // Draw the ray from origin toward far point
                        g.chcolor(vector.color.getRed(), vector.color.getGreen(), vector.color.getBlue(), vector.color.getAlpha());
                        g.line(clipped[0].floor(), clipped[1].floor(), 2);
                        g.chcolor();
                    }
                } catch(Exception e) {
                    // Skip this vector if there's an error
                    continue;
                }
            }
        }
    }

    void drawview(GOut g) {
        if(ui.gui.map==null || sessloc == null || dloc == null)
            return;
        Gob player = ui.gui.map.player();
        if(player != null) {
            // Use same calculation as explored area
            Coord ul = player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz).floor(tilesz).add(sessloc.tc);
            Coord unscaledViewSize = _sgridsz.mul(9).div(tilesz.floor());
            Coord br = ul.add(unscaledViewSize);
            
            // Expand BR by 1,1 to match explored area
            Coord expandedBR = br.add(1, 1);
            
            // Convert to screen coordinates
            Coord hsz = sz.div(2);
            Coord screenUL = ul.sub(dloc.tc).div(scalef()).add(hsz);
            Coord screenBR = expandedBR.sub(dloc.tc).div(scalef()).add(hsz);
            Coord screenSize = screenBR.sub(screenUL);
            
            g.chcolor(VIEW_BG_COLOR);
            g.frect(screenUL, screenSize);
            g.chcolor(VIEW_BORDER_COLOR);
            g.rect(screenUL, screenSize);
            g.chcolor();
        }
    }

    void drawgrid(GOut g) {
        if(dgext == null || dloc == null) return;
        
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        Coord hsz = sz.div(2);
        
        double width = UI.scale(1f);
        Color col = g.getcolor();
        g.chcolor(Color.RED);
        
        // Draw grid lines at grid boundaries
        // Each grid is cmaps tiles at its data level
        int gridSizeInTiles = cmaps.x * (1 << dataLevel);
        
        for (int x = dgext.ul.x; x <= dgext.br.x; x++) {
            // Grid coordinate to tile coordinate
            Coord tilePosX = new Coord(x * gridSizeInTiles, 0);
            // Tile coordinate to screen coordinate
            Coord screenPos = UI.scale(tilePosX).mul(currentScale).sub(dloc.tc.div(scalef())).add(hsz);
            
            if(screenPos.x >= 0 && screenPos.x <= sz.x) {
                g.line(new Coord(screenPos.x, 0), new Coord(screenPos.x, sz.y), width);
            }
        }
        
        for (int y = dgext.ul.y; y <= dgext.br.y; y++) {
            // Grid coordinate to tile coordinate
            Coord tilePosY = new Coord(0, y * gridSizeInTiles);
            // Tile coordinate to screen coordinate
            Coord screenPos = UI.scale(tilePosY).mul(currentScale).sub(dloc.tc.div(scalef())).add(hsz);
            
            if(screenPos.y >= 0 && screenPos.y <= sz.y) {
                g.line(new Coord(0, screenPos.y), new Coord(sz.x, screenPos.y), width);
            }
        }
        
        g.chcolor(col);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(ui.gui.map==null)
            return;

        // Keep following the pointer while the left button is held: the map scrolls with
        // the character, so the spot under a still cursor keeps moving.
        if((holdGrab != null) && holdMove.steering())
            steerHold(ui.mc.sub(rootpos()));

        NGameUI gui = NUtils.getGameUI();
        
        // Smooth zoom interpolation
        if(Math.abs(currentScale - targetScale) > 0.001f) {
            // Interpolate towards target scale
            currentScale += (targetScale - currentScale) * ZOOM_SPEED;
            
            // Snap to target if very close
            if(Math.abs(currentScale - targetScale) < 0.001f) {
                currentScale = targetScale;
            }
        }

        // Only update explored area from the main corner minimap (gui.mmap)
        // This prevents multiple minimap instances from conflicting
        if((Boolean) NConfig.get(NConfig.Key.exploredAreaEnable) && gui != null && gui.mmap == this) {
            if ((sessloc != null) && ((curloc == null) || (sessloc.seg.id == curloc.seg.id))) {
                exploredArea.tick(dt);
                Gob player = ui.gui.map.player();
                if (player != null && dloc != null) {
                    Coord ul = player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz).floor(tilesz).add(sessloc.tc);
                    Coord unscaledViewSize = _sgridsz.mul(9).div(tilesz.floor());
                    Coord br = ul.add(unscaledViewSize);
                    
                    // Expand BR by 1,1 to cover rounding gaps
                    Coord expandedBR = br.add(1, 1);
                    
                    exploredArea.updateExploredTiles(ul, expandedBR, curloc.seg.id);
                }
            }
        }

        // Process waypoint movement queue through the centralized service
        if(gui != null && gui.waypointMovementService != null) {
            gui.waypointMovementService.processMovementQueue(file, sessloc);
        }
    }

    // Linear scale factor - this is the actual zoom level
    // scale = 4.0 means 4x zoom in, scale = 0.25 means 4x zoom out
    private float currentScale = 1.0f;
    private float targetScale = 1.0f;
    
    // Smooth zoom speed (how fast to interpolate to target)
    private static final float ZOOM_SPEED = 0.15f; // 15% per frame at 60fps = very smooth
    
    // Public accessor for currentScale (needed by MinimapClaimRenderer)
    public float getCurrentScale() {
        return currentScale;
    }
    
    // Invalidates all display caches to force complete map regeneration
    // Call this when settings change that affect map rendering (search, uniform colors, etc.)
    public void invalidateDisplayCache() {
        currentLevelCache = null;
        previousLevelCache = null;
        nextLevelCache = null;
        display = null;
        dgext = null;
    }
    
    // Returns true if terrain search is active in the main map window
    private boolean isTerrainSearchActive() {
        try {
            nurgling.NGameUI gui = nurgling.NUtils.getGameUI();
            if (gui != null && gui.mapfile != null) {
                String pattern = gui.mapfile.searchPattern;
                return pattern != null && !pattern.trim().isEmpty();
            }
        } catch (Exception ignored) { }
        return false;
    }

    // Helper method to calculate which data level to use based on current scale
    private int getDataLevel() {
        // When terrain search is active, force finest detail to ensure MapSource.drawmap()
        // is used so tile highlighting (selectedtex) is applied.
        if (cachedSearchActive) {
            return 0;
        }
        // Choose data level based on scale:
        // scale >= 1.0: use level 0 (finest detail)
        // scale >= 0.5: use level 0 (still detailed enough)
        // scale >= 0.25: use level 1 (2x coarser)
        // scale >= 0.125: use level 2 (4x coarser)
        // scale >= 0.0625: use level 3 (8x coarser)
        // etc.
        if(currentScale >= 0.5f) {
            return 0; // Use finest detail
        } else if(currentScale >= 0.25f) {
            return 1;
        } else if(currentScale >= 0.125f) {
            return 2;
        } else if(currentScale >= 0.0625f) {
            return 3;
        } else if(currentScale >= 0.03125f) {
            return 4;
        } else {
            return 5;
        }
    }
    
    // Public accessor for getDataLevel (needed by MinimapClaimRenderer)
    public int getDataLevelPublic() {
        return getDataLevel();
    }
    
    private float getScaleFactor() {
        // Calculate how much to scale the current data level
        int dataLevel = getDataLevel();
        
        // The scale factor is how much to scale the tiles at this data level
        // to achieve the desired currentScale
        // Each data level represents 2^level zoom out from level 0
        // So to get currentScale, we need: scaleFactor * (1 / 2^level) = currentScale
        // Therefore: scaleFactor = currentScale * 2^level
        
        return currentScale * (1 << dataLevel);
    }

    // Track current data level to detect when it changes
    private int currentDataLevel = 0;
    
    // Track last known search pattern to detect changes
    private String lastSearchPattern = "";
    
    // Cached state of whether terrain search is currently active
    private boolean cachedSearchActive = false;
    
    // Multi-level cache: keep current, previous, and next levels loaded
    // This eliminates black screens and loading freezes
    private class LevelCache {
        DisplayGrid[] display;
        Area dgext;
        int dataLevel;
        
        LevelCache(DisplayGrid[] display, Area dgext, int dataLevel) {
            this.display = display;
            this.dgext = dgext;
            this.dataLevel = dataLevel;
        }
    }
    
    private LevelCache currentLevelCache = null;
    private LevelCache previousLevelCache = null;
    private LevelCache nextLevelCache = null;

    // Override redisplay to support smooth zoom with fractional scaling
    protected void redisplay(Location loc) {
        Coord hsz = sz.div(2);
        
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        
        // Check if search pattern changed and force rebuild if needed
        String currentSearchPattern = "";
        try {
            nurgling.NGameUI gui = nurgling.NUtils.getGameUI();
            if (gui != null && gui.mapfile != null && gui.mapfile.searchPattern != null) {
                currentSearchPattern = gui.mapfile.searchPattern;
            }
        } catch (Exception ignored) { }
        
        boolean searchPatternChanged = !currentSearchPattern.equals(lastSearchPattern);
        if (searchPatternChanged) {
            lastSearchPattern = currentSearchPattern;
            cachedSearchActive = !currentSearchPattern.trim().isEmpty();
            invalidateDisplayCache();
        }
        
        // Calculate grid size for this data level (in tiles)
        int gridTileSize = cmaps.x * (1 << dataLevel);
        
        // Calculate effective screen size of one tile
        float tileScreenSize = UI.scale(1) * scaleFactor / (1 << dataLevel);
        
        // Calculate how many tiles fit on screen
        int tilesOnScreenX = (int)Math.ceil(UI.unscale(sz.x) / (scaleFactor / (1 << dataLevel))) + gridTileSize * 2;
        int tilesOnScreenY = (int)Math.ceil(UI.unscale(sz.y) / (scaleFactor / (1 << dataLevel))) + gridTileSize * 2;
        
        // Calculate grid coordinates
        Coord centerGrid = loc.tc.div(gridTileSize);
        Coord gridsNeeded = new Coord(
            (int)Math.ceil((float)tilesOnScreenX / gridTileSize) + 2,
            (int)Math.ceil((float)tilesOnScreenY / gridTileSize) + 2
        );
        
        Area next = Area.sized(centerGrid.sub(gridsNeeded.div(2)), gridsNeeded);
        
        // Detect data level changes and segment changes
        boolean dataLevelChanged = (dataLevel != currentDataLevel);
        boolean segmentChanged = (loc.seg != dseg);
        
        // If segment changed (teleport), clear all caches
        if(segmentChanged) {
            currentLevelCache = null;
            previousLevelCache = null;
            nextLevelCache = null;
        }
        
        if(dataLevelChanged) {
            // Shift cache: current becomes previous, next becomes current (if available)
            if(dataLevel > currentDataLevel) {
                // Zooming out: use preloaded next level if available
                previousLevelCache = currentLevelCache;
                if(nextLevelCache != null && nextLevelCache.dataLevel == dataLevel) {
                    currentLevelCache = nextLevelCache;
                    nextLevelCache = null;
                } else {
                    currentLevelCache = null;
                }
            } else {
                // Zooming in: use preloaded previous level if available
                nextLevelCache = currentLevelCache;
                if(previousLevelCache != null && previousLevelCache.dataLevel == dataLevel) {
                    currentLevelCache = previousLevelCache;
                    previousLevelCache = null;
                } else {
                    currentLevelCache = null;
                }
            }
            currentDataLevel = dataLevel;
        }
        
        // Update current level display
        boolean needsUpdate = (currentLevelCache == null) || 
                             (loc.seg != dseg) || 
                             (zoomlevel != dlvl) || 
                             !next.equals(dgext) || 
                             super.needUpdate || 
                             dataLevelChanged || 
                             searchPatternChanged;
                             
        if(needsUpdate) {
            DisplayGrid[] nd = new DisplayGrid[next.rsz()];
            
            // Try to reuse grids from cache only if segment hasn't changed
            if(currentLevelCache != null && !dataLevelChanged && !segmentChanged && currentLevelCache.dgext != null) {
                for(Coord c : currentLevelCache.dgext) {
                    if(next.contains(c))
                        nd[next.ri(c)] = currentLevelCache.display[currentLevelCache.dgext.ri(c)];
                }
            }
            
            super.needUpdate = false;
            currentLevelCache = new LevelCache(nd, next, dataLevel);
            
            // Update base class members
            display = nd;
            dseg = loc.seg;
            dlvl = zoomlevel;
            dmag = maglevel;
            dgext = next;
            dtext = Area.sized(next.ul.mul(gridTileSize), next.sz().mul(gridTileSize));
        }
        dloc = loc;
        
        // Load grids for current level
        if(file.lock.readLock().tryLock()) {
            try {
                // Load current level grids
                if(currentLevelCache != null && currentLevelCache.display != null) {
                    for(Coord c : dgext) {
                        if(currentLevelCache.display[dgext.ri(c)] == null) {
                            currentLevelCache.display[dgext.ri(c)] = new DisplayGrid(this, dloc.seg, c, dataLevel, dloc.seg.grid(dataLevel, c.mul(1 << dataLevel)));
                        }
                    }
                    display = currentLevelCache.display;
                }
                
                // Preload next level (more zoomed out) in background
                int nextLevel = dataLevel + 1;
                if(nextLevel <= 5 && (nextLevelCache == null || nextLevelCache.dataLevel != nextLevel)) {
                    int nextGridTileSize = cmaps.x * (1 << nextLevel);
                    Coord nextCenterGrid = loc.tc.div(nextGridTileSize);
                    int nextTilesOnScreenX = (int)Math.ceil(UI.unscale(sz.x) / (currentScale / (1 << nextLevel))) + nextGridTileSize * 2;
                    int nextTilesOnScreenY = (int)Math.ceil(UI.unscale(sz.y) / (currentScale / (1 << nextLevel))) + nextGridTileSize * 2;
                    Coord nextGridsNeeded = new Coord(
                        (int)Math.ceil((float)nextTilesOnScreenX / nextGridTileSize) + 2,
                        (int)Math.ceil((float)nextTilesOnScreenY / nextGridTileSize) + 2
                    );
                    Area nextArea = Area.sized(nextCenterGrid.sub(nextGridsNeeded.div(2)), nextGridsNeeded);
                    
                    DisplayGrid[] nextDisplay = new DisplayGrid[nextArea.rsz()];
                    // Load a few grids to start preloading
                    int loaded = 0;
                    for(Coord c : nextArea) {
                        if(loaded++ > 4) break; // Don't load too many at once to avoid lag
                        nextDisplay[nextArea.ri(c)] = new DisplayGrid(this, dloc.seg, c, nextLevel, dloc.seg.grid(nextLevel, c.mul(1 << nextLevel)));
                    }
                    nextLevelCache = new LevelCache(nextDisplay, nextArea, nextLevel);
                }
                
                // Preload previous level (more zoomed in) in background
                int prevLevel = dataLevel - 1;
                if(prevLevel >= 0 && (previousLevelCache == null || previousLevelCache.dataLevel != prevLevel)) {
                    int prevGridTileSize = cmaps.x * (1 << prevLevel);
                    Coord prevCenterGrid = loc.tc.div(prevGridTileSize);
                    int prevTilesOnScreenX = (int)Math.ceil(UI.unscale(sz.x) / (currentScale / (1 << prevLevel))) + prevGridTileSize * 2;
                    int prevTilesOnScreenY = (int)Math.ceil(UI.unscale(sz.y) / (currentScale / (1 << prevLevel))) + prevGridTileSize * 2;
                    Coord prevGridsNeeded = new Coord(
                        (int)Math.ceil((float)prevTilesOnScreenX / prevGridTileSize) + 2,
                        (int)Math.ceil((float)prevTilesOnScreenY / prevGridTileSize) + 2
                    );
                    Area prevArea = Area.sized(prevCenterGrid.sub(prevGridsNeeded.div(2)), prevGridsNeeded);
                    
                    DisplayGrid[] prevDisplay = new DisplayGrid[prevArea.rsz()];
                    // Load a few grids to start preloading
                    int loaded = 0;
                    for(Coord c : prevArea) {
                        if(loaded++ > 4) break; // Don't load too many at once
                        prevDisplay[prevArea.ri(c)] = new DisplayGrid(this, dloc.seg, c, prevLevel, dloc.seg.grid(prevLevel, c.mul(1 << prevLevel)));
                    }
                    previousLevelCache = new LevelCache(prevDisplay, prevArea, prevLevel);
                }
            } finally {
                file.lock.readLock().unlock();
            }
        }
        for(DisplayIcon icon : icons)
            icon.dispupdate();
    }

    private void drawtempmarks(GOut g) {
        if((Boolean)NConfig.get(NConfig.Key.tempmark)) {
            Gob player = NUtils.player();
            if (player != null && sessloc != null && dloc != null) {
                // Calculate visible area boundaries (same as explored area calculation)
                Coord ul = player.rc.floor(sgridsz).sub(4, 4).mul(sgridsz).floor(tilesz).add(sessloc.tc);
                Coord unscaledViewSize = _sgridsz.mul(9).div(tilesz.floor());
                Coord br = ul.add(unscaledViewSize).add(1, 1);

                synchronized (((NMapView)ui.gui.map).tempMarkList)
                {
                for (TempMark cm : ((NMapView)ui.gui.map).tempMarkList) {
                    if (cm.loc!=null && ui.gui.mmap.curloc.seg.id == cm.loc.seg.id) {
                        if (cm.icon != null && !cm.gc.equals(Coord.z)) {
                            // Check if mark is outside the 81-tile visible area
                            boolean isOutsideVisibleArea = 
                                cm.gc.x < ul.x || cm.gc.x >= br.x ||
                                cm.gc.y < ul.y || cm.gc.y >= br.y;
                            
                            // Draw icon if:
                            // 1. Mark is outside visible area, OR
                            // 2. Object no longer exists in game (disappeared)
                            // This ensures we show the mark for objects that left the zone
                            Gob gob = nurgling.tools.Finder.findGob(cm.id);
                            boolean objectDisappeared = (gob == null);
                            
                            if (isOutsideVisibleArea || objectDisappeared) {
                                Coord gc = p2c(cm.gc.sub(sessloc.tc).mul(tilesz));
                                int dsz = Math.max(cm.icon.sz().y, cm.icon.sz().x);
                                
                                // Apply buddy color if available
                                if(cm.buddyColor != null) {
                                    g.chcolor(cm.buddyColor.getRed(), cm.buddyColor.getGreen(), cm.buddyColor.getBlue(), 255);
                                }
                                g.aimage(cm.icon, gc, 0.5, 0.5, UI.scale(18 * cm.icon.sz().x / dsz, 18 * cm.icon.sz().y / dsz));
                                g.chcolor();
                            }
                        }
                    }
                }
                }
            }
        }
    }

    /**
     * Draw labeled marks on the minimap (from Checker bots like CheckWater, CheckClay).
     * Shows an icon with a quality label underneath (e.g., "q20").
     * Data is loaded from LabeledMarkService for persistence between sessions.
     */
    private void drawLabeledMarks(GOut g) {
        if(sessloc == null || dloc == null) return;
        
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.labeledMarkService == null) return;
        
        /* Looked up once per frame rather than per mark; NConfig.get is not free. */
        nurgling.conf.ProspectMarkSettings settings = prospectSettings();
        if(settings != null && !settings.master)
            return;

        java.util.List<LabeledMinimapMark> marks = gui.labeledMarkService.getMarksForSegment(dloc.seg.id);
        if(marks.isEmpty())
            return;

        Coord hsz = sz.div(2);
        float scale = scalef();

        for(LabeledMinimapMark mark : marks) {
            if(settings != null && !settings.shows(mark.kind, mark.quality))
                continue;

            /* Screen position, computed without allocating: a well-explored world holds
             * thousands of samples and only a few are ever on screen. */
            int px = (int)Math.round((mark.tileCoords.x - dloc.tc.x) / (double)scale) + hsz.x;
            int py = (int)Math.round((mark.tileCoords.y - dloc.tc.y) / (double)scale) + hsz.y;
            if(px < 0 || px > sz.x || py < 0 || py > sz.y)
                continue;

            Coord screenPos = new Coord(px, py);

            // Draw icon if available
            TexI iconTex = mark.getIconTex();
            if(iconTex != null) {
                int dsz = Math.max(iconTex.sz().y, iconTex.sz().x);
                int targetSize = UI.scale(18);
                g.aimage(iconTex, screenPos, 0.5, 0.5,
                    UI.scale(targetSize * iconTex.sz().x / dsz, targetSize * iconTex.sz().y / dsz));
            }

            // Draw label under the icon (like quest giver names)
            Text labelText = mark.getLabelText();
            if(labelText != null) {
                Coord textPos = screenPos.add(0, UI.scale(10));
                g.aimage(labelText.tex(), textPos, 0.5, 0);
            }
        }
    }

    private void drawterrainname(GOut g) {
        if((Boolean)NConfig.get(NConfig.Key.showTerrainName) && currentTerrainName != null && !currentTerrainName.isEmpty()) {
            Text.Foundry fnd = new Text.Foundry(Text.dfont, 10);
            Text terrainText = fnd.render(currentTerrainName, Color.WHITE);
            Coord textPos = new Coord((sz.x - terrainText.sz().x) / 2, 5);
            g.chcolor(0, 0, 0, 180);
            g.frect(textPos.sub(2, 1), terrainText.sz().add(4, 2));
            g.chcolor();
            g.image(terrainText.tex(), textPos);
        }
    }



    @Override
    public void mousemove(MouseMoveEvent ev) {
        if(wpGrab != null) {
            // Dragging a queued waypoint - don't pan the map along with it.
            dragWaypointTo(ev.c, false);
            return;
        }
        if(holdGrab != null) {
            holdMove.pointer(ev.c);
            steerHold(ev.c);
        }
        wpHoverId = waypointAt(ev.c);
        super.mousemove(ev);
        // Base class drag uses private d2lscale which doesn't match our zoom - recompute with scalef()
        if(drag != null && dragging) {
            curloc = new Location(curloc.seg, dmc.add(dsc.sub(ev.c).mul(scalef())));
        }
        if((Boolean)NConfig.get(NConfig.Key.showTerrainName)) {
            updateCurrentTerrainName(ev.c);
        }
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if(ev.a > 0) {
            // Zoom out - multiply by 0.95 (5% decrease per step)
            targetScale *= 0.95f;
            // Limit minimum scale
            if(targetScale < 0.03125f) // 1/32 zoom out
                targetScale = 0.03125f;
        } else {
            // Zoom in - multiply by 1.0526 (inverse of 0.95, ~5.3% increase)
            targetScale *= 1.0526f;
            // Limit maximum scale to 4x
            if(targetScale > 4.0f)
                targetScale = 4.0f;
        }
        
        // Update zoomlevel for compatibility with base class
        // Must be small (0-5) since base class uses it in bit shifts: 1 << zoomlevel
        zoomlevel = Utils.clip((int)(Math.log(1.0 / targetScale) / Math.log(2)), 0, 5);
        
        return(true);
    }

    protected boolean allowzoomout() {
        // Allow zoom out as long as scale is above minimum
        return currentScale > 0.03125f;
    }

    @Override
    public float scalef() {
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        return(UI.unscale((float)(1 << dataLevel) / scaleFactor));
    }

    @Override
    public Coord xlate(Location loc) {
        Location dloc = this.dloc;
        if((dloc == null) || (dloc.seg != loc.seg))
            return(null);
        return(loc.tc.sub(dloc.tc).div(scalef()).add(sz.div(2)));
    }

    @Override
    public Location xlate(Coord sc) {
        Location dloc = this.dloc;
        if(dloc == null)
            return(null);
        Coord tc = sc.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
        return(new Location(dloc.seg, tc));
    }

    @Override
    public Coord st2c(Coord tc) {
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        
        Coord base = tc.add(sessloc.tc).sub(dloc.tc).div(1 << dataLevel);
        return(UI.scale(base).mul(scaleFactor).add(sz.div(2)));
    }

    @Override
    public Coord c2st(Coord c) {
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        
        Coord unscaled = UI.unscale(c.sub(sz.div(2)).div(scaleFactor));
        return unscaled.mul(1 << dataLevel).add(dloc.tc).sub(sessloc.tc);
    }

    @Override
    public void drawmap(GOut g) {
        Coord hsz = sz.div(2);
        int dataLevel = getDataLevel();
        float scaleFactor = getScaleFactor();
        
        // Draw cached previous level if transitioning (to avoid black screen)
        boolean shouldDrawPrevious = false;
        if(previousLevelCache != null && previousLevelCache.display != null && previousLevelCache.dgext != null) {
            // Check if current level is fully loaded with textures
            int loadedGrids = 0;
            int totalGrids = 0;
            
            if(currentLevelCache != null && currentLevelCache.display != null && currentLevelCache.dgext != null) {
                for(Coord c : currentLevelCache.dgext) {
                    totalGrids++;
                    DisplayGrid disp = currentLevelCache.display[currentLevelCache.dgext.ri(c)];
                    if(disp != null) {
                        // Just check if grid object exists, don't force texture load
                        loadedGrids++;
                    }
                }
            }
            
            // Draw previous level if current level is less than 80% loaded
            // More conservative threshold to keep old level visible longer
            shouldDrawPrevious = (totalGrids == 0 || loadedGrids < totalGrids * 0.8f);
            
            if(shouldDrawPrevious) {
                // Draw previous level with adjusted scale
                float prevScaleFactor = currentScale * (1 << previousLevelCache.dataLevel);
                
                for(Coord c : previousLevelCache.dgext) {
                    DisplayGrid disp = previousLevelCache.display[previousLevelCache.dgext.ri(c)];
                    if(disp == null) continue;
                    
                    // Calculate position with exact tile boundaries to avoid gaps
                    Coord2d ulDouble = new Coord2d(UI.scale(c.mul(cmaps))).mul(prevScaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                    Coord2d brDouble = new Coord2d(UI.scale(c.add(1, 1).mul(cmaps))).mul(prevScaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                    
                    // Floor upper-left, ceil bottom-right to ensure tiles overlap slightly rather than gap
                    Coord ul = new Coord((int)Math.floor(ulDouble.x), (int)Math.floor(ulDouble.y));
                    Coord br = new Coord((int)Math.ceil(brDouble.x), (int)Math.ceil(brDouble.y));
                    Coord size = br.sub(ul);
                    
                    drawgrid(g, ul, disp, size);
                }
            }
            // Note: We keep previousLevelCache around for quick access when zooming back
        }
        
        // Draw current level
        if(display != null && dgext != null) {
            for(Coord c : dgext) {
                DisplayGrid disp = display[dgext.ri(c)];
                if(disp == null)
                    continue;
                    
                // Calculate position with exact tile boundaries to avoid gaps
                // Calculate the exact position of this grid corner and the next grid corner
                Coord2d ulDouble = new Coord2d(UI.scale(c.mul(cmaps))).mul(scaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                Coord2d brDouble = new Coord2d(UI.scale(c.add(1, 1).mul(cmaps))).mul(scaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                
                // Floor upper-left, ceil bottom-right to ensure tiles overlap slightly rather than gap
                Coord ul = new Coord((int)Math.floor(ulDouble.x), (int)Math.floor(ulDouble.y));
                Coord br = new Coord((int)Math.ceil(brDouble.x), (int)Math.ceil(brDouble.y));
                Coord size = br.sub(ul);
                
                drawgrid(g, ul, disp, size);
            }
        }
    }

    public void drawgrid(GOut g, Coord ul, DisplayGrid disp, Coord size) {
        try {
            Tex img = disp.img();
            if(img != null) {
                // Use the explicitly calculated size to avoid gaps
                g.image(img, ul, size);
            }
        } catch(Loading l) {
        }
        // Call overlay hook with size for correct scaling
        drawgridOverlays(g, ul, disp, size);
    }
    
    /**
     * Hook method for subclasses to add overlay rendering.
     * Called after base grid image is drawn.
     * @param g Graphics context
     * @param ul Upper-left screen coordinate
     * @param disp Display grid data
     * @param size Calculated size for rendering (matches grid scaling)
     */
    protected void drawgridOverlays(GOut g, Coord ul, DisplayGrid disp, Coord size) {
        // Default: no overlays. Subclasses (like MapWnd.View) can override.
    }
    
    // Compatibility method for old code paths
    public void drawgrid(GOut g, Coord ul, DisplayGrid disp) {
        float scaleFactor = getScaleFactor();
        Coord imgsz = null;
        try {
            Tex img = disp.img();
            if(img != null) {
                // Use double precision and round to avoid gaps between tiles
                Coord2d imgsizDouble = new Coord2d(UI.scale(img.sz())).mul(scaleFactor);
                imgsz = new Coord((int)Math.round(imgsizDouble.x), (int)Math.round(imgsizDouble.y));
                g.image(img, ul, imgsz);
            }
        } catch(Loading l) {
        }
        // Call overlay hook with calculated size
        if(imgsz != null) {
            drawgridOverlays(g, ul, disp, imgsz);
        }
    }

    @Override
    public void drawmarkers(GOut g) {
        Coord hsz = sz.div(2);

        // Get marker search pattern from NMapWnd if we're inside one
        String markerSearchPattern = null;
        Widget parentWidget = this.parent;
        while(parentWidget != null) {
            if(parentWidget instanceof NMapWnd) {
                markerSearchPattern = ((NMapWnd) parentWidget).markerSearchPattern;
                break;
            }
            parentWidget = parentWidget.parent;
        }

        for(Coord c : dgext) {
            DisplayGrid dgrid = display[dgext.ri(c)];
            if(dgrid == null)
                continue;
            for(DisplayMarker mark : dgrid.markers(true)) {
                // First check the normal filter (marker config, etc.)
                if(filter(mark))
                    continue;

                // Then check marker search pattern filter
                if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                    String markerName = mark.m.nm;
                    if(markerName == null) {
                        continue; // Hide markers with no name when searching
                    }
                    // Show only markers that contain the search pattern (case-insensitive)
                    if(!markerName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                        continue; // Hide markers that don't match
                    }
                }

                Coord markPos = mark.m.tc.sub(dloc.tc).div(scalef()).add(hsz);
                // This custom drawmarkers draws at markPos instead of mark.sc, but
                // MiniMap.mousehover() still hit-tests hover against mark.sc (and skips
                // markers whose sc is null). Keep sc in sync with where we actually draw
                // so marker hover -- e.g. thingwall province lines -- works.
                mark.sc = markPos;
                mark.draw(g, markPos);

                // Draw name for quest giver markers (bush/bumling)
                if(mark.m instanceof MapFile.SMarker) {
                    MapFile.SMarker sm = (MapFile.SMarker)mark.m;
                    if((Boolean)NConfig.get(NConfig.Key.showQuestGiverNames) && NParser.checkName(sm.res.name, "small/bush", "small/bumling", "gianttoad") && mark.m.nm != null && !mark.m.nm.isEmpty()) {
                        Text nameText = NStyle.meter.render(mark.m.nm);
                        Coord textPos = markPos.add(0, UI.scale(10));
                        g.aimage(nameText.tex(), textPos, 0.5, 0);
                    }

                    if((Boolean)NConfig.get(NConfig.Key.showThingwallNames) && NParser.checkName(sm.res.name, "thingwall") && mark.m.nm != null && !mark.m.nm.isEmpty()) {
                        Text nameText = NStyle.cmeter.render(mark.m.nm);
                        Coord textPos = markPos.add(0, UI.scale(10));
                        g.aimage(nameText.tex(), textPos, 0.5, 0);
                    }
                }
            }
        }
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        if(dloc != null && sessloc != null) {
            Coord hsz = sz.div(2);

            // Check for tree location tooltip first (check in screen space)
            NGameUI gui = NUtils.getGameUI();
            if(gui != null && gui.treeLocationService != null && showTreeIcons()) {
                // Check if markers are hidden (respect "Hide Markers" button)
                MapWnd mapwnd = gui.mapfile;
                boolean markersHidden = (mapwnd != null && Utils.eq(mapwnd.markcfg, MapWnd.MarkerConfig.hideall));

                if(!markersHidden) {
                    // Get marker search pattern (if any) for filtering
                    String markerSearchPattern = null;
                    Widget parentWidget = this.parent;
                    while(parentWidget != null) {
                        if(parentWidget instanceof NMapWnd) {
                            markerSearchPattern = ((NMapWnd) parentWidget).markerSearchPattern;
                            break;
                        }
                        parentWidget = parentWidget.parent;
                    }

                    java.util.List<nurgling.TreeLocation> treeLocations = gui.treeLocationService.getTreeLocationsForSegment(sessloc.seg.id);
                    int threshold = UI.scale(10); // Screen pixels

                    for(nurgling.TreeLocation loc : treeLocations) {
                        // Apply marker search pattern filter
                        if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                            String treeName = loc.getTreeName();
                            if(treeName == null || !treeName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                                continue; // Skip trees that don't match search
                            }
                        }

                        Coord screenPos = loc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

                        if(c.dist(screenPos) < threshold) {
                            return Text.render(loc.getTreeName());
                        }
                    }
                }
            }

            // Check for fish location tooltip (check in screen space)
            if(gui != null && gui.fishLocationService != null && showFishIcons()) {
                // Check if markers are hidden (respect "Hide Markers" button)
                MapWnd mapwnd = gui.mapfile;
                boolean markersHidden = (mapwnd != null && Utils.eq(mapwnd.markcfg, MapWnd.MarkerConfig.hideall));

                if(!markersHidden) {
                    // Get marker search pattern from NMapWnd if we're inside one
                    String markerSearchPattern = null;
                    Widget parentWidget = this.parent;
                    while(parentWidget != null) {
                        if(parentWidget instanceof NMapWnd) {
                            markerSearchPattern = ((NMapWnd) parentWidget).markerSearchPattern;
                            break;
                        }
                        parentWidget = parentWidget.parent;
                    }

                    java.util.List<nurgling.FishLocation> locations = gui.fishLocationService.getFishLocationsForSegment(sessloc.seg.id);
                    int threshold = UI.scale(10); // Screen pixels

                    for(nurgling.FishLocation loc : locations) {
                        // Apply marker search pattern filter
                        if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                            String fishName = loc.getFishName();
                            if(fishName == null) {
                                continue; // Skip fish with no name when searching
                            }
                            // Show only fish that contain the marker search pattern (case-insensitive)
                            if(!fishName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                                continue; // Skip fish that don't match
                            }
                        }

                        // Convert segment-relative coordinates to screen coordinates (same as drawing)
                        Coord screenPos = loc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

                        if(c.dist(screenPos) < threshold) {
                            // Simple tooltip with just the fish name
                            return Text.render(loc.getFishName());
                        }
                    }
                }
            }

            Coord tc = c.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
            DisplayMarker mark = markerat(tc);
            if(mark != null) {
                try {
                    return(new TexI(mark.tooltip()));
                } catch(Loading l) {}
            }

            // Get terrain type tooltip
            String terrainInfo = getTerrainTooltip(c);
            if(terrainInfo != null) {
                return(Text.render(terrainInfo));
            }
        }
        return(super.tooltip(c, prev));
    }
    
    private String getTerrainTooltip(Coord c) {
        // Only show terrain tooltip when Shift is pressed
        if(ui == null || !ui.modshift) {
            return null;
        }
        return getTerrainNameAtCoord(c);
    }
    
    private void updateCurrentTerrainName(Coord c) {
        String terrainName = getTerrainNameAtCoord(c);
        if(terrainName != null && !terrainName.equals(currentTerrainName)) {
            currentTerrainName = terrainName;
        } else if(terrainName == null) {
            currentTerrainName = null;
        }
    }
    
    private String getTerrainNameAtCoord(Coord c) {
        if(dloc == null || display == null || dgext == null) {
            return null;
        }
        
        try {
            // Convert screen coordinates to tile coordinates  
            Coord tc = c.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
            
            // Find which DisplayGrid contains this coordinate
            Coord zmaps = cmaps.mul(1 << dlvl);
            Coord gridCoord = tc.div(zmaps);
            
            // Check if this grid coordinate is in our display extent
            if(!dgext.contains(gridCoord)) {
                return null;
            }
            
            // Get the DisplayGrid
            DisplayGrid dgrid = display[dgext.ri(gridCoord)];
            if(dgrid == null) {
                return null;
            }
            
            // Get the DataGrid from the DisplayGrid
            MapFile.DataGrid grid = dgrid.gref.get();
            if(grid == null) {
                return null;
            }
            
            // Calculate coordinates within the grid (0-99 range)
            Coord localTC = tc.sub(gridCoord.mul(zmaps));
            Coord tileCoord = localTC.div(1 << dlvl);
            
            // Ensure coordinates are within grid bounds
            if(tileCoord.x < 0 || tileCoord.x >= cmaps.x || tileCoord.y < 0 || tileCoord.y >= cmaps.y) {
                return null;
            }
            
            // Get the tile type ID
            int tileId = grid.gettile(tileCoord);
            if(tileId < 0 || tileId >= grid.tilesets.length) {
                return null;
            }
            
            // Get the TileInfo for this tile
            MapFile.TileInfo tileInfo = grid.tilesets[tileId];
            if(tileInfo == null || tileInfo.res == null) {
                return null;
            }
            
            // Format the terrain name for display
            String resName = tileInfo.res.name;
            String terrainName = formatTerrainName(resName);
            
            return terrainName;
            
        } catch(Exception e) {
            // Silently handle any exceptions
            return null;
        }
    }

    private String formatTerrainName(String resName) {
        if(resName == null) {
            return "Unknown";
        }
        
        // Remove "gfx/tiles/" prefix if present
        String name = resName;
        if(name.startsWith("gfx/tiles/")) {
            name = name.substring("gfx/tiles/".length());
        }

        // Capitalize first letter and replace underscores with spaces
        name = name.replace("_", " ");
        if(name.length() > 0) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    private void drawResourceTimers(GOut g) {
        if(dloc == null) return;

        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.localizedResourceTimerService == null) return;

        java.util.List<LocalizedResourceTimer> timers = gui.localizedResourceTimerService.getTimersForSegment(dloc.seg.id);

        Coord hsz = sz.div(2);

        // Create bordered text furnaces for timer display (like barrel names and character nicknames)
        Text.Furnace readyTimerFurnace = new PUtils.BlurFurn(
            new Text.Foundry(Text.dfont, UI.scale(9), Color.GREEN).aa(true),
            2, 1, Color.BLACK
        );
        Text.Furnace activeTimerFurnace = new PUtils.BlurFurn(
            new Text.Foundry(Text.dfont, UI.scale(9), Color.WHITE).aa(true),
            2, 1, Color.BLACK
        );

        for(LocalizedResourceTimer timer : timers) {
            // Calculate screen position for the timer
            Coord screenPos = timer.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

            // Only draw if on screen
            if(screenPos.x >= 0 && screenPos.x <= sz.x &&
               screenPos.y >= 0 && screenPos.y <= sz.y) {

                String timeText = timer.getFormattedRemainingTime();

                // Use appropriate furnace based on timer state
                Text.Furnace furnace = timer.isExpired() ? readyTimerFurnace : activeTimerFurnace;
                Text timerDisplay = furnace.render(timeText);

                // Position text slightly below the resource icon
                Coord textPos = screenPos.add(-timerDisplay.sz().x / 2, 15);

                // Draw timer text with black border (no background needed)
                g.image(timerDisplay.tex(), textPos);
            }
        }
    }

    private void drawFishLocations(GOut g) {
        if(sessloc == null || dloc == null) return;

        // Check if fish icons are hidden by checkbox
        if(!showFishIcons()) return;

        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.fishLocationService == null) return;

        // Check if markers are hidden (respect "Hide Markers" button)
        MapWnd mapwnd = gui.mapfile;
        if(mapwnd != null && Utils.eq(mapwnd.markcfg, MapWnd.MarkerConfig.hideall)) {
            return; // Don't draw fish locations when markers are hidden
        }

        // Get marker search pattern from NMapWnd if we're inside one
        String markerSearchPattern = null;
        Widget parentWidget = this.parent;
        while(parentWidget != null) {
            if(parentWidget instanceof NMapWnd) {
                markerSearchPattern = ((NMapWnd) parentWidget).markerSearchPattern;
                break;
            }
            parentWidget = parentWidget.parent;
        }

        // Use sessloc.seg.id like waypoints and markers do
        java.util.List<nurgling.FishLocation> fishLocations = gui.fishLocationService.getFishLocationsForSegment(sessloc.seg.id);

        Coord hsz = sz.div(2);

        for(nurgling.FishLocation fishLoc : fishLocations) {
            // Apply marker search pattern filter to fish names
            if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                String fishName = fishLoc.getFishName();
                if(fishName == null) {
                    continue; // Hide fish with no name when searching
                }
                // Show only fish that contain the marker search pattern (case-insensitive)
                if(!fishName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                    continue; // Hide fish that don't match
                }
            }

            // Convert segment-relative coordinates to screen coordinates
            // Same approach as markers: mark.m.tc.sub(dloc.tc).div(scalef()).add(hsz)
            Coord screenPos = fishLoc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

            // Only draw if on screen
            if(screenPos.x >= 0 && screenPos.x <= sz.x &&
               screenPos.y >= 0 && screenPos.y <= sz.y) {

                try {
                    String fishResource = fishLoc.getFishResource();
                    TexI tex = fishIconCache.get(fishResource);

                    // Load and cache if not already cached
                    if(tex == null) {
                        Resource fishRes = Resource.remote().loadwait(fishResource);
                        BufferedImage icon = fishRes.layer(Resource.imgc).img;
                        tex = new TexI(icon);
                        fishIconCache.put(fishResource, tex);
                    }

                    // Draw scaled fish icon
                    int dsz = Math.max(tex.sz().y, tex.sz().x);
                    int targetSize = UI.scale(18);
                    g.aimage(tex, screenPos, 0.5, 0.5, UI.scale(targetSize * tex.sz().x / dsz, targetSize * tex.sz().y / dsz));

                } catch (Exception e) {
                    // Fallback: draw colored dot if icon fails
                    g.chcolor(0, 150, 255, 200); // Blue for fish
                    g.fellipse(screenPos, new Coord(UI.scale(4), UI.scale(4)));
                    g.chcolor();
                }
            }
        }
    }

    private void drawTreeLocations(GOut g) {
        if(sessloc == null || dloc == null) return;

        // Check if tree icons are hidden by checkbox
        if(!showTreeIcons()) return;

        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.treeLocationService == null) return;

        // Check if markers are hidden (respect "Hide Markers" button)
        MapWnd mapwnd = gui.mapfile;
        if(mapwnd != null && Utils.eq(mapwnd.markcfg, MapWnd.MarkerConfig.hideall)) {
            return; // Don't draw tree locations when markers are hidden
        }

        // Get marker search pattern from NMapWnd if we're inside one
        String markerSearchPattern = null;
        Widget parentWidget = this.parent;
        while(parentWidget != null) {
            if(parentWidget instanceof NMapWnd) {
                markerSearchPattern = ((NMapWnd) parentWidget).markerSearchPattern;
                break;
            }
            parentWidget = parentWidget.parent;
        }

        // Use sessloc.seg.id like waypoints and markers do
        java.util.List<nurgling.TreeLocation> treeLocations = gui.treeLocationService.getTreeLocationsForSegment(sessloc.seg.id);

        Coord hsz = sz.div(2);

        for(nurgling.TreeLocation treeLoc : treeLocations) {
            // Apply marker search pattern filter to tree names
            if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                String treeName = treeLoc.getTreeName();
                if(treeName == null) {
                    continue; // Hide trees with no name when searching
                }
                // Show only trees that contain the marker search pattern (case-insensitive)
                if(!treeName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                    continue; // Hide trees that don't match
                }
            }

            // Convert segment-relative coordinates to screen coordinates
            Coord screenPos = treeLoc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

            // Only draw if on screen
            if(screenPos.x >= 0 && screenPos.x <= sz.x &&
               screenPos.y >= 0 && screenPos.y <= sz.y) {

                try {
                    String treeResource = treeLoc.getTreeResource();

                    // Convert tree/bush resource path to minimap icon path
                    // "gfx/terobjs/trees/oak" -> "gfx/terobjs/mm/trees/oak"
                    // "gfx/terobjs/bushes/arrowwood" -> "gfx/terobjs/mm/bushes/arrowwood"
                    String mmResource = treeResource
                        .replace("gfx/terobjs/trees/", "gfx/terobjs/mm/trees/")
                        .replace("gfx/terobjs/bushes/", "gfx/terobjs/mm/bushes/");

                    TexI tex = treeIconCache.get(mmResource);

                    // Load and cache if not already cached
                    if(tex == null) {
                        Resource treeRes = Resource.remote().loadwait(mmResource);
                        BufferedImage icon = treeRes.layer(Resource.imgc).img;
                        tex = new TexI(icon);
                        treeIconCache.put(mmResource, tex);
                    }

                    // Draw scaled tree icon (same size as fish icons)
                    int dsz = Math.max(tex.sz().y, tex.sz().x);
                    int targetSize = UI.scale(18);
                    g.aimage(tex, screenPos, 0.5, 0.5, UI.scale(targetSize * tex.sz().x / dsz, targetSize * tex.sz().y / dsz));

                } catch (Exception e) {
                    // Fallback: draw green circle if icon fails
                    g.chcolor(34, 139, 34, 255);
                    g.fellipse(screenPos, new Coord(UI.scale(4), UI.scale(4)));
                    g.chcolor();
                }
            }
        }
    }

    private nurgling.FishLocation fishLocationAt(Coord tc) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.fishLocationService == null || dloc == null) return null;

        java.util.List<nurgling.FishLocation> locations = gui.fishLocationService.getFishLocationsForSegment(dloc.seg.id);
        int threshold = UI.scale(10); // Click radius

        for(nurgling.FishLocation loc : locations) {
            if(loc.getTileCoords().dist(tc) < threshold) {
                return loc;
            }
        }
        return null;
    }
    
    /**
     * Find a labeled minimap mark at the given screen coordinate.
     * Used for right-click deletion of water/soil quality marks.
     */
    private LabeledMinimapMark labeledMarkAt(Coord screenCoord) {
        if(dloc == null || sessloc == null) return null;
        
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.labeledMarkService == null) return null;
        
        java.util.List<LabeledMinimapMark> marks = gui.labeledMarkService.getMarksForSegment(dloc.seg.id);
        
        Coord hsz = sz.div(2);
        int threshold = UI.scale(12); // Click radius
        nurgling.conf.ProspectMarkSettings settings = prospectSettings();

        for(LabeledMinimapMark mark : marks) {
            /* A filtered-out mark is not drawn, so it must not be clickable either -
             * otherwise it keeps an invisible hitbox that swallows right-clicks. */
            if(settings != null && !settings.shows(mark.kind, mark.quality))
                continue;

            // Calculate screen position for this mark
            Coord markScreenPos = mark.tileCoords.sub(dloc.tc).div(scalef()).add(hsz);
            
            // Check if click is within threshold
            if(screenCoord.dist(markScreenPos) < threshold) {
                return mark;
            }
        }
        return null;
    }

    @Override
    public boolean filter(DisplayMarker mark) {
        // Check if we're inside an NMapWnd and if it has an active marker search pattern
        Widget parent = this.parent;
        while(parent != null) {
            if(parent instanceof NMapWnd) {
                NMapWnd mapWnd = (NMapWnd) parent;
                String markerSearchPattern = mapWnd.markerSearchPattern;

                // If marker search pattern is active, filter by marker name
                if(markerSearchPattern != null && !markerSearchPattern.trim().isEmpty()) {
                    String markerName = mark.m.nm;
                    if(markerName == null) {
                        return true; // Hide markers with no name when searching
                    }
                    // Show only markers that contain the search pattern (case-insensitive)
                    if(!markerName.toLowerCase().contains(markerSearchPattern.toLowerCase())) {
                        return true; // Hide markers that don't match
                    }
                }
                break;
            }
            parent = parent.parent;
        }

        // Default: don't filter (show the marker)
        return false;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        // Alt+Shift+LMB pings the clicked spot to the selected chat channel. Plain
        // alt+LMB is left for waypoint queueing - it means "walk here next" here, on the
        // map window and in the world alike (NMapWnd.mouseup, NMiniMapWnd.clickloc,
        // NMapView.addWaypointAt). Checked first so the ping never doubles as a walk or a
        // waypoint grab.
        if(ev.b == 1 && ui.modmeta && ui.modshift && !ui.modctrl) {
            if(sendPointPing(ev.c))
                return true;
        }

        // Pick up a queued waypoint under the cursor instead of panning/walking. Plain left
        // button only: alt+LMB is "queue a waypoint here" (NMiniMapWnd.clickloc, NMapWnd.mouseup)
        // and would otherwise be swallowed whenever the cursor sat near a node already queued.
        if(ev.b == 1 && !ui.modmeta && !ui.modshift && !ui.modctrl && startWaypointDrag(ev.c))
            return true;

        // Handle left-click for forager path recording - prevent player movement
        if(ev.b == 1 && !ui.modmeta && !ui.modshift && !ui.modctrl && dloc != null && sessloc != null) {
            NGameUI gui = NUtils.getGameUI();
            if(gui != null) {
                // Find a PathRecordable window (Forager or TrufflePigHunter)
                nurgling.widgets.bots.PathRecordable pathWnd = null;
                for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
                    if(wdg instanceof nurgling.widgets.bots.PathRecordable) {
                        pathWnd = (nurgling.widgets.bots.PathRecordable) wdg;
                        break;
                    }
                }

                // If recording, consume the event to prevent player movement
                if(pathWnd != null && pathWnd.isRecording()) {
                    return true; // Consume mousedown to prevent movement
                }
            }
        }
        
        // Check for right-click on fish location
        if(ev.b == 3 && dloc != null) { // Button 3 is right-clicked
            Coord tc = ev.c.sub(sz.div(2)).mul(scalef()).add(dloc.tc);
            nurgling.FishLocation fishLoc = fishLocationAt(tc);
            if(fishLoc != null) {
                // Handle right-click on fish - will be processed in mouseup
                return true;
            }
        }

        // Check for right-click on an undiscovered-LP marker. Our marker isn't a real
        // DisplayIcon, so without this check, base MiniMap.mousedown() falls through to its own
        // clickloc(..., press=true), which fires mvclick() with no gob immediately on press -
        // walking the player to the coarse clicked tile before our (correct, gob-precise)
        // mouseup handler ever runs. Consume here; actual handling happens in mouseup.
        if(ev.b == 3 && dloc != null && sessloc != null) {
            if(MinimapDiscoveryRenderer.gobAt(this, ev.c) != null) {
                return true;
            }
        }

        // Press-and-hold steering arms last, so every other meaning of the left button
        // keeps priority. The event is not consumed - the press still walks as before.
        if(ev.b == 1 && ui.modflags() == 0)
            startHoldSteer(ev.c);

        return super.mousedown(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if((holdGrab != null) && (ev.b == 1))
            endHoldSteer();
        if(wpGrab != null) {
            if(ev.b == 1) {
                dragWaypointTo(ev.c, true);
                endWaypointDrag();
            }
            return true;
        }

        // Handle left-click for forager path recording (without modifiers)
        if(ev.b == 1 && !ui.modmeta && !ui.modshift && !ui.modctrl && dloc != null && sessloc != null) {
            NGameUI gui = NUtils.getGameUI();
            if(gui != null) {
                // Find a PathRecordable window (Forager or TrufflePigHunter)
                nurgling.widgets.bots.PathRecordable pathWnd = null;
                for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
                    if(wdg instanceof nurgling.widgets.bots.PathRecordable) {
                        pathWnd = (nurgling.widgets.bots.PathRecordable) wdg;
                        break;
                    }
                }

                if(pathWnd != null && pathWnd.isRecording()) {
                    try {
                        // Get the MiniMap.Location at clicked position
                        MiniMap.Location clickLoc = xlate(ev.c);

                        if(clickLoc != null && sessloc != null && clickLoc.seg.id == sessloc.seg.id) {
                            // Create ForagerWaypoint from MiniMap.Location
                            nurgling.routes.ForagerWaypoint wp = new nurgling.routes.ForagerWaypoint(clickLoc);
                            pathWnd.addWaypointToRecording(wp);
                        }
                    } catch(Loading e) {
                        // Grid not loaded, ignore
                    }
                    return true; // Consume the event
                }
            }
        }
        
        // Handle right-click release on ANY marker - draw line to it
        if(ev.b == 3 && dloc != null && sessloc != null && display != null && dgext != null) {
            Coord hsz = sz.div(2);
            int threshold = UI.scale(10); // Same threshold as fish/tree

            // Loop through all markers and check if click is near one
            for(Coord c : dgext) {
                DisplayGrid dgrid = display[dgext.ri(c)];
                if(dgrid == null)
                    continue;

                for(DisplayMarker mark : dgrid.markers(true)) {
                    if(filter(mark))
                        continue;

                    // Calculate marker's screen position (same as drawmarkers)
                    Coord screenPos = mark.m.tc.sub(dloc.tc).div(scalef()).add(hsz);

                    // Check if click is within threshold
                    if(ev.c.dist(screenPos) < threshold) {
                        NGameUI gui = NUtils.getGameUI();
                        if(gui != null && gui.map instanceof NMapView) {
                            NMapView mapView = (NMapView) gui.map;

                            // Toggle selection based on coordinates (works for markers and pointers)
                            if(mapView.selectedMarkerTileCoords != null &&
                               mapView.selectedMarkerTileCoords.equals(mark.m.tc)) {
                                // Deselect - same location clicked again
                                mapView.setSelectedMarker(null, null);
                            } else {
                                // Select this marker
                                mapView.setSelectedMarker(mark, mark.m.tc);
                            }
                        }
                        return true;
                    }
                }
            }
        }

        // Handle right-click release on labeled mark (water/soil quality) - delete it
        if(ev.b == 3 && dloc != null && sessloc != null) {
            LabeledMinimapMark labeledMark = labeledMarkAt(ev.c);
            if(labeledMark != null) {
                NGameUI gui = NUtils.getGameUI();
                if(gui != null && gui.labeledMarkService != null) {
                    gui.labeledMarkService.removeMark(labeledMark);
                }
                return true;
            }
        }
        
        // Handle right-click release on tree location - open details window
        if(ev.b == 3 && dloc != null && sessloc != null && showTreeIcons()) { // Button 3 is right-clicked
            NGameUI gui = NUtils.getGameUI();
            if(gui != null && gui.treeLocationService != null) {
                // Check for tree location at click position (in screen space)
                java.util.List<nurgling.TreeLocation> treeLocations = gui.treeLocationService.getTreeLocationsForSegment(sessloc.seg.id);
                int threshold = UI.scale(10);
                Coord hsz = sz.div(2);

                for(nurgling.TreeLocation loc : treeLocations) {
                    Coord screenPos = loc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

                    if(ev.c.dist(screenPos) < threshold) {
                        // Check if a window is already open for this tree location
                        String locationId = loc.getLocationId();
                        TreeLocationDetailsWindow existingWnd = gui.openTreeDetailWindows.get(locationId);

                        if(existingWnd != null && existingWnd.visible()) {
                            // Window already exists and is visible, just raise it
                            existingWnd.raise();
                        } else {
                            // Create new window and track it
                            TreeLocationDetailsWindow detailsWnd = new TreeLocationDetailsWindow(loc, gui);
                            gui.add(detailsWnd, new Coord(100, 100));
                            gui.openTreeDetailWindows.put(locationId, detailsWnd);
                        }
                        return true;
                    }
                }
            }
        }

        // Handle right-click release on fish location - open details window
        if(ev.b == 3 && dloc != null && sessloc != null && showFishIcons()) { // Button 3 is right-clicked
            NGameUI gui = NUtils.getGameUI();
            if(gui != null && gui.fishLocationService != null) {
                // Check for fish location at click position (in screen space)
                java.util.List<nurgling.FishLocation> locations = gui.fishLocationService.getFishLocationsForSegment(sessloc.seg.id);
                int threshold = UI.scale(10);
                Coord hsz = sz.div(2);

                for(nurgling.FishLocation loc : locations) {
                    Coord screenPos = loc.getTileCoords().sub(dloc.tc).div(scalef()).add(hsz);

                    if(ev.c.dist(screenPos) < threshold) {
                        // Check if a window is already open for this fish location
                        String locationId = loc.getLocationId();
                        FishLocationDetailsWindow existingWnd = gui.openFishDetailWindows.get(locationId);

                        if(existingWnd != null && existingWnd.visible()) {
                            // Window already exists and is visible, just raise it
                            existingWnd.raise();
                        } else {
                            // Create new window and track it
                            FishLocationDetailsWindow detailsWnd = new FishLocationDetailsWindow(loc, gui);
                            gui.add(detailsWnd, new Coord(100, 100));
                            gui.openFishDetailWindows.put(locationId, detailsWnd);
                        }
                        return true;
                    }
                }
            }
        }
        // Handle right-click release on an undiscovered-LP marker - open the same flower
        // menu a real gob icon would. mvclick() derives its click destination from the clicked
        // MINIMAP TILE (coarse, tile-granularity) rather than the gob itself, which walked the
        // player near the tree but not precisely to it, and didn't reliably register as an
        // interact-click on arrival. Use the same fix NMapView already applies for the analogous
        // "clicked a small floating icon, redirect to the actual gob" case (its
        // findClickThroughIconGob() handling): send the gob's own exact position as the click
        // destination instead of the imprecise clicked location.
        if(ev.b == 3 && dloc != null && sessloc != null) {
            Gob gob = MinimapDiscoveryRenderer.gobAt(this, ev.c);
            if(gob != null) {
                NGameUI gui = NUtils.getGameUI();
                if(gui != null && gui.map != null) {
                    Coord pres = gob.rc.floor(OCache.posres);
                    // Register this as a real gob click the same way MapView.Click.hit() does for a
                    // 3D-world click. LpExplorer.recentHarvestClick() gates discovery recording on
                    // map.clickedGob being a freshly-clicked harvestable gob; the raw wdgmsg below
                    // goes straight to the server and never touches clickedGob, so without this the
                    // gate reads whatever stale gob the last 3D click left there - and the harvest
                    // gets recorded only if that happened to be harvestable and recent.
                    gui.map.clickedGob = new MapView.ClickedGob(gob, ev.b);
                    // ui.mc (current absolute mouse position) rather than Coord.z, so a resulting
                    // flower menu opens where the cursor actually is - matches what
                    // MiniMap.mvclick() itself falls back to when its own mc param is null.
                    gui.map.wdgmsg("click", ui.mc, pres, ev.b, ui.modflags(),
                        0, (int) gob.id, pres, 0, -1);
                    return true;
                }
            }
        }

        return super.mouseup(ev);
    }

    // Accessors for MinimapClaimRenderer to access protected MiniMap fields
    public DisplayGrid[] getDisplay() {
        return display;
    }

    public Area getDgext() {
        return dgext;
    }

    /**
     * Draw tile highlight overlay on top of map tiles
     */
    private void drawTileHighlightOverlay(GOut g) {
        // Draw tile highlight overlay if any tiles are highlighted
        if(!TileHighlight.getHighlighted().isEmpty() && display != null && dgext != null && dloc != null) {
            Coord hsz = sz.div(2);
            int dataLevel = getDataLevel();
            float scaleFactor = getScaleFactor();
            
            // Calculate dynamic alpha for pulsating effect
            int alpha = (int)(100 + 155 * Math.sin(Math.PI * ((System.currentTimeMillis() % 1000) / 1000.0)));
            
            for(Coord c : dgext) {
                DisplayGrid disp = display[dgext.ri(c)];
                if(disp == null)
                    continue;
                
                try {
                    Tex overlayImg = getTileHighlightOverlay(disp);
                    if(overlayImg != null) {
                        // Use round for consistent alignment without gaps or overlaps
                        Coord2d ulDouble = new Coord2d(UI.scale(c.mul(cmaps))).mul(scaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                        Coord2d brDouble = new Coord2d(UI.scale(c.add(1, 1).mul(cmaps))).mul(scaleFactor).sub(new Coord2d(dloc.tc.div(scalef()))).add(new Coord2d(hsz));
                        Coord ul = new Coord((int)Math.round(ulDouble.x), (int)Math.round(ulDouble.y));
                        Coord br = new Coord((int)Math.round(brDouble.x), (int)Math.round(brDouble.y));
                        Coord imgsz = br.sub(ul);
                        
                        g.chcolor(255, 255, 255, alpha);
                        g.image(overlayImg, ul, imgsz);
                        g.chcolor();
                    }
                } catch(Exception e) {
                    // Ignore overlay rendering errors
                }
            }
        }
    }

    /**
     * Cache for tile highlight overlays with version tracking
     */
    private static class TileHighlightCache {
        Tex img;
        long seq;
        MapFile.DataGrid grid;
    }
    
    private final java.util.Map<DisplayGrid, TileHighlightCache> tileHighlightCache = new java.util.HashMap<>();

    /**
     * Get tile highlight overlay for a display grid with caching
     */
    private Tex getTileHighlightOverlay(DisplayGrid disp) {
        TileHighlightCache cache = tileHighlightCache.get(disp);
        MapFile.DataGrid grid = (MapFile.DataGrid) disp.gref.get();
        
        // Check if cache is valid
        if(cache != null && cache.grid == grid && cache.seq == TileHighlight.seq) {
            return cache.img;
        }
        
        // Generate new overlay
        try {
            java.awt.image.BufferedImage overlayBuf = TileHighlight.olrender(grid);
            Tex overlayTex = new TexI(overlayBuf);
            
            // Update cache
            cache = new TileHighlightCache();
            cache.img = overlayTex;
            cache.seq = TileHighlight.seq;
            cache.grid = grid;
            tileHighlightCache.put(disp, cache);
            
            return overlayTex;
        } catch(Exception e) {
            return null;
        }
    }
}
