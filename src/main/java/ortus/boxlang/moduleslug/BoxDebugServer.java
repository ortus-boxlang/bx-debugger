package ortus.boxlang.moduleslug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;

/**
 * BoxLang Debug Server implementation of the Debug Adapter Protocol
 */
public class BoxDebugServer implements IDebugProtocolServer {

	private static final Logger		LOGGER	= Logger.getLogger( BoxDebugServer.class.getName() );

	// Debug session state
	private VirtualMachine			vm;
	private IDebugProtocolClient	client;
	private Process					debuggedProcess;
	private ExecutorService			outputMonitorExecutor;
	private BreakpointManager		breakpointManager;
	
	// Exit handling state
	private volatile boolean		sessionActive = false;
	private volatile boolean		sessionCleaned = false;
	private final Object			exitLock = new Object();

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
				LOGGER.info( "Launching BoxLang program with JDI: " + program );

				// Use JDI to launch the program with debugging enabled
				LaunchingConnector				launchingConnector	= Bootstrap.virtualMachineManager().defaultConnector();
				Map<String, Connector.Argument>	arguments			= launchingConnector.defaultArguments();

				// Set up the command line arguments
				String							classpath			= System.getProperty( "java.class.path" );
				arguments.get( "options" ).setValue( "-cp \"" + classpath + "\"" );

				StringBuilder command = new StringBuilder();
				command.append( "ortus.boxlang.runtime.BoxRunner " + program );

				String bxHome = ( String ) args.get( "bx-home" );
				if ( bxHome != null ) {
					command.append( " --bx-home" );
					command.append( " " + bxHome );
				}

				arguments.get( "main" ).setValue( command.toString() );

				// For testing, use our test class, otherwise use BoxRunner
				// if ( isTestEnvironment() ) {
				// arguments.get( "main" ).setValue( "ortus.boxlang.moduleslug.TestOutputProducer " + program );
				// } else {
				// arguments.get( "main" ).setValue( "ortus.boxlang.runtime.BoxRunner " + program );
				// }

				// Launch the VM
				vm = launchingConnector.launch( arguments );
				LOGGER.info( "JDI VM launched successfully" );

				// Initialize or update breakpoint manager with the VM
				if ( breakpointManager == null ) {
					breakpointManager = new BreakpointManager( vm, client );
				} else {
					// Transfer pending breakpoints to a new manager with the VM
					BreakpointManager oldManager = breakpointManager;
					breakpointManager = new BreakpointManager( vm, client );

					// Transfer pending breakpoints from the temporary manager
					for ( Map.Entry<String, List<BreakpointManager.PendingBreakpoint>> entry : oldManager.getAllPendingBreakpoints().entrySet() ) {
						for ( BreakpointManager.PendingBreakpoint pending : entry.getValue() ) {
							breakpointManager.storePendingBreakpoint(
							    pending.getSource(),
							    pending.getSourceBreakpoint(),
							    pending.getBreakpoint()
							);
						}
					}
					LOGGER.info( "Transferred pending breakpoints to VM-enabled breakpoint manager" );
				}

				// Set actual breakpoints for all pending breakpoints
				verifyAndSetPendingBreakpoints();

				// Start breakpoint event processing
				breakpointManager.startEventProcessing();

				// Start output monitoring using the VM's process
				startOutputMonitoring();
				
				// Mark session as active and start process monitoring
				sessionActive = true;
				sessionCleaned = false;
				startProcessMonitoring();

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
	 * Start monitoring output from the debugged VM process
	 */
	private void startOutputMonitoring() {
		if ( outputMonitorExecutor == null ) {
			outputMonitorExecutor = Executors.newFixedThreadPool( 2 );
		}

		// Get the process from the VM
		if ( vm != null && vm.process() != null ) {
			Process process = vm.process();

			// Monitor stdout
			outputMonitorExecutor.submit( () -> monitorOutputStream( process.getInputStream(), "stdout" ) );

			// Monitor stderr
			outputMonitorExecutor.submit( () -> monitorOutputStream( process.getErrorStream(), "stderr" ) );
		}
	}
	
