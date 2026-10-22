package nurgling.pf;

import haven.*;
import nurgling.*;

public class CellsArray {
    public Coord begin;
    public Coord end;
    public short[][] cells;

    public int x_len;
    public int y_len;

    public CellsArray(Gob gob) {
        this(NHitBoxD.solid(gob));
    }


    public CellsArray(int x_len, int y_len) {
        this.cells = new short[x_len][y_len];
        this.x_len = x_len;
        this.y_len = y_len;
    }

    public CellsArray(NHitBox hb, double angl, Coord2d rc) {
        this(new NHitBoxD[]{new NHitBoxD(hb.begin, hb.end, rc, angl)});
    }

    /** The cells any of the boxes touch; cells between them stay free. */
    public CellsArray(NHitBoxD[] boxes) {
        Coord2d ul = boxes[0].getCircumscribedUL(), br = boxes[0].getCircumscribedBR();
        for (NHitBoxD box : boxes) {
            Coord2d bul = box.getCircumscribedUL(), bbr = box.getCircumscribedBR();
            ul = Coord2d.of(Math.min(ul.x, bul.x), Math.min(ul.y, bul.y));
            br = Coord2d.of(Math.max(br.x, bbr.x), Math.max(br.y, bbr.y));
        }
        begin = Utils.toPfGrid(ul);
        end = Utils.toPfGrid(br);
        NHitBoxD tile = new NHitBoxD(begin);
        x_len = end.x - begin.x + 1;
        y_len = end.y - begin.y + 1;
        cells = new short[x_len][y_len];
        for (int i = 0; i < x_len; i++) {
            for (int j = 0; j < y_len; j++) {
                tile.setUnitSquare(begin.add(i, j));
                for (NHitBoxD box : boxes) {
                    if (tile.intersects(box, false)) {
                        cells[i][j] = 1;
                        break;
                    }
                }
            }
        }
    }
}
