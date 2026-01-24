package ortus.boxlang.bxdebugger.instrumentation;

import java.util.Map;

import ortus.boxlang.runtime.context.IBoxContext;

/**
 * @deprecated This class is no longer needed. The debugging functionality has been
 *             moved to {@code ortus.boxlang.runtime.services.DebuggerService} which
 *             is built into the BoxLang runtime.
 *
 * @see ortus.boxlang.runtime.services.DebuggerService
 */
@Deprecated
public class DebugUtils {

	public static Map<String, String> snapshotContext( IBoxContext ctx ) {
		return Map.of( "applicationName", ctx.getApplicationName() );
	}
}
