package ortus.boxlang.bxdebugger;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import com.sun.jdi.StringReference;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;

/**
 * Handles DAP {@code evaluate} requests that are BoxLang dump calls
 * ({@code writeDump} / {@code dump}).
 *
 * <p>
 * Flow:
 * <ol>
 * <li>Detects the dump call via {@link DumpExpressionParser}.</li>
 * <li>Builds and executes an injected {@code savecontent} script in the
 * target JVM to capture the HTML output of {@code writeDump}.</li>
 * <li>Fires the custom {@code boxlang.dump} DAP event via
 * {@link IBoxLangDebugClient#boxlangDump}.</li>
 * <li>Returns a short confirmation {@link EvaluateResponse}.</li>
 * </ol>
 */
public class DumpRequestHandler {

	private static final Logger			LOGGER	= Logger.getLogger( DumpRequestHandler.class.getName() );

	private final VMController			vmController;
	private final IBoxLangDebugClient	client;
	private final DumpExpressionParser	parser;
	private final int					defaultTop;

	public DumpRequestHandler( VMController vmController, IBoxLangDebugClient client, DumpExpressionParser parser, int defaultTop ) {
		this.vmController	= vmController;
		this.client			= client;
		this.parser			= parser;
		this.defaultTop		= defaultTop;
	}

	/**
	 * Handles a dump evaluate request.
	 *
	 * @param args the DAP evaluate arguments
	 *
	 * @return an {@link EvaluateResponse} containing a confirmation message
	 */
	public CompletableFuture<EvaluateResponse> handle( EvaluateArguments args ) {
		String	expression	= args.getExpression();
		int		frameId		= args.getFrameId();

		String	label		= parser.extractLabel( expression );
		int		top			= parser.extractTopArg( expression ).orElse( defaultTop );
		String	varExpr		= parser.extractVarExpression( expression ).orElse( expression );

		String	script		= buildDumpScript( varExpr, top );

		LOGGER.info( "Executing dump for label='" + label + "' top=" + top + " frameId=" + frameId );

		return vmController.evaluateExpressionInFrame( frameId, script ).thenApply( result -> {
			if ( ! ( result instanceof StringReference html ) || html.value().isBlank() ) {
				throw new IllegalStateException( "Dump produced no HTML output" );
			}
			client.boxlangDump( new BoxLangDumpEventBody( html.value(), label, Instant.now().toString() ) );
			EvaluateResponse response = new EvaluateResponse();
			response.setResult( "Variable '" + label + "' dumped to editor" );
			response.setVariablesReference( 0 );
			return response;
		} );
	}

	private String buildDumpScript( String varExpr, int top ) {
		return "bx:savecontent variable=\"__bxDumpOutput__\" {\n"
		    + " writeDump( var=" + varExpr + ", top=" + top + ", format='html', output='buffer' )\n"
		    + "}\n"
		    + "return __bxDumpOutput__;";
	}

}
