package nurgling.tools;

import haven.Gob;
import nurgling.NConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Private, local labels for the roads listed in a milestone / roadsign window. The window carries no
 * road id, so a tag belongs to (post, road name); the post is keyed by its global grid id and in-grid
 * offset, which stay put when the post's resource changes (unlike ngob.hash). Stored in the per-world
 * NConfig profile, never in the shared database.
 */
public class RoadTags {

    private RoadTags() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final String POSTS_KEY = "posts";
    public static final int MAX_LENGTH = 40;

    /** "&lt;grid id&gt;:&lt;x&gt;,&lt;y&gt;", or null while the gob's place is not known yet. */
    public static String postKey(Gob gob) {
        if (gob == null || gob.ngob == null || gob.ngob.gcoord == null || gob.ngob.grid_id == 0)
            return null;
        return postKey(gob.ngob.grid_id, gob.ngob.gcoord.x, gob.ngob.gcoord.y);
    }

    public static String postKey(long gridId, int x, int y) {
        return gridId + ":" + x + "," + y;
    }

    /** Key of the nth road (0-based) named name on one post, so two roads sharing a name keep apart. */
    public static String roadKey(String name, int occurrence) {
        return occurrence == 0 ? name : name + "#" + (occurrence + 1);
    }

    public static String get(String postKey, String roadKey) {
        return get(NConfig.getAsMap(NConfig.Key.roadTags), postKey, roadKey);
    }

    public static void set(String postKey, String roadKey, String tag) {
        Map<String, Object> root = set(NConfig.getAsMap(NConfig.Key.roadTags), postKey, roadKey, tag);
        NConfig.set(NConfig.Key.roadTags, root);
    }

    @SuppressWarnings("unchecked")
    static String get(Map<String, Object> root, String postKey, String roadKey) {
        Object posts = root.get(POSTS_KEY);
        if (!(posts instanceof Map))
            return null;
        Object post = ((Map<String, Object>) posts).get(postKey);
        if (!(post instanceof Map))
            return null;
        Object tag = ((Map<String, Object>) post).get(roadKey);
        return (tag instanceof String) ? (String) tag : null;
    }

    /** Returns a new root with the tag set; a blank tag removes it, and a post left without tags goes too. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> set(Map<String, Object> root, String postKey, String roadKey, String tag) {
        Map<String, Object> out = new HashMap<>(root);
        Object postsObj = out.get(POSTS_KEY);
        Map<String, Object> posts = (postsObj instanceof Map) ? new HashMap<>((Map<String, Object>) postsObj) : new HashMap<>();
        Object postObj = posts.get(postKey);
        Map<String, Object> post = (postObj instanceof Map) ? new HashMap<>((Map<String, Object>) postObj) : new HashMap<>();

        String clean = (tag == null) ? "" : tag.trim();
        if (clean.length() > MAX_LENGTH)
            clean = clean.substring(0, MAX_LENGTH);
        if (clean.isEmpty())
            post.remove(roadKey);
        else
            post.put(roadKey, clean);

        if (post.isEmpty())
            posts.remove(postKey);
        else
            posts.put(postKey, post);
        out.put(POSTS_KEY, posts);
        return out;
    }
}
