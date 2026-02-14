package ortus.boxlang.bxdebugger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
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
import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.eclipse.lsp4j.debug.ExceptionInfoArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.SourceResponse;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.TerminateArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import ortus.boxlang.bxdebugger.vm.IVMConnection;

/**
 * BoxLang Debug Server implementation of the Debug Adapter Protocol
 */
public class BoxDebugServer implements IDebugProtocolServer {

	private static final Logger								LOGGER					= Logger.getLogger( BoxDebugServer.class.getName() );

	// Debug session state
	private VirtualMachine									vm;
	private IDebugProtocolClient							client;
	private ExecutorService									outputMonitorExecutor;
	private VMController									vmController;
	private VariableManager									variableManager;
	private SourceManager									sourceManager			= new SourceManager();
	private PathMappingService								pathMappingService;
	// Ensure we only start output monitoring once per session
	private final java.util.concurrent.atomic.AtomicBoolean	outputMonitoringStarted	= new java.util.concurrent.atomic.AtomicBoolean( false );

	// BoxLang debugging configuration
	private String											debugMode				= "BoxLang"; // Default to BoxLang mode

	// Exit handling state
	private volatile boolean								sessionCleaned			= false;
	private volatile boolean								terminatedEventSent		= false;
	private final Object									exitLock				= new Object();

	private IVMConnection									vmConnection			= null;
	private boolean											falseExit				= false;

	// Track launch/attach completion for coordinating with configurationDone
	private volatile CompletableFuture<Void>				launchFuture			= null;
	private volatile CompletableFuture<Void>				attachFuture			= null;

	// Timing instrumentation
	private long											sessionStartTime		= 0;

	public BoxDebugServer() {
		parseFalseExitProperty();
	}

	/**
	 * Connect to the language client
	 */
	public void connect( IDebugProtocolClient client ) {
		this.client				= client;
		this.sessionStartTime	= System.currentTimeMillis();
		LOGGER.info( "Connected to debug client" );
		LOGGER.fine( "[TIMING] Session started at T+0ms" );
	}

	private void parseFalseExitProperty() {
		String falseExitProp = System.getProperty( "BOX_DEBUGGER_FALSEEXIT", "false" );
		this.falseExit = Boolean.parseBoolean( falseExitProp );
	}

	public void setFalseExit( boolean falseExit ) {
		this.falseExit = falseExit;
	}

	/**
	 * Configure debug mode and path mapping from launch/attach arguments.
	 *
	 * @param args the launch or attach arguments map
	 */
	private void configureDebugSettings( Map<String, Object> args ) {
		String requestedMode = ( String ) args.get( "debugMode" );
		if ( requestedMode != null ) {
			debugMode = requestedMode;
		}
		LOGGER.info( "Debug mode set to: " + debugMode );

		String	localRoot		= ( String ) args.get( "localRoot" );
		String	remoteRoot		= ( String ) args.get( "remoteRoot" );
		String	workspaceFolder	= ( String ) args.get( "workspaceFolder" );
		pathMappingService = new PathMappingService( localRoot, remoteRoot, workspaceFolder );
	}

