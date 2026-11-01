package eu.liehne.morpheus.infraexplorer.it

import spock.lang.Specification
import spock.lang.TempDir

import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Offline test of capturing and restoring the plugin state (no appliance needed). */
class PluginBaselineSpec extends Specification {

	@TempDir
	File dir

	FakePlugins plugins = new FakePlugins()

	def "aborts before any change if the installed version cannot be restored"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [folderTags: 'A,B'], true, 'loaded')

		Map<String, Closure<File>> resolvers = [
			none         : { String v -> null },
			missing      : { String v -> new File('/nonexistent.jar') },
			otherVersion : { String v -> jar('infra-explorer', '0.18.0') },
			otherPlugin  : { String v -> jar('other-plugin', v) }]

		when:
		PluginBaseline.capture(plugins, 'infra-explorer', resolvers[restoreJar])

		then:
		PreconditionException e = thrown()
		e.message.contains(message)
		plugins.calls.every { it == 'find' }

		where:
		restoreJar     || message
		'none'         || 'no jar of the installed version'
		'missing'      || 'no jar of the installed version'
		'otherVersion' || 'does not match'
		'otherPlugin'  || 'does not match'
	}

	def "aborts if the installed plugin is not loaded"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [:], true, 'error')

		when:
		PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }

		then:
		PreconditionException e = thrown()
		e.message.contains("status 'error'")
	}

	def "restores the previous version, its settings and a disabled state"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [folderTags: 'A,B'], false, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }

		and: 'the test installs a new version, enables it and changes a setting'
		plugins.install('infra-explorer', '0.21.0', [folderTags: 'X'], true, 'loaded')

		when:
		List<String> steps = baseline.restore(plugins, true, 5)

		then:
		steps.size() == 3
		with(plugins.find('infra-explorer')) {
			version == '0.19.1'
			enabled == false
			config == [folderTags: 'A,B']
		}
		plugins.uploads == ['infra-explorer-0.19.1-all.jar']
	}

	def "uploads the previous jar again after an upload of the same version"() {
		given:
		plugins.install('infra-explorer', '0.21.0', [:], true, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }

		when:
		baseline.restore(plugins, true, 5)

		then:
		plugins.uploads == ['infra-explorer-0.21.0-all.jar']
	}

	def "does not upload again if the upload never happened and nothing changed"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [folderTags: 'A'], true, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }

		when:
		List<String> steps = baseline.restore(plugins, false, 5)

		then:
		steps.isEmpty()
		plugins.uploads.isEmpty()
	}

	def "removes the plugin again if it was not installed before"() {
		given:
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> null }
		plugins.install('infra-explorer', '0.21.0', [:], true, 'loaded')

		when:
		List<String> steps = baseline.restore(plugins, true, 5)

		then:
		steps == ['removed infra-explorer 0.21.0']
		plugins.find('infra-explorer') == null
	}

	def "fails if the restored state differs"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [folderTags: 'A'], true, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }
		plugins.ignoreUpdates = true
		plugins.install('infra-explorer', '0.21.0', [folderTags: 'X'], true, 'loaded')

		when:
		baseline.restore(plugins, true, 5)

		then:
		IllegalStateException e = thrown()
		e.message.contains('not restored')
		e.message.contains('settings')
	}

	def "restores empty settings (PUT replaces the whole config)"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [:], true, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }
		plugins.install('infra-explorer', '0.21.0', [folderTags: 'X'], true, 'loaded')

		when:
		baseline.restore(plugins, true, 5)

		then:
		plugins.find('infra-explorer').config == [:]
	}

	def "a restore whose upload ends in status error fails"() {
		given:
		plugins.install('infra-explorer', '0.19.1', [:], true, 'loaded')
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> jar('infra-explorer', v) }
		plugins.install('infra-explorer', '0.21.0', [:], true, 'loaded')
		plugins.installStatus = 'error'

		when:
		baseline.restore(plugins, true, 5)

		then:
		IllegalStateException e = thrown()
		e.message.contains('could not be loaded')
	}

	def "nothing to do if the plugin was not installed and the upload failed"() {
		given:
		PluginBaseline baseline = PluginBaseline.capture(plugins, 'infra-explorer') { String v -> null }

		expect:
		baseline.restore(plugins, true, 5).isEmpty()
		!plugins.calls.contains('delete')
	}

	def "compares settings independent of value types"() {
		expect:
		PluginBaseline.normalize([b: 2, a: '1', c: null]) == [a: '1', b: '2']
		PluginBaseline.normalize(null) == [:]
		PluginBaseline.normalize([folderTags: null]) == PluginBaseline.normalize([:])
	}

	private File jar(String code, String version) {
		Manifest manifest = new Manifest()
		manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, '1.0')
		manifest.mainAttributes.putValue('Morpheus-Code', code)
		manifest.mainAttributes.putValue('Plugin-Version', version)
		File file = new File(dir, "$code-$version-all.jar")
		new JarOutputStream(new FileOutputStream(file), manifest).close()
		file
	}

	/** In-memory plugin list: an upload installs the version from the jar's manifest, keeping id, settings and enabled. */
	static class FakePlugins implements PluginOperations {
		Map<String, Map> plugins = [:]
		List<String> calls = []
		List<String> uploads = []
		boolean ignoreUpdates
		int nextId = 30

		void install(String code, String version, Map config, boolean enabled, String status) {
			Map existing = plugins[code]
			plugins[code] = [id: existing?.id ?: nextId++, code: code, version: version, status: status,
				enabled: enabled, config: new LinkedHashMap(config)]
		}

		Map find(String code) {
			calls << 'find'
			plugins[code] ? new LinkedHashMap(plugins[code]) : null
		}

		/** Status the next installation ends with. */
		String installStatus = 'loaded'

		Map install(PluginJar jar, int timeoutSeconds) {
			calls << 'upload'
			uploads << jar.file.name
			Map existing = plugins[jar.code]
			install(jar.code, jar.version, existing?.config ?: [:], existing ? existing.enabled : true, installStatus)
			if (installStatus == 'error') {
				throw new IllegalStateException("plugin $jar could not be loaded")
			}
			find(jar.code)
		}

		void update(Object id, Map plugin) {
			calls << 'update'
			if (ignoreUpdates) {
				return
			}
			Map target = plugins.values().find { it.id == id }
			plugin.each { k, v -> target[k] = v instanceof Map ? new LinkedHashMap(v) : v }
		}

		void delete(Object id) {
			calls << 'delete'
			plugins.removeAll { k, v -> v.id == id }
		}

		void waitForAbsence(String code, int timeoutSeconds) {
			assert !plugins[code]
		}
	}
}
