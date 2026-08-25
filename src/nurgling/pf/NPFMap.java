package nurgling.pf;

import haven.*;
import haven.Window;
import nurgling.*;
import nurgling.tasks.GateDetector;

import java.awt.*;
import java.util.*;


public class NPFMap
{
    public boolean waterMode = false;
    public boolean gatesAlwaysClosed = false;
    public Cell[][] cells;

    public boolean lastMul = false;
    // 1 hitbox
    // 0 have path
    // 2 unpathable tiles
    // 3 dilated: free, but too close to an obstacle for the agent's footprint
    // 4 pf been here
    // 7 approach point (marked blue)
    // 8 pf line
    // 9 pf turn

    /**
     * Half-width the planner assumes for whoever is walking, in world units.
     *
     * <p>Defaults to 3, which is what the rest of the pf stack has always implicitly assumed:
     * {@link nurgling.NHitBox} clamps every hitbox out to at least +/-3, and the corridor beam in
     * {@code Graph.getPath} is 3 wide. Towing raises it, which is what makes the search and the
     * smoother agree that a gap the character fits through is not necessarily one the cart does.
     */
    public double agentRadius = PLAYER_RADIUS;

    public static final double PLAYER_RADIUS = 3.0;

    /**
     * Radius to plan with while towing a cart. The cart's obstacle is a square of +/-5.892, and it
     * rotates about its own centre as it re-aims at the character, so the safe figure is the
     * circumscribed radius 5.892*sqrt(2). It does not swing around the character -- a towed cart
     * parks until pulled -- so this is the whole of the extra clearance, with no turn disc.
     */
    public static final double TOWED_CART_RADIUS = 8.33;

    /**
     * The vehicle this character is towing, or -1.
     *
     * <p>It is deliberately still rasterised as an obstacle: the server collides the character with
     * their own cart regardless of the tow, so a planner that cannot see it will happily draw a
     * straight line through it and the character grinds to a halt against it. That is not a rare
     * corner either — measured over towing legs, the cart sits ahead of or beside the direction of
     * travel about 15% of the time, and at the start of a leg it can be anywhere.
     *
     * <p>What the id is for is {@link #dilateForAgent}: the cart must not inflate <em>itself</em>.
     * Dilation exists so the cart clears other obstacles, and seeding it from the cart's own cells
     * would wrap the character in a five-cell block of forbidden ground.
     */
    public long towedId = -1;
    public Coord begin;
    Coord end;
    int dsize;
    public int size;
    long currentTransport = -1;
    public boolean bad = false;

    private boolean isGate(Gob gob) {
        if (gob.ngob == null || gob.ngob.name == null) return false;
        for (String gateName : GateDetector.GATE_NAMES) {
            if (gob.ngob.name.equals(gateName)) return true;
        }
        return false;
    }

    public CellsArray addGob(Gob gob) {
        CellsArray ca;

        if (gob.ngob != null && gob.ngob.hitBox != null && (ca = getCa(gob)) != null && NUtils.player() != null && gob.id != NUtils.player().id && gob.getattr(Following.class) == null) {
            CellsArray old = new CellsArray(ca.x_len, ca.y_len);
            old.begin = ca.begin;
            old.end = ca.end;
            if (ca.end.x >= begin.x && ca.begin.x <= end.x &&
                    ca.end.y >= begin.y && ca.begin.y <= end.y) {
                for (int i = 0; i < ca.x_len; i++)
                    for (int j = 0; j < ca.y_len; j++) {
                        int ii = i + ca.begin.x - begin.x;
                        int jj = j + ca.begin.y - begin.y;
                        if (ii > 0 && (ii + 1) < size && jj > 0 && (jj + 1) < size) {
                            old.cells[i][j] = cells[ii][jj].val;

                            if (ca.cells[i][j] != 0) {
                                if (cells[ii][jj].val != 1)
                                    cells[ii][jj].val = ca.cells[i][j];
                                cells[ii][jj].content.add(gob.id);
                            }
                        }
                    }
            }
            return old;
        }
        return null;
    }

    public void setCellArray(CellsArray ca) {
        if (ca.end.x >= begin.x && ca.begin.x <= end.x &&
                ca.end.y >= begin.y && ca.begin.y <= end.y) {
            for (int i = 0; i < ca.x_len; i++)
                for (int j = 0; j < ca.y_len; j++) {
                    int ii = i + ca.begin.x - begin.x;
                    int jj = j + ca.begin.y - begin.y;
                    if (ii > 0 && (ii + 1) < size && jj > 0 && (jj + 1) < size) {
                        cells[ii][jj].val = ca.cells[i][j];
                        //TODO placeable vs passable
                        cells[ii][jj].fullVal.force((ca.cells[i][j] == 0) ? CellType.Placeable : CellType.Blocked);
                    }
                }
        }
    }