	@Override
	public CompletableFuture<Capabilities> initialize( InitializeRequestArguments args ) {
		LOGGER.info( "Initialize request received from client: " + args.getClientName() );
		LOGGER.fine( "[TIMING] Initialize request at T+" + ( System.currentTimeMillis() - sessionStartTime ) + "ms" );

		Capabilities capabilities = new Capabilities();
		capabilities.setSupportsConfigurationDoneRequest( true );
		capabilities.setSupportsTerminateRequest( true );
		capabilities.setSupportsConditionalBreakpoints( true );
		capabilities.setSupportsEvaluateForHovers( false );
		capabilities.setSupportTerminateDebuggee( true );
		capabilities.setSupportsTerminateRequest( true );

		capabilities.setSupportsFunctionBreakpoints( false );
		capabilities.setSupportsHitConditionalBreakpoints( true );
		capabilities.setSupportsLogPoints( true );
		capabilities.setSupportsStepBack( false );
		capabilities.setSupportsSetVariable( false );
		capabilities.setSupportsRestartFrame( false );
		capabilities.setSupportsGotoTargetsRequest( false );
		capabilities.setSupportsStepInTargetsRequest( false );
		capabilities.setSupportsCompletionsRequest( false );
		capabilities.setSupportsModulesRequest( false );
		capabilities.setSupportsRestartRequest( false );
		capabilities.setSupportsExceptionOptions( true );
		capabilities.setSupportsValueFormattingOptions( false );
		capabilities.setSupportsExceptionInfoRequest( true );

		// Set up exception breakpoint filters for BoxLang
		ExceptionBreakpointsFilter caughtExceptionFilter = new ExceptionBreakpointsFilter();
		caughtExceptionFilter.setFilter( "caught" );
		caughtExceptionFilter.setLabel( "Caught Exceptions" );
		caughtExceptionFilter.setDescription( "Break on caught BoxLang runtime exceptions" );
		caughtExceptionFilter.setDefault_( false );
		caughtExceptionFilter.setSupportsCondition( false );

		ExceptionBreakpointsFilter uncaughtExceptionFilter = new ExceptionBreakpointsFilter();
		uncaughtExceptionFilter.setFilter( "uncaught" );
		uncaughtExceptionFilter.setLabel( "Uncaught Exceptions" );
		uncaughtExceptionFilter.setDescription( "Break on uncaught BoxLang runtime exceptions" );
		uncaughtExceptionFilter.setDefault_( false );
		uncaughtExceptionFilter.setSupportsCondition( false );

		capabilities.setExceptionBreakpointFilters( new ExceptionBreakpointsFilter[] { caughtExceptionFilter, uncaughtExceptionFilter } );
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

		// Send initialized event immediately - no delay needed
		// The client will then send setBreakpoints, configurationDone, etc.
		CompletableFuture.runAsync( () -> client.initialized() );

		return CompletableFuture.completedFuture( capabilities );
	}

	@Override
	public CompletableFuture<Void> attach( Map<String, Object> args ) {
		// Create the future first so configurationDone can wait for it
		CompletableFuture<Void> future = new CompletableFuture<>();
		this.attachFuture = future;

		CompletableFuture.runAsync( () -> {
			try {
				if ( vm != null ) {
					LOGGER.warning( "Attach requested but VM already present" );
					future.complete( null );
					return;
				}
				String	serverName	= ( String ) args.getOrDefault( "serverName", "" );
				int		port		= ( int ) ( ( Double ) args.getOrDefault( "serverPort", "0" ) ).doubleValue();
				// int port = ( int ) Double.parseDouble( strPort );

				if ( serverName.isEmpty() && port == 0 ) {
					LOGGER.warning( "Attach requested but neither serverName nor serverPort is provided" );
					future.complete( null );
					return;
				}

				configureDebugSettings( args );

				try {
					if ( port > 0 ) {
						this.vmConnection = new ortus.boxlang.bxdebugger.vm.BareJDWPConnection( "localhost", port );
					} else {
						this.vmConnection = new ortus.boxlang.bxdebugger.vm.CommandBoxConnection( serverName );
					}
					this.vm = vmConnection.getVirtualMachine();
				} catch ( Exception e ) {
					LOGGER.severe( "Failed to attach to VM: " + e.getMessage() );
					e.printStackTrace();
					sendOutput( "Error: Failed to attach to VM: " + e.getMessage(), "stderr" );
					future.complete( null );
					return;
				}

				// Initialize breakpoint manager
				if ( vmController == null ) {
					vmController = new VMController( vm, client );
					vmController.setSessionStartTime( sessionStartTime );
				} else {
					// If you want to migrate pending breakpoints (mirrors launch logic)
					VMController old = vmController;
					vmController = new VMController( old, vm, client );
					LOGGER.info( "Transferred pending breakpoints to VM-enabled breakpoint manager" );
				}

				// Start event processing so we can catch ClassPrepareEvents for breakpoints
				vmController.startEventProcessing();

				// When attaching to an already-running VM, the DebuggerUtil class may have
				// already been loaded (we won't receive a ClassPrepareEvent for it).
				// Proactively detect and initialize the DebuggerUtil.
				if ( !vmController.detectDebuggerUtilOnAttach() ) {
					LOGGER.warning( "DebuggerUtil not detected - variable evaluation may not work. "
					    + "Ensure BoxLang is started with debugMode=true (--debug flag for miniserver)" );
					sendOutput( "Warning: DebuggerUtil not detected. Variable evaluation may not work. "
					    + "Ensure BoxLang is started with debugMode=true.", "stderr" );
				}

				// Configure path mapping for remote debugging support
				if ( pathMappingService != null ) {
					vmController.setPathMappingService( pathMappingService );
				}

				this.variableManager = new VariableManager( vmController );

				startOutputMonitoring(); // may be a no-op if remote

				LOGGER.info( "Attach completed successfully, VM is ready" );
				future.complete( null );
			} catch ( Exception e ) {
				LOGGER.severe( "Attach failed with exception: " + e.getMessage() );
				e.printStackTrace();
				future.completeExceptionally( e );
			}
		} );

		return future;
	}

