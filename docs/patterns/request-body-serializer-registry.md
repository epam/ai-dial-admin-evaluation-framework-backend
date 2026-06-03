# RequestBodySerializerRegistry (Pluggable Body Serialization)

Strategy pattern for content-type-specific body serialization. `RequestBodySerializer` interface (`service.domain`) has `supports(ResolvedBodyDto)` + `serialize(ResolvedBodyDto) → SerializedBody(MediaType, Object)`.

Implementations: `JsonRequestBodySerializer`, `MultipartFormDataRequestBodySerializer` (downloads file bytes from DIAL Core via `DialFileClient`), `UrlEncodedFormRequestBodySerializer`. Registry selects the correct serializer based on resolved body type. Used by `TryItOutService` and `EvaluationWorker` before invoking `DialCoreDeploymentInvoker`.
