# Integration test

`./gradlew integrationTest` builds the plugin, installs it on a real Morpheus appliance, checks it there and then
puts the appliance back into the state it was in before. It is not part of `./gradlew test` or `check`.

> The test replaces the installed plugin **for all users** of the appliance for about a minute. Use a test
> appliance, and run only one integration test per appliance at a time.

## Running it

```bash
export MORPHEUS_URL=https://morpheus.example.com
export MORPHEUS_TOKEN=…                 # API token, see below
export MORPHEUS_ADMIN_USER=admin MORPHEUS_ADMIN_PASSWORD=…
export MORPHEUS_CACERT=/path/to/ca.pem  # only for a self-signed certificate
export LAB_TEST_PREFIX=it-test-
./gradlew integrationTest
```

Only the offline part (restore logic, no appliance needed):
`./gradlew integrationTest --tests '*PluginBaselineSpec'`.

The report is written to `build/reports/tests/integrationTest/index.html`; the console shows each step and the state
before and after the test (`[integration test] …`).

## Variables
| Variable | Required | Meaning |
|---|---|---|
| `MORPHEUS_URL` | yes | Base URL of the appliance |
| `MORPHEUS_TOKEN` | yes | API token of an administrator: manage plugins (`admin-plugins`), users and roles |
| `MORPHEUS_ADMIN_USER`, `MORPHEUS_ADMIN_PASSWORD` | yes | UI login of an administrator (plugin routes only accept a UI session) |
| `LAB_TEST_PREFIX` | yes | Name prefix of all test objects, e.g. `it-test-` (letters, digits, `.`, `_`, `-`) |
| `MORPHEUS_CACERT` | no | PEM file with the CA certificate of the appliance |
| `MORPHEUS_INSECURE` | no | `1` = do not check the certificate (emergency only) |
| `INFRA_EXPLORER_RESTORE_JAR` | no | Jar of the installed version, or a directory with `infra-explorer-<version>-all.jar` (see below) |
| `INFRA_EXPLORER_TEST_TIMEOUT` | no | Seconds to wait for an installation to finish (default 300) |

## What is checked
1. Upload of the built jar (`build/libs/infra-explorer-<version>-all.jar`): status `loaded`, the new version, enabled,
   same plugin ID and settings as before.
2. Pages of signed-in users contain the overlay of the new version (`data-ie-version`) and its three assets load;
   the login page does not contain it.
3. `clusters` matches the clusters of the API; for up to five clusters `hosts`, `vms` (up to five hosts),
   `datastores` and `networks` answer and fit the API (`/api/clusters/<id>`, `/api/clusters/<id>/datastores`).
4. `vm-folders` uses the levels of the setting `folderTags`; a changed setting takes effect immediately and is reset.
5. Invalid parameters → JSON error with 400/404; without a UI session, and with the API token, the routes redirect to
   the login page.
6. Permissions with a test user (`<prefix>ie-user`, role `<prefix>ie-role`): without any permission no data; with
   only `infrastructure-cluster` `read` clusters without actions, and 403 for hosts, datastores, networks and
   vm-folders.
7. Optional: if the Storage Path Monitor plugin (`storage-path-monitor`) is installed and enabled, its path state of
   each cluster refers only to datastores and hosts that Infra Explorer shows for the cluster. Otherwise the step is
   reported as skipped and the test stays green.

## Restoring the previous state
Before changing anything the test records the state of the plugin: installed version, settings (`config`) and enabled
state – or that it is not installed. Afterwards, also when a step fails:

- The test user and role are deleted (also leftovers of an aborted earlier run with the same names).
- **Plugin was installed:** the jar of the previous version is uploaded again, then settings and enabled state are
  set back if they differ. Morpheus accepts an older version by upload (downgrade) and keeps plugin ID, settings and
  enabled state across uploads.
- **Plugin was not installed:** the plugin is removed (`DELETE /api/plugins/<id>`). This path is covered by the
  offline tests but has not yet been run against an appliance.

An upload counts as installed when the plugin shows the version of the jar, a final status and a changed
`lastUpdated` – so an upload of the same version number is not mistaken for the one installed before. Settings are
written with `PUT /api/plugins/<id>`, which replaces the whole `config` (unset settings appear as `null`).
- Finally the state is compared with the recorded one; any difference fails the test with `RESTORE FAILED` and the
  expected state.

The jar of the installed version is looked up in this order:
1. `INFRA_EXPLORER_RESTORE_JAR` (a file, or a directory with `infra-explorer-<version>-all.jar`),
2. `build/releases/infra-explorer-<version>-all.jar`,
3. built from the git tag `v<version>` with [`scripts/build-release.sh`](../scripts/build-release.sh) into
   `build/releases/`.

Its manifest must name the installed code and version. If no matching jar is found, or the installed plugin is not
`loaded`, the test stops **before** any change. Note: a restore installs the released build of that version; a
development build with the same version number that was installed by hand is replaced by it.

## If the restore fails
No restore runs if the test process itself is killed (Ctrl+C, kill, timeout of a CI job) – restore by hand as below.
The expected state is the line `state before the test: …` at the start of the output; a failed restore names it as
well. Disable the plugin
(`PUT /api/plugins/<id>` with `{"plugin":{"enabled":false}}`) or upload the previous jar under
*Administration → Integrations → Plugins*, then set its settings and enabled state as shown. Remove leftover test
objects: users and roles whose names start with `LAB_TEST_PREFIX` followed by `ie-`.
