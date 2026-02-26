# Hafen Upstream Integration - February 2026

## Integration Date
February 26, 2026

## Summary
Successfully integrated 25 commits from hafen/master (upstream Haven & Hearth client) into Nurgling2, bringing major refactoring of the material and resource systems.

## Upstream Changes Integrated

### New Files Added
- `src/haven/Maybe.java` - Alternative to Optional with better error preservation
- `src/haven/PType.java` - New parameter type system for parsing

### Major Refactorings
1. **Material System Refactoring**
   - Replaced Resolver-based material construction with new Buffer/Spec system
   - Introduced `Material.Spec` interface for material part construction
   - Added `Material.Buffer` class for building materials
   - Simplified material loading and construction process

2. **Resource Loading**
   - Added HTTP compression support for resource fetches (`Http.java`)
   - Added remote resource access point through Session (`Session.pool()`)
   - Improved resource virtual construction

3. **Utility Improvements**
   - Refactored `Utils.java` to use new PType system
   - Cleaned up material and texture parsing code
   - Better error handling with Maybe<T>

### Files Modified
- `src/haven/DynresWindow.java` - Resource window updates
- `src/haven/KeywordArgs.java` - Argument parsing improvements
- `src/haven/Light.java` - Lighting system updates
- `src/haven/Material.java` - Complete material system refactoring
- `src/haven/QuestWnd.java` - Quest window updates to use PTypes
- `src/haven/RenderedNormals.java` - Normal rendering updates
- `src/haven/Resource.java` - Resource loading improvements
- `src/haven/Session.java` - Added remote resource pool access
- `src/haven/ShadowMap.java` - Shadow map updates
- `src/haven/TexRender.java` - Texture rendering refactoring
- `src/haven/Utils.java` - Utility method cleanup with PTypes
- `src/haven/resutil/BumpMap.java` - Bump mapping updates
- `src/haven/resutil/EnvMap.java` - Environment mapping updates
- `src/haven/resutil/LatentMat.java` - Latent material updates
- `src/haven/resutil/OverTex.java` - Texture overlay updates
- `src/haven/resutil/TexAnim.java` - Texture animation updates
- `src/haven/resutil/TexPal.java` - Texture palette updates
- `src/haven/rs/AvaRender.java` - Avatar rendering updates

## Merge Conflicts Resolved

### 1. Material.java (5 conflicts)
**Resolution Strategy:** Adopted Hafen's new Buffer/Spec system as primary, with compatibility stubs for Nurgling

**Changes:**
- Added both `import nurgling.tools.MaterialFactory;` and `import static haven.PType.*;`
- Kept Hafen's new `Material.Spec` and `Material.Buffer` system
- Added stub `Material.Res.Resolver` interface for backward compatibility
- Added stub `get(int mask)` method that calls `get()` (MaterialFactory integration TODO)
- Removed Nurgling's old Resolver-based material construction system

**Impact:**
- ✅ Core material loading now uses Hafen's efficient new system
- ⚠️ MaterialFactory custom material features temporarily disabled (needs reimplementation)
- ⚠️ CustomResolver pattern no longer functional (needs adaptation to new system)

### 2. QuestWnd.java (1 conflict)
**Resolution:** Kept both imports
- `import nurgling.i18n.L10n;`
- `import static haven.PType.*;`

### 3. Session.java (1 conflict)
**Resolution:** Added Hafen's new `pool()` method, kept `CachedRes` as public
- Added `public Resource.Pool pool()` returning `Resource.remote()`
- Kept `Session.CachedRes` as public (Nurgling's NGob.java and NRelation.java use it)

### 4. TexRender.java (1 conflict)
**Resolution:** Adopted Hafen's new Spec-based `$tex` class
- Uses new `Material.Spec` interface
- Simplified texture rendering with direct Buffer manipulation
- Added support for `clip` parameter in KeywordArgs
- Uses `flayer()` instead of `layer()` for resource access

## Known Limitations & TODOs

### 1. MaterialFactory Integration
**Status:** Temporarily disabled
**Issue:** MaterialFactory's custom material system was built on the old Resolver pattern
**TODO:** Reimplement MaterialFactory to work with new Buffer/Spec system
```java
// Current stub in Material.Res:
public Material get(int mask) {
    // TODO: Reimplement MaterialFactory integration with new Buffer/Spec system
    return get();
}
```

### 2. CustomResolver Pattern
**Status:** Interface exists but not functional
**Issue:** CustomResolver extended the old Resolver pattern for texture overrides
**TODO:** Create new pattern compatible with Spec.cons(Buffer) method

### 3. Affected Features
The following Nurgling features may be impacted:
- Custom texture resolution in materials
- Material status-based variants (MaterialFactory.Status)
- Dynamic material construction based on game state

## Build Verification
✅ Build successful with ant
✅ All Java compilation completed
✅ Resource processing completed (with expected resource warnings)
⚠️ 52 deprecation warnings (mostly for deprecated Context usage)

## Testing Recommendations

1. **Basic Functionality**
   - Test that game loads and renders correctly
   - Verify materials appear correct
   - Check texture rendering

2. **Nurgling Features**
   - Test bot actions that don't rely on MaterialFactory
   - Verify inventory and container operations work
   - Check pathfinding and navigation

3. **Features Needing Attention**
   - MaterialFactory-dependent features
   - Custom material variants
   - Any code using Material.Res.Resolver

## Branch Information
- Integration branch: `hafen-upstream-integration-2026-02`
- Based on: `master` (c3bb78e3f - fix/tooltip fixes elixir)
- Integrated: `hafen/master` (d58dcb242 - Remove the implicit virtual resource constructor)
- Commits integrated: 25

## Next Steps
1. Merge integration branch to master if testing successful
2. Reimplement MaterialFactory for new Buffer/Spec system
3. Update CustomResolver pattern for new architecture
4. Test all Nurgling bot features thoroughly
5. Update documentation for material customization patterns

## References
- Hafen commit range: `36ef7d609..d58dcb242`
- Key hafen commits:
  - `8b6ebd7a4` - Add PType utility
  - `d5be5fd35` - Add Maybe as alternative to Optional
  - `b5886d812` - Use PTypes for material and texture parsing
  - `6d404b952` - Allow loading material specs from resources
  - `59be6a258` - Request HTTP compression for resource fetches
