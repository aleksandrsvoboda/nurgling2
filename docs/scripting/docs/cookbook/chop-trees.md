# Chop nearby trees

Chops the nearest grown trees one after another and drinks when stamina runs low. Needs an axe in
your hands and water in a waterskin or other vessel.

```python
--8<-- "chop_trees.py"
```

How it works:

- **Finding trees.** All trees are under `gfx/terobjs/trees/`; the alias excludes stumps and logs,
  which live there too. `exclude` takes the ids of trees the script gave up on, so it doesn't pick
  them again.
- **Chopping.** `actions.flower(tree, "Chop")` right-clicks the tree and picks *Chop*; the server
  walks the character there. It returns as soon as the option is chosen, so the script then waits for
  the chopping pose to start (`wait.pose`) and to end (`wait.pose_not`).
- **Why chopping stopped.** When the pose ends, either the tree is down (`gobs.get` returns `None`,
  or the object is now a stump) or stamina ran out. In the second case the script drinks with
  `actions.drink` and chops on.
- **Errors.** A sapling has no *Chop* (`NotFound`), a tree behind a fence is unreachable
  (`PathNotFound`), a tree can vanish (`GobGone`): those are skipped. `ActionFailed` from `drink`
  (nothing left to drink) ends the script. `Stopped` is not caught, so the stop button works.
