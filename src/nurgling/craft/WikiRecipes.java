package nurgling.craft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/** Bounded, checksummed binary reader. No network dependency in the client. */
public final class WikiRecipes {
    private static final byte[] MAGIC = "NRCPDB01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    public final String source, fetchedAt, attribution;
    public final List<AtlasCatalog.Recipe> recipes;
    private final Map<String, String> references = new HashMap<>();
    private static WikiRecipes cached;
    private static Path cachedPath;
    private static java.nio.file.attribute.FileTime cachedTime;
    private static long cachedSize = -1;

    private WikiRecipes(String source, String fetchedAt, String attribution, List<AtlasCatalog.Recipe> recipes) {
        this.source = source; this.fetchedAt = fetchedAt; this.attribution = attribution; this.recipes = List.copyOf(recipes);
        Set<String> ambiguous = new HashSet<>();
        for(AtlasCatalog.Recipe recipe : recipes) {
            List<AtlasCatalog.Material> all = new ArrayList<>(recipe.inputs); all.addAll(recipe.outputs);
            for(AtlasCatalog.Material material : new ArrayList<>(all)) all.addAll(material.choices);
            for(AtlasCatalog.Material material : all) {
                String key = normalize(material.name), old = references.putIfAbsent(key, material.resource);
                if(old != null && !old.equals(material.resource)) ambiguous.add(key);
            }
        }
        ambiguous.forEach(references::remove);
    }
    public static String normalize(String value) {
        return value.replaceAll("[\\u200b-\\u200f\\ufeff]", "").replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public String reference(String name) { return references.getOrDefault(normalize(name), ""); }

    public static synchronized WikiRecipes bundled() throws IOException {
        // Called by a background worker after startup, so even validation of a new file cannot stall rendering.
        Path external = null;
        try {
            Path code = Path.of(WikiRecipes.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isRegularFile(code)) external = code.resolveSibling("recipes.bin");
        } catch(Exception ignored) {}
        return loadAvailable(external);
    }

    static synchronized WikiRecipes loadAvailable(Path external) throws IOException {
        if(external != null && Files.isRegularFile(external)) {
            java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(external, java.nio.file.attribute.BasicFileAttributes.class);
            if(cached == null || !external.equals(cachedPath) || !attributes.lastModifiedTime().equals(cachedTime) || attributes.size() != cachedSize) {
                WikiRecipes replacement;
                try(InputStream in = Files.newInputStream(external)) { replacement = read(in); }
                // Publish only after the whole file validates; a bad update never replaces the last good catalog.
                cached = replacement; cachedPath = external;
                cachedTime = attributes.lastModifiedTime(); cachedSize = attributes.size();
            }
        } else if(cached == null) {
                try(InputStream in = WikiRecipes.class.getResourceAsStream("recipes.bin")) {
                    if(in == null) throw new FileNotFoundException("Missing offline recipe database recipes.bin");
                    cached = read(in);
                }
        }
        return cached;
    }

    public static WikiRecipes read(InputStream source) throws IOException {
        DataInputStream header = new DataInputStream(source);
        byte[] magic = new byte[8]; header.readFully(magic);
        if(!Arrays.equals(MAGIC, magic)) throw new IOException("Unsupported recipe database format");
        int rawSize = bounded(header.readInt(), MAX_BYTES, "uncompressed size"), packedSize = bounded(header.readInt(), MAX_BYTES, "compressed size");
        byte[] digest = new byte[32]; header.readFully(digest);
        byte[] packed = new byte[packedSize]; header.readFully(packed);
        if(header.read() != -1) throw new IOException("Trailing data after recipe database");
        byte[] raw = new byte[rawSize]; Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            int offset = 0;
            while(offset < raw.length && !inflater.finished()) {
                int read = inflater.inflate(raw, offset, raw.length - offset);
                if(read == 0) break;
                offset += read;
            }
            if(offset != rawSize || !inflater.finished() || inflater.getRemaining() != 0) throw new IOException("Invalid recipe database compression");
        } catch(DataFormatException e) { throw new IOException("Invalid compressed recipe database", e); }
        finally { inflater.end(); }
        try {
            if(!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(raw))) throw new IOException("Recipe database checksum mismatch");
        } catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw));
        int count = bounded(input.readInt(), 200000, "string count");
        String[] strings = new String[count];
        for(int i = 0; i < count; i++) {
            int length = bounded(input.readInt(), Math.min(1_000_000, input.available()), "string length");
            byte[] bytes = new byte[length]; input.readFully(bytes); strings[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        Reader reader = new Reader(input, strings);
        String origin = reader.string(), date = reader.string(), attribution = reader.string();
        count = bounded(input.readInt(), 20000, "recipe count");
        List<AtlasCatalog.Recipe> recipes = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for(int i = 0; i < count; i++) {
            String id = reader.string(), name = reader.string(), group = reader.string(), url = reader.string(), revised = reader.string();
            String kind = reader.string(), action = reader.string(), expression = reader.string();
            int revision = input.readInt(); boolean arithmetic = reader.flag();
            if(!ids.add(id) || !id.startsWith("wiki:") || revision <= 0 || !url.startsWith("https://ringofbrodgar.com/"))
                throw new IOException("Invalid recipe identity or provenance");
            List<AtlasCatalog.Material> ingredients = reader.materials(), outputs = reader.materials();
            List<String> quality = reader.list(), tools = reader.list(), requirements = reader.list(), formulas = reader.list();
            AtlasCatalog.WikiInfo wiki = new AtlasCatalog.WikiInfo(url, revised, kind, action, expression, revision, arithmetic, requirements, formulas);
            recipes.add(new AtlasCatalog.Recipe(id, name, group, ingredients, outputs, quality, tools, false, wiki));
        }
        if(input.available() != 0) throw new IOException("Trailing recipe records");
        return new WikiRecipes(origin, date, attribution, recipes);
    }
    private static int bounded(int value, int max, String field) throws IOException {
        if(value < 0 || value > max) throw new IOException("Invalid " + field);
        return value;
    }
    private static final class Reader {
        final DataInputStream input; final String[] strings;
        Reader(DataInputStream input, String[] strings) { this.input = input; this.strings = strings; }
        String string() throws IOException {
            int index = input.readInt();
            if(index < 0 || index >= strings.length) throw new IOException("Invalid recipe string reference");
            return strings[index];
        }
        boolean flag() throws IOException {
            int value = input.readUnsignedByte();
            if(value > 1) throw new IOException("Invalid recipe flag");
            return value == 1;
        }
        List<String> list() throws IOException {
            int count = bounded(input.readInt(), 256, "list count"); List<String> list = new ArrayList<>();
            for(int i = 0; i < count; i++) list.add(string());
            return list;
        }
        List<AtlasCatalog.Material> materials() throws IOException {
            int count = bounded(input.readInt(), 1024, "ingredient count"); List<AtlasCatalog.Material> result = new ArrayList<>();
            for(int i = 0; i < count; i++) {
                String resource = string(), name = string(); double amount = input.readDouble(); String unit = string(); int flags = input.readUnsignedByte();
                if(!Double.isFinite(amount) || amount < -1 || amount > 1_000_000 || flags > 3) throw new IOException("Invalid ingredient amount/flags");
                try { result.add(new AtlasCatalog.Material(resource, name, amount, unit, (flags & 1) != 0, (flags & 2) != 0, "")); }
                catch(IllegalArgumentException | org.json.JSONException e) { throw new IOException("Invalid ingredient choice", e); }
            }
            return result;
        }
    }
}
