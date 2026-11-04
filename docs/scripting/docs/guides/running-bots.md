# Running the client's bots (layer 1)

The client has more than two hundred bots. A script can run any of them, wait for it to finish and
decide what to do next. This is the quickest way to automate a routine: the hard parts (pathfinding,
containers, stockpiles, your areas) are already solved inside the bot.

## Finding a bot

The [bot catalogue](../reference/bot-catalogue.md) lists every built-in bot with its id and parameters. Ask the
client for the same list at runtime, which also includes your own scripts:

```python
for b in nc.bots.list():
    print(b["id"], "-", b["title"])

print(nc.bots.get("goto_area"))
```

Each entry has:

| Field | Meaning |
|---|---|
| `id` | What you pass to `bots.run`. Stable across client versions. |
| `title`, `description` | As shown in the bot menu (in your client language). |
| `type`, `group` | The bot menu folder it lives in. |
| `menu`, `scenario_step` | Whether it is in the bot menu, and whether it can be a scenario step. |
| `interactive` | The bot stops to ask you something (an area, a target, a settings window). |
| `params` | Declared parameters: `name`, `type`, `doc`, `required`, `default`, `values`. Most bots have none. |

## Running a bot

```python
ok = nc.bots.run("goto_area", params={"areaId": 12})
if not ok:
    nc.msg("Could not get there")
    return False
```

`bots.run` runs the bot **on your script's bot thread** and returns when the bot is done:

- It returns `True` if the bot reported success and `False` if it reported failure. A failing bot
  does not raise; check the result.
- The bot behaves exactly as when you start it from the menu: it reads your areas and
  specialisations, opens its own dialogs, and uses the inventory and equipment you have.
- Every call creates a fresh instance of the bot, so nothing carries over from a previous run.
- While the bot runs, the script waits. Calls from other Python threads on the same run queue up
  behind it.
- The script's stop button stops the bot too. The script then gets `Stopped`.

### Parameters

Most bots take no parameters: they read their configuration from your areas, specialisations and
settings, as they do from the menu. Set those up in the game first.

For bots that declare parameters (the catalogue's *Parameters* column), `params` is checked before
the bot starts: unknown names, missing required values and wrong types raise `BadArgs`. A bot that
declares none gets `params` passed through unchecked, as the settings a scenario step would give it.
`goto_area`, for example, requires `areaId` (type `area`). Pass areas by id: `nc.areas.get("Logs").id`, or an
entry of `nc.areas.list()` / `nc.areas.by_spec(...)`.

### Bots that ask questions

A bot marked `interactive` stops and waits for you: to pick an area, click an object, or confirm a
settings window. That works while you watch the character, but not on a **background** session,
where nobody can answer. There `bots.run` raises `NotSupported` instead of hanging.

If you want an interactive bot to run unattended, look for a script-friendly alternative in the
curated API (layer 2), or ask the player once with a [form](script-ui.md) and do the work yourself.

## Running a scenario

Scenarios are saved chains of bots. Run one by name or id:

```python
for s in nc.scenarios.list():
    print(s["id"], s["name"], [step["id"] for step in s["steps"]])

if not nc.scenarios.run("Morning chores"):
    nc.msg("A step failed")
```

The scenario runs step by step on the script's bot thread and stops at the first step that fails.
`scenarios.run` returns `True` only if every step succeeded.

## Scripts calling scripts

Your scripts are bots too (`script:hello.py`), so `nc.bots.run("script:other.py", params={...})`
starts another script and waits for it. The other script runs as its own Python process, on your
bot thread. Use this sparingly: an ordinary Python `import` of shared code is simpler and faster.

## Combining bots with your own logic

```python
# nurgling: name="Water and feed"
from nurgling import script

@script
def main(nc):
    for _ in range(3):
        if not nc.bots.run("filwaterzone"):
            return False
        nc.sleep(60)
```

Layer 1 is a good start. When you need finer control than a whole bot gives you, move to the curated
API (layer 2), and to [raw UI](raw-ui.md) (layer 3) only for what layer 2 doesn't cover.
