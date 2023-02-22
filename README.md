### Disclaimer

This is a fork of cloudfoundry/uaa. See [original](./ORIGINAL_README.md) readme for more details.

### Running kubernetes with the uaa as an OIDC provider

1. Install the [oidc-login](https://github.com/int128/kubelogin) plugin
1. Install the `w3m` browser

```sudo apt install w3m```

1. Install the uaac CLI:

```sudo gem install cf-uaac```

1. Insert the oidc user in your KUBECONFIG:

```
- name: oidc
  user:
    exec:
      apiVersion: client.authentication.k8s.io/v1beta1
      args:
      - oidc-login
      - get-token
      - --oidc-issuer-url=https://localhost/oauth/token
      - --oidc-client-id=app
      - --oidc-client-secret=app
      - --insecure-skip-tls-verify
      - --browser-command=w3m
      command: kubectl
      env: null
      interactiveMode: IfAvailable
      provideClusterInfo: false
```

See [oidc-login setup](https://github.com/int128/kubelogin#setup) for details

1. Run `./k8s/run-kind.sh`. This script will
  - create a kind cluster
  - deploy UAA on the cluster
  - configure the cluster to use the UAA above as an OIDC provider
  - targets the `uaac` CLI to the UAA and gets an `admin` token that is stored in `~/.uaa/admin_client_secret.json`
  - create an UAA client that matches the KUBECONFIG user above
  - create user `pesho` with password `pesho` in UAA
  - create a `ClusterRoleBinding` that grants `pesho` the permission to only list pods

1. Try to list pods as user `oidc:pesho`, that should succeed:

```kubectl --user=oidc get pods```

1. Try to list namespaces as user `oidc:pesho`, that should fail with `forbidden` error:

```kubectl --user=oidc get ns```
