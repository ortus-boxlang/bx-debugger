package ortus.boxlang.moduleslug;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.services.LanguageClient;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;

/**
 * BoxLang Debug Server implementation of the Debug Adapter Protocol
 */
public class BoxDebugServer implements IDebugProtocolServer {

	private static final Logger	LOGGER	= Logger.getLogger( BoxDebugServer.class.getName() );
	private VirtualMachine		vm;

	private LanguageClient		client;

	/**
	 * Connect to the language client
	 */
	public void connect( LanguageClient client ) {
		this.client = client;
		LOGGER.info( "Connected to debug client" );
	}

	@Override
	public CompletableFuture<Capabilities> initialize( InitializeRequestArguments args ) {
		LOGGER.info( "Initialize request received from client: " + args.getClientName() );

		Capabilities capabilities = new Capabilities();
		capabilities.setSupportsConfigurationDoneRequest( true );
		capabilities.setSupportsTerminateRequest( true );
		capabilities.setSupportsFunctionBreakpoints( false );
		capabilities.setSupportsConditionalBreakpoints( true );
		capabilities.setSupportsHitConditionalBreakpoints( false );
		capabilities.setSupportsEvaluateForHovers( true );
		capabilities.setSupportsStepBack( false );
		capabilities.setSupportsSetVariable( false );
		capabilities.setSupportsRestartFrame( false );
		capabilities.setSupportsGotoTargetsRequest( false );
		capabilities.setSupportsStepInTargetsRequest( false );
		capabilities.setSupportsCompletionsRequest( false );
		capabilities.setSupportsModulesRequest( false );
		capabilities.setSupportsRestartRequest( false );
		capabilities.setSupportsExceptionOptions( false );
		capabilities.setSupportsValueFormattingOptions( false );
		capabilities.setSupportsExceptionInfoRequest( false );
		capabilities.setSupportTerminateDebuggee( true );
		capabilities.setSupportsDelayedStackTraceLoading( false );
		capabilities.setSupportsLoadedSourcesRequest( false );
		capabilities.setSupportsLogPoints( false );
		capabilities.setSupportsTerminateThreadsRequest( false );
		capabilities.setSupportsSetExpression( false );
		capabilities.setSupportsTerminateRequest( true );
		capabilities.setSupportsDataBreakpoints( false );
		capabilities.setSupportsReadMemoryRequest( false );
		capabilities.setSupportsDisassembleRequest( false );
		capabilities.setSupportsCancelRequest( false );
		capabilities.setSupportsBreakpointLocationsRequest( false );

		LOGGER.info( "Sending capabilities to client" );
		return CompletableFuture.completedFuture( capabilities );
	}

	@Override
	public CompletableFuture<Void> launch( Map<String, Object> args ) {
		LaunchingConnector				launchingConnector	= Bootstrap.virtualMachineManager().defaultConnector();
		Map<String, Connector.Argument>	arguments			= launchingConnector.defaultArguments();
		String							cp					= System.getProperty( "java.class.path" );

		String							program				= ( String ) args.get( "program" );

		arguments.get( "options" ).setValue( "-cp \"" + cp + "\"" );
		arguments.get( "main" ).setValue( "ortus.boxlang.runtime.BoxRunner" + " " + program );

		try {
			this.vm = launchingConnector.launch( arguments );
		} catch ( Exception e ) {
			LOGGER.severe( "Failed to launch VM: " + e.getMessage() );
			CompletableFuture.failedFuture( e );
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return CompletableFuture.completedFuture( null );
	}

	@Override
	public CompletableFuture<SetBreakpointsResponse> setBreakpoints( SetBreakpointsArguments args ) {
		LOGGER.info( "Set breakpoints request received: " + args.getBreakpoints().length + " breakpoints" );

		SetBreakpointsResponse response = new SetBreakpointsResponse();
		// response.setBreakpoints( args.getBreakpoints() );
		response.setBreakpoints( new Breakpoint[ 0 ] );

		return CompletableFuture.completedFuture( response );
	}
}
