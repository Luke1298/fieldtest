# Postgres recorder example

A recorder that writes exposures and feedback to Postgres using next.jdbc. `resources/schema.sql` has the tables and `resources/analysis.sql` has the per-arm query described in [doc/analysis.md](../../doc/analysis.md). The tests run fieldtest's test kit against the recorder and push an A/A run through the analysis query.

To run them against a throwaway Postgres:

```sh
docker run --rm -d --name fieldtest-pg -p 55432:5432 -e POSTGRES_PASSWORD=postgres postgres:16-alpine
clojure -M:test
docker stop fieldtest-pg
```

To use a different database, set `FIELDTEST_PG_URL` to its JDBC URL. Be aware that the tests truncate all three tables.

In a server:

```clojure
(def ds (next.jdbc/get-datasource {:jdbcUrl "jdbc:postgresql://…"}))
(example.postgres-recorder/migrate! ds)
(fieldtest.server/server {:config-source … :recorder (example.postgres-recorder/recorder ds)})
```

`decide` waits for the recorder to finish, so in production use a connection pool such as HikariCP and keep the database near the server.
