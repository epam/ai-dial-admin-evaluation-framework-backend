# AI DIAL Admin Evaluation Framework Installation Instructions

## Introduction
This guide provides instructions for installing the AI DIAL Admin Evaluation Framework. This includes deploying the evaluation framework backend service and its associated PostgreSQL database for storing metadata and analytics.

##### Prerequisites
Ensure that you have the necessary permissions to deploy and configure Helm charts and services within the Kubernetes cluster. You will also need Helm installed and configured to access your cluster.

## Step 1: Update Components
Make sure the following components are updated to the specified versions:
* `ai-dial-admin-evaluation-framework-backend` to version `development` (or target release version)
* `ai-dial-admin-evaluation-metrics` to version `development` (or target release version)
* `bitnami/postgresql` (PostgreSQL database dependency)

## Step 2: Deploy AI DIAL Admin Evaluation Framework with Helm

First, deploy the PostgreSQL database dependency, and then deploy the backend service and metrics component. 

Run the following Helm commands to deploy the components:

```bash
# Deploy PostgreSQL database
helm upgrade --install dial-admin-eval-postgresql bitnami/postgresql -f ./postgresql-values.yaml -n dial-admin

# Deploy Backend Service
helm upgrade --install dial-admin-evaluation-framework ai-dial/ai-dial-admin-evaluation-framework-backend -f ./backend-values.yaml -n dial-admin

# Deploy Metrics Component
helm upgrade --install dial-admin-evaluation-metrics ai-dial/ai-dial-admin-evaluation-metrics -f ./metrics-values.yaml -n dial-admin
```

##### Example of a values.yaml file

Below are sample `values.yaml` configurations based on the internal environment. 

**1. PostgreSQL (`postgresql-values.yaml`)**
This configuration sets up the required databases (`evaluation-framework` and `evaluation_analytics_db`) and provisions backup storage.

```yaml
fullnameOverride: dial-admin-eval-postgresql
extraDeploy:
  - apiVersion: v1
    kind: PersistentVolumeClaim
    metadata:
      name: &backupPVC dial-eval-postgres-backup
    spec:
      accessModes:
        - ReadWriteMany
      resources:
        requests:
          storage: 100Gi
      storageClassName: backup
      volumeMode: Filesystem
primary:
  initdb:
    scripts:
      create-multiple-dbs.sql: |
        SELECT 'CREATE DATABASE evaluation_analytics_db'
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'evaluation_analytics_db')\gexec
    user: "postgres"
    # Provide the primary.initdb.password securely during deployment
auth:
  database: evaluation-framework
  usePasswordFiles: false
global:
  security:
    allowInsecureImages: true
image:
  registry: public.ecr.aws
  repository: bitnami/postgresql
backup:
  enabled: false # Enable and configure cronjob for production environments
```

**2. Backend Service (`backend-values.yaml`)**
This configuration specifies the image, ingress, and environment variables required to connect to the database, identity providers (Azure/Keycloak), and telemetry services.

```yaml
fullnameOverride: dial-admin-evaluation-framework-backend
image:
  pullPolicy: Always
  registry: registry-dev.deltixhub.com
  repository: ai/dial/ai-dial-admin-evaluation-framework-backend
  tag: development
  pullSecrets:
    - epm-rtc-registry-eval-dev

containerPorts:
  http: 8080

service:
  ports:
    http: 80

ingress:
  enabled: true
  ingressClassName: nginx-whitelist
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-production
  hosts:
    - eval-swagger.aks.dev.dial.parts
  tls:
    - secretName: "eval-tls"
      hosts:
        - eval-swagger.aks.dev.dial.parts
  extraPaths:
    - path: /swagger-ui
      pathType: Prefix
      backend:
        service:
          name: dial-admin-evaluation-framework-backend
          port:
            number: 80

# Environment variables are passed under the `env` block.
env:
  CONFIG_REST_SECURITY_MODE: "oidc"
  POSTGRES_META_DATASOURCE_URL: "jdbc:postgresql://dial-admin-eval-postgresql:5432/evaluation-framework"
  POSTGRES_META_DATASOURCE_USERNAME: "postgres"
  # Add other required environment variables here (see Step 4)
```

**3. Metrics Component (`metrics-values.yaml`)**
This configuration specifies the image, ingress, and environment variables required for the metrics component.

