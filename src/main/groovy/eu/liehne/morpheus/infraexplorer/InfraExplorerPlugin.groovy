package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import eu.liehne.morpheus.infraexplorer.source.HvmInfraSource
import eu.liehne.morpheus.infraexplorer.source.InfraSource

class InfraExplorerPlugin extends Plugin {

	static final String AUTHOR = 'Pascal Liehne'
	static final String LICENSE = 'MIT'
	static final String DESCRIPTION = 'Hierarchical infrastructure navigation as an overlay: clusters, hosts, VMs, VM folders, networks and storage'
	static final String REPOSITORY_URL = 'https://github.com/tigpas-ai/Morpheus-Infra-Explorer'

    @Override
    String getCode() {
        return 'infra-explorer'
    }

    @Override
    void initialize() {
        this.setName("Infra Explorer")
		this.setAuthor(AUTHOR)
		this.setDescription(DESCRIPTION)
		this.setWebsiteUrl(REPOSITORY_URL)
		this.setSourceCodeLocationUrl(REPOSITORY_URL)
		this.setIssueTrackerUrl(REPOSITORY_URL + '/issues')
		// Own renderer: without it, Morpheus 9.1 adds the plugin's templates to the shared renderer when controllers
		// are registered, and that fails (UnsupportedOperationException in DynamicTemplateLoader).
		this.setRenderer(new HandlebarsRenderer('renderer', this.classLoader))
		this.settings << FolderSettings.optionType()
        
		this.registerProvider(new InfraExplorerOverlayProvider(this,this.morpheus))
		AccessPolicy accessPolicy = new AccessPolicy()
		List<InfraSource> sources = [new HvmInfraSource(this.morpheus, accessPolicy)]
		this.controllers.add(new InfraExplorerController(this, this.morpheus, sources, accessPolicy))
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        //nothing to do for now
    }
}
