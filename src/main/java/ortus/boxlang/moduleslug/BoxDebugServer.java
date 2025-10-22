package ortus.boxlang.moduleslug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.SourceResponse;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;

/**
 * BoxLang Debug Server implementation of the Debug Adapter Protocol
 */
public class BoxDebugServer implements IDebugProtocolServer {

	private static final Logger								LOGGER					= Logger.getLogger( BoxDebugServer.class.getName() );

	// Debug session state
	private VirtualMachine									vm;
	private IDebugProtocolClient							client;
	private ExecutorService									outputMonitorExecutor;
	private BreakpointManager								breakpointManager;
	private SourceManager									sourceManager			= new SourceManager();
	// Ensure we only start output monitoring once per session
	private final java.util.concurrent.atomic.AtomicBoolean	outputMonitoringStarted	= new java.util.concurrent.atomic.AtomicBoolean( false );

	// BoxLang debugging configuration
	private String											debugMode				= "BoxLang"; // Default to BoxLang mode

	// Exit handling state
	private volatile boolean								sessionCleaned			= false;
	private volatile boolean								terminatedEventSent		= false;
	private final Object									exitLock				= new Object();
	private VariableManager									variableManager			= new VariableManager();

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
		capabilities.setSupportsConditionalBreakpoints( true );
		capabilities.setSupportsEvaluateForHovers( true );
		capabilities.setSupportTerminateDebuggee( true );
		capabilities.setSupportsTerminateRequest( true );

		capabilities.setSupportsFunctionBreakpoints( false );
		capabilities.setSupportsHitConditionalBreakpoints( false );
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
		capabilities.setSupportsDelayedStackTraceLoading( false );
		capabilities.setSupportsLoadedSourcesRequest( false );
		capabilities.setSupportsLogPoints( false );
		capabilities.setSupportsTerminateThreadsRequest( false );
		capabilities.setSupportsSetExpression( false );
		capabilities.setSupportsDataBreakpoints( false );
		capabilities.setSupportsReadMemoryRequest( false );
		capabilities.setSupportsDisassembleRequest( false );
		capabilities.setSupportsCancelRequest( false );
		capabilities.setSupportsBreakpointLocationsRequest( false );

		LOGGER.info( "Sending capabilities to client" );

		CompletableFuture.supplyAsync( () -> {
			try {
				Thread.sleep( 1000 );
				client.initialized();
			} catch ( InterruptedException e ) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			return null;
		} );