    public enum  CellType {
        Land((byte) 0, (byte) -1, -1),
        Bog((byte) 1, (byte) -1, -1),
        Shallow((byte) 2, (byte) -1, -1),
        ShallowOcean((byte) 3, (byte) -1, -1),
        Deep((byte) 4, (byte) -1, -1),
        DeepOcean((byte) 5, (byte) -1, -1),
        OpenSea((byte) 6, (byte) -1, -1),

        Placeable((byte) -1, (byte) 0, -1),
        Passable((byte) -1, (byte) 1, -1),
        Blocked((byte) -1, (byte) 2, -1),
        Forbidden((byte) -1, (byte) 3, -1),

        PavedFloor((byte) -1, (byte) -1, 1),
        GrassFloor((byte) -1, (byte) -1, 0.8),
        ForestFloor((byte) -1, (byte) -1, 0.6),
        SubmergedWalk((byte) -1, (byte) -1, 0.2),

        Default((byte) 0, (byte) 0, 1);

        CellType(byte land, byte obstruct, double moveSpeed) {
            landType = land;
            obstructionState = obstruct;
            speedK = moveSpeed;
        }

        void cumulate(CellType other) {
            if (other.landType != -1)
                if (landType < other.landType)
                    landType = other.landType;
            if (other.obstructionState != -1)
                if (obstructionState < other.obstructionState)
                    obstructionState = other.obstructionState;
            if (other.speedK != -1)
                if (speedK > other.speedK)
                    speedK = other.speedK;
        }
        void force(CellType other) {
            if (other.landType != -1)
                landType = other.landType;
            if (other.obstructionState != -1)
                obstructionState = other.obstructionState;
            if (other.speedK != -1)
                speedK = other.speedK;
        }

        private byte landType;
        //0 walkable
        //1 bog
        //2 shallow
        //3 shallow ocean
        //4 deep
        //5 deep ocean
        //6 open sea

        private byte obstructionState;
        //0 passable & placeable
        //1 non-placeable
        //2 blocked by hit-box
        //3 blocked by immovable object

        private double speedK;
        public short pfColour = -1;
        //0 traversable
        //1

        public boolean isPfVisited() {
            return pfVisited;
        }

        public void visitedByPf() {
            this.pfVisited = true;
        }


        private boolean pfVisited = false;

        public boolean isPlace_able() {
            return ((landType == 0) && (obstructionState < 1));
        }

        public boolean isWalk_able() {
            return ((landType <= 3) && (obstructionState < 2));
        }

        public boolean isSwim_able() {
            return ((landType >= 1) && (landType <= 6) && (obstructionState < 2));
        }

        public boolean isSail_able() {
            return ((landType >= 2) && (landType <= 6) && (obstructionState < 2));
        }

        public boolean isBlockedByMajorObject() {
            return (obstructionState == 2);
        }

        public boolean isBlockedByHitbox() {
            return (obstructionState == 3);
        }

        public boolean isBlocked() {
            return (obstructionState == 3) || (obstructionState == 2);
        }

        public double getMoveSpeed() {
            return speedK;
        }
    }

    public static class Cell
    {
        public Cell(Coord pos)
        {
            this.pos = pos;
        }

        public Coord pos;

        public short val;
        public CellType fullVal = CellType.Default;

        public ArrayList<Long> content = new ArrayList<>();
    }



