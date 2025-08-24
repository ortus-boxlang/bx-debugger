package ortus.boxlang.moduleslug;

import java.io.IOException;
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
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;

/**
 * Manages JDI breakpoints and handles breakpoint events
 */
public class BreakpointManager {

	private static final Logger												LOGGER						= Logger.getLogger( BreakpointManager.class.getName() );

	private final VirtualMachine											vm;
	private final IDebugProtocolClient										client;
	private final List<BreakpointRequest>									activeBreakpoints			= new CopyOnWriteArrayList<>();
	private final List<PendingBreakpointInfo>								pendingBreakpoints			= new CopyOnWriteArrayList<>();
	private volatile boolean												eventProcessingActive		= false;
	private Thread															eventProcessingThread;

	// DAP-level breakpoint storage - organized by file path
	private final Map<String, List<PendingBreakpoint>>						pendingBreakpointsByFile	= new ConcurrentHashMap<>();
	private final Map<Integer, PendingBreakpoint>							pendingBreakpointsById		= new ConcurrentHashMap<>();
	private int																breakpointIdCounter			= 1;

	private final Map<Integer, BreakpointContext>							breakPointContexts			= new WeakHashMap<>();

	private MethodEntryRequest												methodEntryRequest			= null;
	private final ConcurrentLinkedQueue<CompletableFuture<ThreadReference>>	debugThreadAccessQueue		= new ConcurrentLinkedQueue<>();

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

