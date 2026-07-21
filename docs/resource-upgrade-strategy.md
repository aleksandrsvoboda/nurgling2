# Resource Upgrade Strategy

## How Resource Code Works

The game server distributes code at runtime via resources. Some of this code is
interesting for client modification but is not part of the git source tree. The
`get-code` command fetches source files from the server so they can be compiled
locally:

```
java -cp bin/hafen.jar haven.Resource get-code <resource-name>
```

Fetched files land in `src/haven/res/` and are compiled with the client. Each
fetched file gets annotated with `@haven.FromResource(name, version)` recording
which server resource and version it came from.

### The Version Override Mechanism

The `@FromResource` annotation controls whether your local compiled code or the
server's dynamically-loaded code runs at runtime:

- **Version matches server** — your local code takes precedence. This is what
  you want: your customizations run.
- **Version does not match** — the server's code takes precedence. Your local
  code is silently ignored. This is intentional: it prevents crashes when your
  local code is out of date and incompatible with server changes.

**This means the version in the annotation must exactly equal the server's
current version.** Not higher, not lower — equal. Setting it higher than the
server version is just as wrong as setting it lower; either way your code won't
run.

If multiple files share the same resource name (e.g., `Stack.java` and
`StackName.java` both annotated as `ui/tt/stackn`), they must ALL have the same
version number.

### Why `get-code` Is Dangerous

`get-code` overwrites files without asking. If you run it directly on your
development branch, any custom modifications in those files are destroyed.
Always fetch into a staging area or a dedicated branch.


## Step 1: Detect Updates

Run `find-updates` to scan all `@FromResource` annotations and check the server
for newer versions:

```
java -cp bin/hafen.jar haven.Resource find-updates
```

### Interpreting the Output

| Message | Meaning | Action |
|---------|---------|--------|
| `needs update: X (local vN, remote vM)` | Server has a newer version | Upgrade this resource |
| `conflicting versions of X: N and M` | Multiple files for the same resource disagree on version | Fix annotations so all files match |
| `X is, strangely, newer locally (N locally, M remotely)` | Your annotation version exceeds the server | Lower your version to match the server — your code isn't running |
| `resource no longer found: X` | Server no longer distributes this resource | Safe to ignore. Code still compiles; it just can't be checked for updates |

A clean `find-updates` run shows only "no longer found" warnings (if any).
Everything else needs to be resolved.


## Step 2: Fetch Both Clean Versions

You need **two** clean upstream versions — old and new. Diffing your tree
against new-clean alone does not work: that diff mixes upstream's changes and
your customizations together, and you cannot reliably tell them apart. Code
that looks like a nurgling addition is often upstream code that upstream has
since removed, and "fixing" it by keeping it silently reintroduces stale
upstream behavior.

### New clean

```
java -cp bin/hafen.jar haven.Resource get-code -o newstage <resource-names>
```

### Old clean

The server only serves the current version, so old-clean cannot be fetched
over HTTP. It comes from the client's on-disk resource cache, which still
holds the version last downloaded — the version your `@FromResource`
annotations were written against.

Dump those cached resources back out as `.res` files. This is throwaway
scaffolding, not something the repo carries — put it in a scratch file,
use it, delete it:

```java
import haven.*;
import java.io.*;
import java.nio.file.*;

public class DumpCachedRes {
    public static void main(String[] args) throws Exception {
	Path dst = Paths.get(args[0]);
	ResCache cache = ResCache.global;
	for(int i = 1; i < args.length; i++) {
	    String nm = args[i];
	    Path out = dst.resolve(nm + ".res");
	    Files.createDirectories(out.getParent());
	    try(InputStream in = cache.fetch("res/" + nm);
		OutputStream o = Files.newOutputStream(out))
	    {
		byte[] buf = new byte[8192];
		int n;
		while((n = in.read(buf)) > 0)
		    o.write(buf, 0, n);
	    } catch(FileNotFoundException e) {
		System.out.println("miss: " + nm + " (not in cache)");
		continue;
	    }
	    /* "Haven Resource 1" signature, then a little-endian uint16. */
	    byte[] all = Files.readAllBytes(out);
	    System.out.println("wrote " + out + " (version " +
			       ((all[16] & 0xff) | ((all[17] & 0xff) << 8)) + ")");
	}
    }
}
```

```
javac -cp bin/hafen.jar -d scratch scratch/DumpCachedRes.java
java -cp "bin/hafen.jar;scratch" DumpCachedRes oldres <resource-names>
```

