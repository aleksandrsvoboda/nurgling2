# Nurgling-Modified Haven Files

This document lists all haven package files that contain nurgling-specific code. These files require special attention during hafen integrations.

**Last Updated:** 2026-02-27
**Branch:** hafen-integration-proper-v2

## Summary

- **Total haven files modified:** 107
- **Critical files (must handle carefully):** 14
- **UI extensions:** 93

---

## Critical Files (High Priority)

These files have essential nurgling customizations that must be preserved during hafen integrations.

### Material System (3 files)

**src/haven/Material.java**
- **Changes:** MaterialFactory integration, get(int mask) method
- **Purpose:** Custom materials for container status colors
- **Conflict strategy:** Merge hafen's architecture + nurgling's get(int mask)
- **Key code:**
  ```java
  import nurgling.tools.MaterialFactory;

  private transient HashMap<MaterialFactory.Status, Material> hm = new HashMap<>();

  public Material get(int mask) {
      // Custom material resolution using MaterialFactory
  }
  ```

**src/haven/ModSprite.java**
- **Changes:** customMask forcing for dframes/barrels (~25 lines)
- **Purpose:** Ensures material system works with ModSprite rendering
- **Conflict strategy:** Usually no conflicts (pure addition)
- **Key code:**
  ```java
  // Force customMask for dframes/barrels even if name is null
  if (gob != null && gob.ngob != null && !hasCustomMask) {
      if (resName.contains("gfx/terobjs/dframe") ||
          resName.contains("gfx/terobjs/barrel")) {
          gob.ngob.customMask = true;
          if (gob.ngob.name == null) {
              gob.ngob.name = resName;
          }
          hasCustomMask = true;
      }
  }
  ```

**src/haven/StaticSprite.java**
- **Changes:** customMask forcing for barrels/dframes (~30 lines)
- **Purpose:** Material system integration for static sprites
- **Conflict strategy:** Usually no conflicts (pure addition)
- **Key code:**
  ```java
  boolean needsCustomMat = (res.name.contains("gfx/terobjs/barrel") ||
                           res.name.contains("gfx/terobjs/dframe"));

  if (needsCustomMat && owner instanceof RecOwner && ...) {
      rd.gob.ngob.customMask = true;
      maskValue = rd.gob.ngob.mask();
  }
  ```

### Game Logic Core (3 files)

**src/haven/Gob.java** ⚠️ CRITICAL
- **Changes:** `public NGob ngob;` field + initialization
- **Purpose:** Foundation for all nurgling bot features
- **Conflict strategy:** Must preserve NGob field and initialization
- **Key code:**
  ```java
  public NGob ngob;

  // In constructor or initialization:
  ngob = new NGob(this);
  ```

**src/haven/Inventory.java**
- **Changes:** NInventory integration, ItemWatcher cache invalidation
- **Purpose:** Inventory monitoring and automation
- **Conflict strategy:** Merge carefully, preserve nurgling hooks
- **Key code:**
  ```java
  import nurgling.NInventory;

  if(ni.parentGob != null && ni.parentGob.ngob != null &&
     ni.parentGob.ngob.hash != null) {
      monitoring.ItemWatcher.invalidateContainerCache(ni.parentGob.ngob.hash);
  }
  ```

**src/haven/Session.java** ⚠️ CRITICAL
- **Changes:**
  - `CachedRes` class made public
  - `injectMessage()` method
  - `res_id_cache` map
- **Purpose:** Multi-session support, resource name caching
- **Conflict strategy:** Accept hafen changes, then restore nurgling extensions
- **Key code:**
  ```java
  // Make public (hafen makes it private)
  public static class CachedRes {
      public String resnm = null;  // hafen makes this private

      public class Ref implements Indir<Resource> {
          public String resnm() {  // hafen removes this
              return CachedRes.this.resnm;
          }
      }
  }

  // Nurgling extension for multi-session
  private volatile PMessage injectedMessage = null;
  public void injectMessage(PMessage msg) {
      synchronized(uimsgs) {
          injectedMessage = msg;
          uimsgs.notifyAll();
      }
  }

  // Resource name cache
  final Map<Integer, String> res_id_cache = new TreeMap<>();
  public String getResName(Integer id) {
      synchronized (res_id_cache) {
          return res_id_cache.get(id);
      }
  }
  ```

### Resource System (2 files)

**src/haven/ResDrawable.java**
- **Changes:** NGob integration for drawable resources
- **Purpose:** Links rendered objects to nurgling extensions
- **Conflict strategy:** Usually minimal conflicts

**src/haven/Resource.java**
- **Changes:** ~3 lines with nurgling references
- **Purpose:** Resource loading hooks
- **Conflict strategy:** Usually minimal conflicts

### Other Game Logic (6 files)

**src/haven/GItem.java**
- Purpose: Item tracking and analysis

**src/haven/GobHealth.java**
- Purpose: Health tracking for bots

**src/haven/GobIcon.java**
- Purpose: Icon/status recognition

**src/haven/ItemDrag.java**
- Purpose: Item interaction hooks

**src/haven/ItemInfo.java**
- Purpose: Extended item information

**src/haven/WItem.java**
- Purpose: Widget item extensions

---

## UI Extensions (93 files)

These files have nurgling UI integrations. Most are low-risk for conflicts.

### Core UI Files

**src/haven/GameUI.java**
- autoMapper integration, NUtils references
- Usually minimal conflicts

**src/haven/MapView.java**
- Map overlays, NMapView, grid checking
- May have conflicts in rendering code

**src/haven/MainFrame.java**
- Headless mode, session management
- Usually minimal conflicts

**src/haven/LoginScreen.java**
- Login automation hooks
- Usually minimal conflicts

