# Errors

Every error the client reports has a type. The SDK raises the exception class of the same name (`from nurgling import Timeout`); all derive from `NurglingError`.

## BadArgs

Missing, unknown or wrongly typed arguments.

## ActionFailed

A game action ran but reported failure (the Java action returned FAIL or ERROR).

## Stopped

The run was stopped (stop button, script exit, or session closed). Not catchable as a normal failure: the run is over.

## Timeout

A wait or blocking call did not finish within its timeout.

## JavaError

A Java exception thrown inside a java.* call (layer 4). The message carries the Java class and message.

## Internal

An unexpected error inside the client. Please report it with the message.

## Cancelled

The user dismissed a form or picker.

## NotSupported

The op cannot run in this context (e.g. an interactive prompt on a headless session).

## GobGone

The game object no longer exists (out of view, destroyed, or picked up).

## ItemGone

The item widget no longer exists (moved, used up, or its container closed).

## UnknownOp

No op with that name exists in this client version.

## NoSuchArea

No area with that id, name or specialisation exists.

## PathNotFound

The pathfinder found no route to the target.

## AuthFailed

The handshake token was wrong or missing.

## WidgetGone

The window or widget no longer exists.

## NotFound

Something the op needed (a window, a menu option, an item) was not found.
