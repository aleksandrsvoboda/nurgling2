# Stability and changelog

A script you write today should keep working after the next client update. This page says what is
promised, and what isn't.

## Versions

The API has a version `major.minor`, separate from the client's own version. The client reports it in
the handshake (`nc.hello["api"]`) and in `nc.core.version()`. These docs have one version per API
minor version; pick yours in the selector at the top of the page.

Each op in the reference shows the version it appeared in (*since 1.0*).

## What is promised (layers 1 and 2)

The curated API (layer 2), the bot and scenario ops (layer 1), the plumbing (`core`, `sessions`,
`runs`, `run`, `events`), the snapshot types and the error types follow
[Semantic Versioning](https://semver.org/):

- **Minor versions only add.** A minor version can add ops, optional parameters, snapshot fields,
  event kinds and error types. It does not remove or rename anything, change a parameter's meaning,
  or make an optional parameter required.
- **Deprecation before removal.** An op that is replaced is marked *deprecated* in the reference,
  with what to use instead, and keeps working for **at least one more minor version**. Removal
  happens only in a new major version.
- **Major versions may break things**, and the changelog lists every break. An SDK refuses to connect
  to a client with a different major version (`NotSupported`) instead of failing halfway through a
  script.
- **Bot ids are stable.** A bot that is retired disappears from the catalogue; `bots.run` then raises
  `NotFound`.

What this means for your code:

- Ignore snapshot fields you don't know. New ones will appear.
- Treat unknown error types like `Internal`. The SDK already does.
- A script written for 1.0 works on every 1.x client. A script that uses an op added in 1.3 gets
  `UnknownOp` on a 1.2 client.
- A newer 1.x SDK works with an older 1.x client.

## What is not promised

- **Layer 3 (raw UI)**: the framing and the `ui.*` / `raw.*` ops follow the rules above, but what a
  widget message *means* is defined by the game server. When the game changes a window or a message,
  scripts that send it may break. Widget class names in snapshots and events are the client's Java
  class names and can change in any version.
- **Layer 4 (Java)**: **no guarantees at all.** `java.*` ops expose the client's internals, which
  change whenever the code is refactored. Any client update can break a script that uses them,
  including patch releases. The ops themselves are marked *unstable* in the reference.
- **Game behaviour**: the API wraps what the game allows. When the game changes a mechanic, a bot or
  action may behave differently even though its op did not change.
- **Bot behaviour**: bots run by `bots.run` evolve with the client. Their ids and declared
  parameters are stable; how exactly they do their job may improve.
- **Timing**: how long an op takes is not part of the contract.

## Changelog

### 1.0

First public version.

- Wire protocol: length-prefixed JSON frames over localhost TCP, token handshake, runs, events.
- Plumbing: `core`, `sessions`, `runs`, `run`, `events`.
- Layer 1: `bots.list`, `bots.get`, `bots.run`, `scenarios.list`, `scenarios.run`; scripts in the
  Scripts folder are bots too (`script:<file>`) and can be scenario steps.
- Layer 2: `player`, `gobs`, `inv`, `equip`, `windows`, `areas`, `map`, `chat`, `actions`, `craft`,
  `wait`, `combat`, `form`, `overlay`.
- Layer 3: `ui` (widget tree, raw widget messages) and `raw` (typed clicks and item messages);
  `ui.*` events including outgoing traffic (`ui.out`).
- Layer 4 (unstable): `java.*`, enabled with `java=true`.
- Python SDK `nurgling` 1.0 (Python 3.9+, standard library only).
