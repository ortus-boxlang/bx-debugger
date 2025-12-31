package ortus.boxlang.moduleslug;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassType;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.StepRequest;

/**
 * Manages JDI breakpoints and handles breakpoint events
 */
public class VMController {

	private static final Logger												LOGGER							= Logger.getLogger( VMController.class.getName() );

	public final VirtualMachine												vm;
	private final IDebugProtocolClient										client;
	private final List<BreakpointRequest>									activeBreakpoints				= new CopyOnWriteArrayList<>();
	private final List<PendingBreakpointInfo>								pendingBreakpoints				= new CopyOnWriteArrayList<>();
	private volatile boolean												eventProcessingActive			= false;
	private Thread															eventProcessingThread;

	// DAP-level breakpoint storage - organized by file path
	private final Map<String, List<PendingBreakpoint>>						pendingBreakpointsByFile		= new ConcurrentHashMap<>();
	private final Map<Integer, PendingBreakpoint>							pendingBreakpointsById			= new ConcurrentHashMap<>();
	private int																breakpointIdCounter				= 1;

	private final Map<Integer, BreakpointContext>							breakPointContexts				= new WeakHashMap<>();

	private MethodEntryRequest												methodEntryRequest				= null;
	private final ConcurrentLinkedQueue<CompletableFuture<ThreadReference>>	debugThreadAccessQueue			= new ConcurrentLinkedQueue<>();
	private Map<Long, StepRequest>											stepRequests					= new ConcurrentHashMap<>();
	private Map<Long, EventSet>												eventSets						= new ConcurrentHashMap<>();

	private MethodEntryRequest												methodEntryRequestDebugger		= null;
	private CompletableFuture<Void>											debugFuture						= null;
	private ThreadReference													debugThread						= null;

	// Exception breakpoint support
	private static final String												BOX_RUNTIME_EXCEPTION_CLASS		= "ortus.boxlang.runtime.types.exceptions.BoxRuntimeException";
	private volatile boolean												exceptionBreakpointsEnabled		= false;
	private ExceptionRequest												exceptionRequest				= null;
	private final Map<Long, ExceptionInfo>									exceptionInfoByThread			= new ConcurrentHashMap<>();

	// Conditional breakpoint support
	private static final int												CONDITION_EVAL_TIMEOUT_SECONDS	= 30;
	private final Map<Integer, Integer>										breakpointHitCounts				= new ConcurrentHashMap<>();

	private CompletableFuture<Value>										runtimeFuture					= null;

	/**
	 * Information about an exception that caused a stop event
	 */
	public static class ExceptionInfo {

		private final String	exceptionId;
		private final String	description;
		private final String	breakMode;
		private final String	exceptionType;
		private final String	exceptionMessage;

		public ExceptionInfo( String exceptionId, String description, String breakMode, String exceptionType, String exceptionMessage ) {
			this.exceptionId		= exceptionId;
			this.description		= description;
			this.breakMode			= breakMode;
			this.exceptionType		= exceptionType;
			this.exceptionMessage	= exceptionMessage;
		}

		public String getExceptionId() {
			return exceptionId;
		}

		public String getDescription() {
			return description;
		}

		public String getBreakMode() {
			return breakMode;
		}

		public String getExceptionType() {
			return exceptionType;
		}

		public String getExceptionMessage() {
			return exceptionMessage;
		}
	}

	/**
	 * Represents a DAP breakpoint that has been requested but not yet verified
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
	 * Information about a breakpoint that couldn't be set yet because the class isn't loaded
	 */
	private static class PendingBreakpointInfo {

		final String	filePath;
		final int		lineNumber;
		final int		breakpointId;
		final String	condition;
		final String	hitCondition;
		final String	logMessage;

		PendingBreakpointInfo( String filePath, int lineNumber, int breakpointId ) {
			this( filePath, lineNumber, breakpointId, null, null, null );
		}

		PendingBreakpointInfo( String filePath, int lineNumber, int breakpointId, String condition, String hitCondition, String logMessage ) {
			this.filePath		= filePath;
			this.lineNumber		= lineNumber;
			this.breakpointId	= breakpointId;
			this.condition		= condition;
			this.hitCondition	= hitCondition;
			this.logMessage		= logMessage;
		}
	}

	public VMController( VirtualMachine vm, IDebugProtocolClient client ) {
		this.vm		= vm;
		this.client	= client;

		// Only setup class prepare events if VM is available
		if ( vm != null ) {
			setupClassPrepareEvents();
			setupMethodEntryRequest();
		}
	}

	public CompletableFuture<Value> invokeStatic( String className, String methodName, List<String> paramTypes, List<Value> args ) {
		return InvokeTools.submitAndInvokeStatic( this, className, methodName, paramTypes, args );
	}

	public CompletableFuture<Value> invoke( ObjectReference obj, String methodName, List<String> paramTypes, List<Value> args ) {
		return InvokeTools.submitAndInvoke( this, obj, methodName, paramTypes, args );
	}

	public CompletableFuture<Void> pauseDebugThread() {
		// create other debug thread request
		this.methodEntryRequestDebugger = vm.eventRequestManager().createMethodEntryRequest();
		this.methodEntryRequestDebugger.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebuggerHelper" );
		// // req.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebugHelper" );
		this.methodEntryRequestDebugger.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		this.methodEntryRequestDebugger.enable();
		LOGGER.info( "Set up method entry request for DebugAgent" );
		this.debugFuture = new CompletableFuture<>();

		return this.debugFuture;
	}

