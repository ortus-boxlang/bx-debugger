package ortus.boxlang.bxdebugger.instrumentation;

import java.util.Map;

import ortus.boxlang.runtime.context.IBoxContext;

public class DebugUtils {

	public static Map<String, String> snapshotContext( IBoxContext ctx ) {
		return Map.of( "applicationName", ctx.getApplicationName() );
	}
}
