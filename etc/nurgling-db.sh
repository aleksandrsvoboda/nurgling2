#!/bin/sh
# Village database for Nurgling - for a machine that hosts but does not play.
#
# The container's whole job is an empty PostgreSQL with one admin role. Tables, grants and
# every future schema change belong to the client, so nothing in this script has to be kept
# in step with a Nurgling version: set a server up once and it keeps working.
#
#   sh nurgling-db.sh up --host=vault.example.com
#   sh nurgling-db.sh status | invite | backup | restore <file> | logs | tls | down
set -eu

DIR="${NURGLING_DB_DIR:-$HOME/nurgling-db}"
IMAGE="postgres:17-alpine"
ADMIN="nurgling_admin"
DBNAME="nurgling_db"
PORT="${NURGLING_DB_PORT:-5436}"
HOSTADDR=""

for arg in "$@"; do
    case "$arg" in
        --host=*) HOSTADDR="${arg#--host=}" ;;
        --port=*) PORT="${arg#--port=}" ;;
    esac
done
CMD="${1:-help}"

die() { echo "error: $*" >&2; exit 1; }

need_docker() {
    command -v docker >/dev/null 2>&1 || die "docker is not installed"
    # The CLI answers happily while the engine is stopped, so ask the server.
    docker version --format '{{.Server.Version}}' >/dev/null 2>&1 \
        || die "docker is installed but the engine is not running"
    docker compose version >/dev/null 2>&1 || die "this docker has no 'docker compose'"
}

gen_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-32
    else
        # /dev/urandom is always there; openssl is not on a minimal image.
        tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32
    fi
}

guess_host() {
    [ -n "$HOSTADDR" ] && { echo "$HOSTADDR"; return; }
    ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}' \
        || hostname -f 2>/dev/null || echo "YOUR-SERVER-ADDRESS"
}

write_files() {
    mkdir -p "$DIR"
    if [ ! -f "$DIR/.env" ]; then
        PW="$(gen_password)"
        cat > "$DIR/.env" <<EOF
POSTGRES_USER=$ADMIN
POSTGRES_PASSWORD=$PW
POSTGRES_DB=$DBNAME
PGPORT=$PORT
EOF
        chmod 600 "$DIR/.env"
        echo "generated a new password in $DIR/.env"
    else
        echo "keeping the existing $DIR/.env"
    fi

    cat > "$DIR/docker-compose.yml" <<EOF
services:
  postgres:
    image: $IMAGE
    container_name: nurgling_db
    restart: unless-stopped
    env_file: .env
    ports:
      - "\${PGPORT:-$PORT}:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \$\$POSTGRES_USER -d \$\$POSTGRES_DB"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
EOF
}

env_get() { grep "^$1=" "$DIR/.env" | cut -d= -f2-; }

print_invite() {
    [ -f "$DIR/.env" ] || die "no $DIR/.env - run 'up' first"
    H="$(guess_host)"
    U="$(env_get POSTGRES_USER)"
    P="$(env_get POSTGRES_PASSWORD)"
    D="$(env_get POSTGRES_DB)"
    K="$(env_get PGPORT)"
    echo
    echo "Paste this into the client: Settings -> Database -> My village -> Invite code"
    echo
    echo "  postgresql://$U:$P@$H:$K/$D"
    echo
    echo "This is a password. Send it privately, and only to yourself -"
    echo "add other players from the client's Villagers panel, so each gets a revocable account."
}

case "$CMD" in
up)
    need_docker
    write_files
    ( cd "$DIR" && docker compose up -d )
    printf 'waiting for postgres'
    i=0
    while [ $i -lt 30 ]; do
        if ( cd "$DIR" && docker compose ps --format '{{.Health}}' 2>/dev/null | grep -q healthy ); then
            echo " ok"
            print_invite
            echo "If this machine is reachable from the internet, run 'tls' next -"
            echo "without it the connection is unencrypted, and this database holds hearth secrets."
            exit 0
        fi
        printf '.'
        sleep 2
        i=$((i + 1))
    done
    echo
    die "it started but never became healthy - try: sh $0 logs"
    ;;
status)
    need_docker
    ( cd "$DIR" && docker compose ps )
    ;;
invite)
    print_invite
    ;;
backup)
    need_docker
    OUT="${2:-$DIR/backup-$(date +%Y%m%d-%H%M%S).sql}"
    case "$OUT" in --*) OUT="$DIR/backup-$(date +%Y%m%d-%H%M%S).sql" ;; esac
    ( cd "$DIR" && docker compose exec -T postgres \
        pg_dump -U "$(env_get POSTGRES_USER)" "$(env_get POSTGRES_DB)" ) > "$OUT"
    echo "wrote $OUT"
    echo "This village's areas, routes and shared map exist here and nowhere else."
    ;;
restore)
    need_docker
    IN="${2:-}"
    [ -n "$IN" ] && [ -f "$IN" ] || die "usage: $0 restore <file.sql>"
    echo "This REPLACES the contents of $DBNAME. Type yes to continue."
    read -r ans
    [ "$ans" = "yes" ] || die "cancelled"
    ( cd "$DIR" && docker compose exec -T postgres \
        psql -U "$(env_get POSTGRES_USER)" "$(env_get POSTGRES_DB)" ) < "$IN"
    echo "restored"
    ;;
logs)
    need_docker
    ( cd "$DIR" && docker compose logs --tail=200 )
    ;;
tls)
    need_docker
    mkdir -p "$DIR/certs"
    [ -f "$DIR/certs/server.key" ] && die "certs already exist - replacing them invalidates every pinned invite"
    # EC rather than RSA: small enough to travel inside an invite code later.
    openssl req -new -x509 -days 3650 -nodes \
        -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
        -subj "/CN=nurgling" -keyout "$DIR/certs/server.key" -out "$DIR/certs/server.crt"
    chmod 600 "$DIR/certs/server.key"
    # Must be owned by root or by the container's postgres user, or the server will not start.
    chown 0:0 "$DIR/certs/server.key" 2>/dev/null || sudo chown 0:0 "$DIR/certs/server.key"
    cat <<'EOF'

Certificates written. Add these two entries to the postgres service in docker-compose.yml:

    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./certs:/certs:ro
    command: >
      postgres -c ssl=on
               -c ssl_cert_file=/certs/server.crt
               -c ssl_key_file=/certs/server.key

then: docker compose up -d
and set Encryption to Required in the client.
EOF
    ;;
down)
    need_docker
    ( cd "$DIR" && docker compose down )
    echo "stopped; the data volume is untouched"
    ;;
*)
    sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
