package haven.resutil;

import haven.*;
import haven.render.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;

public class WaterSkyTest {
    static void require(boolean ok, String text) { if(!ok) throw new AssertionError(text); }
    static class Memory extends Environment.Proxy {
        public Environment back() { throw new AssertionError("Unexpected GPU operation"); }
        public FillBuffer fillbuf(DataBuffer target, int from, int to) {
            return new FillBuffer() {
                byte[] pixels;
                public int size() { return to - from; }
                public boolean compatible(Environment env) { return env == Memory.this; }
                public ByteBuffer push() { throw new AssertionError("Unexpected push"); }
                public void pull(ByteBuffer buffer) { pixels = new byte[buffer.remaining()]; buffer.get(pixels); last = pixels; }
                public void dispose() {}
            };
        }
        byte[] last;
    }
    static BufferedImage image(int type) {
        BufferedImage image = new BufferedImage(32, 24, type);
        for(int y=0;y<24;y++) for(int x=0;x<32;x++)
            image.setRGB(x,y,((x*7+32)&255)<<24|((x*17)&255)<<16|((y*21)&255)<<8|((x+y)*9&255));
        return image;
    }
    public static void main(String[] args) throws Exception {
        int[][] order={{3,1},{1,1},{2,0},{2,2},{2,1},{0,1}};
        for(int type:new int[]{BufferedImage.TYPE_INT_ARGB,BufferedImage.TYPE_INT_ARGB_PRE,BufferedImage.TYPE_3BYTE_BGR,BufferedImage.TYPE_BYTE_INDEXED}) {
            BufferedImage src=image(type); byte[][] faces=WaterSky.faces(src);
            TextureCube tex=WaterSky.texture(8,8,faces);
            for(int face=0;face<6;face++) {
                byte[] old=TexI.convert(src,new Coord(8,8),new Coord(order[face][0]*8,order[face][1]*8),new Coord(8,8));
                require(Arrays.equals(old,faces[face]),"Pixel/orientation mismatch type="+type+" face="+face);
                for(int context=0;context<2;context++) {
                    Memory env=new Memory();
                    tex.init.fill(tex.image(TextureCube.Face.values()[face],0),env);
                    require(Arrays.equals(old,env.last),"Context recreation changed cube bytes");
                    tex.init.done();
                }
            }
            require(tex.init.fill(tex.image(TextureCube.Face.XP,1),new Memory())==null,"Unexpected mipmap");
        }
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        WaterSky sky=new WaterSky(()->{
            entered.countDown();
            try { release.await(); } catch(InterruptedException e) { throw new RuntimeException(e); }
            return image(BufferedImage.TYPE_INT_ARGB);
        });
        require(entered.await(2,TimeUnit.SECONDS),"Worker did not start");
        TextureCube.SamplerCube placeholder;
        try {
            placeholder=sky.get();
            require(placeholder.tex.w==1,"Pending sky did not return fallback");
            Memory env=new Memory();placeholder.tex.init.fill(placeholder.tex.image(TextureCube.Face.XP,0),env);
            require(env.last.length==4,"Fallback upload waits for the source");
        } finally { release.countDown(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(sky.get()==placeholder&&System.nanoTime()<deadline) Thread.sleep(2);
        require(sky.get()!=placeholder&&sky.get().tex.w==8,"Prepared texture not published");
        System.out.println("Water sky PASS: legacy pixels/orientation, repeat upload, nonblocking fallback and async publication");
        System.exit(0);
    }
}
