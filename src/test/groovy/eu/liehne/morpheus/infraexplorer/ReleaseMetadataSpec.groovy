package eu.liehne.morpheus.infraexplorer

import spock.lang.Specification

/** Version, author, license and repository link are consistent across build, changelog and documentation. */
class ReleaseMetadataSpec extends Specification {

	static String version() {
		Properties props = new Properties()
		new File('gradle.properties').withInputStream { props.load(it) }
		props.getProperty('version')
	}

	def "the version in gradle.properties is a release version with a changelog entry"() {
		given:
		String version = version()

		expect:
		version ==~ /\d+\.\d+\.\d+/
		new File('CHANGELOG.md').text.contains("\n## ${version} – ")
	}

	def "the plugin version in the header of CONTRACT.md is the current version"() {
		expect:
		new File('CONTRACT.md').text.contains("\n- Plugin version: ${version()} (")
	}

	def "every repository link in the documentation points to the repository of the plugin"() {
		given:
		List<String> links = ['README.md', 'CHANGELOG.md', 'SECURITY.md'].collectMany { name ->
			(new File(name).text =~ /https:\/\/github\.com\/[^\s)`>]+/).collect { it as String }
		}

		expect:
		links.every { it == InfraExplorerPlugin.REPOSITORY_URL || it.startsWith(InfraExplorerPlugin.REPOSITORY_URL + '/') }
	}

	def "license and readme name the author, license and repository of the plugin"() {
		given:
		String license = new File('LICENSE').text
		String readme = new File('README.md').text

		expect:
		license.startsWith('MIT License')
		license =~ /Copyright \(c\) 20\d\d(-20\d\d)? ${InfraExplorerPlugin.AUTHOR}/
		InfraExplorerPlugin.LICENSE == 'MIT'
		readme.contains(InfraExplorerPlugin.REPOSITORY_URL)
		readme.contains(InfraExplorerPlugin.AUTHOR)
	}
}