It prints the recovered version for each resource. **Check that these match
your local `@FromResource` versions** — if the client has re-downloaded a
resource since the update, the cache holds new-clean and that resource's
baseline is unusable. A cached version *older* than your annotation is fine;
the resulting diff just covers more versions than strictly needed.

`get-code` accepts a `file:` URL, so the extraction runs through the normal
path:

```
java -cp bin/hafen.jar haven.Resource get-code \
    -U "file:///<abs-path>/oldres/" -o oldstage <resource-names>
```

### Isolate the upstream delta

`get-code` writes files with platform line endings, so on Windows normalize
before diffing or every file appears entirely rewritten:

```
find oldstage newstage -name '*.java' -exec sed -i 's/\r$//' {} +
```

Now diff old-clean against new-clean. This — and only this — is what upstream
actually changed:

```
diff -u oldstage/haven/res/path/to/File.java newstage/haven/res/path/to/File.java
```

Most files will differ only in the version annotation. Those resources are
pure version bumps: nothing to integrate, and your custom code is unaffected.
Only the files with a real delta need work.


## Step 3: Create a PR Branch

Never commit directly to master. Create a branch for the upgrade:

```
git checkout -b resource-update-YYYY-MM-DD master
```


## Step 4: Apply Updates

### Files With No Upstream Delta

If old-clean and new-clean differ only in the version annotation, there is
nothing to integrate. Bump the annotation in your file and leave everything
else alone. Do **not** copy the staged file over yours — that would wipe your
customizations for no reason.

### Files With an Upstream Delta

Apply only what the old→new diff showed, keeping your custom code. Common
upstream changes include:

- New method parameters or return types
- A method being replaced by a differently-named one
- `UI.scale()` wrapping around hardcoded pixel values
- New imports or removed imports
- Bug fixes in existing logic

Note that upstream *removals* matter as much as additions. If upstream deleted
a method, delete it from your file too, even if it looks like something worth
keeping — leaving it behind means running stale upstream code.

If an upstream change touches a line you have also modified, use judgment: the
upstream change may need to be adapted to work with your modification.

### Verify the Result Mechanically

Let git check your merge rather than trusting a read-through. For each file,
three-way merge with old-clean as the base and compare against what you
actually wrote:

```
git merge-file -p --diff3 src/haven/res/path/to/File.java \
    oldstage/haven/res/path/to/File.java \
    newstage/haven/res/path/to/File.java
```

A clean merge whose output matches your file byte-for-byte means you dropped
nothing and invented nothing. Conflicts are expected where custom code sits
next to a rewritten region — inspect those and confirm your resolution is the
union of both sides.


## Step 5: Fix Version Annotations

After applying all changes, ensure every `@FromResource` annotation matches
the server's current version:

1. The version number must equal what the server reports (the version shown
   by `find-updates` as "remote").
2. All files sharing the same resource name must have the same version.

This is what tells the version override system that your local code is current
and should run instead of the server's code.


## Step 6: Build and Verify

```
ant clean && ant
```

Build clean, not incrementally. Upstream changes frequently add or remove
method overrides, and incremental `javac` does not recompile the subclasses
that such a change breaks.

Fix any compilation errors — upstream changes may have introduced new method
signatures or removed deprecated ones.

Then run `find-updates` again:

```
java -cp bin/hafen.jar haven.Resource find-updates
```

Expected result: no "needs update", no "conflicting versions", no "strangely
newer locally". Only "no longer found" warnings (for retired resources) are
acceptable.

Clean up the staging directories:

```
rm -rf oldres/ oldstage/ newstage/ scratch/
```

Commit, push, open a PR.


## Alternative: Git Merge Approach

For large updates affecting many resources, manually applying changes is
tedious. The `doc/resource-code` Tips & Tricks section describes using a
dedicated branch to let git handle three-way merging automatically.

### Why This Works

Git's three-way merge needs three versions of each file:

| Version | Role | Source |
|---------|------|--------|
| **Base** (merge-base) | The old clean upstream | Previous `get-code` commit on `upstream-resources` |
| **Ours** | Your customized code | Your dev branch |
| **Theirs** | The new clean upstream | New `get-code` commit on `upstream-resources` |

Git computes two diffs — base→ours (your customizations) and base→theirs
(upstream changes) — and combines them. Conflicts only appear where both sides
modified the same lines.

