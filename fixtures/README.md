# Fixtures

Committed so that running the benchmark needs neither Docker nor the Rails reference app.

| file | sha256 | what it is |
|---|---|---|
| `seed.sqlite3` | `483e07226dd9b7b2dbdae61188192c2d98511a7e3c88024cf303db17aeec760c` | The Rust port's parity seed: the database every app starts from (a fresh copy per start). |
| `cookie.txt` | `d85994edc065652aca41cff77c277bfd35d6f3eb35bf9b03a7f7fec4484e6cdd` | David's signed `session_token` cookie (user 127326141, session 481019661), the one the article's harness sends. |

How they were made, from the Rust port at the commit in `upstream/once-campfire-rust` (6dae2fd):

```sh
cd upstream/once-campfire-rust
parity/bin/seed build default                  # builds the Rails reference app in Docker and seeds it
cp parity/.seed/default/db/production.sqlite3 ../../fixtures/seed.sqlite3
```

`cookie.txt` is the `cookie_header` of the first session in `vectors/campfire_sessions.json`, without a
trailing newline.

The cookie is signed with the `SECRET_KEY_BASE` of the Rust port's `parity/.env.reference`, which
`bench.sh` loads for every app; both are public test values.
