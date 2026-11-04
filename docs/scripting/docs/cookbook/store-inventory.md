# Store your inventory in its areas

Puts everything you carry away into the areas set up for it, then optionally fetches an item. This
uses the same area and specialisation setup as the built-in bots, so set up your storage areas first
(the items each area takes, or a specialisation).

```python
--8<-- "store_inventory.py"
```

How it works:

- `areas.for_item(name, "out")` lists the areas configured to take an item. The script uses it to
  warn about items that have nowhere to go; they stay in your inventory.
- `actions.free_inventory()` does the work: it walks to each area that is needed, puts the items into
  its containers or piles, and returns when done.
- `actions.take_items(name, count)` fetches from the areas that store the item until you carry
  `count` of them, counting what you already have, and returns the number you now carry.
- `fetch` has an empty default, so the start form does not insist on it.
