# Slash-containing path values

A path value that may contain `/` (e.g. a DIAL Core deployment id like `applications/public/Quick App with RAG__0.0.1`) cannot be bound with `@PathVariable`.

Map the endpoint with a trailing `/**` and resolve the tail via `web.path.WildcardPathResolver` (injected, not a private controller method): it extracts the wildcard tail from the handler-mapping attributes and percent-decodes it **exactly once** with `UriUtils.decode` — never `java.net.URLDecoder`, which turns `+` into a space (wrong for a URL path component).

The resolver returns an empty string when there is no tail; the controller owns the emptiness check and its 400. Do not decode again downstream — `RestClient` applies the single wire encoding.

Alternative for filter-like ids: pass them as a query param (see `GET /deployments/tools?deploymentId=`).
