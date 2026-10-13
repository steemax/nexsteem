{{- define "nxc.name" -}}{{ .Release.Name | trunc 40 | trimSuffix "-" }}-cluster{{- end -}}
{{- define "nxc.labels" -}}
app.kubernetes.io/name: nexus-cluster
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "nxc.commonPod" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- . | toYaml | nindent 2 }}
{{- end }}
{{- with .Values.tolerations }}
tolerations:
  {{- . | toYaml | nindent 2 }}
{{- end }}
{{- end -}}
{{- define "nxc.secret" -}}{{ default (printf "%s-secrets" (include "nxc.name" .)) .Values.secrets.existingSecret }}{{- end -}}
{{- define "nxc.tls" -}}{{ default (printf "%s-tls" (include "nxc.name" .)) .Values.tls.existingSecret }}{{- end -}}
{{- define "nxc.domain" -}}{{ printf "%s.svc.%s" .Release.Namespace .Values.clusterDomain }}{{- end -}}
{{- define "nxc.antiAffinity" -}}
podAntiAffinity:
  {{- if .Values.placement.requiredAntiAffinity }}
  requiredDuringSchedulingIgnoredDuringExecution:
    - labelSelector:
        matchLabels:
          app.kubernetes.io/instance: {{ .Release.Name }}
          app.kubernetes.io/component: {{ .component }}
      topologyKey: {{ .Values.placement.topologyKey }}
  {{- else }}
  preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 100
      podAffinityTerm:
        labelSelector:
          matchLabels:
            app.kubernetes.io/instance: {{ .Release.Name }}
            app.kubernetes.io/component: {{ .component }}
        topologyKey: {{ .Values.placement.topologyKey }}
  {{- end }}
{{- end -}}