		PendingBreakpointInfo( String filePath, int lineNumber, int breakpointId ) {
			this.filePath		= filePath;
			this.lineNumber		= lineNumber;
			this.breakpointId	= breakpointId;
		}
	}

	public BreakpointManager( VirtualMachine vm, IDebugProtocolClient client ) {
		this.vm		= vm;
		this.client	= client;

		// Only setup class prepare events if VM is available
		if ( vm != null ) {
			setupClassPrepareEvents();
			setupDebugThread();
		}
	}

	private void setupDebugThread() {
		this.methodEntryRequest = vm.eventRequestManager().createMethodEntryRequest();
		this.methodEntryRequest.addClassFilter( "ortus.boxlang.*" );
		this.methodEntryRequest.setSuspendPolicy( EventRequest.SUSPEND_EVENT_THREAD );

		String id = String.valueOf( vm.process().pid() );

		try {
			com.sun.tools.attach.VirtualMachine attachVM = com.sun.tools.attach.VirtualMachine.attach( id );
			vm.resume();
			attachVM.loadAgent( "C:\\Users\\jacob\\Dev\\ortus-boxlang\\bx-debugger\\build\\libs\\@MODULE_SLUG@-1.0.0-snapshot-agent.jar" );
			attachVM.detach();
		} catch ( AgentLoadException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch ( AgentInitializationException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch ( IOException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch ( AttachNotSupportedException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		vm.suspend();
	}

	public CompletableFuture<ThreadReference> getSuspendedDebugThread() {
		var debug = getDebugThread();
		if ( !methodEntryRequest.isEnabled() && debug.isSuspended() ) {
			while ( debug.isSuspended() ) {
				debug.resume();
			}
		}

		methodEntryRequest.enable();

		var f = new CompletableFuture<ThreadReference>();

		debugThreadAccessQueue.add( f );
		return f;
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
		classPrepareRequest.enable();
		LOGGER.info( "Set up class prepare events for all classes (to catch BoxLang generated classes)" );
	}

	/**
	 * Set a breakpoint at the specified file and line
	 */
	public boolean setBreakpoint( PendingBreakpoint pending ) {
		String	filePath	= pending.getFilePath();
		int		lineNumber	= pending.getSourceBreakpoint().getLine();
		try {
			LOGGER.info( "Attempting to set breakpoint at " + filePath + ":" + lineNumber );

			// If VM is not available, just add to pending breakpoints
			if ( vm == null ) {
				pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, pending.breakpoint.getId() ) );
				LOGGER.info( "VM not available, added breakpoint to pending list: " + filePath + ":" + lineNumber );
				return true;
			}

			// Try to set the breakpoint immediately if the class is already loaded
			if ( trySetBreakpointOnLoadedClass( pending.getBreakpoint().getId(), filePath, lineNumber ) ) {
				return true;
			}

			// If not successful, add to pending breakpoints
			pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber, pending.getBreakpoint().getId() ) );
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
	 */
	private boolean trySetBreakpointOnLoadedClass( int breakpointId, String filePath, int lineNumber ) {
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
						return createBreakpointRequest( breakpointId, location, filePath, lineNumber );
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
	 */
	private boolean createBreakpointRequest( int breakpointId, Location location, String filePath, int lineNumber ) {
		try {
			EventRequestManager	requestManager		= vm.eventRequestManager();
			BreakpointRequest	breakpointRequest	= requestManager.createBreakpointRequest( location );

			// Enable the breakpoint
			breakpointRequest.enable();
			activeBreakpoints.add( breakpointRequest );
			breakpointRequest.putProperty( "breakPointId", breakpointId );

			LOGGER.info( "Successfully set breakpoint at " + filePath + ":" + lineNumber );
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

					if ( event instanceof BreakpointEvent ) {
						handleBreakpointEvent( ( BreakpointEvent ) event );
					} else if ( event instanceof ClassPrepareEvent ) {
						handleClassPrepareEvent( ( ClassPrepareEvent ) event );
					} else if ( event instanceof MethodEntryEvent ) {
						handleMethodEntryEvent( ( MethodEntryEvent ) event );
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
					if ( evt instanceof BreakpointEvent || evt instanceof MethodEntryEvent ) {
						shouldResume = false; // Don't auto-resume on breakpoint - wait for continue request
						break;
					}
				}

				if ( shouldResume ) {
					eventSet.resume();
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

	private ThreadReference getDebugThread() {
		for ( ThreadReference ref : vm.allThreads() ) {
			if ( ref.name().equals( "bxDebugAgent" ) ) {
				return ref;
			}
		}

		return null;
	}

	private void handleMethodEntryEvent( MethodEntryEvent event ) {
		if ( !event.location().declaringType().name().equals( "ortus.boxlang.moduleslug.instrumentation.DebugAgent" ) ) {
			vm.resume();
		}

		CompletableFuture<ThreadReference> f = debugThreadAccessQueue.poll();

		if ( f == null ) {
			LOGGER.warning( "No future available for method entry event" );
			return;
		}

		methodEntryRequest.setEnabled( debugThreadAccessQueue.size() > 0 );

		f.complete( getDebugThread() );
	}

	/**
	 * Handle a breakpoint event
	 */
	private void handleBreakpointEvent( BreakpointEvent event ) {
		try {
			Location	location	= event.location();
			String		sourceName	= getSourceName( location );
			int			lineNumber	= location.lineNumber();

			LOGGER.info( "Breakpoint hit at " + sourceName + ":" + lineNumber );

			// this MUST happen before we respond to the client
			breakPointContexts.put(
			    Integer.valueOf( ( int ) event.request().getProperty( "breakPointId" ) ),
			    new BreakpointContext( ( int ) event.request().getProperty( "breakPointId" ), event.thread() )
			);

			// Send stopped event to the debug client
			if ( client != null ) {
				StoppedEventArguments stoppedArgs = new StoppedEventArguments();
				stoppedArgs.setReason( "breakpoint" );
				stoppedArgs.setDescription( "Paused on breakpoint" );
				stoppedArgs.setThreadId( ( int ) event.thread().uniqueID() );
				stoppedArgs.setHitBreakpointIds( new Integer[] { ( int ) event.request().getProperty( "breakPointId" ) } );

				client.stopped( stoppedArgs );
				LOGGER.info( "Sent stopped event to client" );
			}

		} catch ( Exception e ) {
			LOGGER.severe( "Error handling breakpoint event: " + e.getMessage() );
		}
	}

	/**
	 * Handle a class prepare event - try to set pending breakpoints
	 */
	private void handleClassPrepareEvent( ClassPrepareEvent event ) {
		ReferenceType refType = event.referenceType();
		LOGGER.info( "Class loaded: " + refType.name() );

		// Check if this is a BoxLang generated class
		if ( refType.name().toLowerCase().contains( "box" ) ||
		    refType.name().toLowerCase().contains( "generated" ) ||
		    refType.name().toLowerCase().contains( "script" ) ) {
			LOGGER.info( "Potential BoxLang class detected: " + refType.name() );
		}

		// Try to set any pending breakpoints for this class
		List<PendingBreakpointInfo> toRemove = new ArrayList<>();

		for ( PendingBreakpointInfo pending : pendingBreakpoints ) {
			if ( trySetBreakpointOnLoadedClass( pending.breakpointId, pending.filePath, pending.lineNumber ) ) {
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
}
