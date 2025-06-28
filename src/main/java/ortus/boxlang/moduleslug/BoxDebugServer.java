package ortus.boxlang.moduleslug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import com.sun.jdi.VirtualMachine;

/**
 * BoxLang Debug Server implementation of the Debug Adapter Protocol
 */
public class BoxDebugServer implements IDebugProtocolServer {

	private static final Logger							LOGGER						= Logger.getLogger( BoxDebugServer.class.getName() );

	// Debug session state
	private VirtualMachine								vm;
	private IDebugProtocolClient						client;
	private Process										debuggedProcess;
	private ExecutorService								outputMonitorExecutor;
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
	public void connect( IDebugProtocolClient client ) {
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
		return CompletableFuture.supplyAsync( () -> {
			try {
				String program = ( String ) args.get( "program" );
				LOGGER.info( "Launching BoxLang program: " + program );

				// Build the command to run BoxLang
				ProcessBuilder	processBuilder	= new ProcessBuilder();

				List<String>	command			= new ArrayList<>();
				command.add( "java" );
				command.add( "-cp" );
				command.add( System.getProperty( "java.class.path" ) );
				command.add( "ortus.boxlang.runtime.BoxRunner" );
				command.add( program );

				String bxHome = ( String ) args.get( "bx-home" );
				if ( bxHome != null ) {
					command.add( "--bx-home" );
					command.add( bxHome );
				}

				processBuilder.command( command );

				// Start the process
				debuggedProcess = processBuilder.start();
				LOGGER.info( "Process started with PID: " + debuggedProcess.pid() );

				// Initialize output monitoring
				startOutputMonitoring();

				// TODO: Verify pending breakpoints against the actual source file
				verifyPendingBreakpoints();

				return null;
			} catch ( Exception e ) {
				LOGGER.severe( "Failed to launch program: " + e.getMessage() );
				e.printStackTrace();
				throw new RuntimeException( "Launch failed", e );
			}
		} );
	}

	/**
	 * Check if we're running in a test environment
	 */
	private boolean isTestEnvironment() {
		// Simple heuristic: check if junit is on the classpath
		try {
			Class.forName( "org.junit.jupiter.api.Test" );
			return true;
		} catch ( ClassNotFoundException e ) {
			return false;
		}
	}

	/**
	 * Start monitoring output from the debugged process
	 */
	private void startOutputMonitoring() {
		if ( outputMonitorExecutor == null ) {
			outputMonitorExecutor = Executors.newFixedThreadPool( 2 );
		}

		// Monitor stdout
		outputMonitorExecutor.submit( () -> monitorOutputStream( debuggedProcess.getInputStream(), "stdout" ) );

		// Monitor stderr
		outputMonitorExecutor.submit( () -> monitorOutputStream( debuggedProcess.getErrorStream(), "stderr" ) );

		LOGGER.info( "Started output monitoring for debugged process" );
	}

	/**
	 * Monitor an output stream and send events to the client
	 */
	private void monitorOutputStream( InputStream inputStream, String category ) {
		try ( BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream ) ) ) {
			String line;
			while ( ( line = reader.readLine() ) != null ) {
				if ( client != null ) {
					OutputEventArguments outputEvent = new OutputEventArguments();
					outputEvent.setOutput( line + System.lineSeparator() );
					outputEvent.setCategory( category );

					LOGGER.info( "Sending output event: " + line );
					client.output( outputEvent );
				}
			}
		} catch ( IOException e ) {
			LOGGER.warning( "Error reading from " + category + " stream: " + e.getMessage() );
		}
	}

	/**
	 * Verify pending breakpoints against the actual source (placeholder implementation)
	 */
	private void verifyPendingBreakpoints() {
		// TODO: Implement actual breakpoint verification logic
		// For now, just log that verification would happen here
		int totalPending = pendingBreakpointsById.size();
		if ( totalPending > 0 ) {
			LOGGER.info( "Would verify " + totalPending + " pending breakpoints against loaded sources" );
		}
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

	/**
	 * Clean up resources when debugging session ends
	 */
	public void cleanup() {
		LOGGER.info( "Cleaning up debug session resources" );

		// Shutdown output monitoring
		if ( outputMonitorExecutor != null ) {
			outputMonitorExecutor.shutdownNow();
			outputMonitorExecutor = null;
		}

		// Terminate debugged process if still running
		if ( debuggedProcess != null && debuggedProcess.isAlive() ) {
			debuggedProcess.destroyForcibly();
			debuggedProcess = null;
		}

		// Clean up VM if present
		if ( vm != null ) {
			try {
				vm.exit( 0 );
			} catch ( Exception e ) {
				LOGGER.warning( "Error closing VM: " + e.getMessage() );
			}
			vm = null;
		}

		LOGGER.info( "Debug session cleanup completed" );
	}

	public CompletableFuture<Void> disconnect( Map<String, Object> args ) {
		return CompletableFuture.runAsync( () -> {
			LOGGER.info( "Disconnect request received" );
			cleanup();
		} );
	}
}
