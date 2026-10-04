package haven;
import haven.iosys.tk.*;
import nurgling.styles.*;
import java.awt.image.BufferedImage;

/** Shared close/delete source, color and sizing, including legacy resource aliases. */
public class CloseIconTest {
 public static void main(String[] args)throws Exception {
  nurgling.NConfig.getGlobalInstance();
  Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();
  try {
   window.sizing(new Windeye.Sizing().fixsize(UiThemeTest.SIZE)).show(true);
   UI ui=new UI(window,new Audio.Root(haven.iosys.audio.DummyAudio.instance),UiThemeTest.SIZE,null);
   Window host=ui.root.add(new Window(UI.scale(710,490),"Shared orange close / delete"),UI.scale(10,10));host.tick(1);
   int x=20;
   for(int size:new int[]{16,32,64}) {
    int side=UI.scale(size);BufferedImage icon=GeneratedButtons.iconImage("close",side);
    UiThemeTest.check(icon.getWidth()==side&&icon.getHeight()==side,"Wrong dimensions");
    int visible=0,transparent=0;
    for(int y=0;y<side;y++)for(int xx=0;xx<side;xx++) {
     int pixel=icon.getRGB(xx,y),alpha=pixel>>>24;
     if(alpha==0)transparent++;
     if(alpha>100) {
      visible++;
      for(int shift:new int[]{0,8,16})
       UiThemeTest.check(Math.abs(((pixel>>shift)&255)-((UITheme.ACCENT.getRGB()>>shift)&255))<=2,"Non-orange pixel");
     }
    }
    UiThemeTest.check(visible>0&&transparent>0,"Missing glyph/transparency");
    for(String alias:new String[]{"nurgling/hud/icons/close/cross","nurgling/hud/icons/close/cross_push","nurgling/hud/icons/close/cross_hover","nurgling/hud/buttons/square/cross/u","nurgling/hud/sessions/close/10x10"}) {
     BufferedImage legacy=UIResources.image(alias,side,side,UI.scale(1f));
     UiThemeTest.check(legacy!=null,"Missing close alias");
     for(int y=0;y<side;y++)for(int xx=0;xx<side;xx++)
      UiThemeTest.check(legacy.getRGB(xx,y)==icon.getRGB(xx,y),"Legacy cross has different artwork");
    }
    host.add(new Img(new TexI(icon)),UI.scale(x,35));
    host.add(new Label(size+" px"),UI.scale(x,112));x+=125;
   }
   host.add(new IButton(nurgling.NStyle.cbtni[0],nurgling.NStyle.cbtni[1],nurgling.NStyle.cbtni[2]),UI.scale(20,170));
   host.add(new Label("Window close"),UI.scale(50,170));
   host.add(new IButton(nurgling.NStyle.crossSquare[0].back,nurgling.NStyle.crossSquare[1].back,nurgling.NStyle.crossSquare[2].back),UI.scale(20,210));
   host.add(new Label("Legacy delete"),UI.scale(50,210));
   UiThemeTest.check(Utils.imgsz(nurgling.NStyle.cbtni[0]).equals(UI.scale(16,16)),"Window cross not 16px");
   UiThemeTest.check(nurgling.NStyle.crossSquare[0].sz().equals(UI.scale(16,16)),"Delete cross not 16px");
   UiThemeTest.capture(window,host,"shared-cross-vulkan");
   System.out.println("PASS: 16/32/64 sizes, orange alpha glyph, identical legacy close/delete aliases");
  }finally {window.dispose();toolkit.dispose();}
  System.exit(0);
 }
}
