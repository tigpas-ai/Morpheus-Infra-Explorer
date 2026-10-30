package eu.liehne.morpheus.infraexplorer

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import eu.liehne.morpheus.infraexplorer.source.HvmInfraSource
import eu.liehne.morpheus.infraexplorer.source.InfraSource

class InfraExplorerPlugin extends Plugin {

    @Override
    String getCode() {
        return 'infra-explorer'
    }

    @Override
    void initialize() {
        this.setName("Infra Explorer")
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