	/**
	 * Start monitoring the debugged process for exit events
	 */
	private void startProcessMonitoring() {
		if (vm != null && vm.process() != null) {
			Process process = vm.process();
			debuggedProcess = process;
			
			// Monitor process termination in a separate thread
			if (outputMonitorExecutor == null) {
				outputMonitorExecutor = Executors.newFixedThreadPool(3); // Increased for process monitoring
			}
			
			outputMonitorExecutor.submit(() -> {
				try {
					LOGGER.info("Starting process exit monitoring");
					int exitCode = process.waitFor();
					LOGGER.info("Debugged process exited with code: " + exitCode);
					
					// Handle the program exit
					handleProgramExit(exitCode);
					
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					LOGGER.info("Process monitoring interrupted");
					// If interrupted, assume abnormal termination
					handleProgramExit(-1);
				} catch (Exception e) {
					LOGGER.severe("Error monitoring process exit: " + e.getMessage());
					// On error, assume abnormal termination
					handleProgramExit(-1);
				}
			});
		} else {
			LOGGER.warning("Cannot start process monitoring - VM or process is null");
		}
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
	 * Verify and set pending breakpoints using the BreakpointManager
	 */
	private void verifyAndSetPendingBreakpoints() {
		if ( breakpointManager == null ) {
			LOGGER.warning( "BreakpointManager not available, cannot set breakpoints" );
			return;
		}

		breakpointManager.verifyAndSetPendingBreakpoints();
	}

	@Override
	public CompletableFuture<SetBreakpointsResponse> setBreakpoints( SetBreakpointsArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			SetBreakpointsResponse	response			= new SetBreakpointsResponse();
			List<Breakpoint>		responseBreakpoints	= new ArrayList<>();

			// Initialize breakpoint manager if not yet available (before launch)
			if ( breakpointManager == null ) {
				// Create a temporary breakpoint manager for pending breakpoint storage
				// This will be replaced with a proper one when launch() is called
				breakpointManager = new BreakpointManager( null, client );
				LOGGER.info( "Created temporary breakpoint manager for pending breakpoints" );
			}

			// Clear existing pending breakpoints for this file first
			// (setBreakpoints replaces all breakpoints for the file)
			if ( args.getSource() != null && args.getSource().getPath() != null ) {
				breakpointManager.clearPendingBreakpointsForFile( args.getSource().getPath() );
			}

			if ( args.getBreakpoints() != null ) {
				for ( SourceBreakpoint sourceBreakpoint : args.getBreakpoints() ) {
					// Use the encapsulated method to track the breakpoint
					Breakpoint breakpoint = breakpointManager.trackSourceBreakpoint( args.getSource(), sourceBreakpoint );
					responseBreakpoints.add( breakpoint );
				}
			}

			response.setBreakpoints( responseBreakpoints.toArray( new Breakpoint[ 0 ] ) );

			return response;
		} );
	}

	/**
	 * Clean up resources when debugging session ends
	 */
	public void cleanup() {
		LOGGER.info( "Cleaning up debug session resources" );
		
		// Use our exit handling cleanup mechanism
		performSessionCleanup();
	}

	@Override
	public CompletableFuture<StackTraceResponse> stackTrace( StackTraceArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				LOGGER.info( "Stack trace request received for thread: " + args.getThreadId() );

				StackTraceResponse response = new StackTraceResponse();

				if ( vm != null && breakpointManager != null ) {
					// Get stack frames from the breakpoint manager
					List<StackFrame> stackFrames = breakpointManager.getStackFrames( args.getThreadId() );
					response.setStackFrames( stackFrames.toArray( new StackFrame[ 0 ] ) );
					response.setTotalFrames( stackFrames.size() );

					LOGGER.info( "Returning " + stackFrames.size() + " stack frames" );
				} else {
					LOGGER.warning( "VM or breakpoint manager not available for stack trace" );
					response.setStackFrames( new StackFrame[ 0 ] );
					response.setTotalFrames( 0 );
				}

				return response;
			} catch ( Exception e ) {
				LOGGER.severe( "Error processing stack trace request: " + e.getMessage() );
				e.printStackTrace();

				// Return empty response on error
				StackTraceResponse response = new StackTraceResponse();
				response.setStackFrames( new StackFrame[ 0 ] );
				response.setTotalFrames( 0 );
				return response;
			}
		} );
	}
	
	/**
	 * Handle program exit events and send DAP exited event to client
	 * @param exitCode The exit code of the program
	 */
	public void handleProgramExit(int exitCode) {
		synchronized (exitLock) {
			// Only process exit once
			if (sessionCleaned) {
				LOGGER.info("Exit event already processed, ignoring additional exit with code: " + exitCode);
				return;
			}
			
			LOGGER.info("Handling program exit with code: " + exitCode);
			
			// Send exited event to client if we have a client connection
			if (client != null) {
				try {
					org.eclipse.lsp4j.debug.ExitedEventArguments exitArgs = new org.eclipse.lsp4j.debug.ExitedEventArguments();
					exitArgs.setExitCode(exitCode);
					client.exited(exitArgs);
					LOGGER.info("Sent exited event to client with exit code: " + exitCode);
				} catch (Exception e) {
					LOGGER.warning("Failed to send exited event to client: " + e.getMessage());
				}
			}
			
			// Perform cleanup
			performSessionCleanup();
		}
	}
	
	/**
	 * Check if the debug session has been cleaned up
	 * @return true if the session has been cleaned up
	 */
	public boolean isSessionCleaned() {
		return sessionCleaned;
	}
	
	/**
	 * Perform cleanup of debug session resources
	 */
	private void performSessionCleanup() {
		if (sessionCleaned) {
			return;
		}
		
		LOGGER.info("Performing debug session cleanup");
		
		try {
			// Stop breakpoint event processing
			if (breakpointManager != null) {
				breakpointManager.stopEventProcessing();
			}
			
			// Shutdown output monitoring
			if (outputMonitorExecutor != null && !outputMonitorExecutor.isShutdown()) {
				outputMonitorExecutor.shutdown();
				try {
					if (!outputMonitorExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
						outputMonitorExecutor.shutdownNow();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					outputMonitorExecutor.shutdownNow();
				}
			}
			
			// Clean up VM
			if (vm != null) {
				try {
					if (!vm.process().isAlive()) {
						// Process is already dead, just dispose
						vm.dispose();
					} else {
						// Try graceful exit first
						vm.exit(0);
					}
				} catch (Exception e) {
					LOGGER.warning("Error during VM cleanup: " + e.getMessage());
					try {
						vm.dispose();
					} catch (Exception disposeError) {
						LOGGER.warning("Error disposing VM: " + disposeError.getMessage());
					}
				}
				vm = null;
			}
			
			// Clean up process reference
			debuggedProcess = null;
			
			// Mark session as inactive and cleaned
			sessionActive = false;
			sessionCleaned = true;
			
			LOGGER.info("Debug session cleanup completed");
			
		} catch (Exception e) {
			LOGGER.severe("Error during session cleanup: " + e.getMessage());
			e.printStackTrace();
			// Ensure we still mark as cleaned even if cleanup partially failed
			sessionActive = false;
			sessionCleaned = true;
		}
	}
}