	@Override
	public CompletableFuture<Void> launch( Map<String, Object> args ) {
		// Create a future to track when launch is complete
		// configurationDone will wait for this to ensure VM is ready
		CompletableFuture<Void> future = CompletableFuture.supplyAsync( () -> {
			try {
				String program = ( String ) args.get( "program" );
				LOGGER.info( "Launching BoxLang program with JDI: " + program );
				LOGGER.fine( "[TIMING] Launch request at T+" + ( System.currentTimeMillis() - sessionStartTime ) + "ms" );

				configureDebugSettings( args );

				this.vmConnection	= new ortus.boxlang.bxdebugger.vm.LaunchedConnection( program );
				this.vm				= vmConnection.getVirtualMachine();

				// Initialize or update breakpoint manager with the VM BEFORE starting output monitoring
				// This sets up ClassPrepareRequest before the VM is resumed
				if ( vmController == null ) {
					vmController = new VMController( vm, client );
					vmController.setSessionStartTime( sessionStartTime );
				} else {
					// Transfer pending breakpoints to a new manager with the VM
					VMController old = vmController;
					vmController = new VMController( old, vm, client );
					LOGGER.info( "Transferred pending breakpoints to VM-enabled breakpoint manager" );
				}

				// Start event processing BEFORE resuming the VM so we can catch ClassPrepareEvents
				vmController.startEventProcessing();

				// Note: The DebuggerUtil will be started automatically when its class is loaded
				// via the ClassPrepareEvent handler in VMController. We no longer need to call
				// startDebuggerUtil here because the class may not be loaded yet at this point.

				// Start output monitoring after VM is resumed
				startOutputMonitoring();

				// Configure path mapping for remote debugging support
				if ( pathMappingService != null ) {
					vmController.setPathMappingService( pathMappingService );
				}

				this.variableManager = new VariableManager( vmController );

				LOGGER.info( "Launch completed successfully, VM is ready" );
				return null;
			} catch ( Exception e ) {
				LOGGER.severe( "Failed to launch program: " + e.getMessage() );
				e.printStackTrace();
				sendOutput( "Error: Failed to launch program: " + e.getMessage(), "stderr" );
				throw new RuntimeException( "Launch failed", e );
			}
		} );

		this.launchFuture = future;
		return future;
	}