    public NPFMap(Coord2d src, Coord2d tgt, int mul) {
        Coord2d a = new Coord2d(Math.min(src.x, tgt.x), Math.min(src.y, tgt.y));
        Coord2d b = new Coord2d(Math.max(src.x, tgt.x), Math.max(src.y, tgt.y));
        Coord center = Utils.toPfGrid((a.add(b)).div(2));
        dsize = Math.max(8,((int) Math.ceil(b.dist(a) / MCache.tilehsz.x)) * mul);
        size = 2 * dsize + 1;

        cells = new Cell[size][size];
        begin = center.sub(dsize, dsize);
        end = center.add(dsize, dsize);
        if(!Utils.inVisibleArea(Utils.pfGridToWorld(begin)) || !Utils.inVisibleArea(Utils.pfGridToWorld(end)))
        {
            Gob player = NUtils.player();
            if(player!=null) {
                Coord2d cc = player.rc;
                Coord2d cmap = new Coord2d(MCache.cmaps);
                Coord2d fixator = cc.floor(cmap).mul(cmap).add(cmap.div(2));
                Coord2d ul = fixator.add(450,450);
                Coord2d br = fixator.sub(450,450);
                end = Utils.toPfGrid(ul);
                begin = Utils.toPfGrid(br);
                size = end.x-begin.x;
                cells = new Cell[size][size];
                lastMul = true;
            }
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                cells[i][j] = new Cell(begin.add(i, j));
                if (i == 0 || j == 0 || i == size - 1 || j == size - 1)
                    cells[i][j].val = 2;
            }
        }

    }

    public NPFMap(Coord2d src, Coord2d dst, int mul, boolean waterMode)throws InterruptedException
    {
        this(src,dst,mul);
        this.waterMode = waterMode;
    }

    public Coord getBegin()
    {
        return begin;
    }

    public Coord getEnd()
    {
        return end;
    }

    public Cell[][] getCells()
    {
        return cells;
    }

    public int getSize()
    {
        return size;
    }

    public void build()
    {
        if(NUtils.playerID()!=-1) {
            Following fl = NUtils.player().getattr(Following.class);
            if(fl!= null)
            {
                currentTransport = fl.tgt;
            }
        }
        OCache oc = NUtils.getGameUI().ui.sess.glob.oc;
        synchronized (oc)
        {
            // A cart under tow trails 9-29 units behind and carries no Following attr, so the
            // skip in addGob never fires for it: without this it is rasterised as an obstacle
            // attached to the planner, and was measured covering the player's own start cell in
            // 13 of 65 samples.
            long towed = -1;
            NCore core = (NUtils.getUI() == null) ? null : NUtils.getUI().core;
            if (core != null)
                towed = core.towedVehicle.resolve(oc, NUtils.playerID());
            towedId = towed;
            if (towed >= 0)
                agentRadius = TOWED_CART_RADIUS;

            for (Gob gob : oc)
            {
                if(gob.id!=currentTransport)
                    addGob(gob);
            }
        }
        for (int i = 0; i < size; i += 1)
        {
            for (int j = 0; j < size; j += 1)
            {

                if (cells[i][j].val == 0)
                {
                    ArrayList<Coord> cand = new ArrayList<>();
                    cand.add((Utils.pfGridToWorld(cells[i][j].pos).add(new Coord2d(-MCache.tileqsz.x,MCache.tileqsz.y))).div(MCache.tilesz).floor());
                    cand.add((Utils.pfGridToWorld(cells[i][j].pos).add(new Coord2d(MCache.tileqsz.x,-MCache.tileqsz.y))).div(MCache.tilesz).floor());
                    cand.add((Utils.pfGridToWorld(cells[i][j].pos).add(new Coord2d(-MCache.tileqsz.x,-MCache.tileqsz.y))).div(MCache.tilesz).floor());
                    cand.add((Utils.pfGridToWorld(cells[i][j].pos).add(new Coord2d(MCache.tileqsz.x,MCache.tileqsz.y))).div(MCache.tilesz).floor());

                    for(Coord c : cand) {
                        String name = NUtils.getGameUI().ui.sess.glob.map.tilesetname(NUtils.getGameUI().ui.sess.glob.map.gettile(c));
                        if(!waterMode) {
                            if (name != null && (name.startsWith("gfx/tiles/cave") || name.startsWith("gfx/tiles/rocks") || name.equals("gfx/tiles/deep") || name.equals("gfx/tiles/odeep") || name.startsWith("gfx/tiles/nil"))) {
                                cells[i][j].val = 2;
                            }
                        }
                        else
                        {
                            if (name != null && !(name.startsWith("gfx/tiles/water") || name.startsWith("gfx/tiles/owater") || name.equals("gfx/tiles/deep") || name.equals("gfx/tiles/odeep"))) {
                                cells[i][j].val = 2;
                            }
                        }
                    }
                }
            }
        }
    }

    public ArrayList<Coord> checkCA(CellsArray ca) {
        ArrayList<Coord> result = new ArrayList<>();
        if ((ca.begin.x >= begin.x && ca.begin.x <= end.x ||
                ca.end.x >= begin.x && ca.end.x <= end.x) &&
                (ca.begin.y >= begin.y && ca.begin.y <= end.y ||
                        ca.end.y >= begin.y && ca.end.y <= end.y))
        {
            for (int i = 0; i < ca.x_len; i++)
                for (int j = 0; j < ca.y_len; j++)
                {
                    int ii = i + ca.begin.x - begin.x;
                    int jj = j + ca.begin.y - begin.y;
                    if (ii > 0 && ii < size && jj > 0 && jj < size)
                    {
                        if(ca.cells[i][j] != 0 && cells[ii][jj].val !=0)
                        {
                            result.add(new Coord(ii,jj));
                        }
                    }
                }
        }
        return result;
    }

    public static Window wnd = null;
    public static void print(int size, Cell[][] cells)
    {
        if(NUtils.getUI().core.debug && (Boolean) NConfig.get(NConfig.Key.printpfmap))
        {
            Coord csz = new Coord(UI.scale(10), UI.scale(10));
            if(wnd!=null)
                wnd.destroy();
            wnd = NUtils.getUI().root.add(new Window(new Coord(size * UI.scale(10), size * UI.scale(10)), "PFMAP")
            {
                @Override
                public void draw(GOut g)
                {
                    super.draw(g);
                    for (int i = 0; i < size; i++)
                    {
                        for (int j = size - 1; j >= 0; j--)
                        {
                            if (cells[i][j].val == 1) {
                                g.chcolor(Color.RED);
                                g.frect(new Coord(i * UI.scale(10), j * UI.scale(10)).add(deco.contarea().ul), csz);
                                continue;
                            }
                            else if (cells[i][j].val == 0)
                                g.chcolor(Color.GREEN);
                            else if (cells[i][j].val == 4)
                            {
                                g.chcolor(Color.YELLOW);
                                g.frect(new Coord(i * UI.scale(10), j * UI.scale(10)).add(deco.contarea().ul), csz);
                                continue;
                            }
                            else if (cells[i][j].val == 7)
                                g.chcolor(Color.BLUE);
                            else if (cells[i][j].val == 8)
                            {
                                g.chcolor(Color.MAGENTA);
                                g.frect(new Coord(i * UI.scale(10), j * UI.scale(10)).add(deco.contarea().ul), csz);
                                continue;
                            }
                            else if (cells[i][j].val == 9)
                            {
                                g.chcolor(Color.CYAN);
                                g.frect(new Coord(i * UI.scale(10), j * UI.scale(10)).add(deco.contarea().ul), csz);
                                continue;
                            }
                            else
                                g.chcolor(Color.BLACK);
                            g.rect(new Coord(i * UI.scale(10), j * UI.scale(10)).add(deco.contarea().ul), csz);
                        }
                    }
                }

                public void wdgmsg(Widget sender, String msg, Object... args)
                {
                    if ((sender == this) && (msg == "close"))
                    {
                        destroy();
                    }
                    else
                    {
                        super.wdgmsg(sender, msg, args);
                    }
                }

            }, new Coord(UI.scale(100), UI.scale(100)));
            NUtils.getUI().bind(wnd, 7002);
        }
    }

    /**
     * Widen obstacles by the amount the agent is bigger than the pathfinder's baseline.
     *
     * <p>The grid search treats the walker as a point, and every obstacle is already pre-inflated
     * to the baseline {@link #PLAYER_RADIUS}. Towing a cart makes the moving footprint larger, so
     * the difference has to be added here or the search will happily thread a gap the cart cannot
     * take.
     *
     * <p>Call this <em>after</em> approach points have been chosen. Dilation deliberately leaves
     * alone: cells already marked as approach points (7), which are the goal and would otherwise
     * be swallowed; the start and its immediate neighbours, since the character demonstrably got
     * there with the cart in tow, so that ground is passable by construction; and the towed cart
     * itself, which is part of the moving agent rather than something it has to clear.
     *
     * @param start the player's cell, in grid coordinates local to this map
     */
    public void dilateForAgent(Coord start) {
        int r = (int) Math.ceil((agentRadius - PLAYER_RADIUS) / MCache.tilehsz.x);
        if (r <= 0)
            return;

        boolean[][] seed = new boolean[size][size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                seed[i][j] = (cells[i][j].val == 1 || cells[i][j].val == 2) && !isOwnCart(cells[i][j]);

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (!seed[i][j])
                    continue;
                for (int di = -r; di <= r; di++) {
                    for (int dj = -r; dj <= r; dj++) {
                        int ii = i + di, jj = j + dj;
                        if (ii < 0 || jj < 0 || ii >= size || jj >= size)
                            continue;
                        if (cells[ii][jj].val != 0)
                            continue;
                        if (start != null && Math.abs(ii - start.x) <= 1 && Math.abs(jj - start.y) <= 1)
                            continue;
                        cells[ii][jj].val = 3;
                    }
                }
            }
        }
    }

    /** True when a cell is blocked by the towed cart and nothing else. */
    private boolean isOwnCart(Cell cell) {
        return towedId >= 0 && cell.val == 1
                && cell.content.size() == 1 && cell.content.contains(towedId);
    }

    private CellsArray getCa(Gob gob) {
        if(gatesAlwaysClosed && isGate(gob)) {
            return gob.ngob.getTrueCA();
        } else {
            return gob.ngob.getCA();
        }
    }
}
