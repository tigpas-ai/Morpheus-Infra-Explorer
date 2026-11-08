package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification

class InfraExplorerPluginSpec extends Specification {

	def "initialize registers the overlay provider and the controller"() {
		given:
		InfraExplorerPlugin plugin = new InfraExplorerPlugin()
		plugin.morpheus = Stub(MorpheusContext)
		plugin.classLoader = getClass().classLoader

		when:
		plugin.initialize()

		then:
		plugin.name == 'Infra Explorer'
		plugin.author == 'Pascal Liehne'
		plugin.description.contains('networks')
		plugin.sourceCodeLocationUrl == 'https://github.com/tigpas-ai/Morpheus-Infra-Explorer'
		plugin.websiteUrl == plugin.sourceCodeLocationUrl
		plugin.issueTrackerUrl == plugin.sourceCodeLocationUrl + '/issues'
		plugin.providers*.code == [InfraExplorerOverlayProvider.PROVIDER_CODE]
		plugin.controllers*.code == ['infra-explorer.controller']
		plugin.controllers[0].routes.size() == 9
		plugin.settings*.fieldName == ['folderTags']
		plugin.hasCustomRender()
	}
}
