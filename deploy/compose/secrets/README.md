# Local secret files

`scripts/bootstrap.sh` generates the files in this directory on first run; they are
gitignored and contain development-only values.

They exist as files rather than as environment variables because the services read
configuration from `configtree:/run/secrets/`, which is the same mechanism used in
production, where the files are projected from a SOPS-encrypted Kubernetes secret. Using
env vars locally and files in production would mean the code path that reads secrets is
never exercised until it fails in a cluster.

The file name is the property name. `spring.datasource.password` in this directory becomes
the Spring property of the same name.
