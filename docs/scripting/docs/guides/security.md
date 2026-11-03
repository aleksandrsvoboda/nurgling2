# Security

Scripting lets programs on your computer control your game client. This page explains what protects
you and what doesn't.

## The short version

- **Only run scripts you trust.** A script is a normal Python program. It can do anything you can do
  on your computer: read and delete your files, upload them, install software. The client cannot
  sandbox it and doesn't try.
- Scripting is **off by default**. When it is off, the client opens no network port at all.
- When it is on, only programs **on your own computer** that can read a secret token can connect.
  Web pages cannot.

## What a script can do

- **On your computer:** everything your user account can do. This has nothing to do with Nurgling;
  it is true of every `.py` file you run.
- **In the game:** everything you can do by hand on the characters you are logged in with: walk,
  trade, drop and destroy items, chat, fight. Bots can do it faster and without asking. A malicious
  or buggy script can lose you items or get a character killed.
- **In the client:** with `java=true` in its header, a script can read and change the client's
  internal state, including settings and stored data.

Before running someone else's script, read it. Be suspicious of:

- code that is hard to read on purpose (long encoded strings, `exec`, `eval`, `base64`,
  `marshal`, `compile`);
- network access (`urllib`, `requests`, `socket`, `http`) in a script that has no reason to talk to
  the internet;
- file access outside the script's own folder;
- `subprocess` or `os.system`;
- `java=true` in the header of a script that only does ordinary gameplay;
- anything that installs packages.

Scripts in the `scripts` folder are listed in your bot menu, but they only run when you start one.

## How the client protects the connection

These protections are about **other programs and web pages** driving your client, not about the
scripts you run yourself.

**Local only.** The server listens on `127.0.0.1` (localhost). Other computers on your network
cannot reach it.

**Raw TCP, not HTTP.** The protocol is length-prefixed JSON over a plain socket. Browsers cannot
open plain sockets, so a web page cannot talk to the client, even with tricks such as DNS rebinding.

**A token per launch.** At every start the client makes a new random 32-byte token. A connection
must present it in its first message within five seconds or it is closed. The token is compared in
constant time. It is stored in two places only:

- the info file (`%APPDATA%\Haven and Hearth\scripting.json` on Windows,
  `~/.haven/scripting.json` elsewhere), which only your user account can read;
- the environment of the scripts the client starts itself.

Any program running as your user can read that file, which is fine: such a program could already do
anything to your account. The token keeps out other users on a shared computer and anything that is
not running as you.

**Random port.** By default the port is picked at random at each launch. You can fix it in
Options > Python Scripting (it applies the next time the server starts); the token is still required.

**Off means off.** Turning scripting off in Options > Python Scripting (and pressing Save) stops
the server, closes every connection (their runs end as if stopped) and deletes the info file. The
info file is also deleted when the client exits.

## Sharing scripts

- Don't put your info file or its token in a script, a screenshot or a bug report. It changes at the
  next launch, but until then it lets any local program drive your client.
- Share scripts as plain `.py` files so others can read them before they run them.
- If a script needs layer 4 (`java=true`), say why in its description.
