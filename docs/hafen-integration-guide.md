# Hafen Integration Guide

## Overview

This document explains how to properly integrate upstream hafen changes into nurgling2 while preserving nurgling's custom functionality.

## Critical Principle

**ALWAYS use git merge, NEVER cherry-pick or rebase hafen commits.**

### Why Merge?

- **Preserves git ancestry**: Future `git merge` commands will know which hafen commits are already integrated
- **Prevents duplicate commits**: Cherry-picking creates new commit SHAs, causing git to re-apply the same changes
- **Maintains history**: Shows the relationship between hafen and nurgling branches

### What Happens With Cherry-Picking (WRONG)

```bash
# DON'T DO THIS:
git cherry-pick <hafen-commits>
```

Result:
- Creates new commits with different SHAs
- Git doesn't know these are hafen commits
- Next integration will try to re-apply the same changes
- Merge conflicts on already-integrated code

## Integration Process

### Step 1: Prepare

```bash
# Ensure you're on master or create a new integration branch
git checkout master  # or: git checkout -b hafen-integration-YYYY-MM

# Fetch latest hafen changes
git fetch hafen
```

### Step 2: Check What's New

```bash
# See how many commits hafen is ahead
git log --oneline origin/master..hafen/master

# See what files changed (excluding resources)
git diff --stat origin/master hafen/master -- "*.java"

# Expected: ~20-30 commits, ~20-25 Java files for a typical integration
```

### Step 3: Merge (Not Cherry-Pick!)

```bash
# Merge hafen/master into your branch
git merge hafen/master

# This will likely cause merge conflicts in nurgling-customized files
```

### Step 4: Resolve Conflicts

The following files typically have conflicts:

#### Critical Files With Nurgling Customizations

**src/haven/Material.java**
- **Nurgling changes**: `Material.Res.get(int mask)` method, `HashMap<MaterialFactory.Status, Material> hm`
- **Resolution strategy**:
  - Keep hafen's `Buffer cons` field
  - Keep nurgling's `HashMap hm` field
  - Adapt `get(int mask)` to use hafen's new Buffer/Spec system
  - Example from working integration at commit 57d9570b2

**src/haven/Session.java**
- **Nurgling changes**:
  - `CachedRes` class made public (hafen made it private)
  - `injectMessage()` method for multi-session support
  - `res_id_cache` map and `getResName()` method
- **Resolution strategy**:
  - Accept hafen's changes but restore nurgling extensions
  - Make `CachedRes` and `Ref` public again
  - Add back `injectMessage()` method
  - Add back `res_id_cache` functionality
  - Example at commit 51a5a5f4e

**src/haven/QuestWnd.java**
- **Nurgling changes**: Usually minimal
- **Resolution strategy**: Take hafen's version (--theirs)

**src/haven/TexRender.java**
- **Nurgling changes**: Usually minimal
- **Resolution strategy**: Take hafen's version (--theirs)

### Step 5: Add Nurgling Customizations

After resolving conflicts, ensure these nurgling-specific files are included:

**Critical Customizations:**

1. **src/haven/ModSprite.java**
   - Purpose: Container status color system
   - Changes: customMask forcing logic for dframes/barrels
   - Lines: ~25 lines added in `Meshes.operate()` method

2. **src/haven/StaticSprite.java**
   - Purpose: Container status color system
   - Changes: customMask forcing logic for barrels/dframes
   - Lines: ~30 lines added in `lsparts()` method

3. **src/nurgling/NGob.java**
   - Purpose: Core nurgling game object extensions
   - No hafen conflicts (purely nurgling code)

4. **src/nurgling/tools/MaterialFactory.java**
   - Purpose: Provides custom materials for container status colors
   - No hafen conflicts (purely nurgling code)

### Step 6: Build and Test

```bash
# Build to check for compilation errors
ant clean compile

# Common compilation errors after hafen integration:
# - Session.CachedRes access issues → ensure CachedRes is public
# - Material.Res changes → check get(int mask) method
# - Missing imports → hafen may have reorganized packages
```

### Step 7: Commit the Merge

```bash
git add -A
git commit -m "Merge hafen/master - integrate upstream changes

- Merged X commits from hafen/master
- Resolved conflicts in Material.java, Session.java, etc.
- Preserved nurgling's MaterialFactory integration
- Preserved container status color system
- Updated to hafen's new [describe major change, e.g., PType system]"
```

### Step 8: Verify History

```bash
# Verify hafen commits are in ancestry
git log --oneline --graph --decorate -20

# Should show merge commit with two parents:
# * <hash> (HEAD) Merge hafen/master
# |\
# | * <hash> (hafen/master) <hafen commit>
# | * <hash> <hafen commit>
# * | <hash> <nurgling commit>

# Test that hafen commits are ancestors
git merge-base --is-ancestor <hafen-commit-sha> HEAD
# Should exit with code 0 (success)
```

## Files With Nurgling Customizations

### Material System (3 files)
- `src/haven/Material.java` - MaterialFactory integration
- `src/haven/ModSprite.java` - customMask forcing
- `src/haven/StaticSprite.java` - customMask forcing

### Game Logic (9 files)
- `src/haven/Gob.java` - `public NGob ngob` field
- `src/haven/GItem.java` - Item tracking
- `src/haven/Inventory.java` - Inventory monitoring
- `src/haven/GobHealth.java` - Health tracking
- `src/haven/GobIcon.java` - Icon recognition
- `src/haven/ItemDrag.java` - Item interaction
- `src/haven/ItemInfo.java` - Extended item info
- `src/haven/WItem.java` - Widget extensions
- `src/haven/res/ui/stackinv/ItemStack.java` - Stack handling

