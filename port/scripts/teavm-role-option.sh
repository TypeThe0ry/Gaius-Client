#!/usr/bin/env bash

# Match generate-pom.sh's role-specific option precedence in build metadata.
gaius_teavm_role_option() {
  local role_key="$1" option="$2" default="$3"
  local role_variable="GAIUS_TEAVM_${role_key}_${option}"
  local shared_variable="GAIUS_TEAVM_${option}"
  if [[ -n "${!role_variable:-}" ]]; then
    printf '%s\n' "${!role_variable}"
  elif [[ -n "${!shared_variable:-}" ]]; then
    printf '%s\n' "${!shared_variable}"
  else
    printf '%s\n' "$default"
  fi
}
