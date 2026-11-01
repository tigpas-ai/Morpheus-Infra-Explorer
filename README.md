# Infra Explorer – Morpheus plugin

A Morpheus plugin for hierarchical infrastructure navigation. A button at the bottom left, available on every
Morpheus page, opens an overlay with a cluster tree (clusters → hosts → VMs), VM folders (based on tags), the
networks and the storage of each cluster. From the tree you can open the VM page and console, start and stop VMs and open
reconfigure. The first version supports HVM clusters. Details and open points: [`REQUIREMENTS.md`](REQUIREMENTS.md).

> Tested with **HPE Morpheus 9.1.0** (plugin API 1.4.1) and HVM clusters.

## Features
- **Clusters** – clusters, their hosts and the VMs of each host, with power state and CPU/RAM utilization.
- **Instances** – VMs grouped by tenant and folders. Folders are plain Morpheus tags on the server
  (`Folder-1=Prod`, `Folder-2=Web` → `Prod / Web`), so they stay visible in the API and survive uninstalling the plugin.
  VMs can be moved by context menu or drag and drop; folders and levels can be renamed.
- **Networks** – networks of each cluster with VLAN, IP networks (CIDR, subnets) and the number of VMs, and below
  them the VMs with their addresses in that network. A VM with several network interfaces appears below each of its
  networks.
- **Storage** – datastores of each cluster with capacity and usage. If the separate Storage Path Monitor plugin
  (code `storage-path-monitor`) is installed, shared datastores also show the state of their storage paths per node
  (e.g. `Nodes: 2 ✅, 1 ⚠️`); clicking it opens that plugin's detail view.
- Actions via context menu (right-click, Shift+F10 or the context menu key): details page, console, power,
  reconfigure, convert to managed, maintenance mode, cluster refresh, new instance in a folder.
- The permissions of the signed-in user apply: nothing is shown or offered that the user could not see or do in
  Morpheus.
- Languages: English (default) and German. The overlay follows the language of the Morpheus UI (user or appliance
  locale, otherwise the browser language).
- The info button (ⓘ) in the overlay shows version, author and license.

## Contents
| Path | Content |
|---|---|
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | What the plugin should do, technical findings, open points, acceptance |
| [`CONTRACT.md`](CONTRACT.md) | **External interfaces** (endpoints, options, data formats) for API clients and scripts |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes per version |
| [`SECURITY.md`](SECURITY.md) | How to report a vulnerability |
| `src/` | Plugin code (Groovy, Morpheus plugin API), overlay script and styles, unit tests |
| `src/integrationTest/` | Integration test against a Morpheus appliance ([`docs/integration-test.md`](docs/integration-test.md)) |
| `scripts/` | `build-release.sh`: builds the jar of a released version from its git tag |

## Quick start
Requirements: Morpheus 9.1.0 or later; for building **JDK 17** (the Gradle wrapper 8.3 runs on Java up to 20, the
Morpheus manager runs Java 17; the code is compiled for Java 11). Any JDK 17 distribution works, for example Eclipse
Temurin 17 from https://adoptium.net – `java -version` must report `17.x`, or set `JAVA_HOME` accordingly.

```bash
./gradlew shadowJar   # builds build/libs/infra-explorer-<version>-all.jar
```

### Install
Upload the jar in Morpheus under *Administration → Integrations → Plugins → Upload File*, or via the API:
```bash
curl -fsS -H "Authorization: Bearer $TOKEN" -F "file=@build/libs/infra-explorer-<version>-all.jar" \
  "https://<morpheus>/api/plugins/upload"
```
Installation runs asynchronously; `GET /api/plugins` shows the status (`loaded` or `error`). Uploading a newer jar
updates the plugin and keeps its settings.

Plugins run inside the Morpheus manager – test on a non-production appliance first. To disable the plugin in an
emergency: `PUT /api/plugins/<id>` with `{"plugin":{"enabled":false}}`.

### Uninstall
Delete the plugin under *Administration → Integrations → Plugins*, or `DELETE /api/plugins/<id>`. The folder tags on
the servers (`Folder-1` …) are ordinary Morpheus tags and stay in place; remove them in Morpheus if no longer needed.

### Configuration
| Setting | Default | Meaning |
|---|---|---|
| Folder tags per level (`folderTags`) | `Folder-1,Folder-2,Folder-3` | Comma-separated tag names that form the folder levels, outermost first |

The setting can be changed under *Administration → Integrations → Plugins*, via the API or with "Edit levels …" in
the instances view of the overlay.

## Scope
- Cluster types: HVM. Type-specific code sits behind an interface (`InfraSource`) so further cluster types can be
  added.
- Read and navigate; actions use existing Morpheus functions (links, API) – no own provisioning logic.
- The storage path state needs the separate Storage Path Monitor plugin; without it the storage view shows capacity
  and usage only.

## Development
```bash
./gradlew test              # unit tests (also run by CI)
./gradlew shadowJar         # build the jar
./gradlew integrationTest   # installs and checks the plugin on a Morpheus appliance, then restores the previous state
```

The integration test needs the connection data of a test appliance in environment variables and puts back the
previously installed version with its settings (or removes the plugin if it was not installed). Details:
[`docs/integration-test.md`](docs/integration-test.md).

1. Make the change and run `./gradlew test`.
2. Build with `./gradlew shadowJar` and upload the jar to a test appliance, or run `./gradlew integrationTest`.
3. Check the result in the UI and/or via the API.
4. Changed an endpoint, option or data format? → update `CONTRACT.md` in the same commit.

Releases are annotated tags `v<version>` (version in `gradle.properties`, entry in `CHANGELOG.md`);
`scripts/build-release.sh <version>` builds the jar of a released version into `build/releases/`.

### Translations
All overlay texts live in [`src/assets/javascripts/infra-explorer-messages.js`](src/assets/javascripts/infra-explorer-messages.js),
one object per language code. To add a language, copy the `en` object, name it after the language code (e.g. `fr`,
or `pt-br` for a regional variant) and translate the values; keep the keys and the `{placeholders}`. Keys ending in
`.one`/`.other` are plural forms ([`Intl.PluralRules`](https://developer.mozilla.org/docs/Web/JavaScript/Reference/Global_Objects/Intl/PluralRules));
add `.few`, `.many` … where the language needs them. Missing keys fall back to English. Messages returned by the
plugin routes (e.g. validation errors) and the plugin setting label stay English.

The Gradle project was created with the plugin generator of the Morpheus Developer Portal
(https://developer.morpheusdata.com/start/, `morpheusVersion=9.1.x`); the plugin API was then set to 1.4.1
(see `REQUIREMENTS.md`).

## References
- [HPE Morpheus Software documentation 9.1.0](https://support.hpe.com/hpesc/public/docDisplay?docId=sd00008772en_us&docLocale=en_US)
- [Morpheus Developer Portal – plugin development](https://developer.morpheusdata.com/)
- [Morpheus API reference](https://apidocs.morpheusdata.com/)

## Author
Pascal Liehne – https://github.com/tigpas-ai/morpheus-infra-explorer

## License
[MIT](LICENSE), © 2026 Pascal Liehne. HPE and Morpheus are trademarks of Hewlett Packard Enterprise; this project is
not affiliated with HPE.
