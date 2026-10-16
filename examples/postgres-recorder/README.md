# Postgres recorder example

A fieldtest recorder that writes exposures and feedback to Postgres with next.jdbc, the schema it needs (`resources/schema.sql`), and the per-arm analysis query from [doc/analysis.md](../../doc/analysis.md) (`resources/analysis.sql`). Its tests run the fieldtest test kit against it, plus an A/A run through the analysis query.

Start a scratch Postgres and run the tests:

```sh
docker run --rm -d --name fieldtest-pg -p 55432:5432 -e POSTGRES_PASSWORD=postgres postgres:16-alpine
clojure -M:test
docker stop fieldtest-pg
```

Point `FIELDTEST_PG_URL` at another database to use it instead. The tests truncate the three tables.

Use it in a server:

```clojure
(def ds (next.jdbc/get-datasource {:jdbcUrl "jdbc:postgresql://…"}))
(example.postgres-recorder/migrate! ds)
(fieldtest.server/server {:config-source … :recorder (example.postgres-recorder/recorder ds)})
```

`decide` waits for the recorder, so in production use a pooled datasource (HikariCP) and keep the database close to the server.
