# Snapshot types

Ops return plain data. In Python every snapshot is a `dict` with attribute access (`gob.name == gob["name"]`). Pass a snapshot, or just its id, back to any op that takes that type.

## Gob

A game object in the world (tree, animal, container, player...). A snapshot; call gobs.get to refresh.

| Field | Type | Description |
|---|---|---|
| `gob` | `int` | Handle: the object's id. Pass the whole snapshot or this id to ops taking a Gob. |
| `name` | `str\|null` | Resource name, e.g. "gfx/terobjs/trees/oak". Null while loading. |
| `hash` | `str\|null` | Stable identity that survives reloads (position + resource). |
| `rc` | `Coord` | World position. |
| `a` | `float` | Facing angle in radians. |
| `pose` | `str\|null` | Current pose resource, e.g. "gfx/borka/idle" (the first of `poses`). |
| `poses` | `list[str]` | All pose resources playing at once (composite objects can have several). |
| `attr` | `int` | Model attribute: growth stage for crops and trees, fill flags for containers; -1 if none. |
| `overlays` | `list[str]` | Resource names of overlays (fire, contents, markers...). |
| `dist` | `float\|null` | Distance from the player when the snapshot was taken. |

## Item

An inventory item. A snapshot; its handle stays valid while the item widget exists.

| Field | Type | Description |
|---|---|---|
| `item` | `int` | Handle: the item widget id. |
| `name` | `str\|null` | Display name, e.g. "Block of Birch". |
| `res` | `str\|null` | Resource name, e.g. "gfx/invobjs/wblock-birch". |
| `q` | `float\|null` | Quality. |
| `num` | `int` | Number shown on the item (stack amount, uses), -1 if none. |
| `contents` | `list[dict]` | Contents of containers (bucket, waterskin): [{name, q}]. |
| `stack` | `int` | Number of items in this item stack, 0 if it is not a stack. |
| `slot` | `Tile\|null` | Inventory cell [x, y] of the item's top-left corner. |
| `size` | `Tile\|null` | Size in inventory cells [w, h]. |
| `parent` | `int\|null` | Widget id of the inventory or stack holding it. |

## Inventory

An open inventory (the player's, or a container's).

| Field | Type | Description |
|---|---|---|
| `wdg` | `int` | Handle: the inventory widget id. |
| `window` | `str\|null` | Caption of the window holding it. |
| `size` | `Tile\|null` | Size in cells [w, h]. |

## Widget

Any UI element. Windows add a caption.

| Field | Type | Description |
|---|---|---|
| `wdg` | `int` | Handle: the widget id (server-assigned; -1 for client-only widgets). |
| `class` | `str` | Java class name. |
| `caption` | `str` | Window caption (windows only). |
| `parent` | `int\|null` | Parent widget id. |
| `visible` | `bool` | Whether it is shown. |
| `c` | `Tile` | Position inside the parent, in UI pixels. |
| `sz` | `Tile` | Size in UI pixels. |

## Area

A saved area (zone) with its specialisations.

| Field | Type | Description |
|---|---|---|
| `area` | `int` | Handle: the area id. |
| `name` | `str` | Area name. |
| `specs` | `list[dict]` | Specialisations: [{name, subtype?}]. |
| `rect` | `Rect\|null` | World rectangle; null if the area's part of the map is not loaded. |
| `in` | `list[dict]` | Items configured to be taken from here. |
| `out` | `list[dict]` | Items configured to be put here. |

## Coord

A world position [x, y] in world units (11 units per tile).

## Tile

An integer pair [x, y]: a tile, an inventory cell, or a size.

## Rect

A world rectangle [[x1, y1], [x2, y2]].

## Alias

A name matcher: "text", ["a", "b"], or {"include": [...], "exclude": [...]}. Matches substrings of resource and display names, like the client's NAlias.
