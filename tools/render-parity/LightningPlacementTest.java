package nurgling.render;

import haven.Coord2d;
import haven.MCache;
import java.util.Random;

/** Keep storm impacts outside the player's immediate surroundings, in world units. */
public class LightningPlacementTest {
    public static void main(String[] args) {
        Random random=new Random(20261004);
        for(Coord2d player:new Coord2d[]{Coord2d.z,Coord2d.of(71234,-98765),Coord2d.of(-99999,45678)}) {
            for(int i=0;i<10000;i++) {
                Coord2d impact=Lightning.strikePosition(player,random);
                double tiles=impact.dist(player)/MCache.tilesz.x;
                if(!Double.isFinite(tiles)||tiles<18-1e-9||tiles>40+1e-9)
                    throw new AssertionError("Strike too close/far from player: "+tiles+" tiles");
            }
        }
        for(double unit:new double[]{0,Math.nextDown(1.0)}) {
            Random edge=new Random(){@Override public double nextDouble(){return unit;}};
            double distance=Lightning.strikePosition(Coord2d.z,edge).dist(Coord2d.z)/MCache.tilesz.x;
            double expected=unit==0?18:40;
            if(Math.abs(distance-expected)>1e-9)throw new AssertionError("Incorrect annulus boundary");
        }
        System.out.println("PASS: 30,000 impacts stay 18-40 tiles from the player, including negative coordinates and radius boundaries");
    }
}