```yaml
fullnameOverride: ai-dial-admin-evaluation-metrics
image:
  pullPolicy: Always
  registry: registry-dev.deltixhub.com
  repository: ai/dial/ai-dial-admin-evaluation-metrics
  tag: development
  pullSecrets:
    - epm-rtc-registry-eval-dev

containerPorts:
  http: 5000

service:
  ports:
    http: 80

ingress:
  enabled: true
  ingressClassName: nginx-whitelist
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-production
  hosts:
    - eval-metrics.aks.dev.dial.parts
  tls:
    - secretName: "eval-metrics-tls"
      hosts:
        - eval-metrics.aks.dev.dial.parts

env:
  DIAL_URL: "http://dial-core.dial.svc.cluster.local"
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://alloy-otel.monitoring.svc.cluster.local.:4317"
  OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
  OTEL_SERVICE_NAME: eval-metrics
  OTEL_PYTHON_LOG_CORRELATION: "true"
```

## Step 3: Configure Dependencies
After deploying, ensure that the following dependencies are correctly configured and accessible by the backend service:
1. **PostgreSQL Database**: Must be reachable at the URL specified in `POSTGRES_META_DATASOURCE_URL` and `POSTGRES_ANALYTICS_DATASOURCE_URL`.
2. **Identity Providers**: Ensure Azure AD and/or Keycloak are configured with the correct audiences and roles.
3. **DIAL Core**: The backend needs to communicate with DIAL Core (`DIAL_COMPONENTS_CORE_BASE_URL`).
4. **OpenTelemetry / Grafana**: Ensure the OTEL collector and Grafana instances are reachable for metrics and tracing.

## Step 4: Environment Variables
Specify the following environment variables in your backend and metrics `values.yaml` files under the `env` section:

| Variable | Description | Example Value |
|----------|-------------|---------------|
| `CONFIG_REST_SECURITY_MODE` | Security mode for the REST API | `"oidc"` |
| `POSTGRES_META_DATASOURCE_URL` | JDBC URL for the metadata database | `"jdbc:postgresql://dial-admin-eval-postgresql:5432/evaluation-framework"` |
| `POSTGRES_META_DATASOURCE_USERNAME` | Username for the metadata database | `"postgres"` |
| `POSTGRES_ANALYTICS_DATASOURCE_URL` | JDBC URL for the analytics database | `"jdbc:postgresql://dial-admin-eval-postgresql:5432/evaluation_analytics_db"` |
| `POSTGRES_ANALYTICS_DATASOURCE_USERNAME` | Username for the analytics database | `"postgres"` |
| `DIAL_COMPONENTS_CORE_BASE_URL` | Base URL for DIAL Core | `"http://dial-core.dial.svc.cluster.local:80"` |
| `DIAL_URL` | URL for DIAL Core (used by metrics component) | `"http://dial-core.dial.svc.cluster.local"` |
| `providers.*.*` | OIDC configuration for identity providers (e.g., Azure AD, Keycloak) including issuer, jwk-set-uri, audiences, roles | See internal values for specific tenant/realm details |
| `OTEL_*` | OpenTelemetry configuration for logs, metrics, and traces | `"otlp"` / `"http://alloy-otel...:4317"` |
| `GRAFANA_BASE_URL` | Base URL for Grafana dashboards | `"https://grafana.aks.dev.dial.parts/"` |
| `METRIC_PROVIDERS_SYNC_ENABLED` | Enable synchronization of metric providers | `"true"` |
| `METRIC_PROVIDERS_DIAL_BASE_URL` | Base URL for DIAL Admin Evaluation Metrics | `"http://ai-dial-admin-evaluation-metrics...:80"` |

## Step 5: Configure AI DIAL Admin Frontend
For the AI DIAL Admin frontend, you must add the `DIAL_EVAL_API_URL` environment variable with the local address of the evaluation framework backend service:

```yaml
DIAL_EVAL_API_URL: "http://dial-admin-evaluation-framework-backend.dial-admin.svc.cluster.local:80/"
```

## Annex
* **Database Passwords**: Ensure that database passwords (like `PGPASSWORD` or `primary.initdb.password`) are injected securely via Kubernetes Secrets rather than hardcoded in the `values.yaml` files.
* **Logging**: The backend includes an extra ConfigMap deployment (`admin-eval-backend-log-level-config`) to manage logging levels dynamically.
* **Security Note (Swagger)**: It is highly recommended **not** to expose the Swagger UI (`/swagger-ui` and `/v3/api-docs` ingress paths) on UAT and Production environments for security reasons.