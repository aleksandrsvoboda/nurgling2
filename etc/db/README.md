# Village database

A shared PostgreSQL lets a village keep areas, routes, the map, containers, storage items,
hearth secrets and live positions in one place instead of in each player's local files.

The client does all the database work itself: **this directory only starts an empty
PostgreSQL with one admin role.** Tables, indexes and grants are created on first connect by
the migration code in the client, which is why nothing here has to change when the schema
does. A server set up once keeps working across client updates without anyone touching it.

## New village - the easy way

**Settings -> Database -> My village -> Host a database...**

Pick "On this PC" and press *Create the database*: the client checks Docker, generates a
password, writes the files, starts the container, waits for it to report healthy, and points
itself at it.

Pick "On another machine" for a server or VPS. The client generates the password and one
self-contained shell block; *Copy setup command*, paste it into an SSH session on that
machine, then type the address players will use. Nothing is downloaded, nothing is piped into
a shell, and every line is readable before you run it - so it also works on a box with no
internet access.

Then **Villagers...** to add each player. Every one gets their own account and their own
invite code, which they paste into a single field. Never hand out your own.

## New village - by hand

For a machine you would rather set up from a shell, use `../nurgling-db.sh`:

```sh
sh nurgling-db.sh up --host=vault.example.com
```

It writes the same files, starts the same container and prints a connection string you can
paste straight into the client's **Invite code** box. Other subcommands: `status`, `invite`,
`backup`, `restore <file>`, `logs`, `tls`, `down`. `nurgling-db.ps1` is the Windows twin.

Or entirely manually, with the files in this directory:

```sh
cp .env.example .env
# put a generated password in .env - see the comment in the file
docker compose up -d
docker compose ps          # should say "healthy" within ~15s
```

Then in the client, **Settings -> Database -> My village -> Show connection details**:

| Field | Value |
|---|---|
| Host | the address other players will use to reach this machine |
| Port | `5436`, or whatever `PGPORT` you set |
| Database | `nurgling_db` |
| Username | `nurgling_admin` |
| Password | the one from `.env` |
| Encryption | `Use if available` until you do the TLS step below |

Press **Test connection**. It reports exactly what went wrong if anything did - a closed
port, a wrong password and a missing database are three different sentences.

### Reaching it from outside

- **Same house?** Use the LAN address. Nothing else to do.
- **Over the internet?** Forward the port on the router and allow it through the firewall.
  A home address usually moves, so expect to hand out a new one occasionally.
- **On Linux, `ufw` does not apply to published Docker ports.** Docker writes its own
  forwarding rules, so `ufw deny 5436` looks like it worked and does not. Either publish to
  a specific address (`"127.0.0.1:5436:5432"` plus a tunnel) or add rules to `DOCKER-USER`.

The client cannot test its own inbound port. Send the first invite and have that player press
**Test connection** - that is the only check that proves anything.

### Encryption

Without this step the connection is unencrypted, and the database holds every villager's
hearth secret. It is separate from the steps above on purpose: certificate permissions are
the most common reason a PostgreSQL container refuses to start, and the easy path should not
be breakable by the hard part.

```sh
mkdir -p certs
openssl req -new -x509 -days 3650 -nodes \
  -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
  -subj "/CN=nurgling" -keyout certs/server.key -out certs/server.crt
chmod 600 certs/server.key
# the key must be owned by root or by the container's postgres user (uid 70 on alpine),
# or the server will not start
chown 0:0 certs/server.key 2>/dev/null || sudo chown 0:0 certs/server.key
```

Add to the `postgres` service in `docker-compose.yml`:

```yaml
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./certs:/certs:ro
    command: >
      postgres -c ssl=on
               -c ssl_cert_file=/certs/server.crt
               -c ssl_key_file=/certs/server.key
```

Then `docker compose up -d`, and set **Encryption** to `Required` in the client. The status
line will say `encrypted`.

This stops anyone reading the traffic. It does not stop someone who can actively impersonate
the server, because a self-signed certificate is not verified - closing that gap needs the
certificate pinned in the client, which is planned but not built.

## Backups

The village's areas, routes and shared map exist here and nowhere else.

```sh
docker compose exec -T postgres pg_dump -U nurgling_admin nurgling_db > backup-$(date +%F).sql
```

## Upgrading a village that already exists

If you already run a database from an older version of this file, nothing breaks when you
update the client - your existing settings keep working, and the client fills in the new
host/port/database fields from them the first time you open the panel.

Do these in order. Only the order matters - each step is safe on its own.

1. **Harden the server first.** Add `restart: unless-stopped` and the healthcheck from the
   compose file in this directory, and do the TLS step above if the machine is reachable from
   the internet. Doing this before you hand out any invite means each villager is invited
   once rather than twice.

2. **Connect once with the account that owns the database** (for most villages that is
   `postgres`). The client repairs permissions automatically on connect: it grants the five
   tables that used to come from `init.sql` and were granted to nobody, grants the sequences
   that were never granted anywhere, and sets default privileges so future tables are covered
   without anyone remembering. It is idempotent and disconnects nobody, so it is safe while
   people are playing.

3. **Add each villager** from the Villagers panel and send them their invite. Tell them the
   shared login is going away, and give a date.

4. **Watch the "last seen" column.** Anyone showing *never* has not switched yet.

5. **Only then, change the shared account's password.** Doing this earlier disconnects
   everyone still using it, at the same instant - the one action here that can take a village
   offline in a single click.

Do not change the `image:` tag on a database that already has data - a PostgreSQL major
version will not start against an older data directory. Existing villages stay on the version
they were created with; moving majors means backup, fresh volume, restore.

`init.sql` used to live here and is gone. It was a hidden prerequisite: the client could only
ever initialise a database that had been created through this exact compose file, because
migration 1 assumed those tables already existed. They are created by the client now, which is
what makes any PostgreSQL usable.
