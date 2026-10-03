package nurgling.render;

/** Motion history stays in world space and never emits for stationary objects. */
public class WaterWakesTest {
    static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) {
        WaterWakes wakes=new WaterWakes();
        for(int i=0;i<20;i++)wakes.sample(1,0,0,0,2,3,i*.1);
        require(wakes.impulses.isEmpty(),"Stationary object generates wake");
        for(int i=0;i<20;i++)wakes.sample(1,i,0,0,2,3,2+i*.1);
        require(wakes.impulses.size()>5,"Moving object has no trail");
        int retained=wakes.impulses.size();
        wakes.vertices(3.85);
        require(wakes.impulses.size()==retained && !wakes.tracks.isEmpty(),"Render clock preceding simulation deletes fresh wakes");
        WaterWakes.Impulse first=wakes.impulses.getFirst(),last=wakes.impulses.getLast();
        require(first.x<last.x && last.x>19 && last.x<=22 && last.dx>.99,"Wave origin is not at the front of the object");
        float[] wedge=wakes.vertices(3.9);
        float oldEdge=(wedge[1]+wedge[23])*.5f;
        int tail=wedge.length-132;
        float newEdge=(wedge[tail+1]+wedge[tail+23])*.5f;
        require(Math.abs(oldEdge)>Math.abs(newEdge),"Wake does not widen behind the source");
        require(Math.abs(oldEdge+(wedge[67]+wedge[89])*.5f)<.0001,"Wake has no mirrored second arm");
        float[] stopped=wakes.vertices(4.4);
        float laterEdge=(stopped[1]+stopped[23])*.5f;
        require(Math.abs(laterEdge)>Math.abs(oldEdge)+1,"Stopped wake does not propagate away from the source");
        float[] fresh=wakes.vertices(first.born);
        require(Math.abs((fresh[1]+fresh[23])*.5f-(fresh[67]+fresh[89])*.5f)<.001,"Fresh wave fronts do not meet at their origin");
        double fixedX=first.x;
        for(int i=0;i<10;i++)wakes.sample(1,19,i,0,2,3,4+i*.1);
        require(first.x==fixedX && wakes.impulses.getLast().dy>.99,"Trail rotates or follows the emitter after a turn");
        int count=wakes.impulses.size();
        wakes.sample(1,1000,1000,0,2,3,5);
        require(count==wakes.impulses.size(),"Teleport leaves a long wake");
        float[] alive=wakes.vertices(5);
        require(alive.length>0,"Live wake missing");
        for(float v:alive)require(Float.isFinite(v),"Nonfinite wake vertex");
        require(wakes.vertices(11).length==0 && wakes.tracks.isEmpty(),"Stopped wake never expires");
        for(int frame=0;frame<70;frame++)for(int id=0;id<50;id++)wakes.sample(id,frame*2,id*10,0,2,3,20+frame*.16);
        require(wakes.tracks.size()<=WaterWakes.MAX_SOURCES && wakes.impulses.size()<=WaterWakes.MAX_IMPULSES,"Wake budget exceeded");
        wakes.dispose();require(wakes.tracks.isEmpty()&&wakes.impulses.isEmpty(),"View disposal retains trails");
        WaterWakes coarse=new WaterWakes(),fine=new WaterWakes(),slow=new WaterWakes();
        for(int i=0;i<=10;i++)coarse.sample(1,i*2.4,0,0,2,3,i*.24);
        for(int i=0;i<=120;i++)fine.sample(1,i*.2,0,0,2,3,i*.02);
        for(int i=0;i<=120;i++)slow.sample(1,i*.2,0,0,2,3,i*.04);
        require(coarse.impulses.size()==16 && fine.impulses.size()==16 && slow.impulses.size()==16,"Wake count depends on speed/update rate rather than distance");
        java.util.Iterator<WaterWakes.Impulse> a=coarse.impulses.iterator(),b=fine.impulses.iterator(),c=slow.impulses.iterator();
        double expected=3;
        while(a.hasNext()) {
            expected+=WaterWakes.DISTANCE_STEP;
            require(Math.abs(a.next().x-expected)<.0001 && Math.abs(b.next().x-expected)<.0001 && Math.abs(c.next().x-expected)<.0001,"Spatial wave spacing or forward offset drifted");
        }
        nurgling.NHitBox box=new nurgling.NHitBox(new haven.Coord2d(-8,-4),new haven.Coord2d(10,4),true);
        require(Math.abs(WaterWakes.frontDistance(box,0,1,0)-10)<.0001,"Asymmetric bounding-box front ignored");
        require(Math.abs(WaterWakes.frontDistance(box,0,-1,0)-8)<.0001,"Reverse movement uses wrong bounding-box edge");
        require(Math.abs(WaterWakes.frontDistance(box,Math.PI/2,0,-1)-10)<.0001,"Rotated bounding-box front ignored");
        require(Math.abs(WaterWakes.frontDistance(box,0,.6f,-.8f)-5)<.0001,"Diagonal front is outside the bounding-box edge");
        coarse.dispose();fine.dispose();slow.dispose();
        System.out.println("Water wakes: distance spacing, update-rate/speed independence, bounding-box front, turns, teleport, lifetime and budgets PASS");
    }
}