		return CompletableFuture.completedFuture( capabilities );
	}

	@Override
	public CompletableFuture<Void> launch( Map<String, Object> args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				String program = ( String ) args.get( "program" );
				LOGGER.info( "Launching BoxLang program with JDI: " + program );

				// Configure debug mode
				String requestedMode = ( String ) args.get( "debugMode" );
				if ( requestedMode != null ) {
					debugMode = requestedMode;
				}
				LOGGER.info( "Debug mode set to: " + debugMode );

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

				// Launch the VM with a couple of quick retries to avoid transient failures
				final int	maxAttempts	= 3;
				int			attempt		= 1;
				while ( true ) {
					try {
						vm = launchingConnector.launch( arguments );
						LOGGER.info( "JDI VM launched successfully" );
						// Ensure the dedicated debug execution thread is created as early as possible
						ensureDebugExecThread();
						break;
					} catch ( Exception launchEx ) {
						if ( attempt >= maxAttempts ) {
							LOGGER.severe( "Failed to launch program after " + attempt + " attempt(s): " + launchEx.getMessage() );
							throw launchEx;
						}
						LOGGER.warning( "Launch attempt " + attempt + " failed: " + launchEx.getMessage() + "; retrying..." );
						try {
							Thread.sleep( 300L * attempt );
						} catch ( InterruptedException ie ) {
							Thread.currentThread().interrupt();
							throw launchEx;
						}
						attempt++;
					}
				}

				// Start output monitoring as early as possible to avoid missing early program output
				startOutputMonitoring();

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

				// client.initialized();

				return null;
			} catch ( Exception e ) {
				LOGGER.severe( "Failed to launch program: " + e.getMessage() );
				e.printStackTrace();
				throw new RuntimeException( "Launch failed", e );
			}
		} );
	}

	/**
	 * Start monitoring output from the debugged VM process
	 */
	private void startOutputMonitoring() {
		// Idempotent start to prevent duplicate stream readers
		if ( !outputMonitoringStarted.compareAndSet( false, true ) ) {
			return;
		}

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
		} else {
			// If VM/process not ready yet, allow a later attempt via configurationDone
			outputMonitoringStarted.set( false );
		}
	}

	/**
	 * Start monitoring the debugged process for exit events
	 */
	private void startProcessMonitoring() {
		if ( vm != null && vm.process() != null ) {
			Process process = vm.process();

			// Monitor process termination in a separate thread
			if ( outputMonitorExecutor == null ) {
				outputMonitorExecutor = Executors.newFixedThreadPool( 3 ); // Increased for process monitoring
			}

			outputMonitorExecutor.submit( () -> {
				try {
					LOGGER.info( "Starting process exit monitoring" );
					int exitCode = process.waitFor();
					LOGGER.info( "Debugged process exited with code: " + exitCode );

					// Handle the program exit
					handleProgramExit( exitCode );

				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					LOGGER.info( "Process monitoring interrupted" );
					// If interrupted, assume abnormal termination
					handleProgramExit( -1 );
				} catch ( Exception e ) {
					LOGGER.severe( "Error monitoring process exit: " + e.getMessage() );
					// On error, assume abnormal termination
					handleProgramExit( -1 );
				}
			} );
		} else {
			LOGGER.warning( "Cannot start process monitoring - VM or process is null" );
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
				LOGGER.info( "Stack trace request received for thread: " + args.getThreadId() + " in " + debugMode + " mode" );

				return handleStackTraceRequest( args.getThreadId(), debugMode, args.getStartFrame(), args.getLevels() );

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

	@Override
	public CompletableFuture<ScopesResponse> scopes( ScopesArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				LOGGER.info( "Scopes request received for frame: " + args.getFrameId() );

				ScopesResponse						response			= new ScopesResponse();
				Optional<BreakpointContext>			breakpointContext	= this.breakpointManager.getBreakpointContextbyStackFrame( args.getFrameId() );

				CompletableFuture<ThreadReference>	debugThreadFuture	= this.breakpointManager.getSuspendedDebugThread();
				CompletableFuture<List<Value>>		scopeValueFuture	= debugThreadFuture.thenCompose( debugThread -> {
																			return breakpointContext
																			    .map( context -> context.getVisibleScopes( debugThread, args.getFrameId() ) )
																			    .orElseGet( () -> CompletableFuture.completedFuture( new ArrayList<>() ) );
																		} );

				List<Scope>							scopes				= debugThreadFuture.thenCombine( scopeValueFuture, ( debugThread, scopeList ) -> {
																			return scopeList.stream()
																			    .map(
																			        scope -> this.variableManager.convertScopeToDAPScope( debugThread, scope ) )
																			    .collect( Collectors.toList() );
																		} )
				    .exceptionally( e -> {
					    LOGGER.severe( "Error getting suspended debug thread: " + e.getMessage() );
					    return new ArrayList<>();
				    } ).join();

				response.setScopes( scopes.toArray( Scope[]::new ) );

				LOGGER.info( "Returning " + scopes.size() + " scopes for frame " + args.getFrameId() );
				return response;

			} catch ( Exception e ) {
				LOGGER.severe( "Error processing scopes request: " + e.getMessage() );
				e.printStackTrace();

				// Return empty response on error
				ScopesResponse response = new ScopesResponse();
				response.setScopes( new Scope[ 0 ] );
				return response;
			}
		} );
	}

	@Override
	public CompletableFuture<VariablesResponse> variables( VariablesArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Variables request received for variables reference: " + args.getVariablesReference() );

			// For now, we will return an empty response
			VariablesResponse response = new VariablesResponse();
			response.setVariables( new org.eclipse.lsp4j.debug.Variable[ 0 ] );

			// In a real implementation, you would retrieve variables based on the reference
			LOGGER.info( "Returning empty variables response for reference: " + args.getVariablesReference() );
			return response;
		} );
	}

	@Override
	public CompletableFuture<EvaluateResponse> evaluate( EvaluateArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Evaluate request received. context=" + args.getContext() + ", expr=" + args.getExpression() );

			EvaluateResponse	response	= new EvaluateResponse();
			String				expr		= args.getExpression() != null ? args.getExpression().trim() : "";
			String				context		= args.getContext() != null ? args.getContext().toLowerCase() : "repl";

			try {
				// Very small initial support:
				// - string literals "..." -> return without quotes
				// - otherwise, if not paused (no vm or no suspended threads), return error for hover/watch
				// - in repl, if unsupported expression, return friendly error string

				// Handle double-quoted string literal
				if ( expr.startsWith( "\"" ) && expr.endsWith( "\"" ) && expr.length() >= 2 ) {
					String value = expr.substring( 1, expr.length() - 1 );
					response.setResult( value );
					response.setVariablesReference( 0 );
					return response;
				}

				boolean	isHoverOrWatch	= context.equals( "hover" ) || context.equals( "watch" );

				// Determine if we are paused at a breakpoint (any suspended thread)
				boolean	isPaused		= false;
				try {
					if ( vm != null ) {
						isPaused = vm.allThreads().stream().anyMatch( t -> {
							try {
								return t.isSuspended();
							} catch ( Exception e ) {
								return false;
							}
						} );
					}
				} catch ( Exception e ) {
					isPaused = false;
				}

				if ( isHoverOrWatch && !isPaused ) {
					response.setResult( "Error: not paused; cannot evaluate in " + context + " context" );
					response.setVariablesReference( 0 );
					return response;
				}

				// Placeholder for future BoxLang expression evaluation in local context
				// For now, return a simple error for non-literals
				response.setResult( context.equals( "repl" )
				    ? ( "Error: unsupported expression: " + expr )
				    : ( "Error: unsupported expression in " + context + " context" ) );
				response.setVariablesReference( 0 );
				return response;

			} catch ( Exception e ) {
				LOGGER.severe( "Error handling evaluate request: " + e.getMessage() );
				response.setResult( "Error: " + e.getMessage() );
				response.setVariablesReference( 0 );
				return response;
			}
		} );
	}

	/**
	 * Enhanced stack trace request handler that supports BoxLang and Java modes with pagination
	 * 
	 * @param threadId   the thread ID to get stack frames for
	 * @param mode       the debug mode ("BoxLang" or "Java")
	 * @param startFrame optional start index for frames (defaults to 0)
	 * @param levels     optional number of frames to return (all if null)
	 * 
	 * @return StackTraceResponse with filtered frames based on mode and paged
	 */
	private StackTraceResponse handleStackTraceRequest( int threadId, String mode, Integer startFrame, Integer levels ) {
		StackTraceResponse response = new StackTraceResponse();

		if ( vm == null || breakpointManager == null ) {
			LOGGER.warning( "VM or breakpoint manager not available for stack trace" );
			response.setStackFrames( new StackFrame[ 0 ] );
			response.setTotalFrames( 0 );
			return response;
		}

		try {
			// Get all stack frames from the breakpoint manager
			List<StackFrame>		allFrames		= breakpointManager.getBreakpointContextByThread( threadId )
			    .map( ctx -> ctx.getStackFrames() )
			    .orElse( new ArrayList<>() );

			// Convert to BoxLang stack frames and apply filtering based on mode
			List<BoxLangStackFrame>	boxLangFrames	= new ArrayList<>();
			for ( StackFrame frame : allFrames ) {
				BoxLangStackFrame boxFrame = BoxLangStackFrame.fromJavaFrame( frame );
				boxLangFrames.add( boxFrame );
			}

			// Filter frames based on debug mode
			List<StackFrame>	filteredFrames	= filterStackFramesByMode( boxLangFrames, mode );

			// Set total before pagination
			int					total			= filteredFrames.size();
			response.setTotalFrames( total );

			// Apply pagination per DAP (startFrame default 0; levels optional)
			int start = ( startFrame != null && startFrame > 0 ) ? startFrame : 0;
			if ( start > total ) {
				start = total; // empty result
			}
			int end;
			if ( levels != null && levels > 0 ) {
				end = Math.min( start + levels, total );
			} else {
				end = total;
			}

			List<StackFrame> page = ( start < end ) ? filteredFrames.subList( start, end ) : new ArrayList<>();

			response.setStackFrames( page.toArray( StackFrame[]::new ) );

			LOGGER.info( "Returning " + page.size() + " stack frames (start=" + start + ", levels=" + ( levels != null ? levels : "all" ) + ") from " + total
			    + " filtered frames (" + allFrames.size() + " total)" );

		} catch ( Exception e ) {
			LOGGER.severe( "Error in handleStackTraceRequest: " + e.getMessage() );
			e.printStackTrace();
			response.setStackFrames( new StackFrame[ 0 ] );
			response.setTotalFrames( 0 );
		}

		return response;
	}

	/**
	 * Filter stack frames based on the debug mode
	 * 
	 * @param frames the list of BoxLang stack frames
	 * @param mode   the debug mode ("BoxLang" or "Java")
	 * 
	 * @return filtered list of stack frames
	 */
	private List<StackFrame> filterStackFramesByMode( List<BoxLangStackFrame> frames, String mode ) {
		List<StackFrame> filteredFrames = new ArrayList<>();

		for ( BoxLangStackFrame frame : frames ) {
			boolean includeFrame = false;

			switch ( mode.toLowerCase() ) {
				case "boxlang" :
					// In BoxLang mode, only include frames that are from BoxLang source
					includeFrame = frame.isBoxLangFrame();
					if ( includeFrame ) {
						LOGGER.fine( "Including BoxLang frame: " + frame.getName() );
					}
					break;

				case "java" :
					// In Java mode, include all frames
					includeFrame = true;
					LOGGER.fine( "Including frame (Java mode): " + frame.getName() );
					break;

				default :
					LOGGER.warning( "Unknown debug mode: " + mode + ", defaulting to BoxLang mode" );
					includeFrame = frame.isBoxLangFrame();
					break;
			}

			if ( includeFrame ) {
				filteredFrames.add( frame );
			}
		}

		LOGGER.info( "Filtered " + frames.size() + " frames to " + filteredFrames.size() + " frames in " + mode + " mode" );
		return filteredFrames;
	}

	/**
	 * Handle program exit events and send DAP exited event to client
	 * 
	 * @param exitCode The exit code of the program
	 */
	public void handleProgramExit( int exitCode ) {
		synchronized ( exitLock ) {
			// Only process exit once
			if ( sessionCleaned ) {
				LOGGER.info( "Exit event already processed, ignoring additional exit with code: " + exitCode );
				return;
			}

			LOGGER.info( "Handling program exit with code: " + exitCode );

			// Send terminated event first (if not already sent)
			sendTerminatedEvent();

			// Send exited event to client if we have a client connection
			if ( client != null ) {
				try {
					org.eclipse.lsp4j.debug.ExitedEventArguments exitArgs = new org.eclipse.lsp4j.debug.ExitedEventArguments();
					exitArgs.setExitCode( exitCode );
					client.exited( exitArgs );
					LOGGER.info( "Sent exited event to client with exit code: " + exitCode );
				} catch ( Exception e ) {
					LOGGER.warning( "Failed to send exited event to client: " + e.getMessage() );
				}
			}

			// Perform cleanup
			performSessionCleanup();

			// Exit the current process after handling the debuggee's exit
			LOGGER.info( "Debuggee has exited, shutting down debugger process" );
			System.exit( 0 );
		}
	}

	/**
	 * Handle user-initiated termination and send DAP terminated event to client
	 */
	public void handleTermination() {
		synchronized ( exitLock ) {
			// Only process termination once
			if ( sessionCleaned ) {
				LOGGER.info( "Session already cleaned, ignoring termination request" );
				return;
			}

			LOGGER.info( "Handling user-initiated termination" );

			// Send terminated event
			sendTerminatedEvent();

			// Perform cleanup
			performSessionCleanup();

			// Exit the current process after handling the debuggee's exit
			LOGGER.info( "Debuggee has exited, shutting down debugger process" );
			System.exit( 0 );
		}
	}

	/**
	 * Send terminated event to client
	 */
	private void sendTerminatedEvent() {
		// Only send terminated event once
		if ( terminatedEventSent ) {
			LOGGER.info( "Terminated event already sent, skipping" );
			return;
		}

		if ( client != null ) {
			try {
				org.eclipse.lsp4j.debug.TerminatedEventArguments terminatedArgs = new org.eclipse.lsp4j.debug.TerminatedEventArguments();
				client.terminated( terminatedArgs );
				terminatedEventSent = true;
				LOGGER.info( "Sent terminated event to client" );
			} catch ( Exception e ) {
				LOGGER.warning( "Failed to send terminated event to client: " + e.getMessage() );
			}
		}
	}

	/**
	 * Check if the debug session has been cleaned up
	 * 
	 * @return true if the session has been cleaned up
	 */
	public boolean isSessionCleaned() {
		return sessionCleaned;
	}

	/**
	 * Perform cleanup of debug session resources
	 */
	private void performSessionCleanup() {
		if ( sessionCleaned ) {
			return;
		}

		LOGGER.info( "Performing debug session cleanup" );

		try {
			// Stop breakpoint event processing
			if ( breakpointManager != null ) {
				breakpointManager.stopEventProcessing();
			}

			// Shutdown output monitoring
			if ( outputMonitorExecutor != null && !outputMonitorExecutor.isShutdown() ) {
				outputMonitorExecutor.shutdown();
				try {
					if ( !outputMonitorExecutor.awaitTermination( 5, java.util.concurrent.TimeUnit.SECONDS ) ) {
						outputMonitorExecutor.shutdownNow();
					}
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					outputMonitorExecutor.shutdownNow();
				}
			}

			// Clean up VM
			if ( vm != null ) {
				try {
					if ( !vm.process().isAlive() ) {
						// Process is already dead, just dispose
						vm.dispose();
					} else {
						// Try graceful exit first
						vm.exit( 0 );
					}
				} catch ( Exception e ) {
					LOGGER.warning( "Error during VM cleanup: " + e.getMessage() );
					try {
						vm.dispose();
					} catch ( Exception disposeError ) {
						LOGGER.warning( "Error disposing VM: " + disposeError.getMessage() );
					}
				}
				vm = null;
			}

			// Clean up process reference

			// Mark session as inactive and cleaned
			sessionCleaned = true;

			LOGGER.info( "Debug session cleanup completed" );

		} catch ( Exception e ) {
			LOGGER.severe( "Error during session cleanup: " + e.getMessage() );
			e.printStackTrace();
			// Ensure we still mark as cleaned even if cleanup partially failed
			sessionCleaned = true;
		}
	}

	@Override
	public CompletableFuture<Void> configurationDone( org.eclipse.lsp4j.debug.ConfigurationDoneArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Configuration done request received" );

			// Set actual breakpoints for all pending breakpoints
			verifyAndSetPendingBreakpoints();

			// Start breakpoint event processing
			breakpointManager.startEventProcessing();

			// Start output monitoring using the VM's process
			startOutputMonitoring();

			// Create/invoke debug exec thread inside the VM
			ensureDebugExecThread();

			// Mark session as active and start process monitoring
			sessionCleaned = false;
			startProcessMonitoring();

			// Mark that configuration is complete
			// In a typical DAP flow, this signals that the client has finished sending
			// initial configuration requests (like setting breakpoints) and the debugger
			// can proceed with execution

			// If we have a VM running and breakpoints are set, we can now proceed
			if ( vm != null && breakpointManager != null ) {
				LOGGER.info( "Configuration done: VM is running, breakpoints are ready" );

				// Resume execution if the VM is suspended
				// This is often needed in DAP implementations to continue execution
				// after initial configuration is complete
				try {
					if ( vm.allThreads().stream().anyMatch( thread -> {
						try {
							return thread.isSuspended();
						} catch ( Exception e ) {
							return false;
						}
					} ) ) {
						LOGGER.info( "Resuming suspended threads after configuration done" );
						vm.resume();
					}
				} catch ( Exception e ) {
					java.util.logging.Logger.getLogger( BoxDebugServer.class.getName() )
					    .warning( "Could not resume VM after configuration done: " + e.getMessage() );
				}

			} else {
				LOGGER.info( "Configuration done: No active VM yet, configuration will be applied when VM starts" );
			}

			LOGGER.info( "Configuration done request completed successfully" );
			return null;
		} );
	}

	/**
	 * Ensure the VM has a dedicated debug execution thread by invoking
	 * DebugExecService.start() inside the target VM via JDI.
	 */
	private void ensureDebugExecThread() {
		if ( vm == null )
			return;
		try {
			// TODO move into breakpointmanager
			// TODO Load this agent using a dynamic path
			String								id			= String.valueOf( vm.process().pid() );
			com.sun.tools.attach.VirtualMachine	attachVM	= com.sun.tools.attach.VirtualMachine.attach( id );
			vm.resume();
			attachVM.loadAgent( "C:\\Users\\jacob\\Dev\\ortus-boxlang\\bx-debugger\\build\\libs\\@MODULE_SLUG@-1.0.0-snapshot-agent.jar" );
			attachVM.detach();
			vm.suspend();

		} catch ( Throwable e ) {
			java.util.logging.Logger.getLogger( BoxDebugServer.class.getName() ).warning( "Failed to start debug exec thread: " + e.getMessage() );
		}
	}

	@Override
	public CompletableFuture<ContinueResponse> continue_( org.eclipse.lsp4j.debug.ContinueArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Continue request received for thread: " + args.getThreadId() );

			if ( breakpointManager == null ) {
				LOGGER.warning( "BreakpointManager not available for continue request" );
				return new ContinueResponse();
			}

			// Resume execution for all threads to ensure the VM resumes from suspended state
			breakpointManager.getBreakpointContextByThread( args.getThreadId() ).ifPresent( context -> {
				context.resume();
			} );

			LOGGER.info( "Continue request completed for thread: " + args.getThreadId() );

			ContinueResponse response = new ContinueResponse();
			response.setAllThreadsContinued( true ); // Indicate that all threads will continue
			return response;
		} );
	}

	@Override
	public CompletableFuture<ThreadsResponse> threads() {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Threads request received" );

			ThreadsResponse response = new ThreadsResponse();

			if ( vm == null ) {
				LOGGER.info( "No active VM - returning empty threads list" );
				response.setThreads( new org.eclipse.lsp4j.debug.Thread[ 0 ] );
				return response;
			}

			try {
				// Get all threads from the JDI virtual machine
				List<com.sun.jdi.ThreadReference>		jdiThreads	= vm.allThreads();
				List<org.eclipse.lsp4j.debug.Thread>	dapThreads	= new ArrayList<>();

				LOGGER.info( "Found " + jdiThreads.size() + " threads in VM" );

				for ( com.sun.jdi.ThreadReference jdiThread : jdiThreads ) {
					try {
						org.eclipse.lsp4j.debug.Thread dapThread = new org.eclipse.lsp4j.debug.Thread();

						// Set thread ID (using JDI thread's unique ID)
						dapThread.setId( ( int ) jdiThread.uniqueID() );

						// Set thread name
						String threadName = jdiThread.name();
						if ( threadName == null || threadName.trim().isEmpty() ) {
							threadName = "Thread-" + jdiThread.uniqueID();
						}
						dapThread.setName( threadName );

						dapThreads.add( dapThread );

						LOGGER.fine( "Added thread: ID=" + dapThread.getId() + ", Name=" + dapThread.getName() );
						// Emit names at INFO to help diagnose presence of our exec thread during tests
						LOGGER.info( "Thread present: ID=" + dapThread.getId() + ", Name='" + dapThread.getName() + "'" );

					} catch ( Exception e ) {
						LOGGER.warning( "Error processing thread " + jdiThread.uniqueID() + ": " + e.getMessage() );
						// Continue processing other threads
					}
				}

				// Convert to array and set response
				response.setThreads( dapThreads.toArray( new org.eclipse.lsp4j.debug.Thread[ 0 ] ) );

				LOGGER.info( "Threads request completed - returning " + dapThreads.size() + " threads" );
				return response;

			} catch ( Exception e ) {
				LOGGER.severe( "Error processing threads request: " + e.getMessage() );
				e.printStackTrace();

				// Return empty response on error
				response.setThreads( new org.eclipse.lsp4j.debug.Thread[ 0 ] );
				return response;
			}
		} );
	}

	@Override
	public CompletableFuture<SourceResponse> source( SourceArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				LOGGER.info( "Source request received for: " +
				    ( args.getSource().getPath() != null ? args.getSource().getPath() : "sourceReference " + args.getSource().getSourceReference() ) );

				return handleSourceRequest( args.getSource() );

			} catch ( Exception e ) {
				LOGGER.severe( "Error processing source request: " + e.getMessage() );
				e.printStackTrace();

				// Return empty response on error
				SourceResponse response = new SourceResponse();
				response.setContent( "" );
				return response;
			}
		} );
	}

	/**
	 * Handle DAP disconnect request. Depending on the arguments, either terminate the debuggee,
	 * detach and leave it running, or prepare for restart.
	 */
	@Override
	public CompletableFuture<Void> disconnect( DisconnectArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				boolean	terminate	= args != null && Boolean.TRUE.equals( args.getTerminateDebuggee() );
				boolean	restart		= args != null && Boolean.TRUE.equals( args.getRestart() );

				LOGGER.info( "Disconnect request received: terminateDebuggee=" + terminate + ", restart=" + restart );

				if ( restart ) {
					// For restart, signal termination of current session but let the client decide to relaunch.
					// We perform cleanup and rely on a subsequent initialize/launch from client.
					sendTerminatedEvent();
					performSessionCleanup();
					return null;
				}

				if ( terminate ) {
					// Terminate the debuggee and send terminated/exited ordering via handleProgramExit
					int exitCode = 0;
					try {
						if ( vm != null ) {
							try {
								vm.exit( 0 );
							} catch ( Exception e ) {
								LOGGER.warning( "Error requesting VM exit during disconnect: " + e.getMessage() );
								try {
									vm.dispose();
								} catch ( Exception ignore ) {
								}
							}
						}
					} catch ( Exception e ) {
						LOGGER.warning( "Error while terminating debuggee on disconnect: " + e.getMessage() );
						exitCode = -1;
					}

					// Ensure DAP events and cleanup
					handleProgramExit( exitCode );
				} else {
					// Detach scenario: leave program running, do not send exited; send terminated and cleanup
					detachFromDebuggee();
					sendTerminatedEvent();
					performSessionCleanup();
				}

				return null;
			} catch ( Exception e ) {
				LOGGER.severe( "Error handling disconnect request: " + e.getMessage() );
				// Best-effort cleanup to avoid leaked state
				try {
					detachFromDebuggee();
				} catch ( Exception ignore ) {
				}
				performSessionCleanup();
				return null;
			}
		} );
	}

	/**
	 * Detach from the running debuggee without terminating it.
	 */
	private void detachFromDebuggee() {
		if ( vm != null ) {
			try {
				if ( vm.process() != null && vm.process().isAlive() ) {
					// No direct JDI detach for LaunchingConnector VMs; best effort is to not kill the process
					// and avoid vm.exit(). Dispose JDI connection so program continues.
					vm.dispose();
				} else {
					vm.dispose();
				}
			} catch ( Exception e ) {
				LOGGER.warning( "Error detaching from debuggee: " + e.getMessage() );
			} finally {
				vm = null;
			}
		}
	}

	/**
	 * Enhanced source request handler that retrieves source content
	 * 
	 * @param source the source to retrieve content for
	 * 
	 * @return SourceResponse with the source content
	 */
	private SourceResponse handleSourceRequest( Source source ) {
		SourceResponse response = new SourceResponse();

		try {
			String content = sourceManager.getSourceContent( source );
			response.setContent( content );

			if ( source.getPath() != null ) {
				LOGGER.info( "Retrieved source content for file: " + source.getPath() + " (length: " + content.length() + ")" );
			} else {
				LOGGER.info( "Retrieved source content for reference: " + source.getSourceReference() + " (length: " + content.length() + ")" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error in handleSourceRequest: " + e.getMessage() );
			e.printStackTrace();
			response.setContent( "" );
		}

		return response;
	}
}
