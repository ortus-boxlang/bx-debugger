package ortus.boxlang.bxdebugger;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

public class DAPTestUtils {

	private static final Logger LOGGER = Logger.getLogger( DAPTestUtils.class.getName() );

	public static CompletableFuture<Void> sendConfigurationDone( IDebugProtocolServer server ) {
		LOGGER.info( "Sending configuration done request" );
		ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
		CompletableFuture<Void>		configDoneResult	= server.configurationDone( configArgs );

		return configDoneResult;
	}
}
