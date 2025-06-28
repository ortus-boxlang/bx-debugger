package ortus.boxlang.moduleslug;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
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

	private static final Logger							LOGGER						= Logger.getLogger( BoxDebugServer.class.getName() );

	// Debug session state
	private VirtualMachine								vm;
	private LanguageClient								client;
	private int											breakpointIdCounter			= 1;

	// Breakpoint storage - organized by file path
	private final Map<String, List<PendingBreakpoint>>	pendingBreakpointsByFile	= new ConcurrentHashMap<>();
	private final Map<Integer, PendingBreakpoint>		pendingBreakpointsById		= new ConcurrentHashMap<>();

	/**
	 * Represents a breakpoint that has been requested but not yet verified
	 */
	public static class PendingBreakpoint {

		private final Source			source;
		private final SourceBreakpoint	sourceBreakpoint;
		private final Breakpoint		breakpoint;
		private final long				timestamp;

		public PendingBreakpoint( Source source, SourceBreakpoint sourceBreakpoint, Breakpoint breakpoint ) {
			this.source				= source;
			this.sourceBreakpoint	= sourceBreakpoint;
			this.breakpoint			= breakpoint;
			this.timestamp			= System.currentTimeMillis();
		}

		public Source getSource() {
			return source;
		}

		public SourceBreakpoint getSourceBreakpoint() {
			return sourceBreakpoint;
		}

		public Breakpoint getBreakpoint() {
			return breakpoint;
		}

		public long getTimestamp() {
			return timestamp;
		}

		public String getFilePath() {
			return source != null ? source.getPath() : null;
		}

		@Override
		public String toString() {
			return String.format( "PendingBreakpoint[id=%d, file=%s, line=%d, verified=%s]",
			    breakpoint.getId(), getFilePath(), breakpoint.getLine(), breakpoint.isVerified() );
		}
	}

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
		return CompletableFuture.supplyAsync( () -> {
			SetBreakpointsResponse	response			= new SetBreakpointsResponse();
			List<Breakpoint>		responseBreakpoints	= new ArrayList<>();

			// Clear existing pending breakpoints for this file first
			// (setBreakpoints replaces all breakpoints for the file)
			if ( args.getSource() != null && args.getSource().getPath() != null ) {
				clearPendingBreakpointsForFile( args.getSource().getPath() );
			}

			if ( args.getBreakpoints() != null ) {
				for ( SourceBreakpoint sourceBreakpoint : args.getBreakpoints() ) {
					Breakpoint breakpoint = new Breakpoint();

					// Before launch: Accept breakpoints optimistically but mark as unverified
					breakpoint.setId( generateBreakpointId() );
					breakpoint.setLine( sourceBreakpoint.getLine() );
					breakpoint.setVerified( false ); // Key: Mark as unverified until program starts
					breakpoint.setMessage( "Breakpoint will be verified when program starts" );

					// Store the pending breakpoint for later verification
					storePendingBreakpoint( args.getSource(), sourceBreakpoint, breakpoint );

					responseBreakpoints.add( breakpoint );

					LOGGER.info( "Added unverified breakpoint at line " + sourceBreakpoint.getLine() +
					    " in " + args.getSource().getPath() );
				}
			}

			response.setBreakpoints( responseBreakpoints.toArray( new Breakpoint[ 0 ] ) );

			return response;
		} );
	}

	/**
	 * Generate a unique breakpoint ID
	 */
	private int generateBreakpointId() {
		return breakpointIdCounter++;
	}

	/**
	 * Store pending breakpoint for later verification
	 */
	private void storePendingBreakpoint( Source source, SourceBreakpoint sourceBreakpoint, Breakpoint breakpoint ) {
		PendingBreakpoint	pending		= new PendingBreakpoint( source, sourceBreakpoint, breakpoint );

		// Store by file path for quick lookup during verification
		String				filePath	= normalizeFilePath( source.getPath() );
		pendingBreakpointsByFile.computeIfAbsent( filePath, k -> new ArrayList<>() ).add( pending );

		// Store by ID for quick lookup when client references breakpoint
		pendingBreakpointsById.put( breakpoint.getId(), pending );

		LOGGER.info( "Stored pending breakpoint: " + pending );
	}

	/**
	 * Get all pending breakpoints for a specific file
	 */
	public List<PendingBreakpoint> getPendingBreakpointsForFile( String filePath ) {
		String normalizedPath = normalizeFilePath( filePath );
		return pendingBreakpointsByFile.getOrDefault( normalizedPath, new ArrayList<>() );
	}

	/**
	 * Get pending breakpoint by ID
	 */
	public PendingBreakpoint getPendingBreakpointById( int id ) {
		return pendingBreakpointsById.get( id );
	}

	/**
	 * Remove a pending breakpoint (when verified or deleted)
	 */
	public void removePendingBreakpoint( int breakpointId ) {
		PendingBreakpoint pending = pendingBreakpointsById.remove( breakpointId );
		if ( pending != null ) {
			String					filePath		= normalizeFilePath( pending.getFilePath() );
			List<PendingBreakpoint>	fileBreakpoints	= pendingBreakpointsByFile.get( filePath );
			if ( fileBreakpoints != null ) {
				fileBreakpoints.remove( pending );
				if ( fileBreakpoints.isEmpty() ) {
					pendingBreakpointsByFile.remove( filePath );
				}
			}
			LOGGER.info( "Removed pending breakpoint: " + pending );
		}
	}

	/**
	 * Clear all pending breakpoints for a file (when setting new breakpoints)
	 */
	public void clearPendingBreakpointsForFile( String filePath ) {
		String					normalizedPath	= normalizeFilePath( filePath );
		List<PendingBreakpoint>	fileBreakpoints	= pendingBreakpointsByFile.remove( normalizedPath );
		if ( fileBreakpoints != null ) {
			for ( PendingBreakpoint pending : fileBreakpoints ) {
				pendingBreakpointsById.remove( pending.getBreakpoint().getId() );
			}
			LOGGER.info( "Cleared " + fileBreakpoints.size() + " pending breakpoints for file: " + normalizedPath );
		}
	}

	/**
	 * Get all pending breakpoints (for debugging/monitoring)
	 */
	public Map<String, List<PendingBreakpoint>> getAllPendingBreakpoints() {
		return new HashMap<>( pendingBreakpointsByFile );
	}

	/**
	 * Normalize file path for consistent storage keys
	 */
	private String normalizeFilePath( String filePath ) {
		if ( filePath == null ) {
			return "";
		}

		// Convert to absolute path and normalize separators
		Path path = Paths.get( filePath ).toAbsolutePath().normalize();
		return path.toString().replace( '\\', '/' );
	}
}
