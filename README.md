# Infra Explorer – Morpheus plugin

A Morpheus plugin for hierarchical infrastructure navigation. A button at the bottom left, available on every
Morpheus page, opens an overlay with a cluster tree (clusters → hosts → VMs), VM folders (based on tags) and the
storage of each cluster. From the tree you can open the VM page and console, start and stop VMs and open
reconfigure. The first version supports HVM clusters. Details and open points: [`REQUIREMENTS.md`](REQUIREMENTS.md).

## Features
- **Clusters** – clusters, their hosts and the VMs of each host, with power state and CPU/RAM utilization.
- **Instances** – VMs grouped by tenant and folders. Folders are plain Morpheus tags on the server
  (`Folder-1=Prod`, `Folder-2=Web` → `Prod / Web`), so they stay visible in the API and survive uninstalling the plugin.
  VMs can be moved by context menu or drag and drop; folders and levels can be renamed.
- **Storage** – datastores of each cluster with capacity and usage.
- Actions via context menu (right-click, Shift+F10 or the context menu key): details page, console, power,
  reconfigure, convert to managed, maintenance mode, cluster refresh, new instance in a folder.
- The permissions of the signed-in user apply: nothing is shown or offered that the user could not see or do in
  Morpheus.

## Requirements
- Morpheus 9.1.0 or later (plugin API 1.4.1)
- For building: **JDK 17**. The Gradle wrapper 8.3 runs on Java up to 20, and the Morpheus manager runs Java 17.
  The code is compiled for Java 11.

Any JDK 17 distribution works, for example Eclipse Temurin 17 from https://adoptium.net. Make sure
`java -version` reports `17.x` or set `JAVA_HOME` accordingly.

## Build and test
```bash
./gradlew test        # unit tests
./gradlew shadowJar   # builds build/libs/infra-explorer-<version>-all.jar
```

## Install
Upload the jar in Morpheus under *Administration → Integrations → Plugins → Upload File*, or via the API:
```bash
curl -fsS -H "Authorization: Bearer $TOKEN" -F "file=@build/libs/infra-explorer-<version>-all.jar" \
  "https://<morpheus>/api/plugins/upload"
```
Installation runs asynchronously; `GET /api/plugins` shows the status (`loaded` or `error`).

Plugins run inside the Morpheus manager – test on a non-production appliance first. To disable the plugin in an
emergency: `PUT /api/plugins/<id>` with `{"plugin":{"enabled":false}}`.

## Configuration
| Setting | Default | Meaning |
|---|---|---|
| Folder tags per level (`folderTags`) | `Folder-1,Folder-2,Folder-3` | Comma-separated tag names that form the folder levels, outermost first |

The setting can be changed under *Administration → Integrations → Plugins*, via the API or with "Edit levels …" in
the instances view of the overlay.

## Repository layout
| Path | Content |
|---|---|
| `REQUIREMENTS.md` | What the plugin should do, technical findings, open points, acceptance |
| `CONTRACT.md` | **External interfaces** (endpoints, options, data formats) for API clients and scripts |
| `CHANGELOG.md` | Changes per version |
| `src/` | Plugin code (Groovy, Morpheus plugin API), overlay script and styles, tests |

## Development
1. Make the change and run `./gradlew test`.
2. Build with `./gradlew shadowJar` and upload the jar to a test appliance.
3. Check the result in the UI and/or via the API.
4. Changed an endpoint, option or data format? → update `CONTRACT.md` in the same commit.

The Gradle project was created with the plugin generator of the Morpheus Developer Portal
(https://developer.morpheusdata.com/start/, `morpheusVersion=9.1.x`); the plugin API was then set to 1.4.1
(see `REQUIREMENTS.md`).

## License
MIT – see [`LICENSE`](LICENSE).
