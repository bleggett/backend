{{/*
Expand the name of the chart.
*/}}
{{- define "backend.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "backend.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "backend.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "backend.labels" -}}
helm.sh/chart: {{ include "backend.chart" . }}
{{ include "backend.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "backend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create Keycloak External Url   
*/}}
{{- define "backend.keycloak.externalUrl" }}
{{- if .Values.global.opentdf.common.oidcUrlPath }}
{{- printf "%s/%s" .Values.global.opentdf.common.oidcExternalBaseUrl .Values.global.opentdf.common.oidcUrlPath }}
{{- else }}
{{- default .Values.global.opentdf.common.oidcExternalBaseUrl }}
{{- end }}
{{- end }}

{{/*
Create Keycloak hostname by extracting from the external url base .Values.global.opentdf.common.oidcExternalBaseUrl
*/}}
{{- define "backend.keycloak.externalHostname" }}
{{- if hasPrefix "http://" .Values.global.opentdf.common.oidcExternalBaseUrl }}
{{- default ( trimPrefix "http://" .Values.global.opentdf.common.oidcExternalBaseUrl ) }}
{{- else }}
{{- default ( trimPrefix "https://" .Values.global.opentdf.common.oidcExternalBaseUrl ) }}
{{- end }}
{{- end }}