	public ThreadReference getPreparedDebugInvokeThread() {
		try {

			if ( debugThread.suspendCount() == 0 ) {
				pauseDebugThread().get();
			}

			return debugThread;
		} catch ( InterruptedException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch ( ExecutionException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * Ensure the DebuggerHelper threads are ready for condition evaluation.
	 * The worker thread needs to be running to process queued tasks.
	 */
	private void ensureDebugHelperThreadsReady() {
		try {
			// Resume the worker thread if suspended - it processes the queue
			vm.allThreads().stream()
			    .filter( t -> t.name().equalsIgnoreCase( "DebuggerHelper-Worker" ) )
			    .findFirst()
			    .ifPresent( workerThread -> {
				    if ( workerThread.isSuspended() ) {
					    workerThread.resume();
					    LOGGER.fine( "Resumed DebuggerHelper-Worker thread for condition evaluation" );
				    }
			    } );
		} catch ( Exception e ) {
			LOGGER.warning( "Error ensuring debug helper threads ready: " + e.getMessage() );
		}
	}

	public CompletableFuture<ThreadReference> getSuspendedDebugThread() {
		var debug = getDebugThread();
		if ( !methodEntryRequest.isEnabled() && debug.isSuspended() ) {
			while ( debug.isSuspended() ) {
				debug.resume();
				LOGGER.info( "Resuming debug thread to reach method entry..." );
			}
		}

		methodEntryRequest.enable();
		LOGGER.info( "Method entry request enabled" );

		var f = new CompletableFuture<ThreadReference>();

		debugThreadAccessQueue.add( f );
		return f;
	}

	public CompletableFuture<Value> evaluateExpressionInFrame( int frameId, String expression ) {
		return getBreakpointContextbyStackFrame( frameId )
		    .map( bpContext -> {
			    try {

				    ObjectReference context	= bpContext.getContext();
				    ObjectReference runtime	= ( ObjectReference ) getRuntime().join();

				    var			evalFuture	= InvokeTools.submitAndInvoke(
				        this,
				        runtime,
				        "executeStatement",
				        List.of( "java.lang.String", "ortus.boxlang.runtime.context.IBoxContext" ),
				        List.of( vm.mirrorOf( expression ), context )
				    );

				    return evalFuture;
			    } catch ( Exception e ) {
				    int i = 0;

				    return null;
			    }
		    } )
		    .orElseGet( () -> CompletableFuture.completedFuture( null ) );
	}

	public void stepThread( long threadId ) {
		if ( stepRequests.containsKey( threadId ) ) {
			var oldReq = stepRequests.remove( threadId );
			oldReq.disable();
			vm.eventRequestManager().deleteEventRequest( oldReq );
		}

		var thread = vm.allThreads().stream().filter( t -> t.uniqueID() == threadId ).findFirst();

		if ( thread.isEmpty() ) {
			LOGGER.warning( "Cannot step thread - not found: " + threadId );
			return;
		}

		var stepRequest = vm.eventRequestManager().createStepRequest( thread.get(),
		    StepRequest.STEP_LINE,
		    StepRequest.STEP_OVER );
		stepRequest.addClassFilter( "boxgenerated.*" );
		stepRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		stepRequest.enable();
		stepRequests.put( threadId, stepRequest );

		continueExecution( ( int ) threadId );
	}

	public void stepInThread( long threadId ) {
		if ( stepRequests.containsKey( threadId ) ) {
			var oldReq = stepRequests.remove( threadId );
			oldReq.disable();
			vm.eventRequestManager().deleteEventRequest( oldReq );
		}

		var thread = vm.allThreads().stream().filter( t -> t.uniqueID() == threadId ).findFirst();

		if ( thread.isEmpty() ) {
			LOGGER.warning( "Cannot step thread - not found: " + threadId );
			return;
		}

		var stepRequest = vm.eventRequestManager().createStepRequest( thread.get(),
		    StepRequest.STEP_LINE,
		    StepRequest.STEP_INTO );
		stepRequest.addClassFilter( "boxgenerated.*" );
		stepRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		stepRequest.enable();
		stepRequests.put( threadId, stepRequest );

		continueExecution( ( int ) threadId );
	}

	public void stepOutThread( long threadId ) {
		if ( stepRequests.containsKey( threadId ) ) {
			var oldReq = stepRequests.remove( threadId );
			oldReq.disable();
			vm.eventRequestManager().deleteEventRequest( oldReq );
		}

		var thread = vm.allThreads().stream().filter( t -> t.uniqueID() == threadId ).findFirst();

		if ( thread.isEmpty() ) {
			LOGGER.warning( "Cannot step thread - not found: " + threadId );
			return;
		}

		var stepRequest = vm.eventRequestManager().createStepRequest( thread.get(),
		    StepRequest.STEP_LINE,
		    StepRequest.STEP_OUT );
		stepRequest.addClassFilter( "boxgenerated.*" );
		stepRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		stepRequest.enable();
		stepRequests.put( threadId, stepRequest );

		continueExecution( ( int ) threadId );
	}

	public Optional<BreakpointContext> getBreakpointContext( int breakpointId ) {
		return Optional.ofNullable( breakPointContexts.get( breakpointId ) );
	}

	public Optional<BreakpointContext> getBreakpointContextByThread( int threadId ) {
		return breakPointContexts.values()
		    .stream()
		    .filter( ctx -> ctx.getThreadReference().uniqueID() == threadId )
		    .findFirst();
	}

	public Optional<BreakpointContext> getBreakpointContextbyStackFrame( int stackframeId ) {
		return breakPointContexts.values()
		    .stream()
		    .filter( ctx -> ctx.hasStackFrameId( stackframeId ) )
		    .findFirst();
	}

	/**
	 * Set up class prepare events to catch when classes are loaded
	 */
	private void setupClassPrepareEvents() {
		if ( vm == null ) {
			LOGGER.warning( "Cannot setup class prepare events - VM is null" );
			return;
		}

		EventRequestManager	requestManager		= vm.eventRequestManager();
		ClassPrepareRequest	classPrepareRequest	= requestManager.createClassPrepareRequest();
		// Listen for all classes to catch BoxLang generated classes with any pattern
		classPrepareRequest.addClassFilter( "boxgenerated.*" );
		classPrepareRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		classPrepareRequest.enable();
		LOGGER.info( "Set up class prepare events for all classes (to catch BoxLang generated classes)" );

		// Also listen for BoxRuntimeException class loading (for deferred exception breakpoints)
		ClassPrepareRequest exceptionClassPrepareRequest = requestManager.createClassPrepareRequest();
		exceptionClassPrepareRequest.addClassFilter( BOX_RUNTIME_EXCEPTION_CLASS );
		exceptionClassPrepareRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		exceptionClassPrepareRequest.enable();
		LOGGER.info( "Set up class prepare events for BoxRuntimeException" );
	}

	/**
	 * Enable or disable exception breakpoints for BoxLang exceptions
	 * 
	 * @param enabled true to enable, false to disable
	 */
	public void setExceptionBreakpointsEnabled( boolean enabled ) {
		this.exceptionBreakpointsEnabled = enabled;

		if ( vm == null ) {
			LOGGER.info( "VM not available, exception breakpoints will be set when VM is ready. Enabled=" + enabled );
			return;
		}

		if ( enabled ) {
			setupExceptionRequest();
		} else {
			clearExceptionRequest();
		}
	}

	/**
	 * Set up the JDI exception request for BoxRuntimeException
	 */
	private void setupExceptionRequest() {
		if ( vm == null || exceptionRequest != null ) {
			return;
		}

		// Try to find the BoxRuntimeException class if already loaded
		ReferenceType exceptionClass = findExceptionClass();

		if ( exceptionClass != null ) {
			createExceptionRequest( exceptionClass );
		} else {
			LOGGER.info( "BoxRuntimeException class not yet loaded, will set exception request when class loads" );
		}
	}

	/**
	 * Find the BoxRuntimeException class in loaded classes
	 */
	private ReferenceType findExceptionClass() {
		if ( vm == null ) {
			return null;
		}

		for ( ReferenceType refType : vm.allClasses() ) {
			if ( refType.name().equals( BOX_RUNTIME_EXCEPTION_CLASS ) ) {
				return refType;
			}
		}
		return null;
	}

	/**
	 * Create the JDI exception request for the given exception class
	 */
	private void createExceptionRequest( ReferenceType exceptionClass ) {
		if ( vm == null || exceptionRequest != null ) {
			return;
		}

		try {
			EventRequestManager requestManager = vm.eventRequestManager();

			// Create exception request for caught and uncaught BoxRuntimeException
			exceptionRequest = requestManager.createExceptionRequest( exceptionClass, true, true );
			exceptionRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
			exceptionRequest.enable();

			LOGGER.info( "Created exception request for " + BOX_RUNTIME_EXCEPTION_CLASS );
		} catch ( Exception e ) {
			LOGGER.severe( "Failed to create exception request: " + e.getMessage() );
		}
	}

	/**
	 * Clear the exception request
	 */
	private void clearExceptionRequest() {
		if ( exceptionRequest != null && vm != null ) {
			try {
				exceptionRequest.disable();
				vm.eventRequestManager().deleteEventRequest( exceptionRequest );
				LOGGER.info( "Cleared exception request" );
			} catch ( Exception e ) {
				LOGGER.warning( "Error clearing exception request: " + e.getMessage() );
			}
			exceptionRequest = null;
		}
		exceptionInfoByThread.clear();
	}

	/**
	 * Get exception info for a thread that stopped due to an exception
	 * 
	 * @param threadId the thread ID
	 * 
	 * @return the ExceptionInfo or null if not available
	 */
	public ExceptionInfo getExceptionInfo( long threadId ) {
		return exceptionInfoByThread.get( threadId );
	}

	/**
	 * Set a breakpoint at the specified file and line
	 */
	public boolean setBreakpoint( PendingBreakpoint pending ) {
		String				filePath		= pending.getFilePath();
		int					lineNumber		= pending.getSourceBreakpoint().getLine();
		SourceBreakpoint	srcBp			= pending.getSourceBreakpoint();
		String				condition		= srcBp.getCondition();
		String				hitCondition	= srcBp.getHitCondition();
		String				logMessage		= srcBp.getLogMessage();

		try {
			LOGGER.info( "Attempting to set breakpoint at " + filePath + ":" + lineNumber );

			// If VM is not available, just add to pending breakpoints
			if ( vm == null ) {
				pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, pending.breakpoint.getId(), condition, hitCondition, logMessage ) );
				LOGGER.info( "VM not available, added breakpoint to pending list: " + filePath + ":" + lineNumber );
				return true;
			}

			// Try to set the breakpoint immediately if the class is already loaded
			if ( trySetBreakpointOnLoadedClass( pending.getBreakpoint().getId(), filePath, lineNumber, condition, hitCondition, logMessage ) ) {
				return true;
			}

			// If not successful, add to pending breakpoints
			pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, pending.getBreakpoint().getId(), condition, hitCondition, logMessage ) );
			LOGGER.info( "Added breakpoint to pending list: " + filePath + ":" + lineNumber );
			return true; // Return true since we'll set it when the class loads

		} catch ( Exception e ) {
			LOGGER.severe( "Failed to set breakpoint: " + e.getMessage() );
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Try to set a breakpoint on an already loaded class
	 * 
	 * @param breakpointId unique ID for this breakpoint
	 * @param filePath     source file path
	 * @param lineNumber   line number in source
	 * @param condition    optional condition expression (may be null)
	 * @param hitCondition optional hit count condition (may be null)
	 * @param logMessage   optional log message for logpoints (may be null)
	 * 
	 * @return true if breakpoint was set on a loaded class
	 */
	private boolean trySetBreakpointOnLoadedClass( int breakpointId, String filePath, int lineNumber,
	    String condition, String hitCondition, String logMessage ) {
		// Require VM to be available
		if ( vm == null ) {
			return false;
		}

		// Get all loaded classes that might contain this file
		List<ReferenceType> classes = vm.allClasses();

		for ( ReferenceType refType : classes ) {
			try {
				// Try to find the location for this line in this class
				List<Location> locations = refType.locationsOfLine( lineNumber );

				if ( !locations.isEmpty() ) {
					// Check if this location corresponds to our file
					Location	location	= locations.get( 0 );
					String		sourceName	= getSourceName( location );

					LOGGER.fine( "Found location at " + sourceName + ":" + lineNumber + " in class " + refType.name() );

					// More flexible matching: check if the class name matches what we expect
					if ( sourceName != null && sourceName.equalsIgnoreCase( filePath ) ) {
						return createBreakpointRequest( breakpointId, location, filePath, lineNumber, condition, hitCondition, logMessage );
					}
				}
			} catch ( AbsentInformationException e ) {
				// This class doesn't have debug info, skip it
				continue;
			}
		}

		LOGGER.fine( "Class not yet loaded for breakpoint at " + filePath + ":" + lineNumber );

		return false;
	}

	/**
	 * Create a breakpoint request for the given location
	 * 
	 * @param breakpointId unique ID for this breakpoint
	 * @param location     JDI location for the breakpoint
	 * @param filePath     source file path
	 * @param lineNumber   line number in source
	 * @param condition    optional condition expression (may be null)
	 * @param hitCondition optional hit count condition (may be null)
	 * @param logMessage   optional log message for logpoints (may be null)
	 * 
	 * @return true if breakpoint was created successfully
	 */
	private boolean createBreakpointRequest( int breakpointId, Location location, String filePath, int lineNumber,
	    String condition, String hitCondition, String logMessage ) {
		try {
			EventRequestManager	requestManager		= vm.eventRequestManager();
			BreakpointRequest	breakpointRequest	= requestManager.createBreakpointRequest( location );
			breakpointRequest.setSuspendPolicy( BreakpointRequest.SUSPEND_EVENT_THREAD );

			// Store breakpoint properties
			breakpointRequest.putProperty( "breakPointId", breakpointId );
			breakpointRequest.putProperty( "condition", condition );
			breakpointRequest.putProperty( "hitCondition", hitCondition );
			breakpointRequest.putProperty( "logMessage", logMessage );

			// Initialize hit count for this breakpoint
			breakpointHitCounts.put( breakpointId, 0 );

			// Enable the breakpoint
			breakpointRequest.enable();
			activeBreakpoints.add( breakpointRequest );

			LOGGER.info( "Successfully set breakpoint at " + filePath + ":" + lineNumber +
			    ( condition != null ? " [condition: " + condition + "]" : "" ) +
			    ( hitCondition != null ? " [hitCondition: " + hitCondition + "]" : "" ) +
			    ( logMessage != null ? " [logMessage: " + logMessage + "]" : "" ) );
			return true;

		} catch ( Exception e ) {
			LOGGER.severe( "Failed to create breakpoint request: " + e.getMessage() );
			return false;
		}
	}

	/**
	 * Get source name from location, handling potential exceptions
	 */
	private String getSourceName( Location location ) {
		try {
			return location.sourceName();
		} catch ( AbsentInformationException e ) {
			return null;
		}
	}

	/**
	 * Start processing JDI events (breakpoints, etc.)
	 */
	public void startEventProcessing() {
		if ( vm == null ) {
			LOGGER.warning( "Cannot start event processing - VM is null" );
			return;
		}

		if ( eventProcessingActive ) {
			return;
		}

		eventProcessingActive	= true;
		eventProcessingThread	= new Thread( this::processEvents, "BreakpointEventProcessor" );
		eventProcessingThread.setDaemon( true );
		eventProcessingThread.start();

		LOGGER.info( "Started breakpoint event processing" );
	}

	/**
	 * Stop processing events
	 */
	public void stopEventProcessing() {
		eventProcessingActive = false;
		if ( eventProcessingThread != null ) {
			eventProcessingThread.interrupt();
		}
		LOGGER.info( "Stopped breakpoint event processing" );
	}

	/**
	 * Process JDI events in a loop
	 */
	private void processEvents() {
		EventQueue eventQueue = vm.eventQueue();

		while ( eventProcessingActive ) {
			try {
				// Wait for events (this blocks until an event occurs)
				EventSet eventSet = eventQueue.remove( 1000 ); // 1 second timeout

				if ( eventSet == null ) {
					continue; // Timeout, check if we should continue
				}

				EventIterator eventIterator = eventSet.eventIterator();

				while ( eventIterator.hasNext() ) {
					Event event = eventIterator.nextEvent();

					LOGGER.fine( "Processing event: " + event.toString() );

					if ( event instanceof BreakpointEvent be ) {
						handleBreakpointEvent( be );
					} else if ( event instanceof StepEvent se ) {
						handleStepEvent( se );
					} else if ( event instanceof ClassPrepareEvent cpe ) {
						handleClassPrepareEvent( cpe );
					} else if ( event instanceof MethodEntryEvent mee ) {
						handleMethodEntryEvent( mee );
					} else if ( event instanceof ExceptionEvent ee ) {
						handleExceptionEvent( ee );
					} else if ( event instanceof VMDeathEvent || event instanceof VMDisconnectEvent ) {
						LOGGER.info( "VM terminated, stopping event processing" );
						eventProcessingActive = false;
						return;
					}
				}

				// Only resume for non-breakpoint events
				// For breakpoint events, the thread should remain suspended until continue is called
				boolean			shouldResume	= true;
				EventIterator	iter			= eventSet.eventIterator();
				while ( iter.hasNext() ) {
					Event evt = iter.nextEvent();
					if ( evt instanceof BreakpointEvent be ) {
						eventSets.put( be.thread().uniqueID(), eventSet );
						shouldResume = false; // Don't auto-resume on breakpoint - wait for continue request
						break;
					} else if ( evt instanceof MethodEntryEvent mee ) {
						shouldResume = false; // Don't auto-resume on breakpoint - wait for continue request
						break;
					} else if ( evt instanceof StepEvent se ) {
						eventSets.put( se.thread().uniqueID(), eventSet );
						shouldResume = false; // Don't auto-resume on breakpoint - wait for continue request
						break;
					} else if ( evt instanceof ExceptionEvent ee ) {
						eventSets.put( ee.thread().uniqueID(), eventSet );
						shouldResume = false; // Don't auto-resume on exception - wait for continue request
						break;
					}
				}

				if ( shouldResume ) {
					eventSet.resume();
				}

				var helperThread = vm.allThreads().stream().filter( t -> t.name().equalsIgnoreCase( "DebuggerHelper-Worker" ) ).findFirst().get();

				if ( helperThread.isSuspended() ) {
					helperThread.resume();
				}
				// If shouldResume is false (breakpoint hit), the thread stays suspended
				// until the debugger client sends a continue/step request

			} catch ( InterruptedException e ) {
				LOGGER.info( "Event processing interrupted" );
				Thread.currentThread().interrupt();
				break;
			} catch ( Exception e ) {
				LOGGER.severe( "Error processing events: " + e.getMessage() );
			}
		}
	}

	public ThreadReference getDebugThread() {
		for ( ThreadReference ref : vm.allThreads() ) {
			if ( ref.name().equals( "bxDebugAgent" ) ) {
				return ref;
			}
		}

		return null;
	}

	private void handleMethodEntryEvent( MethodEntryEvent event ) {
		var	className	= event.location().declaringType().name();
		var	methodName	= event.location().method().name();

		LOGGER.info( "Handling MethodEntryEvent for " + className + "." + methodName );

		if ( className.equalsIgnoreCase( "ortus.boxlang.moduleslug.instrumentation.DebuggerHelper" ) ) {
			if ( !methodName.equals( "methodEntryBreakpointHook" ) ) {
				event.thread().resume();
				return;
			}

			LOGGER.fine( "DebuggerHelper.methodEntryBreakpoinHook invoked" );

			if ( debugThread == null ) {
				debugThread = event.thread();
				this.methodEntryRequestDebugger.setEnabled( false );
				this.vm.eventRequestManager().deleteEventRequest( this.methodEntryRequestDebugger );
				LOGGER.info( "Completed debug thread future from method entry hook" );
			}

			if ( this.debugFuture != null ) {
				this.methodEntryRequestDebugger.setEnabled( false );
				this.vm.eventRequestManager().deleteEventRequest( this.methodEntryRequestDebugger );
				this.debugFuture.complete( null );
				this.debugFuture = null;
				LOGGER.info( "Completed pause debug thread future from method entry hook" );
			}

			return;
		}

		if ( !className.equals( "ortus.boxlang.moduleslug.instrumentation.DebugAgent" )
		    && !className.equals( "ortus.boxlang.moduleslug.instrumentation.DebuggerHelper" ) ) {
			event.thread().resume();
			return;
		}

		CompletableFuture<ThreadReference> f = debugThreadAccessQueue.poll();

		if ( f == null ) {
			LOGGER.warning( "No future available for method entry event" );
			methodEntryRequest.setEnabled( debugThreadAccessQueue.size() > 0 );
			event.thread().resume();
			return;
		}

		if ( debugThread != null ) {
			methodEntryRequest.setEnabled( debugThreadAccessQueue.size() > 0 );
			LOGGER.info( "Method entry request enabled: " + methodEntryRequest.isEnabled() );
		}

		f.complete( getDebugThread() );
	}

	/**
	 * Handle a breakpoint event
	 */
	private void handleBreakpointEvent( BreakpointEvent event ) {
		try {
			// Ensure helper threads are available for condition evaluation
			// The worker thread processes tasks, the invoker thread is used for JDI invocations
			// ensureDebugHelperThreadsReady();

			Location			location		= event.location();
			String				sourceName		= getSourceName( location );
			int					lineNumber		= location.lineNumber();
			BreakpointRequest	request			= ( BreakpointRequest ) event.request();
			Integer				breakpointId	= ( Integer ) request.getProperty( "breakPointId" );
			String				condition		= ( String ) request.getProperty( "condition" );
			String				hitCondition	= ( String ) request.getProperty( "hitCondition" );
			String				logMessage		= ( String ) request.getProperty( "logMessage" );

			LOGGER.info( "Breakpoint hit at " + sourceName + ":" + lineNumber );

			// Update hit count
			int hitCount = breakpointHitCounts.getOrDefault( breakpointId, 0 ) + 1;
			breakpointHitCounts.put( breakpointId, hitCount );

			// Track context for expression evaluation (needed before condition check)
			int contextId = generateBreakpointId();
			trackBreakpointContext( contextId, event.thread() );

			// Check hit condition if specified
			if ( hitCondition != null && !hitCondition.isEmpty() ) {
				if ( !checkHitCondition( hitCondition, hitCount ) ) {
					LOGGER.info( "Hit condition not met: " + hitCondition + " (hit count: " + hitCount + ")" );
					event.thread().resume();
					return;
				}
			}

			// Check condition if specified
			if ( condition != null && !condition.isEmpty() ) {
				if ( !evaluateCondition( contextId, condition ) ) {
					LOGGER.info( "Condition evaluated to false: " + condition );
					event.thread().resume();
					return;
				}
			}

			// Handle log point - send output and resume
			if ( logMessage != null && !logMessage.isEmpty() ) {
				String expandedMessage = expandLogMessage( contextId, logMessage, hitCount );
				sendLogOutput( expandedMessage, sourceName, lineNumber );
				LOGGER.info( "Logpoint: " + expandedMessage );
				event.thread().resume();
				return;
			}

			// Send stopped event to the debug client
			if ( client != null ) {
				StoppedEventArguments stoppedArgs = new StoppedEventArguments();
				stoppedArgs.setReason( "breakpoint" );
				stoppedArgs.setDescription( "Paused on breakpoint" );
				stoppedArgs.setThreadId( ( int ) event.thread().uniqueID() );
				stoppedArgs.setHitBreakpointIds( new Integer[] { breakpointId } );

				client.stopped( stoppedArgs );
				LOGGER.info( "Sent stopped event to client" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error handling breakpoint event: " + e.getMessage() );
		}
	}

	/**
	 * Check if the hit condition is satisfied
	 * Supports: N (equals), >N, >=N, <N, <=N, %N (every Nth hit)
	 */
	private boolean checkHitCondition( String hitCondition, int hitCount ) {
		try {
			String trimmed = hitCondition.trim();

			if ( trimmed.startsWith( ">=" ) ) {
				int threshold = Integer.parseInt( trimmed.substring( 2 ).trim() );
				return hitCount >= threshold;
			} else if ( trimmed.startsWith( "<=" ) ) {
				int threshold = Integer.parseInt( trimmed.substring( 2 ).trim() );
				return hitCount <= threshold;
			} else if ( trimmed.startsWith( ">" ) ) {
				int threshold = Integer.parseInt( trimmed.substring( 1 ).trim() );
				return hitCount > threshold;
			} else if ( trimmed.startsWith( "<" ) ) {
				int threshold = Integer.parseInt( trimmed.substring( 1 ).trim() );
				return hitCount < threshold;
			} else if ( trimmed.startsWith( "%" ) ) {
				int modulo = Integer.parseInt( trimmed.substring( 1 ).trim() );
				return modulo > 0 && hitCount % modulo == 0;
			} else {
				// Plain number means equals
				int threshold = Integer.parseInt( trimmed );
				return hitCount == threshold;
			}
		} catch ( NumberFormatException e ) {
			LOGGER.warning( "Invalid hit condition format: " + hitCondition );
			return true; // Default to stopping on error
		}
	}

	/**
	 * Evaluate a condition expression in the current breakpoint context
	 * Returns true if the condition is truthy, false otherwise
	 */
	private boolean evaluateCondition( int contextId, String condition ) {
		try {
			Optional<BreakpointContext> bpContextOpt = getBreakpointContext( contextId );
			if ( bpContextOpt.isEmpty() ) {
				LOGGER.warning( "No breakpoint context found for condition evaluation" );
				return true; // Default to stopping if we can't evaluate
			}

			BreakpointContext	bpContext	= bpContextOpt.get();
			ObjectReference		context		= bpContext.getContext();

			if ( context == null ) {
				LOGGER.warning( "No IBoxContext found for condition evaluation" );
				return true;
			}

			ObjectReference runtime = ( ObjectReference ) getRuntime().get( CONDITION_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS );
			if ( runtime == null ) {
				LOGGER.warning( "Could not get runtime for condition evaluation" );
				return true;
			}

			CompletableFuture<Value>	evalFuture	= InvokeTools.submitAndInvoke(
			    this,
			    runtime,
			    "executeStatement",
			    List.of( "java.lang.String", "ortus.boxlang.runtime.context.IBoxContext" ),
			    List.of( vm.mirrorOf( condition ), context )
			);

			Value						result		= evalFuture.get( CONDITION_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS );
			return isTruthy( result );

		} catch ( TimeoutException e ) {
			LOGGER.warning( "Condition evaluation timed out after " + CONDITION_EVAL_TIMEOUT_SECONDS + " seconds: " + condition );
			return true; // Default to stopping on timeout
		} catch ( Exception e ) {
			LOGGER.warning( "Error evaluating condition: " + e.getMessage() );
			return true; // Default to stopping on error
		}
	}

	/**
	 * Determine if a JDI Value is truthy
	 */
	private boolean isTruthy( Value value ) {
		if ( value == null ) {
			return false;
		}

		// Handle BooleanValue
		if ( value instanceof com.sun.jdi.BooleanValue ) {
			return ( ( com.sun.jdi.BooleanValue ) value ).value();
		}

		// Handle numeric types - non-zero is truthy
		if ( value instanceof com.sun.jdi.PrimitiveValue ) {
			if ( value instanceof com.sun.jdi.IntegerValue ) {
				return ( ( com.sun.jdi.IntegerValue ) value ).value() != 0;
			} else if ( value instanceof com.sun.jdi.LongValue ) {
				return ( ( com.sun.jdi.LongValue ) value ).value() != 0;
			} else if ( value instanceof com.sun.jdi.DoubleValue ) {
				return ( ( com.sun.jdi.DoubleValue ) value ).value() != 0.0;
			} else if ( value instanceof com.sun.jdi.FloatValue ) {
				return ( ( com.sun.jdi.FloatValue ) value ).value() != 0.0f;
			}
		}

		// Handle StringReference - non-empty is truthy
		if ( value instanceof com.sun.jdi.StringReference ) {
			String strValue = ( ( com.sun.jdi.StringReference ) value ).value();
			return strValue != null && !strValue.isEmpty() && !strValue.equalsIgnoreCase( "false" );
		}

		// Handle ObjectReference - non-null is truthy
		if ( value instanceof ObjectReference ) {
			return true;
		}

		return false;
	}

	/**
	 * Expand a log message by replacing {expression} placeholders with evaluated values
	 */
	private String expandLogMessage( int contextId, String logMessage, int hitCount ) {
		// Replace special placeholders
		String			expanded	= logMessage.replace( "{hitCount}", String.valueOf( hitCount ) );

		// Find and evaluate {expression} placeholders
		StringBuilder	result		= new StringBuilder();
		int				i			= 0;
		while ( i < expanded.length() ) {
			if ( expanded.charAt( i ) == '{' ) {
				int end = expanded.indexOf( '}', i );
				if ( end > i ) {
					String expression = expanded.substring( i + 1, end );
					if ( !expression.equals( "hitCount" ) ) { // Already handled
						String value = evaluateExpressionForLog( contextId, expression );
						result.append( value );
					} else {
						result.append( String.valueOf( hitCount ) );
					}
					i = end + 1;
					continue;
				}
			}
			result.append( expanded.charAt( i ) );
			i++;
		}

		return result.toString();
	}

	/**
	 * Evaluate an expression for log message expansion
	 */
	private String evaluateExpressionForLog( int contextId, String expression ) {
		try {
			Optional<BreakpointContext> bpContextOpt = getBreakpointContext( contextId );
			if ( bpContextOpt.isEmpty() ) {
				return "<no context>";
			}

			BreakpointContext	bpContext	= bpContextOpt.get();
			ObjectReference		context		= bpContext.getContext();

			if ( context == null ) {
				return "<no context>";
			}

			ObjectReference runtime = ( ObjectReference ) getRuntime().get( CONDITION_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS );
			if ( runtime == null ) {
				return "<error>";
			}

			CompletableFuture<Value>	evalFuture	= InvokeTools.submitAndInvoke(
			    this,
			    runtime,
			    "executeStatement",
			    List.of( "java.lang.String", "ortus.boxlang.runtime.context.IBoxContext" ),
			    List.of( vm.mirrorOf( expression ), context )
			);

			Value						result		= evalFuture.get( CONDITION_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS );
			return valueToString( result );

		} catch ( Exception e ) {
			return "<error: " + e.getMessage() + ">";
		}
	}

	/**
	 * Convert a JDI Value to a string representation
	 */
	private String valueToString( Value value ) {
		if ( value == null ) {
			return "null";
		}

		if ( value instanceof com.sun.jdi.StringReference ) {
			return ( ( com.sun.jdi.StringReference ) value ).value();
		}

		if ( value instanceof com.sun.jdi.PrimitiveValue ) {
			return value.toString();
		}

		if ( value instanceof ObjectReference ) {
			ObjectReference objRef = ( ObjectReference ) value;
			// Try to invoke toString()
			try {
				ClassType	classType		= ( ClassType ) objRef.referenceType();
				var			toStringMethod	= classType.methodsByName( "toString" ).stream()
				    .filter( m -> m.argumentTypeNames().isEmpty() )
				    .findFirst();

				if ( toStringMethod.isPresent() ) {
					CompletableFuture<Value>	future	= InvokeTools.submitAndInvoke(
					    this, objRef, "toString", List.of(), List.of()
					);
					Value						strVal	= future.get( 2, TimeUnit.SECONDS );
					if ( strVal instanceof com.sun.jdi.StringReference ) {
						return ( ( com.sun.jdi.StringReference ) strVal ).value();
					}
				}
			} catch ( Exception e ) {
				// Fall through to default
			}
			return objRef.referenceType().name() + "@" + objRef.uniqueID();
		}

		return value.toString();
	}

	/**
	 * Send a log output event to the debug client
	 */
	private void sendLogOutput( String message, String sourceName, int lineNumber ) {
		if ( client != null ) {
			OutputEventArguments outputArgs = new OutputEventArguments();
			outputArgs.setCategory( "console" );
			outputArgs.setOutput( message + "\n" );

			if ( sourceName != null ) {
				Source source = new Source();
				source.setPath( sourceName );
				outputArgs.setSource( source );
				outputArgs.setLine( lineNumber );
			}

			client.output( outputArgs );
		}
	}

	private void handleStepEvent( StepEvent event ) {
		try {
			Location	location	= event.location();
			String		sourceName	= getSourceName( location );
			int			lineNumber	= location.lineNumber();

			LOGGER.info( "Step completed at " + sourceName + ":" + lineNumber );

			// Remove the step request as it is no longer needed
			StepRequest stepRequest = stepRequests.remove( event.thread().uniqueID() );
			if ( stepRequest != null ) {
				vm.eventRequestManager().deleteEventRequest( stepRequest );
			}

			trackBreakpointContext( generateBreakpointId(), event.thread() );

			// Send stopped event to the debug client
			if ( client != null ) {
				StoppedEventArguments stoppedArgs = new StoppedEventArguments();
				stoppedArgs.setReason( "step" );
				stoppedArgs.setDescription( "Paused after step" );
				stoppedArgs.setThreadId( ( int ) event.thread().uniqueID() );

				client.stopped( stoppedArgs );
				LOGGER.info( "Sent stopped event to client after step" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error handling step event: " + e.getMessage() );
		}
	}

	/**
	 * Handle an exception event
	 */
	private void handleExceptionEvent( ExceptionEvent event ) {
		try {
			Location		location		= event.catchLocation() != null ? event.catchLocation() : event.location();
			String			sourceName		= getSourceName( location );
			int				lineNumber		= location.lineNumber();
			ObjectReference	exceptionObj	= event.exception();
			String			exceptionType	= exceptionObj.referenceType().name();

			// Determine if this is a caught or uncaught exception
			boolean			isCaught		= event.catchLocation() != null;
			String			breakMode		= isCaught ? "always" : "unhandled";

			LOGGER.info( "Exception hit: " + exceptionType + " at " + sourceName + ":" + lineNumber + " (caught=" + isCaught + ")" );

			// Extract exception message if possible
			String			exceptionMessage	= extractExceptionMessage( exceptionObj );

			// Store exception info for this thread
			ExceptionInfo	exceptionInfo		= new ExceptionInfo(
			    exceptionType,
			    exceptionMessage != null ? exceptionMessage : exceptionType,
			    breakMode,
			    exceptionType,
			    exceptionMessage
			);
			exceptionInfoByThread.put( event.thread().uniqueID(), exceptionInfo );

			// Track the breakpoint context
			trackBreakpointContext( generateBreakpointId(), event.thread() );

			// Send stopped event to the debug client
			if ( client != null ) {
				StoppedEventArguments stoppedArgs = new StoppedEventArguments();
				stoppedArgs.setReason( "exception" );
				stoppedArgs.setDescription( "Paused on exception: " + exceptionType );
				stoppedArgs.setThreadId( ( int ) event.thread().uniqueID() );
				stoppedArgs.setText( exceptionMessage != null ? exceptionMessage : exceptionType );

				client.stopped( stoppedArgs );
				LOGGER.info( "Sent stopped event to client for exception" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error handling exception event: " + e.getMessage() );
		}
	}

	/**
	 * Extract the exception message from the exception object
	 */
	private String extractExceptionMessage( ObjectReference exceptionObj ) {
		try {
			// Try to get the getMessage() result
			com.sun.jdi.Method getMessageMethod = null;
			for ( com.sun.jdi.Method m : exceptionObj.referenceType().methodsByName( "getMessage" ) ) {
				try {
					if ( m.argumentTypes().isEmpty() ) {
						getMessageMethod = m;
						break;
					}
				} catch ( com.sun.jdi.ClassNotLoadedException e ) {
					// Skip methods with unloaded argument types
					continue;
				}
			}

			if ( getMessageMethod != null ) {
				com.sun.jdi.Value result = exceptionObj.invokeMethod(
				    exceptionObj.owningThread(),
				    getMessageMethod,
				    new ArrayList<>(),
				    ObjectReference.INVOKE_SINGLE_THREADED
				);

				if ( result instanceof com.sun.jdi.StringReference ) {
					return ( ( com.sun.jdi.StringReference ) result ).value();
				}
			}
		} catch ( Exception e ) {
			LOGGER.fine( "Could not extract exception message: " + e.getMessage() );
		}
		return null;
	}

	private void trackBreakpointContext( int breakpointId, ThreadReference thread ) {
		for ( var entry : breakPointContexts.entrySet() ) {
			if ( entry.getValue().getThreadReference().equals( thread ) ) {
				breakPointContexts.remove( entry.getKey() );
			}
		}

		breakPointContexts.put( breakpointId, new BreakpointContext( breakpointId, thread, this ) );
	}

	/**
	 * Handle a class prepare event - try to set pending breakpoints
	 */
	private void handleClassPrepareEvent( ClassPrepareEvent event ) {
		ReferenceType refType = event.referenceType();
		LOGGER.info( "Class loaded: " + refType.name() );

		// Check if this is the BoxRuntimeException class and we have exception breakpoints enabled
		if ( refType.name().equals( BOX_RUNTIME_EXCEPTION_CLASS ) && exceptionBreakpointsEnabled && exceptionRequest == null ) {
			LOGGER.info( "BoxRuntimeException class loaded, setting up exception request" );
			createExceptionRequest( refType );
		}

		// Check if this is a BoxLang generated class
		if ( refType.name().toLowerCase().contains( "box" ) ||
		    refType.name().toLowerCase().contains( "generated" ) ||
		    refType.name().toLowerCase().contains( "script" ) ) {
			LOGGER.info( "Potential BoxLang class detected: " + refType.name() );
		}

		// Try to set any pending breakpoints for this class
		List<PendingBreakpointInfo> toRemove = new ArrayList<>();

		for ( PendingBreakpointInfo pending : pendingBreakpoints ) {
			if ( trySetBreakpointOnLoadedClass( pending.breakpointId, pending.filePath, pending.lineNumber,
			    pending.condition, pending.hitCondition, pending.logMessage ) ) {
				toRemove.add( pending );
				LOGGER.fine( "Successfully set pending breakpoint at " + pending.filePath + ":" + pending.lineNumber );
			}
		}

		// Remove successfully set breakpoints from pending list
		pendingBreakpoints.removeAll( toRemove );
	}

	/**
	 * Clear all active breakpoints
	 */
	public void clearAllBreakpoints() {
		if ( vm == null ) {
			LOGGER.info( "VM is null, no active breakpoints to clear" );
			return;
		}

		EventRequestManager requestManager = vm.eventRequestManager();

		for ( BreakpointRequest request : activeBreakpoints ) {
			requestManager.deleteEventRequest( request );
		}

		activeBreakpoints.clear();
		LOGGER.info( "Cleared all breakpoints" );
	}

	/**
	 * Get the count of active breakpoints
	 */
	public int getActiveBreakpointCount() {
		return activeBreakpoints.size();
	}

	/**
	 * Generate a unique breakpoint ID
	 */
	public int generateBreakpointId() {
		return breakpointIdCounter++;
	}

	/**
	 * Store pending breakpoint for later verification
	 */
	public void storePendingBreakpoint( Source source, SourceBreakpoint sourceBreakpoint, Breakpoint breakpoint ) {
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
	 * Verify and set pending breakpoints using JDI
	 */
	public void verifyAndSetPendingBreakpoints() {
		int totalPending = pendingBreakpointsById.size();
		if ( totalPending > 0 ) {
			LOGGER.info( "Setting " + totalPending + " pending breakpoints" );

			for ( PendingBreakpoint pending : pendingBreakpointsById.values() ) {
				String	filePath	= pending.getFilePath();
				int		lineNumber	= pending.getSourceBreakpoint().getLine();

				boolean	success		= setBreakpoint( pending );
				if ( success ) {
					// Mark breakpoint as verified
					pending.getBreakpoint().setVerified( true );
					pending.getBreakpoint().setMessage( "Breakpoint verified and set" );
					LOGGER.info( "Successfully set breakpoint at " + filePath + ":" + lineNumber );
				} else {
					pending.getBreakpoint().setMessage( "Could not verify breakpoint location" );
					LOGGER.warning( "Failed to set breakpoint at " + filePath + ":" + lineNumber );
				}
			}
		}
	}

	/**
	 * Track a source breakpoint by creating a Breakpoint object and storing it as pending.
	 * This encapsulates the breakpoint creation logic and provides better separation of concerns.
	 * 
	 * @param source           The source file information
	 * @param sourceBreakpoint The source breakpoint from the client
	 * 
	 * @return The generated Breakpoint object
	 */
	public Breakpoint trackSourceBreakpoint( Source source, SourceBreakpoint sourceBreakpoint ) {
		// Create the breakpoint with generated ID and initial state
		Breakpoint breakpoint = new Breakpoint();
		breakpoint.setId( generateBreakpointId() );
		breakpoint.setLine( sourceBreakpoint.getLine() );
		breakpoint.setVerified( false ); // Mark as unverified until program starts
		breakpoint.setMessage( "Breakpoint will be verified when program starts" );

		// Store the pending breakpoint for later verification
		storePendingBreakpoint( source, sourceBreakpoint, breakpoint );

		LOGGER.info( "Tracked breakpoint at line " + sourceBreakpoint.getLine() +
		    " in " + ( source != null ? source.getPath() : "unknown" ) );

		return breakpoint;
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
	 * Get stack frames for the specified thread
	 */
	public List<StackFrame> getStackFrames( int threadId ) {
		List<StackFrame> stackFrames = new ArrayList<>();

		if ( vm == null ) {
			LOGGER.warning( "Virtual machine not available for stack trace" );
			return stackFrames;
		}

		try {
			// Find the thread by ID
			ThreadReference targetThread = null;
			for ( ThreadReference thread : vm.allThreads() ) {
				if ( thread.uniqueID() == threadId ) {
					targetThread = thread;
					break;
				}
			}

			if ( targetThread == null ) {
				LOGGER.warning( "Thread not found with ID: " + threadId );
				return stackFrames;
			}

			// Check if thread is suspended
			if ( !targetThread.isSuspended() ) {
				LOGGER.warning( "Thread " + threadId + " is not suspended, cannot get stack frames" );
				return stackFrames;
			}

			// Get stack frames from the suspended thread
			List<com.sun.jdi.StackFrame> jdiFrames = targetThread.frames();

			for ( int i = 0; i < jdiFrames.size(); i++ ) {
				com.sun.jdi.StackFrame	jdiFrame	= jdiFrames.get( i );
				StackFrame				dapFrame	= new StackFrame();

				// Set frame ID (using index)
				dapFrame.setId( i );

				// Set frame name (method name)
				String frameName = jdiFrame.location().method().name();
				if ( frameName != null ) {
					dapFrame.setName( frameName );
				} else {
					dapFrame.setName( "<unknown>" );
				}

				// Set line number
				try {
					int lineNumber = jdiFrame.location().lineNumber();
					dapFrame.setLine( lineNumber );
				} catch ( Exception e ) {
					dapFrame.setLine( 0 );
				}

				// Set column (default to 0)
				dapFrame.setColumn( 0 );

				// Set source information
				try {
					Location	location	= jdiFrame.location();
					String		sourceName	= location.sourceName();
					String		sourcePath	= location.sourcePath();

					Source		source		= new Source();
					source.setName( sourceName );

					// Try to construct full path
					if ( sourcePath != null ) {
						source.setPath( sourcePath );
					}

					dapFrame.setSource( source );
				} catch ( AbsentInformationException e ) {
					// Source information not available
					LOGGER.fine( "Source information not available for frame: " + frameName );
				}

				stackFrames.add( dapFrame );
			}

			LOGGER.info( "Retrieved " + stackFrames.size() + " stack frames for thread " + threadId );

		} catch ( IncompatibleThreadStateException e ) {
			LOGGER.severe( "Thread state incompatible for stack trace: " + e.getMessage() );
		} catch ( Exception e ) {
			LOGGER.severe( "Error retrieving stack frames: " + e.getMessage() );
			e.printStackTrace();
		}

		return stackFrames;
	}

	/**
	 * Resume execution for the specified thread (called when continue is requested)
	 */
	public void continueExecution( int threadId ) {
		if ( vm == null ) {
			LOGGER.warning( "Virtual machine not available for continue" );
			return;
		}

		try {
			if ( eventSets.containsKey( ( long ) threadId ) ) {
				EventSet eventSet = eventSets.remove( ( long ) threadId );
				eventSet.resume();
				LOGGER.info( "Resumed thread " + threadId + " via stored event set" );
				return;
			}
			// Find the thread by ID
			ThreadReference targetThread = null;
			for ( ThreadReference thread : vm.allThreads() ) {
				if ( thread.uniqueID() == threadId ) {
					targetThread = thread;
					break;
				}
			}

			if ( targetThread == null ) {
				LOGGER.warning( "Thread not found with ID: " + threadId );
				return;
			}

			// Resume the thread if it's suspended
			if ( targetThread.isSuspended() ) {
				targetThread.resume();
				LOGGER.info( "Resumed thread " + threadId );
			} else {
				LOGGER.info( "Thread " + threadId + " is not suspended, no action needed" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error resuming thread " + threadId + ": " + e.getMessage() );
			e.printStackTrace();
		}
	}

	/**
	 * Resume execution for all threads (called when continue is requested without specific thread)
	 */
	public void continueAllExecution() {
		if ( vm == null ) {
			LOGGER.warning( "Virtual machine not available for continue" );
			return;
		}

		try {
			vm.resume();
			LOGGER.info( "Resumed all threads" );
		} catch ( Exception e ) {
			LOGGER.severe( "Error resuming all threads: " + e.getMessage() );
			e.printStackTrace();
		}
	}

	private void setupMethodEntryRequest() {
		this.methodEntryRequest = vm.eventRequestManager().createMethodEntryRequest();
		// this.methodEntryRequest.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.*" );
		this.methodEntryRequest.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebugAgent" );
		// this.methodEntryRequest.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebugHelper" );
		this.methodEntryRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		// this.methodEntryRequest.enable();
		LOGGER.info( "Set up method entry request for DebugAgent" );

		// create other debug thread request
		this.methodEntryRequestDebugger = vm.eventRequestManager().createMethodEntryRequest();
		this.methodEntryRequestDebugger.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebuggerHelper" );
		// // req.addClassFilter( "ortus.boxlang.moduleslug.instrumentation.DebugHelper" );
		this.methodEntryRequestDebugger.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		this.methodEntryRequestDebugger.enable();
		LOGGER.info( "Set up method entry request for DebugAgent" );
	}

	private CompletableFuture<Value> getRuntime() {

		return InvokeTools.submitAndInvokeStatic(
		    this,
		    "ortus.boxlang.runtime.BoxRuntime",
		    "getInstance",
		    new ArrayList<String>(),
		    new ArrayList<Value>()
		);
	}

	// private CompletableFuture<Value> getRuntime() {

	// if ( runtimeFuture == null ) {
	// runtimeFuture = CompletableFuture.supplyAsync( () -> {
	// try {
	// return InvokeTools.submitAndInvokeStatic(
	// this,
	// "ortus.boxlang.runtime.BoxRuntime",
	// "getInstance",
	// new ArrayList<String>(),
	// new ArrayList<Value>()
	// ).get();
	// } catch ( Exception e ) {
	// LOGGER.severe( "Error getting BoxRuntime instance: " + e.getMessage() );
	// return null;
	// }
	// } );
	// }

	// return runtimeFuture;
	// }
}
