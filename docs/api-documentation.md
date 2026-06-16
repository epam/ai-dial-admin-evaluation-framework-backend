# API documentation

The backend exposes an OpenAPI 3 spec and Swagger UI for consumers (FE, other BE, BA).

## Where to find the spec

| Artifact | URL (when app is running) |
|----------|---------------------------|
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` |
| **OpenAPI JSON** | `http://localhost:8080/v3/api-docs` |

Use the JSON URL to import the spec into Postman, Insomnia, or code generators. Request and response examples are included in the spec where applicable (minimal and fully filled examples per endpoint when it makes sense).
