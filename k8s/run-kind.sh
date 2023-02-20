#!/usr/bin/env bash

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

UAA_ADMIN_CLIENT_SECRET=""
UAA_CONFIG_DIR="${HOME}/.uaa"
UAA_ADMIN_CLIENT_SECRET_LOCATION="${UAA_CONFIG_DIR}/admin_client_secret.json"

ensure_kind_cluster() {
  local cluster="$1"
  if ! kind get clusters | grep -q "${cluster}"; then
    cat <<EOF | kind create cluster --name "${cluster}" --wait 5m --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
EOF
  fi

  kind export kubeconfig --name "${cluster}"
}

install_contour() {
  kubectl apply -f https://projectcontour.io/quickstart/contour.yaml
  kubectl patch daemonsets -n projectcontour envoy -p '{"spec":{"template":{"spec":{"nodeSelector":{"ingress-ready":"true"},"tolerations":[{"key":"node-role.kubernetes.io/control-plane","operator":"Equal","effect":"NoSchedule"},{"key":"node-role.kubernetes.io/master","operator":"Equal","effect":"NoSchedule"}]}}}}'
}

get_admin_client_secret() {
  mkdir -p "${UAA_CONFIG_DIR}"

  local admin_client_secret
  admin_client_secret=$(jq ".admin.client_secret" "${UAA_ADMIN_CLIENT_SECRET_LOCATION}" -e -r 2>/dev/null)

  if [ $? -ne 0 ]; then
    admin_client_secret="$(openssl rand -hex 12)"
    create_admin_client_secret "${admin_client_secret}"
  fi

  UAA_ADMIN_CLIENT_SECRET="${admin_client_secret}"
}

create_admin_client_secret() {
  local admin_client_secret
  admin_client_secret="${1}"

  cat <<EOF >"${UAA_ADMIN_CLIENT_SECRET_LOCATION}"
{
  "admin": {
    "client_secret": "${admin_client_secret}"
  }
}
EOF

}

ytt_and_minikube() {
  local ytt_kubectl_cmd="ytt -f templates -f addons -v admin.client_secret=\"${UAA_ADMIN_CLIENT_SECRET}\" ${@} | kubectl apply -f -"
  local ytt_kubectl_cmd_exit_code
  echo "Running '${ytt_kubectl_cmd}'"
  eval "${ytt_kubectl_cmd}"
  ytt_kubectl_cmd_exit_code=$?

  if [ ${ytt_kubectl_cmd_exit_code} -ne 0 ]; then
    exit ${ytt_kubectl_cmd_exit_code}
  fi
}

check_k8s_for_admin_client_secret() {
  local admin_client_secret=$(kubectl get secret/uaa-admin-client-credentials -o json |
    jq '.data."admin_client_credentials.yml"' -r - |
    base64 -d |
    yq eval ".oauth.clients.admin.secret")

  if [ -n "${admin_client_secret}" -a "${admin_client_secret}" != "${UAA_ADMIN_CLIENT_SECRET}" ]; then
    create_admin_client_secret "${admin_client_secret}"
    UAA_ADMIN_CLIENT_SECRET="${admin_client_secret}"
  fi
}

wait_for_availability() {
  echo "Waiting for UAA availability"
  kubectl rollout status deployments/uaa --watch=true
}

configure_admin_access() {
  uaac target http://localhost --skip-ssl-validation
  uaac token client get admin -s $(cat $HOME/.uaa/admin_client_secret.json | jq .admin.client_secret -e -r)
}

install_cert_manager() {
  echo "*************************"
  echo " Installing Cert Manager"
  echo "*************************"

  kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.11.0/cert-manager.yaml

  kubectl -n cert-manager rollout status deployment/cert-manager --watch=true
  kubectl -n cert-manager rollout status deployment/cert-manager-webhook --watch=true
  kubectl -n cert-manager rollout status deployment/cert-manager-cainjector --watch=true
}

main() {
  pushd "$SCRIPT_DIR"
  {
    ensure_kind_cluster uaa
    install_contour
    install_cert_manager
    get_admin_client_secret
    ytt_and_minikube "${@}"
    check_k8s_for_admin_client_secret
    wait_for_availability
    configure_admin_access
  }
  popd
}

main "${@}"