### Resource Hooks (2 files)
- `src/haven/ResDrawable.java` - NGob integration
- `src/haven/Resource.java` - Resource loading hooks

### Session/Networking (1 file)
- `src/haven/Session.java` - Multi-session support, CachedRes access

### UI Extensions (93 files)
See `docs/nurgling-ui-modifications.md` for complete list.

## Common Pitfalls

### ❌ WRONG: Cherry-picking hafen commits

```bash
git cherry-pick <hafen-commit>  # DON'T DO THIS
```

**Problem**: Creates duplicate commits, breaks future integrations

### ❌ WRONG: Using --theirs for all conflicts

```bash
git merge hafen/master
git checkout --theirs .  # DON'T DO THIS
git commit
```

**Problem**: Loses all nurgling customizations

### ❌ WRONG: Forgetting to verify ancestry

```bash
git merge hafen/master
# resolve conflicts
git commit
# DONE! ...but is hafen actually in the ancestry?
```

**Problem**: May have accidentally created orphan commits

### ✅ CORRECT: Selective conflict resolution

```bash
git merge hafen/master
# Material.java: carefully merge nurgling + hafen
# Session.java: restore nurgling extensions on top of hafen
# QuestWnd.java: take hafen's version
git commit
# Verify ancestry with: git merge-base --is-ancestor <hafen-commit> HEAD
```

## Conflict Resolution Strategies

### Strategy 1: Take Hafen + Add Nurgling (Material.java)

```bash
# 1. Accept hafen's new architecture
git show hafen/master:src/haven/Material.java > src/haven/Material.java

# 2. Add nurgling's get(int mask) method
# Edit Material.java to add:
#   - private transient HashMap<MaterialFactory.Status, Material> hm
#   - public Material get(int mask) { ... }

# 3. Import MaterialFactory
# Add: import nurgling.tools.MaterialFactory;
```

### Strategy 2: Take Hafen + Restore Extensions (Session.java)

```bash
# 1. Accept most of hafen's changes
git checkout --theirs src/haven/Session.java

# 2. Manually restore nurgling extensions:
#   - Change "private static class CachedRes" → "public static class CachedRes"
#   - Add injectMessage() method
#   - Add res_id_cache field and getResName() method
```

### Strategy 3: Take Hafen Completely (QuestWnd.java)

```bash
# Simple case: no nurgling customizations needed
git checkout --theirs src/haven/QuestWnd.java
```

## Verification Checklist

After integration, verify:

- [ ] Build succeeds: `ant clean compile`
- [ ] Hafen commits in ancestry: `git merge-base --is-ancestor <hafen-commit> HEAD` → exit 0
- [ ] Container colors work: barrels/dframes/ttubs show correct status colors
- [ ] MaterialFactory integration intact: `grep -r "MaterialFactory" src/haven/Material.java`
- [ ] Session extensions present: `grep -r "injectMessage\|CachedRes" src/haven/Session.java`
- [ ] NGob field exists: `grep "public NGob ngob" src/haven/Gob.java`
- [ ] No duplicate hafen commits: `git log --oneline --all | grep "Add PType utility" | wc -l` → should be 1

## Quick Reference Commands

```bash
# Check hafen commits not yet integrated
git log --oneline origin/master..hafen/master

# See file changes (Java only)
git diff --stat origin/master hafen/master -- "*.java"

# Merge hafen
git merge hafen/master

# During conflicts - see what each side changed
git diff HEAD:src/haven/Material.java hafen/master:src/haven/Material.java

# Take hafen's version of a file
git checkout --theirs src/haven/QuestWnd.java

# Take nurgling's version of a file
git checkout --ours src/haven/Material.java

# Verify ancestry after merge
git merge-base --is-ancestor $(git rev-parse hafen/master) HEAD && echo "✓ Ancestry verified"

# See merge commit structure
git log --oneline --graph --decorate -20
```

## Example: Actual Integration (Feb 2026)

Branch: `hafen-integration-proper-v2`
Merge commit: `57d9570b2`

**What was integrated:**
- 20 hafen commits (PType, Maybe, Material refactor)
- 21 Java files from hafen
- Preserved nurgling customizations in 7 additional files

**Conflicts resolved:**
- Material.java: Merged Buffer/Spec system + get(int mask)
- Session.java: Restored public CachedRes + injectMessage()
- QuestWnd.java: Took hafen's version
- TexRender.java: Took hafen's version

**Result:**
- ✅ Build successful
- ✅ Hafen commits in ancestry
- ✅ Container status colors working
- ✅ All nurgling features preserved

**Command used:**
```bash
git checkout origin/master -b hafen-integration-proper-v2
git merge hafen/master
# resolve conflicts
git commit -m "Merge hafen/master into nurgling2 - proper integration with commit history"
```

## Future Integrations

For the next hafen integration:

1. Follow this guide exactly
2. Use `git merge hafen/master` (not cherry-pick!)
3. Resolve conflicts using the strategies above
4. Build and test thoroughly
5. Verify ancestry with `git merge-base --is-ancestor`
6. Update this document if new patterns emerge

## References

- Hafen upstream: https://github.com/dolda2000/hafen-client
- Nurgling2: https://github.com/Katodiy/nurgling2
- Material system details: `docs/material-system.md`
- Container status colors: `docs/container-status-colors.md`

---

**Last Updated:** 2026-02-27
**Last Integration:** hafen-integration-proper-v2 (57d9570b2)
**Hafen Commits Integrated:** 20 commits through d58dcb242
