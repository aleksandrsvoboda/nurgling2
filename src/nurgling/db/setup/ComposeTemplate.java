package nurgling.db.setup;

import nurgling.db.DbSettings;

/**
 * The files that stand up a village database, as text.
 *
 * <p>One template, two deliveries: written to disk when the database runs on this machine, or
 * rendered as a single shell block to paste into an SSH session when it runs on another. Both
 * produce the identical container, because they are the same text.
 *
 * <p>Nothing here knows anything about the schema. The container's whole job is an empty PostgreSQL
 * with one admin role; tables, grants and every future migration are the client's business. That is
 * why this template does not change when the schema does, and why a server set up once keeps working
 * across client updates without anyone touching it.
 */
public class ComposeTemplate {
    /** Pinned. Never change this on a volume that already has data - a major version will not start. */
    public static final String IMAGE = "postgres:17-alpine";
    public static final String ADMIN_ROLE = "nurgling_admin";
    public static final String DIRECTORY = "nurgling-db";

    public static String env(String user, String password, String database, int port) {
        return "POSTGRES_USER=" + user + "\n"
             + "POSTGRES_PASSWORD=" + password + "\n"
             + "POSTGRES_DB=" + database + "\n"
             + "PGPORT=" + port + "\n";
    }

    public static String compose() {
        return "services:\n"
             + "  postgres:\n"
             + "    image: " + IMAGE + "\n"
             + "    container_name: nurgling_db\n"
             + "    restart: unless-stopped\n"
             + "    env_file: .env\n"
             + "    ports:\n"
             + "      - \"${PGPORT:-" + DbSettings.DEFAULT_PORT + "}:5432\"\n"
             + "    volumes:\n"
             + "      - postgres_data:/var/lib/postgresql/data\n"
             + "    healthcheck:\n"
             + "      test: [\"CMD-SHELL\", \"pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB\"]\n"
             + "      interval: 10s\n"
             + "      timeout: 5s\n"
             + "      retries: 5\n"
             + "\n"
             + "volumes:\n"
             + "  postgres_data:\n";
    }

    /**
     * One block to paste into a shell on the machine that will host the database.
     *
     * <p>Deliberately self-contained: no download, nothing piped into a shell, every line readable
     * before it runs - which also means it works on a box with no internet access. The password is
     * already in it because this client generated it, so there is no code to copy back afterwards.
     */
    public static String remoteBlock(String user, String password, String database, int port) {
        return "mkdir -p ~/" + DIRECTORY + " && cd ~/" + DIRECTORY + "\n"
             + "\n"
             + "cat > .env <<'NURGLING_EOF'\n"
             + env(user, password, database, port)
             + "NURGLING_EOF\n"
             + "chmod 600 .env\n"
             + "\n"
             + "cat > docker-compose.yml <<'NURGLING_EOF'\n"
             + compose()
             + "NURGLING_EOF\n"
             + "\n"
             + "docker compose up -d && docker compose ps\n";
    }

    /**
     * The separate step that turns encryption on.
     *
     * <p>Kept out of the block above on purpose. Certificate ownership and key permissions are the
     * most common reason a PostgreSQL container refuses to start, and the path that gets a village
     * running should not be breakable by the part that hardens it.
     */
    public static String tlsBlock() {
        return "cd ~/" + DIRECTORY + " && mkdir -p certs\n"
             + "\n"
             + "# EC rather than RSA: the certificate stays small enough to travel in an invite code.\n"
             + "openssl req -new -x509 -days 3650 -nodes \\\n"
             + "  -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \\\n"
             + "  -subj \"/CN=nurgling\" -keyout certs/server.key -out certs/server.crt\n"
             + "chmod 600 certs/server.key\n"
             + "\n"
             + "# The key must be owned by root or by the container's postgres user, or the server\n"
             + "# will not start. This is the step that catches people out.\n"
             + "chown 0:0 certs/server.key 2>/dev/null || sudo chown 0:0 certs/server.key\n"
             + "\n"
             + "# Add to the postgres service in docker-compose.yml, then bring it back up:\n"
             + "#   volumes:\n"
             + "#     - postgres_data:/var/lib/postgresql/data\n"
             + "#     - ./certs:/certs:ro\n"
             + "#   command: >\n"
             + "#     postgres -c ssl=on\n"
             + "#              -c ssl_cert_file=/certs/server.crt\n"
             + "#              -c ssl_key_file=/certs/server.key\n"
             + "docker compose up -d\n";
    }
}
