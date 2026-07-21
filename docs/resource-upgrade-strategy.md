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


## Step 2: Fetch Clean Upstream Code

Fetch the new server code into a staging directory so you can examine what
changed without destroying anything:

```
java -cp bin/hafen.jar haven.Resource get-code -o staging/src <resource-names>
```

Now diff the staged code against your current code:

```
diff src/haven/res/path/to/File.java staging/src/haven/res/path/to/File.java
```

For each resource, categorize the files:

- **No custom code** — your file is identical to the staged file (minus the
  version number). Safe to overwrite directly.
- **Has custom code** — your file differs from the staged file beyond just the
  version annotation. These need careful merging.

To identify which files have custom modifications, look for project-specific
imports, classes, or patterns (e.g., `NConfig`, `NGItem`, `nurgling` imports).


## Step 3: Create a PR Branch

Never commit directly to master. Create a branch for the upgrade:

```
git checkout -b resource-update-YYYY-MM-DD master
```


## Step 4: Apply Updates

### Files Without Custom Code

For files that have no custom modifications, simply copy the staged version:

```
cp staging/src/haven/res/path/to/File.java src/haven/res/path/to/File.java
```

Or run `get-code` directly into `src/`:

```
java -cp bin/hafen.jar haven.Resource get-code <resource-name>
```

### Files With Custom Code

These require understanding what upstream actually changed so you can apply
only those changes while preserving your modifications.

**Examine the upstream diff.** You need to know what the server changed between
the old version and the new version. The staged file is the new version. To get
the old clean version, use one of:

- The `upstream-resources` branch (if maintained — see "Git Merge Approach"
  below)
- Git history (`git log --follow src/haven/res/path/to/File.java`)
- A previous staging directory snapshot

Diff old clean vs new clean to isolate the upstream changes:

```
diff old-clean/File.java staging/src/haven/res/path/to/File.java
```

**Apply upstream changes manually.** Edit your customized file, incorporating
only the upstream modifications. Common upstream changes include:

- New method parameters or return types
- `UI.scale()` wrapping around hardcoded pixel values
- New imports or removed imports
- Bug fixes in existing logic

Leave all custom code untouched. If an upstream change touches a line you've
also modified, use judgment: the upstream change may need to be adapted to
work with your modification.


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
ant
```

Fix any compilation errors — upstream changes may have introduced new method
signatures or removed deprecated ones.

Then run `find-updates` again:

```
java -cp bin/hafen.jar haven.Resource find-updates
```

Expected result: no "needs update", no "conflicting versions", no "strangely
newer locally". Only "no longer found" warnings (for retired resources) are
acceptable.

Clean up the staging directory:

```
rm -rf staging/
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

- **Running `get-code` on your dev branch** — destroys custom code. Always use
  a staging directory (`-o staging/src`) or a dedicated branch.
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