**Without a correct merge-base, this breaks.** Git cannot distinguish "lines
you added" from "lines upstream removed." This leads to silently dropped custom
code or spurious conflicts.

### Setup (First Time)

Create the branch from `origin/master` and fetch ALL currently-used resources:

```
git checkout -b upstream-resources origin/master
java -cp bin/hafen.jar haven.Resource get-code <all-resource-names>
git add src/haven/res
git commit -m "Clean upstream resource baseline"
git checkout master
git merge upstream-resources
```

The first merge may produce add/add conflicts for files that already existed
on master. Resolve them by keeping your customized versions. After this merge,
the merge-base for future updates is the clean upstream commit — exactly what
three-way merge needs.

### Ongoing Updates

```
git checkout upstream-resources
java -cp bin/hafen.jar haven.Resource get-code <resources-that-need-updating>
git add src/haven/res
git commit -m "Update resources: <names>"
git checkout <pr-branch>
git merge upstream-resources
```

Git now has: base = previous clean upstream, ours = your customized code,
theirs = new clean upstream. Three-way merge produces correct, surgical results.

Each merge advances the merge-base to the latest clean upstream commit. No
manual bookkeeping required.

### Fixing a Stale `upstream-resources` Branch

If `upstream-resources` exists but hasn't been merged into your dev branch
in a long time, the merge-base is too old. Git can't properly distinguish
your customizations from upstream changes, and merging will silently drop
custom code.

**Diagnosis:**

```
git merge-base upstream-resources master
git log --oneline -1 <that-commit>
```

If the merge-base is ancient relative to when your customizations were made,
the branch is stale.

**As of this writing the branch is in exactly this state.** Its last two
`get-code` commits were never merged into master, so the merge-base is a plain
code commit rather than a clean-upstream one, and its own files still contain
nurgling code (for example `RosterWindow.java` on that branch still has the
custom animal imports). Do not treat it as a clean baseline until it has been
repaired as below.

**Fix — re-establish the merge-base:**

1. Ensure `upstream-resources` has current clean upstream code (run `get-code`
   for all resources, commit).
2. On your dev branch, run:
   ```
   git merge -s ours upstream-resources
   ```
   This creates a merge commit recording `upstream-resources` as an ancestor
   but keeps all your files unchanged. Your custom code is untouched. But git
   now considers the clean upstream commit a common ancestor.
3. Future merges of `upstream-resources` into your dev branch will use the
   correct merge-base.

**Why `-s ours`:** A normal merge would try to combine the clean upstream
(which lacks your custom code) with your dev branch using the stale merge-base,
and would silently drop customizations. The `ours` strategy skips the file
merge entirely — it only updates the ancestry graph. After this, the clean
upstream commit IS the merge-base, so subsequent normal merges work correctly.


## Common Pitfalls

- **Diffing your tree against new-clean only** — the single most common
  mistake. That diff cannot distinguish your customizations from upstream's
  changes. Anything upstream *removed* looks exactly like something you added,
  so you "preserve" it and silently keep running stale upstream code. Always
  recover old-clean and diff old-clean against new-clean first.
- **Reconstructing old-clean from git history instead of the cache** — the
  files in git are your customized versions, and were customized at import, so
  `git log -S` cannot tell you whether a line was ever upstream's. It gives
  confident wrong answers. Recover old-clean from the client cache instead
  (Step 2).
- **Trusting the cache without checking the version it printed** — if the
  client re-downloaded the resource after the server updated, the cache holds
  new-clean and the diff will show no upstream delta at all, which looks
  exactly like a clean result.
- **Not normalizing line endings** — `get-code` writes platform line endings.
  On Windows, un-normalized staged files diff as 100% changed, burying the
  real delta.
- **Running `get-code` on your dev branch** — destroys custom code. Always use
  a staging directory (`-o newstage`) or a dedicated branch.
- **Committing directly to master** — always use a PR branch.
- **Setting version higher than server** — your code won't run. The version
  override requires an exact match.
- **Forgetting to check all files for a resource** — if a resource has multiple
  files (e.g., `Stack.java` and `StackName.java`), all must have the same
  version.
- **Silently dropped custom code after merge** — always verify after a git
  merge that your custom modifications survived. Search for project-specific
  patterns (`NConfig`, `NGItem`, `nurgling`, etc.) in the merged files.
- **Ignoring "conflicting versions" warnings** — these mean your code is not
  running correctly. Fix immediately.
