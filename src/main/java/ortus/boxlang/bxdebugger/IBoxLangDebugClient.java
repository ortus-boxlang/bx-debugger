package ortus.boxlang.bxdebugger;

import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;

/**
 * Extended DAP client interface that adds support for the custom
 * <code>boxlang.dump</code> event.
 */
public interface IBoxLangDebugClient extends IDebugProtocolClient {

	@JsonNotification( "boxlang.dump" )
	default void boxlangDump( BoxLangDumpEventBody body ) {
		// Default no-op — override to handle dump events
	}
}
