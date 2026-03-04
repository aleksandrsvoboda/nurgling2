# Gob Context Menu System

## Overview

A custom flower menu that appears when you **Ctrl+Right-Click** a game object. The menu shows actions specific to the type of gob clicked. Each action can launch a bot with that gob as input.

## Package

All files live in `src/nurgling/contextmenu/`:

| File | Purpose |
|------|---------|
| `GobContextAction.java` | Interface that all context actions implement |
| `GobContextRegistry.java` | Central registry of all context actions |
| `NGobContextMenu.java` | The menu widget (renders like NFlowerMenu) |

The only modification outside this package is in `NMapView.mousedown()` which intercepts Ctrl+Right-Click.

## How It Works

1. **Ctrl+Right-Click** on the map triggers a rendering-based hit test in `NMapView`
2. If a gob was clicked, `GobContextRegistry.getActionsFor(gob)` filters all registered actions by calling `appliesTo(gob)` on each
3. If any actions match, `NGobContextMenu` is shown at the cursor position
4. Clicking an option calls `create(gob)` to get an `Action`, then runs it via `BotExecutor.runAsync()`

## Adding a New Action

### 1. Create a class implementing `GobContextAction`

```java
package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.Arrays;

public class MyTreeAction implements GobContextAction {
    private static final NAlias TREE_ALIAS = new NAlias(
            new java.util.ArrayList<>(Arrays.asList("gfx/terobjs/trees/")),
            new java.util.ArrayList<>(Arrays.asList("log", "trunk", "oldtrunk"))
    );

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, TREE_ALIAS);
    }

    @Override
    public String label() {
        return "My Tree Action";
    }

    @Override
    public Action create(Gob gob) {
        // Return any Action - can be a lambda or an existing bot class
        return gui -> {
            gui.msg("Clicked: " + gob.ngob.name);
            return Results.SUCCESS();
        };
    }
}
```

### 2. Register it in `GobContextRegistry`

Add one line to the static block:

```java
static {
    register(new MyTreeAction());
}
```

That's it.

## The Interface

```java
public interface GobContextAction {
    boolean appliesTo(Gob gob);  // Does this action apply to this gob type?
    String label();               // Text shown in the menu
    Action create(Gob gob);      // Create the Action to run when selected
}
```

### `appliesTo(Gob gob)`

Called for every registered action when the menu opens. Use `NParser.checkName()` with `NAlias` for pattern matching on `gob.ngob.name`, or any other logic:

```java
// Match by resource name pattern
NParser.checkName(gob.ngob.name, new NAlias("gfx/terobjs/trees/"))

// Match containers
NContext.contcaps.containsKey(gob.ngob.name)

// Match animals
NParser.checkName(gob.ngob.name, new NAlias("gfx/kritter/"))

// Match by model attribute
gob.ngob.modelAttribute == 17

// Combine conditions
NParser.checkName(gob.ngob.name, new NAlias("gfx/kritter/"))
    && gob.getattr(Moving.class) == null  // only stationary
```

### `label()`

The display text in the menu. Supports localization via `L10n.get()` if needed.

### `create(Gob gob)`

Returns an `Action` that will be executed via `BotExecutor.runAsync()`. The gob is captured so the action knows which specific object was clicked. Can return:

- A lambda: `gui -> { ...; return Results.SUCCESS(); }`
- An existing bot: `return new Chopper();`
- A bot that takes the gob: `return new SomeBot(gob);`

## Common Gob Resource Name Patterns

| Pattern | What it matches |
|---------|----------------|
| `gfx/terobjs/trees/` | Trees (birch, oak, etc.) |
| `gfx/terobjs/bushes/` | Bushes |
| `gfx/terobjs/herbs/` | Herbs/plants |
| `gfx/kritter/` | Animals/creatures |
| `gfx/terobjs/chest` | Chests |
| `gfx/terobjs/crate` | Crates |
| `gfx/terobjs/barrel` | Barrels |
| `gfx/terobjs/map/` | Localized resources (Jotun Clam, etc.) |
| `gfx/terobjs/vehicle/` | Vehicles |

Use `NAlias` exclusions to filter out subtypes (e.g., exclude "log" and "trunk" from trees).

## Menu Controls

- **Click** an option to run it
- **1-9** keys select options by number
- **ESC** or click outside to close
- **Mouse wheel** scrolls if there are more than 10 options
