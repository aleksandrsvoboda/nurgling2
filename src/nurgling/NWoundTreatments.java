package nurgling;

import java.util.*;

/**
 * Static wound -> healing item table for the Health &amp; Wounds page.
 *
 * Keyed by the basename of the wound resource ("paginae/wound/&lt;key&gt;") rather than by the
 * display name, because wound names arrive from the server already localised.
 *
 * Every resource path here was verified against the live resource server, and each of those
 * resources carries a tooltip layer, so row labels come from the game rather than from us.
 */
public class NWoundTreatments {
    public static class Treatment {
	/** Inventory-object resource path, or null for an entry that has no item icon. */
	public final String res;
	/** L10n key for the label, used when {@link #res} is null. */
	public final String textKey;
	/** L10n key base for the side effect, or null. Expands to {@code <base>.short} / {@code <base>.long}. */
	public final String noteKey;

	private Treatment(String res, String textKey, String noteKey) {
	    this.res = res;
	    this.textKey = textKey;
	    this.noteKey = noteKey;
	}
    }

    private static Treatment t(String res) {return(new Treatment(res, null, null));}
    private static Treatment t(String res, String noteKey) {return(new Treatment(res, null, noteKey));}
    private static Treatment txt(String textKey) {return(new Treatment(null, textKey, null));}

    /* Items */
    private static final String GAUZE       = "gfx/invobjs/gauze";
    private static final String SILKSUTURE  = "gfx/invobjs/silksuture";
    private static final String STITCHPATCH = "gfx/invobjs/stitchpatch";
    private static final String WOUNDGLUE   = "gfx/invobjs/jar-woundglue";
    private static final String ROOTFILL    = "gfx/invobjs/rootfill";
    private static final String GRAYGREASE  = "gfx/invobjs/graygrease";
    private static final String KELPCREAM   = "gfx/invobjs/kelpcream";
    private static final String CAMOMILE    = "gfx/invobjs/camomilecompress";
    private static final String COLDCOMP    = "gfx/invobjs/coldcompress";
    private static final String MUDOINT     = "gfx/invobjs/mudointment";
    private static final String POULTICE    = "gfx/invobjs/stingingpoultice";
    private static final String TOADBUTTER  = "gfx/invobjs/toadbutter";
    private static final String HARTSHORN   = "gfx/invobjs/hartshornsalve";
    private static final String LEECH       = "gfx/invobjs/leech";
    private static final String WILLOWWEEP  = "gfx/invobjs/jar-willowweep";
    private static final String SNAKEJUICE  = "gfx/invobjs/jar-snakejuice";
    private static final String YARROW      = "gfx/invobjs/herbs/yarrow";
    private static final String WAYBROAD    = "gfx/invobjs/herbs/waybroad";
    private static final String SOAPBAR     = "gfx/invobjs/soapbar";
    private static final String HONEYWAYBAND = "gfx/invobjs/honeybroadaid";

    /* Side-effect note keys */
    private static final String N_DEEPCUT     = "char.wound.note.todeepcut";
    private static final String N_BLUNT       = "char.wound.note.toblunttrauma";
    private static final String N_LEECHBURNS  = "char.wound.note.toleechburns";
    private static final String N_WART        = "char.wound.note.tonastywart";
    private static final String N_CONLOSS     = "char.wound.note.conloss";

    private static final Map<String, List<Treatment>> map = new HashMap<>();

    private static void put(String wound, Treatment... tt) {
	map.put(wound, Collections.unmodifiableList(Arrays.asList(tt)));
    }

    static {
	put("addervenom",      t(SNAKEJUICE));
	put("antburn",         t(YARROW, N_CONLOSS));
	put("beesting",        t(GRAYGREASE), t(KELPCREAM));
	put("blackeye",        t(HONEYWAYBAND), t(HARTSHORN), t(TOADBUTTER));
	put("bladekiss",       t(GAUZE), t(SILKSUTURE));
	put("blunttrauma",     t(GAUZE), t(LEECH, N_LEECHBURNS), t(HARTSHORN), t(TOADBUTTER),
			       t(WILLOWWEEP), t(CAMOMILE), t(MUDOINT));
	put("bruise",          t(LEECH, N_LEECHBURNS), t(WILLOWWEEP), t(POULTICE));
	put("concussion",      t(COLDCOMP), t(WILLOWWEEP));
	put("crabcaressed",    t(SILKSUTURE));
	put("cruelincision",   t(GAUZE), t(ROOTFILL, N_DEEPCUT), t(SILKSUTURE), t(STITCHPATCH), t(WOUNDGLUE));
	put("deepcut",         t(GAUZE), t(HONEYWAYBAND), t(WAYBROAD), t(ROOTFILL), t(SILKSUTURE), t(POULTICE));
	put("fellslash",       t(GAUZE), t(SILKSUTURE), t(WOUNDGLUE));
	put("infectedsore",    t(CAMOMILE), t(SOAPBAR));
	put("jellysting",      t(GRAYGREASE));
	put("leechburns",      t(TOADBUTTER, N_WART));
	put("midgebite",       t(YARROW));
	put("nastylaceration", t(TOADBUTTER, N_WART), t(SILKSUTURE), t(STITCHPATCH), t(WOUNDGLUE));
	put("nicksnknacks",    t(HONEYWAYBAND), t(YARROW), t(MUDOINT));
	put("punchsore",       t(WILLOWWEEP), t(MUDOINT));
	put("sandfleabites",   t(GRAYGREASE), t(YARROW));
	put("scrapesncuts",    t(HONEYWAYBAND), t(YARROW), t(MUDOINT));
	put("sealfinger",      t(HARTSHORN), t(KELPCREAM));
	put("severemauling",   t(HARTSHORN, N_BLUNT));
	put("starvation",      txt("char.wound.treat.anyfood"));
	put("swampfever",      t(SNAKEJUICE));
	put("swollenbump",     t(COLDCOMP), t(LEECH, N_LEECHBURNS), t(POULTICE));
	put("unfaced",         t(LEECH, N_LEECHBURNS), t(TOADBUTTER, N_WART), t(KELPCREAM), t(MUDOINT), t(YARROW));
	put("wretchedgore",    t(STITCHPATCH));

	/* Wounds that no medicine treats -- listed so the panel can say so explicitly
	 * instead of silently omitting the section. Slugs verified against the res server. */
	put("allergicreaction");
	put("asphyxiation");
	put("birdlung");
	put("bumburn");
	put("deepworm");
	put("dragonbite");
	put("hearthburn");
	put("maddeningrash");
	put("nettleburn");
	put("nidburns");
	put("nosebleed");
	put("pipewheeze");
	put("rotgut");
	put("somethingbroken");
	put("soresnout");
	put("tuskalooza");
    }

    private static final String PREFIX = "paginae/wound/";

    /**
     * @param resName full wound resource name, e.g. "paginae/wound/deepcut"
     * @return the treatments for that wound, or null when the wound is unknown to us
     */
    public static List<Treatment> forWound(String resName) {
	if((resName == null) || !resName.startsWith(PREFIX))
	    return(null);
	return(map.get(resName.substring(PREFIX.length())));
    }
}