**src/haven/FlowerMenu.java**
- Custom menu interactions
- Usually minimal conflicts

### Complete UI File List (93 files)

```
src/haven/Area.java
src/haven/AuthClient.java
src/haven/BAttrWnd.java
src/haven/Bootstrap.java
src/haven/BuddyWnd.java
src/haven/Bufflist.java
src/haven/Button.java
src/haven/Cal.java
src/haven/CharWnd.java
src/haven/Charlist.java
src/haven/ChatUI.java
src/haven/Equipory.java
src/haven/FightWnd.java
src/haven/Fightsess.java
src/haven/Fightview.java
src/haven/FlowerMenu.java
src/haven/Following.java
src/haven/GLPanel.java
src/haven/GameUI.java
src/haven/Glob.java
src/haven/IMeter.java
src/haven/ISBox.java
src/haven/Img.java
src/haven/LinMove.java
src/haven/LoginScreen.java
src/haven/MCache.java
src/haven/MainFrame.java
src/haven/Makewindow.java
src/haven/MapFile.java
src/haven/MapMesh.java
src/haven/MapSource.java
src/haven/MapView.java
src/haven/MapWnd.java
src/haven/MenuGrid.java
src/haven/MiniMap.java
src/haven/Moving.java
src/haven/NFightsess.java
src/haven/OCache.java
src/haven/OptWnd.java
src/haven/Outlines.java
src/haven/PView.java
src/haven/Partyview.java
src/haven/QuestWnd.java
src/haven/RemoteUI.java
src/haven/RootWidget.java
src/haven/SAttrWnd.java
src/haven/SkelSprite.java
src/haven/SkillWnd.java
src/haven/Speedget.java
src/haven/Text.java
src/haven/TextEntry.java
src/haven/TileHighlight.java
src/haven/UI.java
src/haven/Window.java
src/haven/WoundWnd.java
src/haven/render/RenderTree.java
src/haven/res/gfx/fx/bottle/Bottle.java
src/haven/res/gfx/fx/cavewarn/Cavein.java
src/haven/res/gfx/fx/clouds/Clouds.java
src/haven/res/gfx/fx/dragon/Dragon.java
src/haven/res/gfx/fx/floatimg/Score.java
src/haven/res/gfx/fx/lucy/Lucy.java
src/haven/res/gfx/fx/shroomed/Shroomed.java
src/haven/res/gfx/hud/rosters/cow/Ochs.java
src/haven/res/gfx/hud/rosters/goat/Goat.java
src/haven/res/gfx/hud/rosters/horse/Horse.java
src/haven/res/gfx/hud/rosters/pig/Pig.java
src/haven/res/gfx/hud/rosters/sheep/Sheep.java
src/haven/res/gfx/hud/rosters/teimdeer/Teimdeer.java
src/haven/res/gfx/terobjs/barterarea/BarterArea.java
src/haven/res/gfx/terobjs/consobj/Consobj.java
src/haven/res/lib/plants/GrowingPlant.java
src/haven/res/lib/vmat/VarSprite.java
src/haven/res/ui/croster/CattleId.java
src/haven/res/ui/croster/CattleRoster.java
src/haven/res/ui/locptr/Pointer.java
src/haven/res/ui/obj/buddy/Buddy.java
src/haven/res/ui/rchan/RealmChannel.java
src/haven/res/ui/stackinv/ItemStack.java
src/haven/res/ui/surv/LandSurvey.java
src/haven/res/ui/tt/cn/CustomName.java
src/haven/res/ui/tt/curio/Fac.java
src/haven/res/ui/tt/drying/Drying.java
src/haven/res/ui/tt/expire/Expiring.java
src/haven/res/ui/tt/food/Fac.java
src/haven/res/ui/tt/q/quality/Quality.java
src/haven/res/ui/tt/slot/Slotted.java
src/haven/res/ui/tt/slots/ISlots.java
src/haven/res/ui/tt/stackn/Stack.java
src/haven/resutil/CSprite.java
src/haven/resutil/Curiosity.java
src/haven/resutil/Ridges.java
src/haven/resutil/TerrainTile.java
src/haven/resutil/WaterTile.java
```

---

## Conflict Resolution Priority

When doing a hafen integration, resolve conflicts in this order:

### Priority 1: Critical Files (Must Get Right)
1. **Gob.java** - NGob field is foundation
2. **Session.java** - Multi-session support
3. **Material.java** - MaterialFactory integration

### Priority 2: Material System
4. **ModSprite.java** - Container colors
5. **StaticSprite.java** - Container colors

### Priority 3: Game Logic
6. **Inventory.java** - Inventory monitoring
7. **GItem.java**, **ItemInfo.java**, etc.

### Priority 4: UI Files
8. **GameUI.java**, **MapView.java** - Core UI
9. All other UI files - Usually low risk

---

## How to Check if a File Needs Attention

```bash
# Check if a file has nurgling code
grep -l "nurgling\|customMask\|MaterialFactory\|NGob\|NUtils" src/haven/Material.java

# See what nurgling changed in a file
git diff origin/master HEAD -- src/haven/Material.java | grep "^+" | grep -i nurgling

# List all files with nurgling references
git diff origin/master HEAD --name-only | \
  xargs grep -l "nurgling\|customMask\|MaterialFactory" 2>/dev/null
```

---

## Maintenance Notes

- **Add new files here** when nurgling customizations are added to new haven files
- **Remove files** if nurgling customizations are refactored out
- **Update conflict strategies** based on actual integration experience
- **Document new patterns** that emerge during integrations

---

**See Also:**
- `docs/hafen-integration-guide.md` - Step-by-step integration process
- `docs/material-system.md` - Material system architecture
- `docs/container-status-colors.md` - Container color feature details