	/**
	 * Start monitoring output from the debugged VM process
	 */
	private void startOutputMonitoring() {
		// Idempotent start to prevent duplicate stream readers
		if ( !outputMonitoringStarted.compareAndSet( false, true ) ) {
			return;
		}
		if ( vm == null || vm.process() == null ) {
			LOGGER.info( "Output monitoring skipped: VM or process not available" );
			return;
		}
		if ( outputMonitorExecutor == null ) {
			outputMonitorExecutor = Executors.newFixedThreadPool( 2 );
		}
		Process process = vm.process();
		outputMonitorExecutor.submit( () -> monitorOutputStream( process.getInputStream(), "stdout" ) );
		outputMonitorExecutor.submit( () -> monitorOutputStream( process.getErrorStream(), "stderr" ) );
		LOGGER.info( "Output monitoring started" );
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
		LOGGER.info( "Starting monitor for " + category + " stream" );
		try ( BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream ) ) ) {
			String line;
			while ( ( line = reader.readLine() ) != null ) {
				LOGGER.info( "Output from " + category + ": " + line );
				sendOutput( line, category );
			}
			LOGGER.info( "Monitor for " + category + " stream ended (stream closed)" );
		} catch ( IOException e ) {
			LOGGER.warning( "Error reading from " + category + " stream: " + e.getMessage() );
		}
	}

	private void sendOutput( String message, String category ) {
		if ( client == null ) {
			return;
		}

		OutputEventArguments outputEvent = new OutputEventArguments();
		outputEvent.setOutput( message + System.lineSeparator() );
		outputEvent.setCategory( category );

		LOGGER.info( "Sending output event: " + message );
		client.output( outputEvent );
	}

	/**
	 * Verify and set pending breakpoints using the BreakpointManager
	 */
	private void verifyAndSetPendingBreakpoints() {
		if ( vmController == null ) {
			LOGGER.warning( "BreakpointManager not available, cannot set breakpoints" );
			return;
		}

		vmController.verifyAndSetPendingBreakpoints();
	}

	@Override
	public CompletableFuture<SetBreakpointsResponse> setBreakpoints( SetBreakpointsArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			SetBreakpointsResponse	response			= new SetBreakpointsResponse();
			List<Breakpoint>		responseBreakpoints	= new ArrayList<>();

			// Initialize breakpoint manager if not yet available (before launch)
			if ( vmController == null ) {
				// Create a temporary breakpoint manager for pending breakpoint storage
				// This will be replaced with a proper one when launch() is called
				vmController = new VMController( null, client );
				vmController.setSessionStartTime( sessionStartTime );
				LOGGER.info( "Created temporary breakpoint manager for pending breakpoints" );
			}

			// Initialize path mapping if not yet available
			if ( pathMappingService == null ) {
				pathMappingService = new PathMappingService( null, null, null );
			}

			// Get the original local path for the response (client's path)
			String	localPath		= args.getSource() != null ? args.getSource().getPath() : null;

			// Translate to remote path for the debuggee
			String	remotePath		= localPath != null ? pathMappingService.toRemotePath( localPath ) : null;

			// Create a source with the remote path for the VMController
			Source	remoteSource	= new Source();
			if ( args.getSource() != null ) {
				remoteSource.setName( args.getSource().getName() );
				remoteSource.setPath( remotePath );
				remoteSource.setSourceReference( args.getSource().getSourceReference() );
			}

			// Clear existing pending breakpoints for this file first
			// (setBreakpoints replaces all breakpoints for the file)
			if ( remotePath != null ) {
				vmController.clearPendingBreakpointsForFile( remotePath );
				// Also clear using local path in case there are any stored with the original path
				if ( !remotePath.equals( localPath ) ) {
					vmController.clearPendingBreakpointsForFile( localPath );
				}
			}

			if ( args.getBreakpoints() != null ) {
				for ( SourceBreakpoint sourceBreakpoint : args.getBreakpoints() ) {
					// Use the encapsulated method to track the breakpoint with remote path
					Breakpoint breakpoint = vmController.trackSourceBreakpoint( remoteSource, sourceBreakpoint );
					// Update the response breakpoint to show the local path to the client
					if ( breakpoint.getSource() != null && localPath != null ) {
						breakpoint.getSource().setPath( localPath );
					}
					responseBreakpoints.add( breakpoint );
				}
			}

			verifyAndSetPendingBreakpoints();

			response.setBreakpoints( responseBreakpoints.toArray( new Breakpoint[ 0 ] ) );

			return response;
		} );
	}

	@Override
	public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints( SetExceptionBreakpointsArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "SetExceptionBreakpoints request received" );

			SetExceptionBreakpointsResponse response = new SetExceptionBreakpointsResponse();

			// Initialize VM controller if not yet available (before launch)
			if ( vmController == null ) {
				vmController = new VMController( null, client );
				LOGGER.info( "Created temporary VMController for exception breakpoints" );
			}

			// Check if "caught" and/or "uncaught" filters are enabled
			String[]	filters			= args.getFilters();
			boolean		caughtEnabled	= false;
			boolean		uncaughtEnabled	= false;

			if ( filters != null ) {
				for ( String filter : filters ) {
					if ( "caught".equals( filter ) ) {
						caughtEnabled = true;
					} else if ( "uncaught".equals( filter ) ) {
						uncaughtEnabled = true;
					}
				}
			}

			// Configure exception breakpoints in VMController
			vmController.setExceptionBreakpoints( caughtEnabled, uncaughtEnabled );

			LOGGER.info( "Exception breakpoints configured: caught=" + caughtEnabled + ", uncaught=" + uncaughtEnabled );

			return response;
		} );
	}

	@Override
	public CompletableFuture<ExceptionInfoResponse> exceptionInfo( ExceptionInfoArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "ExceptionInfo request received for thread: " + args.getThreadId() );

			ExceptionInfoResponse response = new ExceptionInfoResponse();

			if ( vmController == null ) {
				LOGGER.warning( "VMController not available for exceptionInfo request" );
				response.setExceptionId( "unknown" );
				response.setDescription( "No exception information available" );
				return response;
			}

			// Get exception info from the VMController
			VMController.ExceptionInfo exceptionInfo = vmController.getExceptionInfo( args.getThreadId() );

			if ( exceptionInfo != null ) {
				response.setExceptionId( exceptionInfo.getExceptionId() );
				response.setDescription( exceptionInfo.getDescription() );
				// Convert string break mode to enum
				ExceptionBreakMode breakMode = "unhandled".equals( exceptionInfo.getBreakMode() )
				    ? ExceptionBreakMode.UNHANDLED
				    : ExceptionBreakMode.ALWAYS;
				response.setBreakMode( breakMode );
			} else {
				response.setExceptionId( "unknown" );
				response.setDescription( "No exception information available" );
			}

			return response;
		} );
	}

	public CompletableFuture<Void> next( NextArguments args ) {

		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Next request received for thread: " + args.getThreadId() );

			if ( vmController == null ) {
				LOGGER.warning( "BreakpointManager not available for Next request" );
				return null;
			}

			// Perform step in for the specified thread
			vmController.stepThread( args.getThreadId() );

			LOGGER.info( "Next request completed for thread: " + args.getThreadId() );
			return null;
		} );
	}

	public CompletableFuture<Void> stepIn( StepInArguments args ) {

		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Next request received for thread: " + args.getThreadId() );

			if ( vmController == null ) {
				LOGGER.warning( "BreakpointManager not available for Next request" );
				return null;
			}

			// Perform step in for the specified thread
			vmController.stepInThread( args.getThreadId() );

			LOGGER.info( "Next request completed for thread: " + args.getThreadId() );
			return null;
		} );
	}

	public CompletableFuture<Void> stepOut( StepOutArguments args ) {

		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Next request received for thread: " + args.getThreadId() );

			if ( vmController == null ) {
				LOGGER.warning( "BreakpointManager not available for Next request" );
				return null;
			}

			// Perform step in for the specified thread
			vmController.stepOutThread( args.getThreadId() );

			LOGGER.info( "Next request completed for thread: " + args.getThreadId() );
			return null;
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

	public CompletableFuture<Void> terminate( TerminateArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Terminate request received from client" );

			// Clean up the debugging session
			handleTermination();

			return null;
		} );
	}

	@Override
	public CompletableFuture<ScopesResponse> scopes( ScopesArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			try {
				LOGGER.info( "Scopes request received for frame: " + args.getFrameId() );

				ScopesResponse				response			= new ScopesResponse();
				Optional<BreakpointContext>	breakpointContext	= this.vmController.getBreakpointContextbyStackFrame( args.getFrameId() );

				List<Scope>					scopes				= breakpointContext
				    .map( context -> context.getVisibleScopes( args.getFrameId() ) )
				    .orElseGet( () -> CompletableFuture.completedFuture( new ArrayList<Value>() ) )
				    .thenApply( scopeList -> {
																	    return scopeList
																	        .stream()
																	        .map( scope -> ( Scope ) variableManager
																	            .convertScopeToDAPScope( scope ) )
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

			try {
				response.setVariables(
				    variableManager.getVariablesFor( args.getVariablesReference() ).toArray( new org.eclipse.lsp4j.debug.Variable[ 0 ] ) );
			} catch ( Exception e ) {
				LOGGER.severe( "Error processing variables request: " + e.getMessage() );
				e.printStackTrace();
			}

			return response;
		} );
	}

	@Override
	public CompletableFuture<EvaluateResponse> evaluate( EvaluateArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Evaluate request received. context=" + args.getContext() + ", expr=" + args.getExpression() );

			EvaluateResponse	response	= new EvaluateResponse();

			int					frameId		= args.getFrameId();
			String				expr		= args.getExpression();

			this.vmController.evaluateExpressionInFrame( frameId, expr )
			    .thenAccept( evalValue -> {
				    Variable evalVariable = variableManager.convertValueToVariable( "result", evalValue );

				    evalVariable.setVariablesReference( variableManager.put( evalValue ) );

				    response.setResult( evalVariable.getValue() );
			    } ).join();

			return response;
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

		if ( vm == null || vmController == null ) {
			LOGGER.warning( "VM or breakpoint manager not available for stack trace" );
			response.setStackFrames( new StackFrame[ 0 ] );
			response.setTotalFrames( 0 );
			return response;
		}

		try {
			// Get all stack frames from the breakpoint manager
			List<StackFrame>		allFrames		= vmController.getBreakpointContextByThread( threadId )
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

			// Translate remote paths in stack frames back to local paths for the client
			for ( StackFrame frame : page ) {
				if ( frame.getSource() != null && frame.getSource().getPath() != null ) {
					String	remotePath	= frame.getSource().getPath();
					String	localPath	= pathMappingService.toLocalPath( remotePath );
					// Convert to native OS path separators for the client
					frame.getSource().setPath( Paths.get( localPath ).toString() );
				}
			}

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
			if ( !falseExit ) {
				System.exit( 0 );
			}
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
			if ( !falseExit ) {
				System.exit( 0 );
			}
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
			if ( vmController != null ) {
				vmController.stopEventProcessing();
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
			LOGGER.fine( "[TIMING] ConfigurationDone at T+" + ( System.currentTimeMillis() - sessionStartTime ) + "ms" );

			// Wait for launch or attach to complete before processing configurationDone
			// This ensures the VM is ready before we try to set breakpoints
			if ( launchFuture != null ) {
				try {
					LOGGER.info( "Waiting for launch to complete before processing configurationDone..." );
					launchFuture.get( 30, java.util.concurrent.TimeUnit.SECONDS );
					LOGGER.info( "Launch completed, continuing with configurationDone" );
				} catch ( Exception e ) {
					LOGGER.severe( "Failed waiting for launch to complete: " + e.getMessage() );
					// Continue anyway - the VM might be available
				}
			}
			if ( attachFuture != null ) {
				try {
					LOGGER.info( "Waiting for attach to complete before processing configurationDone..." );
					attachFuture.get( 30, java.util.concurrent.TimeUnit.SECONDS );
					LOGGER.info( "Attach completed, continuing with configurationDone" );
				} catch ( Exception e ) {
					LOGGER.severe( "Failed waiting for attach to complete: " + e.getMessage() );
					// Continue anyway - the VM might be available
				}
			}

			// Set actual breakpoints for all pending breakpoints
			verifyAndSetPendingBreakpoints();

			// Start breakpoint event processing (idempotent if already started)
			vmController.startEventProcessing();

			// Start output monitoring using the VM's process
			startOutputMonitoring();

			// Mark session as active and start process monitoring
			sessionCleaned = false;
			startProcessMonitoring();

			// Signal to VMController that configuration is complete
			// This allows the VM to be resumed if it was waiting
			if ( vmController != null ) {
				vmController.signalConfigurationDone();
			}

			LOGGER.info( "Configuration done request completed successfully" );
			return null;
		} );
	}

	@Override
	public CompletableFuture<ContinueResponse> continue_( org.eclipse.lsp4j.debug.ContinueArguments args ) {
		return CompletableFuture.supplyAsync( () -> {
			LOGGER.info( "Continue request received for thread: " + args.getThreadId() );

			if ( vmController == null ) {
				LOGGER.warning( "BreakpointManager not available for continue request" );
				return new ContinueResponse();
			}

			// Resume execution for all threads to ensure the VM resumes from suspended state
			vmController.getBreakpointContextByThread( args.getThreadId() ).ifPresent( context -> {
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
	 * Enhanced source request handler that retrieves source content.
	 * Translates remote paths to local paths for file reading.
	 *
	 * @param source the source to retrieve content for
	 *
	 * @return SourceResponse with the source content
	 */
	private SourceResponse handleSourceRequest( Source source ) {
		SourceResponse response = new SourceResponse();

		try {
			// If we have a path, try to translate it to local path for reading
			if ( source.getPath() != null && pathMappingService != null ) {
				String	localPath	= pathMappingService.toLocalPath( source.getPath() );
				// Create a modified source with local path for reading
				Source	localSource	= new Source();
				localSource.setName( source.getName() );
				localSource.setPath( localPath );
				localSource.setSourceReference( source.getSourceReference() );

				String content = sourceManager.getSourceContent( localSource );
				response.setContent( content );

				LOGGER.info( "Retrieved source content for file: " + source.getPath() +
				    ( !localPath.equals( source.getPath() ) ? " (translated to: " + localPath + ")" : "" ) +
				    " (length: " + content.length() + ")" );
			} else {
				String content = sourceManager.getSourceContent( source );
				response.setContent( content );

				if ( source.getPath() != null ) {
					LOGGER.info( "Retrieved source content for file: " + source.getPath() + " (length: " + content.length() + ")" );
				} else {
					LOGGER.info( "Retrieved source content for reference: " + source.getSourceReference() + " (length: " + content.length() + ")" );
				}
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error in handleSourceRequest: " + e.getMessage() );
			e.printStackTrace();
			response.setContent( "" );
		}

		return response;
	}
}
