# Query console

Browser client for `POST /api/v1/queries/execute`.

Besides execute, the page calls the schema endpoints to populate the Builder (entity list,
field lists):

- `GET /api/v1/queries/entities`
- `GET /api/v1/queries/entities/schema/{name}`
- `GET /api/v1/queries/entities/schema/{name}/detailed` (only for complex, i.e. instanced, entities
  when an id value is entered)

All of them go through the same `/api/*` proxy when the in-page "API base" setting is empty; a non-empty
API base sends them straight to that URL.

## Run

```bash
python3 local_env/query-console/serve.py
```

Open <http://localhost:8099>.

`serve.py` serves this directory and forwards `/api/*` to the backend. It exists because
the backend sends no CORS headers, so the page must share its origin.

### Parameters

| Flag | Default | Meaning |
|---|---|---|
| `--port` | `8099` | Port the console listens on (`127.0.0.1` only). |
| `--backend` | `http://localhost:8080` | Backend base URL to forward `/api/*` to (backend default `server.port`). |

### In-page settings

| Field | Default | Meaning |
|---|---|---|
| API base | empty | Empty means same origin, i.e. through the proxy. Set it only if the backend serves CORS headers. |
| Auth | `none` | `Bearer` sends `Authorization: Bearer <token>`; `Api-Key` sends `Api-Key: <token>`. |

API base and token are kept in `localStorage`.

## Keys

`Cmd+Enter` / `Ctrl+Enter` runs the query.

The **Builder** / **JSON** toggle switches between a form and the raw query. The JSON is the
source of truth; shapes the form cannot express (subqueries, nested functions) show read-only
and are left untouched.
