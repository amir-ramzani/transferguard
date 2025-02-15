{{- define "tg.name" -}}transferguard{{- end -}}
{{- define "tg.labels" -}}
app.kubernetes.io/name: {{ include "tg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "tg.selector" -}}
app.kubernetes.io/name: {{ include "tg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "tg.securityContext" -}}
runAsNonRoot: true
runAsUser: 65532
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}
