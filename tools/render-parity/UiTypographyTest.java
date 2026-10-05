package haven;

import nurgling.conf.FontSettings;
import nurgling.conf.ItemQualityOverlaySettings;
import nurgling.styles.UIFont;
import nurgling.NStyle;
import nurgling.widgets.login.NLoginTheme;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.text.AttributedCharacterIterator;
import java.util.*;
import java.io.File;
import javax.imageio.ImageIO;

/** Exercises the actual shared text paths, including server rich-text overrides. */
public class UiTypographyTest {
    static void check(boolean pass,String message) {
        if(!pass) throw new AssertionError(message);
    }
    static void allowed(Font font) {
        String family=font.getFamily(Locale.ROOT);
        check(family.equals(UIFont.regular.getFamily(Locale.ROOT)) ||
              family.equals(UIFont.semibold.getFamily(Locale.ROOT)) || family.equals(Font.SANS_SERIF),
              "Unexpected font: "+font);
    }
    static void richFonts(RichText text) {
        for(RichText.Part part=text.parts;part!=null;part=part.next) {
            if(!(part instanceof RichText.TextPart)) continue;
            AttributedCharacterIterator chars=((RichText.TextPart)part).ti();
            for(char c=chars.first();c!=AttributedCharacterIterator.DONE;c=chars.next())
                allowed((Font)chars.getAttribute(TextAttribute.FONT));
        }
    }
    public static void main(String[] args) throws Exception {
        try {
            nurgling.NConfig.getGlobalInstance(); // In-memory defaults only; no user config writes.
            for(String legacy:new String[]{"Serif","Monospaced","Arial","Fira Code","Inter","Roboto","Fractur"}) {
                Font original=new Font(legacy,Font.BOLD|Font.ITALIC,17);
                Text.Foundry foundry=new Text.Foundry(original);
                allowed(foundry.font);
                check(foundry.font.isBold()&&foundry.font.isItalic()&&foundry.font.getSize()==17,"Lost font styling");
                Text.Line line=foundry.render("Strength / Сила 123");
                check(line.advance(line.text.length())==foundry.strsize(line.text).x,"Draw/measurement mismatch");
                FontSettings.FontConfig saved=new FontSettings.FontConfig(new HashMap<String,Object>(){{put("family",legacy);put("size",17);}});
                check(UIFont.FAMILIES.contains(saved.family)&&saved.size==17,"Legacy preference migration failed");
            }
            allowed(Text.std.font);allowed(Text.serif);allowed(Text.mono);allowed(Text.fraktur);
            allowed(TextEntry.fnd.font);allowed(Button.tf.font);allowed(NStyle.ncatf.font);
            allowed(NStyle.nattrf.font);allowed(NLoginTheme.body.font);
            for(Font font:new Font[]{UIFont.regular,UIFont.semibold})
                check(font.canDisplayUpTo("Сила Ловкость Intelligence 0123456789")==-1,"Missing Cyrillic/Latin glyphs");
            RichText text=RichText.stdf.render("$font[Serif,16]{$b{Сила 123}}\n$font[Fira Code,12]{Текст $i{Italic} $col[80,180,220]{link}}",240);
            richFonts(text);
            RichText.Foundry bundled=new RichText.Foundry(UIFont.regular.deriveFont(12f),Color.WHITE);
            RichText styled=bundled.render("$b{Bold} $i{Italic} $size[20]{Large} $font[Roboto,14]{Legacy}",600);
            richFonts(styled);
            RichText.TextPart part=(RichText.TextPart)styled.parts;
            AttributedCharacterIterator chars=part.ti();chars.first();
            check(((Font)chars.getAttribute(TextAttribute.FONT)).isBold(),"Rich text bold lost with bundled font");
            chars.setIndex(5);
            check(((Font)chars.getAttribute(TextAttribute.FONT)).isItalic(),"Rich text italic lost with bundled font");
            chars.setIndex(12);
            check(((Font)chars.getAttribute(TextAttribute.FONT)).getSize2D()==UI.scale(20f),"Rich text size lost");
            for(float scale:new float[]{1f,1.5f,2f}) {
                Text.Foundry f=new Text.Foundry(UIFont.semibold.deriveFont(12f*scale));
                check(f.render("Русский / English").sz().y==f.height(),"Scaled text clipped");
            }
            BufferedImage preview=new BufferedImage(740,410,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=preview.createGraphics();
            g.setColor(new Color(17,20,24));g.fillRect(0,0,740,410);
            int y=20;
            Text[] samples={NLoginTheme.section.render("Главный экран / Main screen"),NLoginTheme.body.render("Сохранённые аккаунты — Saved accounts"),
                NStyle.ncatf.render("Характеристики / Character attributes"),NStyle.nattrf.render("Сила / Strength: 123"),
                Button.nf.render("Применить / Apply"),TextEntry.fnd.render("Поле ввода / Text entry"),
                Text.std.render("Настройки, флажки, списки / Settings, checkboxes, lists"),text,styled};
            for(Text sample:samples) {g.drawImage(sample.img,20,y,null);y+=sample.sz().y+12;}
            g.dispose();new File("build/ui-fonts-preview").mkdirs();
            ImageIO.write(preview,"png",new File("build/ui-fonts-preview/typography.png"));
            System.out.println("UI typography PASS: allowed families, legacy preferences, resource font overrides, Cyrillic, rich text styles, measurements and scale");
            System.exit(0);
        } catch(Throwable failure) {failure.printStackTrace();System.exit(1);}
    }
}
