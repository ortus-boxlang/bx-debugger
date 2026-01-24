package ortus.boxlang.bxdebugger;

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
import com.sun.jdi.event.VMStartEvent;
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
	private volatile boolean												caughtExceptionsEnabled			= false;
	private volatile boolean												uncaughtExceptionsEnabled		= false;
	private ExceptionRequest												exceptionRequest				= null;
	private final Map<Long, ExceptionInfo>									exceptionInfoByThread			= new ConcurrentHashMap<>();

	// DebuggerService support
	private static final String												DEBUGGER_SERVICE_CLASS			= "ortus.boxlang.runtime.services.DebuggerService";
	private volatile boolean												debuggerServiceStarted			= false;
	private volatile ClassType												debuggerServiceClass			= null;

	// Conditional breakpoint support
	private static final int												CONDITION_EVAL_TIMEOUT_SECONDS	= 30;
	private final Map<Integer, Integer>										breakpointHitCounts				= new ConcurrentHashMap<>();

	private CompletableFuture<Value>										runtimeFuture					= null;

	// Path mapping service for remote debugging support
	private PathMappingService												pathMappingService				= null;

	// Verified breakpoints storage - keeps track of breakpoints that have been successfully set
	// This allows re-applying breakpoints when BoxLang recompiles a class
	private final Map<Integer, VerifiedBreakpointInfo>						verifiedBreakpoints				= new ConcurrentHashMap<>();

	// Flag to track whether configurationDone has been called
	// VM should not resume until this is true
	private volatile boolean												configurationDone				= false;
	private volatile boolean												vmStartEventReceived			= false;
	// Store the VMStartEvent's eventSet so we can resume it when configurationDone is called
	private volatile EventSet												vmStartEventSet					= null;

	/**
	 * Information about a verified breakpoint that was successfully set.
	 * This information is kept to allow re-applying breakpoints when classes are reloaded.
	 */
	public static class VerifiedBreakpointInfo {

		private final int		breakpointId;
		private final String	filePath;
		private final int		lineNumber;
		private final String	condition;
		private final String	hitCondition;
		private final String	logMessage;

		public VerifiedBreakpointInfo( int breakpointId, String filePath, int lineNumber,
		    String condition, String hitCondition, String logMessage ) {
			this.breakpointId	= breakpointId;
			this.filePath		= filePath;
			this.lineNumber		= lineNumber;
			this.condition		= condition;
			this.hitCondition	= hitCondition;
			this.logMessage		= logMessage;
		}

		public int getBreakpointId() {
			return breakpointId;
		}

		public String getFilePath() {
			return filePath;
		}

		public int getLineNumber() {
			return lineNumber;
		}

		public String getCondition() {
			return condition;
		}

		public String getHitCondition() {
			return hitCondition;
		}

		public String getLogMessage() {
			return logMessage;
		}

		@Override
		public String toString() {
			return String.format( "VerifiedBreakpointInfo[id=%d, file=%s, line=%d]",
			    breakpointId, filePath, lineNumber );
		}
	}

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

	public VMController( VMController old, VirtualMachine vm, IDebugProtocolClient client ) {
		this.vm		= vm;
		this.client	= client;

		// Migrate existing breakpoints
		this.activeBreakpoints.addAll( old.activeBreakpoints );

		// Migrate pending breakpoints
		if ( old.pendingBreakpointsByFile != null ) {
			old.pendingBreakpointsByFile.forEach( ( path, list ) -> {
				list.forEach( p -> storePendingBreakpoint( p.getSource(), p.getSourceBreakpoint(), p.getBreakpoint() ) );
			} );
		}

		this.setExceptionBreakpoints( old.caughtExceptionsEnabled, old.uncaughtExceptionsEnabled );

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
		this.methodEntryRequestDebugger.addClassFilter( "ortus.boxlang.runtime.services.DebuggerService" );
		this.methodEntryRequestDebugger.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		this.methodEntryRequestDebugger.enable();
		LOGGER.info( "Set up method entry request for DebuggerService" );
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
	 * Ensure the DebuggerService threads are ready for condition evaluation.
	 * The worker thread needs to be running to process queued tasks.
	 */
	private void ensureDebugHelperThreadsReady() {
		try {
			// Resume the worker thread if suspended - it processes the queue
			vm.allThreads().stream()
			    .filter( t -> t.name().equalsIgnoreCase( "BoxLang-DebuggerWorker" ) )
			    .findFirst()
			    .ifPresent( workerThread -> {
				    if ( workerThread.isSuspended() ) {
					    workerThread.resume();
					    LOGGER.fine( "Resumed BoxLang-DebuggerWorker thread for condition evaluation" );
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

		LOGGER.info( "Setting up ClassPrepareRequest before event processing..." );
		EventRequestManager	requestManager		= vm.eventRequestManager();

		ClassPrepareRequest	classPrepareRequest	= requestManager.createClassPrepareRequest();
		// Filter for BoxLang generated classes only for efficiency
		classPrepareRequest.addClassFilter( "boxgenerated.*" );
		classPrepareRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		classPrepareRequest.enable();
		LOGGER.info( "Set up class prepare events for boxgenerated.* classes" );

		// Also listen for BoxRuntimeException class loading (for deferred exception breakpoints)
		ClassPrepareRequest exceptionClassPrepareRequest = requestManager.createClassPrepareRequest();
		exceptionClassPrepareRequest.addClassFilter( BOX_RUNTIME_EXCEPTION_CLASS );
		exceptionClassPrepareRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		exceptionClassPrepareRequest.enable();

		// Listen for DebuggerService class loading to start it
		ClassPrepareRequest debuggerServicePrepareRequest = requestManager.createClassPrepareRequest();
		debuggerServicePrepareRequest.addClassFilter( DEBUGGER_SERVICE_CLASS );
		debuggerServicePrepareRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		debuggerServicePrepareRequest.enable();
	}

	/**
	 * Start the DebuggerService in the target VM.
	 * This is called when the DebuggerService class is loaded (via ClassPrepareEvent).
	 * The service creates the invoker and worker threads needed for JDI method invocations.
	 *
	 * @param thread               The suspended thread from the ClassPrepareEvent
	 * @param debuggerServiceClass The DebuggerService class type
	 */
	/**
	 * Start the DebuggerService in the target VM.
	 * This is called when we need to invoke methods via JDI and the service hasn't been started yet.
	 * Must be called from a properly suspended thread context.
	 *
	 * @param thread               The suspended thread from the ClassPrepareEvent
	 * @param debuggerServiceClass The DebuggerService class type
	 */
	private void startDebuggerService( ThreadReference thread, ClassType debuggerServiceClass ) {
		if ( debuggerServiceStarted ) {
			LOGGER.info( "DebuggerService already started" );
			return;
		}

		try {
			// Find the start() method
			List<com.sun.jdi.Method> startMethods = debuggerServiceClass.methodsByName( "start" );
			if ( startMethods.isEmpty() ) {
				LOGGER.severe( "DebuggerService.start() method not found" );
				return;
			}

			com.sun.jdi.Method startMethod = startMethods.get( 0 );

			// Invoke DebuggerService.start() on the suspended thread
			debuggerServiceClass.invokeMethod(
			    thread,
			    startMethod,
			    java.util.Collections.emptyList(),
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			debuggerServiceStarted = true;
			LOGGER.info( "DebuggerService started successfully in target VM" );

		} catch ( IncompatibleThreadStateException e ) {
			LOGGER.warning( "Could not start DebuggerService - thread not in compatible state: " + e.getMessage() );
		} catch ( Exception e ) {
			LOGGER.warning( "Could not start DebuggerService: " + e.getMessage() );
		}
	}

	/**
	 * Lazily start the DebuggerService when needed.
	 * This method is called from InvokeTools when we need to invoke methods.
	 * It uses the provided suspended thread to start the service.
	 *
	 * @param thread A properly suspended thread reference
	 * 
	 * @return true if the service is started or was already started
	 */
	public boolean ensureDebuggerServiceStarted( ThreadReference thread ) {
		if ( debuggerServiceStarted ) {
			return true;
		}

		if ( debuggerServiceClass == null ) {
			// Try to find the class if it's been loaded
			List<ReferenceType> classes = vm.classesByName( DEBUGGER_SERVICE_CLASS );
			if ( !classes.isEmpty() && classes.get( 0 ) instanceof ClassType ct ) {
				debuggerServiceClass = ct;
			} else {
				LOGGER.warning( "DebuggerService class not loaded yet" );
				return false;
			}
		}

		startDebuggerService( thread, debuggerServiceClass );
		return debuggerServiceStarted;
	}

	/**
	 * Get the DebuggerService class type if it has been loaded.
	 *
	 * @return The ClassType for DebuggerService, or null if not yet loaded
	 */
	public ClassType getDebuggerServiceClass() {
		if ( debuggerServiceClass != null ) {
			return debuggerServiceClass;
		}

		// Try to find the class if it's been loaded
		List<ReferenceType> classes = vm.classesByName( DEBUGGER_SERVICE_CLASS );
		if ( !classes.isEmpty() && classes.get( 0 ) instanceof ClassType ct ) {
			debuggerServiceClass = ct;
			return ct;
		}

		return null;
	}

	/**
	 * Check if the DebuggerService has been started in the target VM.
	 *
	 * @return true if the service has been started
	 */
	public boolean isDebuggerServiceStarted() {
		return debuggerServiceStarted;
	}

	/**
	 * Configure exception breakpoints for BoxLang exceptions
	 *
	 * @param caught   true to break on caught exceptions
	 * @param uncaught true to break on uncaught exceptions
	 */
	public void setExceptionBreakpoints( boolean caught, boolean uncaught ) {
		this.caughtExceptionsEnabled	= caught;
		this.uncaughtExceptionsEnabled	= uncaught;

		if ( vm == null ) {
			LOGGER.info( "VM not available, exception breakpoints will be set when VM is ready. Caught=" + caught + ", Uncaught=" + uncaught );
			return;
		}

		if ( caught || uncaught ) {
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

			// Create exception request based on caught/uncaught settings
			exceptionRequest = requestManager.createExceptionRequest( exceptionClass, caughtExceptionsEnabled, uncaughtExceptionsEnabled );
			exceptionRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
			exceptionRequest.enable();

			LOGGER.info( "Created exception request for " + BOX_RUNTIME_EXCEPTION_CLASS + " (caught=" + caughtExceptionsEnabled + ", uncaught="
			    + uncaughtExceptionsEnabled + ")" );
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
		int					breakpointId	= pending.getBreakpoint().getId();

		try {
			LOGGER.info( "Attempting to set breakpoint at " + filePath + ":" + lineNumber );

			// Always store in verifiedBreakpoints so it can be re-applied when classes are loaded/reloaded
			// This ensures breakpoints added after a file save will be applied when the class is next compiled
			verifiedBreakpoints.put( breakpointId, new VerifiedBreakpointInfo(
			    breakpointId, filePath, lineNumber, condition, hitCondition, logMessage
			) );

			// If VM is not available, just add to pending breakpoints
			if ( vm == null ) {
				pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, breakpointId, condition, hitCondition, logMessage ) );
				LOGGER.info( "VM not available, added breakpoint to pending list: " + filePath + ":" + lineNumber );
				return false; // Return false - breakpoint is not yet verified (VM not running)
			}

			// Try to set the breakpoint immediately if the class is already loaded
			if ( trySetBreakpointOnLoadedClass( breakpointId, filePath, lineNumber, condition, hitCondition, logMessage ) ) {
				return true;
			}

			// If not successful, add to pending breakpoints for ClassPrepareEvent handling
			pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, breakpointId, condition, hitCondition, logMessage ) );
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
			LOGGER.fine( "VM is null, cannot search for loaded classes" );
			return false;
		}

		LOGGER.fine( "Searching for loaded class matching " + filePath + ":" + lineNumber );

		// Get all loaded classes that might contain this file
		List<ReferenceType> classes;
		try {
			classes = vm.allClasses();
			LOGGER.fine( "Found " + classes.size() + " loaded classes to search" );
		} catch ( Exception e ) {
			LOGGER.warning( "Error getting loaded classes: " + e.getMessage() );
			return false;
		}

		// Track best matching class - prefer BoxLang generated classes
		ReferenceType	bestMatch			= null;
		Location		bestMatchLocation	= null;

		for ( ReferenceType refType : classes ) {
			try {
				// Skip non-BoxLang classes for performance
				String className = refType.name();
				if ( !className.startsWith( "boxgenerated" ) ) {
					continue;
				}

				// Try to find the location for this line in this class
				List<Location> locations = refType.locationsOfLine( lineNumber );

				if ( !locations.isEmpty() ) {
					// Check if this location corresponds to our file
					Location	location	= locations.get( 0 );
					String		sourceName	= getSourceName( location );
					String		sourcePath	= getSourcePath( location );

					LOGGER.fine( "Found location at " + sourceName + " (path: " + sourcePath + ") :" + lineNumber + " in class " + className );

					// Use intelligent path matching that handles:
					// 1. Full path match (sourcePath == filePath)
					// 2. Filename-only match (sourceName == filename from filePath)
					// 3. Suffix match (filePath ends with sourcePath or vice versa)
					if ( pathsMatchForBreakpoint( sourceName, sourcePath, filePath ) ) {
						bestMatch			= refType;
						bestMatchLocation	= location;
						LOGGER.fine( "Found matching boxgenerated class: " + className );
						// Don't break - keep looking for potentially newer versions
					}
				}
			} catch ( AbsentInformationException e ) {
				// This class doesn't have debug info, skip it
				continue;
			} catch ( Exception e ) {
				LOGGER.fine( "Error checking class " + refType.name() + ": " + e.getMessage() );
				continue;
			}
		}

		if ( bestMatch != null && bestMatchLocation != null ) {
			LOGGER.info( "Setting breakpoint on class: " + bestMatch.name() + " at line " + lineNumber );
			return createBreakpointRequest( breakpointId, bestMatchLocation, filePath, lineNumber, condition, hitCondition, logMessage );
		}

		LOGGER.info( "Class not yet loaded for breakpoint at " + filePath + ":" + lineNumber );
		return false;

	}

	/**
	 * Set the path mapping service for remote debugging support.
	 * This enables proper path translation between local and remote paths.
	 *
	 * @param pathMappingService The path mapping service to use
	 */
	public void setPathMappingService( PathMappingService pathMappingService ) {
		this.pathMappingService = pathMappingService;
	}

	/**
	 * Check if the source from JDI matches the file path from the breakpoint request.
	 * This handles various path formats and remote/local path differences.
	 *
	 * This method also supports path mapping for remote debugging scenarios
	 * when a PathMappingService has been configured.
	 *
	 * @param sourceName JDI source name (usually just filename like "User.bx")
	 * @param sourcePath JDI source path (may be relative or absolute)
	 * @param filePath   the file path from the breakpoint request
	 *
	 * @return true if the paths refer to the same file
	 */
	private boolean pathsMatchForBreakpoint( String sourceName, String sourcePath, String filePath ) {
		if ( filePath == null ) {
			return false;
		}

		// If we have a path mapping service, use its comprehensive matching
		if ( pathMappingService != null && sourcePath != null ) {
			if ( pathMappingService.pathsMatch( sourcePath, filePath ) ) {
				return true;
			}
		}

		String	normalizedFilePath	= PathMappingService.normalizePath( filePath );
		String	fileName			= PathMappingService.getFileName( filePath );

		// Try full path match first
		if ( sourcePath != null ) {
			String normalizedSourcePath = PathMappingService.normalizePath( sourcePath );
			if ( normalizedSourcePath.equalsIgnoreCase( normalizedFilePath ) ) {
				return true;
			}
			// Check suffix match - the full filePath might end with sourcePath
			if ( normalizedFilePath.toLowerCase().endsWith( normalizedSourcePath.toLowerCase() ) ) {
				return true;
			}
			// Or sourcePath might end with the relative part of filePath
			if ( normalizedSourcePath.toLowerCase().endsWith( fileName.toLowerCase() ) &&
			    normalizedFilePath.toLowerCase().endsWith( fileName.toLowerCase() ) ) {
				// Both paths have the same filename - likely the same file
				return true;
			}
		}

		// Try filename match - compare JDI sourceName with just the filename from filePath
		if ( sourceName != null && fileName != null ) {
			if ( sourceName.equalsIgnoreCase( fileName ) ) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Get source path from a JDI location.
	 *
	 * @param location the JDI location
	 *
	 * @return the source path, or null if not available
	 */
	private String getSourcePath( Location location ) {
		try {
			return location.sourcePath();
		} catch ( AbsentInformationException e ) {
			return null;
		}
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

			// Store verified breakpoint info for class reload handling
			// This allows re-applying the breakpoint when BoxLang recompiles the class
			verifiedBreakpoints.put( breakpointId, new VerifiedBreakpointInfo(
			    breakpointId, filePath, lineNumber, condition, hitCondition, logMessage
			) );

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
	 * Signal that configurationDone has been received from the client.
	 * This allows the VM to be resumed if it was waiting for configuration.
	 */
	public void signalConfigurationDone() {
		LOGGER.info( "Configuration done signaled" );
		configurationDone = true;

		// If VMStartEvent was already received and we were waiting for configuration,
		// now we can resume the VM
		if ( vmStartEventReceived && vm != null ) {
			LOGGER.info( "Resuming VM after configurationDone" );
			try {
				// First resume via the EventSet if we have one stored
				if ( vmStartEventSet != null ) {
					vmStartEventSet.resume();
					vmStartEventSet = null;
				}
				// Also call vm.resume() to ensure the VM is fully resumed
				vm.resume();
			} catch ( Exception e ) {
				LOGGER.severe( "Failed to resume VM after configurationDone: " + e.getMessage() );
			}
		}
	}

	/**
	 * Process JDI events in a loop
	 */
	private void processEvents() {
		EventQueue eventQueue = vm.eventQueue();

		LOGGER.info( "Event processing loop started" );

		while ( eventProcessingActive ) {
			try {
				// Wait for events (this blocks until an event occurs)
				EventSet eventSet = eventQueue.remove( 1000 ); // 1 second timeout

				if ( eventSet == null ) {
					// Check if process is still alive
					try {
						if ( vm != null && vm.process() != null && !vm.process().isAlive() ) {
							LOGGER.warning( "VM process has terminated! Exit value: " + vm.process().exitValue() );
							eventProcessingActive = false;
							return;
						}
					} catch ( Exception pe ) {
						LOGGER.warning( "Error checking process status: " + pe.getMessage() );
					}
					continue; // Timeout, check if we should continue
				}

				EventIterator eventIterator = eventSet.eventIterator();

				while ( eventIterator.hasNext() ) {
					Event event = eventIterator.nextEvent();

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
					} else if ( event instanceof VMStartEvent ) {
						vmStartEventReceived	= true;
						vmStartEventSet			= eventSet;  // Store the eventSet for later resume
						// Only resume VM if configurationDone has been received
						// This follows the proper DAP flow where the client sets breakpoints first
						if ( configurationDone ) {
							LOGGER.info( "VM started and configuration already done, resuming VM" );
							try {
								vmStartEventSet.resume();  // Resume the eventSet, not just the VM
								vmStartEventSet = null;
							} catch ( Exception e ) {
								LOGGER.severe( "Failed to resume VM after VMStartEvent: " + e.getMessage() );
							}
						} else {
							LOGGER.info( "VM started, waiting for configurationDone before resuming" );
						}
					} else if ( event instanceof VMDeathEvent || event instanceof VMDisconnectEvent ) {
						LOGGER.info( "VM terminated, stopping event processing" );
						eventProcessingActive = false;
						return;
					}
				}

				// Only resume for non-breakpoint events
				// For breakpoint events, the thread should remain suspended until continue is called
				boolean			shouldResume	= true;
				boolean			isVMStartEvent	= false;
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
					} else if ( evt instanceof VMStartEvent ) {
						// VMStartEvent is handled by calling vm.resume() above, no need for eventSet.resume()
						isVMStartEvent = true;
					}
				}

				if ( shouldResume && !isVMStartEvent ) {
					eventSet.resume();
				} else if ( !isVMStartEvent ) {
					LOGGER.fine( "Not resuming event set - waiting for continue request" );
				}

				// Resume helper thread if present and suspended
				vm.allThreads().stream()
				    .filter( t -> t.name().equalsIgnoreCase( "BoxLang-DebuggerWorker" ) )
				    .findFirst()
				    .ifPresent( helperThread -> {
					    if ( helperThread.isSuspended() ) {
						    helperThread.resume();
					    }
				    } );
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
			if ( ref.name().equals( "BoxLang-DebuggerInvoker" ) ) {
				return ref;
			}
		}

		return null;
	}

	private void handleMethodEntryEvent( MethodEntryEvent event ) {
		var	className	= event.location().declaringType().name();
		var	methodName	= event.location().method().name();

		LOGGER.info( "Handling MethodEntryEvent for " + className + "." + methodName );

		if ( className.equalsIgnoreCase( "ortus.boxlang.runtime.services.DebuggerService" ) ) {
			if ( !methodName.equals( "debuggerHook" ) ) {
				event.thread().resume();
				return;
			}

			LOGGER.fine( "DebuggerService.debuggerHook invoked" );

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

		// Resume threads that are not from the DebuggerService
		if ( !className.equals( "ortus.boxlang.runtime.services.DebuggerService" ) ) {
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

		// Check if this is the DebuggerService class - mark it as available
		// Note: We don't call startDebuggerService() here because invokeMethod()
		// blocks and would hang the event processing loop. Instead, we defer the
		// start to when we actually need to invoke methods via InvokeTools.
		if ( refType.name().equals( DEBUGGER_SERVICE_CLASS ) && !debuggerServiceStarted ) {
			LOGGER.info( "DebuggerService class loaded - will start service on first method invocation" );
			// Store the class type for later use
			this.debuggerServiceClass = ( ClassType ) refType;
		}

		// Check if this is the BoxRuntimeException class and we have exception breakpoints enabled
		if ( refType.name().equals( BOX_RUNTIME_EXCEPTION_CLASS ) && ( caughtExceptionsEnabled || uncaughtExceptionsEnabled ) && exceptionRequest == null ) {
			LOGGER.info( "BoxRuntimeException class loaded, setting up exception request" );
			createExceptionRequest( refType );
		}

		// Log boxgenerated classes specifically - these are what we care about for breakpoints
		if ( refType.name().startsWith( "boxgenerated." ) ) {
			LOGGER.info( "BoxLang generated class loaded: " + refType.name() );
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

		// Re-apply any verified breakpoints that might match this newly loaded class
		// This handles the case where BoxLang recompiles source code and loads a new class version
		reapplyVerifiedBreakpointsForClass( refType );
	}

	/**
	 * Re-apply verified breakpoints to a newly loaded class.
	 * This is called when a ClassPrepareEvent is received, which may indicate
	 * that BoxLang has recompiled a source file and loaded a new class version.
	 *
	 * @param refType The newly loaded class reference type
	 */
	private void reapplyVerifiedBreakpointsForClass( ReferenceType refType ) {
		if ( verifiedBreakpoints.isEmpty() ) {
			return;
		}

		for ( VerifiedBreakpointInfo verified : verifiedBreakpoints.values() ) {
			try {
				// Try to find matching locations in the newly loaded class
				List<Location> locations = refType.locationsOfLine( verified.getLineNumber() );

				LOGGER.fine( "Checking breakpoint " + verified.getBreakpointId() + " at line " + verified.getLineNumber() +
				    " - found " + locations.size() + " locations in class " + refType.name() );

				if ( !locations.isEmpty() ) {
					Location	location	= locations.get( 0 );
					String		sourceName	= getSourceName( location );
					String		sourcePath	= getSourcePath( location );

					LOGGER.fine( "Location source: name=" + sourceName + ", path=" + sourcePath +
					    ", breakpoint file=" + verified.getFilePath() );

					// Check if this class matches the breakpoint's file
					if ( pathsMatchForBreakpoint( sourceName, sourcePath, verified.getFilePath() ) ) {
						LOGGER.info( "Path match found for breakpoint " + verified.getBreakpointId() +
						    " in newly loaded class " + refType.name() );

						// Remove any stale breakpoint requests for this breakpoint ID
						// The old class version's breakpoint is no longer valid
						removeStaleBreakpointRequests( verified.getBreakpointId() );

						LOGGER.info( "Re-applying breakpoint " + verified.getBreakpointId() +
						    " to reloaded class at " + verified.getFilePath() + ":" + verified.getLineNumber() );

						createBreakpointRequest(
						    verified.getBreakpointId(),
						    location,
						    verified.getFilePath(),
						    verified.getLineNumber(),
						    verified.getCondition(),
						    verified.getHitCondition(),
						    verified.getLogMessage()
						);
					}
				}
			} catch ( AbsentInformationException e ) {
				// This class doesn't have debug info for this line, skip it
				LOGGER.fine( "No debug info for line " + verified.getLineNumber() + " in class " + refType.name() );
			}
		}
	}

	/**
	 * Remove stale breakpoint requests for a given breakpoint ID.
	 * This is called when a class is reloaded and we need to replace the old breakpoint
	 * with a new one on the new class version.
	 *
	 * @param breakpointId The breakpoint ID to remove stale requests for
	 */
	private void removeStaleBreakpointRequests( int breakpointId ) {
		List<BreakpointRequest> toRemove = new ArrayList<>();

		for ( BreakpointRequest request : activeBreakpoints ) {
			Integer reqBreakpointId = ( Integer ) request.getProperty( "breakPointId" );
			if ( reqBreakpointId != null && reqBreakpointId == breakpointId ) {
				toRemove.add( request );
			}
		}

		if ( !toRemove.isEmpty() ) {
			LOGGER.info( "Removing " + toRemove.size() + " stale breakpoint request(s) for breakpoint " + breakpointId );
			EventRequestManager requestManager = vm.eventRequestManager();
			for ( BreakpointRequest request : toRemove ) {
				try {
					request.disable();
					requestManager.deleteEventRequest( request );
				} catch ( Exception e ) {
					LOGGER.warning( "Error removing stale breakpoint request: " + e.getMessage() );
				}
				activeBreakpoints.remove( request );
			}
		}
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
		verifiedBreakpoints.clear();
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

		// Also clear verified breakpoints for this file since they'll be replaced
		clearVerifiedBreakpointsForFile( filePath );

		// Clear active JDI breakpoint requests for this file
		clearActiveBreakpointsForFile( filePath );
	}

	/**
	 * Clear active JDI breakpoint requests for a specific file.
	 * This removes the actual breakpoint requests from the VM.
	 *
	 * @param filePath The file path to clear breakpoints for
	 */
	private void clearActiveBreakpointsForFile( String filePath ) {
		if ( vm == null ) {
			LOGGER.fine( "VM not available, skipping active breakpoint cleanup for file: " + filePath );
			return;
		}

		String					normalizedPath	= normalizeFilePath( filePath );
		List<BreakpointRequest>	toRemove		= new ArrayList<>();
		EventRequestManager		requestManager	= vm.eventRequestManager();

		for ( BreakpointRequest request : activeBreakpoints ) {
			try {
				Location	location	= request.location();
				String		sourcePath	= getSourcePath( location );
				String		sourceName	= getSourceName( location );

				// Check if this breakpoint belongs to the file being cleared
				if ( pathsMatchForBreakpoint( sourceName, sourcePath, filePath ) ) {
					toRemove.add( request );
				}
			} catch ( Exception e ) {
				LOGGER.warning( "Error checking breakpoint location: " + e.getMessage() );
			}
		}

		if ( !toRemove.isEmpty() ) {
			LOGGER.info( "Clearing " + toRemove.size() + " active JDI breakpoint(s) for file: " + normalizedPath );
			for ( BreakpointRequest request : toRemove ) {
				try {
					request.disable();
					requestManager.deleteEventRequest( request );
				} catch ( Exception e ) {
					LOGGER.warning( "Error removing breakpoint request: " + e.getMessage() );
				}
				activeBreakpoints.remove( request );
			}
		}
	}

	/**
	 * Clear verified breakpoints for a specific file.
	 * This is called when new breakpoints are being set for a file, which replaces all previous breakpoints.
	 *
	 * @param filePath The file path to clear breakpoints for
	 */
	private void clearVerifiedBreakpointsForFile( String filePath ) {
		String			normalizedPath	= normalizeFilePath( filePath );
		List<Integer>	toRemove		= new ArrayList<>();

		for ( Map.Entry<Integer, VerifiedBreakpointInfo> entry : verifiedBreakpoints.entrySet() ) {
			String verifiedPath = normalizeFilePath( entry.getValue().getFilePath() );
			if ( verifiedPath.equalsIgnoreCase( normalizedPath ) ) {
				toRemove.add( entry.getKey() );
			}
		}

		for ( Integer id : toRemove ) {
			verifiedBreakpoints.remove( id );
		}

		if ( !toRemove.isEmpty() ) {
			LOGGER.info( "Cleared " + toRemove.size() + " verified breakpoints for file: " + normalizedPath );
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
		this.methodEntryRequest.addClassFilter( "ortus.boxlang.runtime.services.DebuggerService" );
		this.methodEntryRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		// this.methodEntryRequest.enable();
		LOGGER.info( "Set up method entry request for DebuggerService" );

		// create other debug thread request
		this.methodEntryRequestDebugger = vm.eventRequestManager().createMethodEntryRequest();
		this.methodEntryRequestDebugger.addClassFilter( "ortus.boxlang.runtime.services.DebuggerService" );
		this.methodEntryRequestDebugger.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );
		this.methodEntryRequestDebugger.enable();
		LOGGER.info( "Set up method entry request for DebuggerService invoker thread" );
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
