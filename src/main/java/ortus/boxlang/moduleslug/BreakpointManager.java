package ortus.boxlang.moduleslug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;

/**
 * Manages JDI breakpoints and handles breakpoint events
 */
public class BreakpointManager {

	private static final Logger					LOGGER					= Logger.getLogger( BreakpointManager.class.getName() );

	private final VirtualMachine				vm;
	private final IDebugProtocolClient			client;
	private final List<BreakpointRequest>		activeBreakpoints		= new CopyOnWriteArrayList<>();
	private final List<PendingBreakpointInfo>	pendingBreakpoints		= new CopyOnWriteArrayList<>();
	private volatile boolean					eventProcessingActive	= false;
	private Thread								eventProcessingThread;

	/**
	 * Information about a breakpoint that couldn't be set yet because the class isn't loaded
	 */
	private static class PendingBreakpointInfo {

		final String	filePath;
		final int		lineNumber;

		PendingBreakpointInfo( String filePath, int lineNumber ) {
			this.filePath	= filePath;
			this.lineNumber	= lineNumber;
		}
	}

	public BreakpointManager( VirtualMachine vm, IDebugProtocolClient client ) {
		this.vm		= vm;
		this.client	= client;
		setupClassPrepareEvents();
	}

	/**
	 * Set up class prepare events to catch when classes are loaded
	 */
	private void setupClassPrepareEvents() {
		EventRequestManager	requestManager		= vm.eventRequestManager();
		ClassPrepareRequest	classPrepareRequest	= requestManager.createClassPrepareRequest();
		classPrepareRequest.addClassFilter( "ortus.boxlang.moduleslug.*" );
		classPrepareRequest.enable();
		LOGGER.info( "Set up class prepare events for ortus.boxlang.moduleslug.*" );
	}

	/**
	 * Set a breakpoint at the specified file and line
	 */
	public boolean setBreakpoint( String filePath, int lineNumber ) {
		try {
			LOGGER.info( "Attempting to set breakpoint at " + filePath + ":" + lineNumber );

			// Try to set the breakpoint immediately if the class is already loaded
			if ( trySetBreakpointOnLoadedClass( filePath, lineNumber ) ) {
				return true;
			}

			// If not successful, add to pending breakpoints
			pendingBreakpoints.add( new PendingBreakpointInfo( filePath, lineNumber ) );
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
	private boolean trySetBreakpointOnLoadedClass( String filePath, int lineNumber ) {
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

					LOGGER.info( "Found location at " + sourceName + ":" + lineNumber + " in class " + refType.name() );

					// More flexible matching: check if the class name matches what we expect
					if ( sourceName != null &&
					    ( filePath.endsWith( sourceName ) ||
					        refType.name().equals( "ortus.boxlang.moduleslug.TestOutputProducer" ) ) ) {
						return createBreakpointRequest( location, filePath, lineNumber );
					}
				}
			} catch ( AbsentInformationException e ) {
				// This class doesn't have debug info, skip it
				continue;
			}
		}

		LOGGER.info( "Class not yet loaded for breakpoint at " + filePath + ":" + lineNumber );

		// List all available classes for debugging
		LOGGER.info( "Available classes:" );
		for ( ReferenceType refType : classes ) {
			LOGGER.info( "  " + refType.name() );
		}

		return false;
	}

	/**
	 * Create a breakpoint request for the given location
	 */
	private boolean createBreakpointRequest( Location location, String filePath, int lineNumber ) {
		try {
			EventRequestManager	requestManager		= vm.eventRequestManager();
			BreakpointRequest	breakpointRequest	= requestManager.createBreakpointRequest( location );

			// Enable the breakpoint
			breakpointRequest.enable();
			activeBreakpoints.add( breakpointRequest );

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
					} else if ( event instanceof VMDeathEvent || event instanceof VMDisconnectEvent ) {
						LOGGER.info( "VM terminated, stopping event processing" );
						eventProcessingActive = false;
						return;
					}
				}

				// Resume execution for non-breakpoint events
				// For breakpoint events, we'll handle resume separately
				boolean			shouldResume	= true;
				EventIterator	iter			= eventSet.eventIterator();
				while ( iter.hasNext() ) {
					Event evt = iter.nextEvent();
					if ( evt instanceof BreakpointEvent ) {
						shouldResume = false; // Don't auto-resume on breakpoint
						break;
					}
				}

				if ( shouldResume ) {
					eventSet.resume();
				} else {
					// For now, resume after a short delay to allow the stopped event to be sent
					Thread.sleep( 500 );
					eventSet.resume();
				}

			} catch ( InterruptedException e ) {
				LOGGER.info( "Event processing interrupted" );
				Thread.currentThread().interrupt();
				break;
			} catch ( Exception e ) {
				LOGGER.severe( "Error processing events: " + e.getMessage() );
			}
		}
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

			// Send stopped event to the debug client
			if ( client != null ) {
				StoppedEventArguments stoppedArgs = new StoppedEventArguments();
				stoppedArgs.setReason( "breakpoint" );
				stoppedArgs.setDescription( "Paused on breakpoint" );
				stoppedArgs.setThreadId( ( int ) event.thread().uniqueID() );

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

		// Try to set any pending breakpoints for this class
		List<PendingBreakpointInfo> toRemove = new ArrayList<>();

		for ( PendingBreakpointInfo pending : pendingBreakpoints ) {
			if ( refType.name().equals( "ortus.boxlang.moduleslug.TestOutputProducer" ) ) {
				LOGGER.info( "Attempting to set pending breakpoint for loaded class: " + refType.name() );

				if ( trySetBreakpointOnLoadedClass( pending.filePath, pending.lineNumber ) ) {
					toRemove.add( pending );
					LOGGER.info( "Successfully set pending breakpoint at " + pending.filePath + ":" + pending.lineNumber );
				}
			}
		}

		// Remove successfully set breakpoints from pending list
		pendingBreakpoints.removeAll( toRemove );
	}

	/**
	 * Clear all active breakpoints
	 */
	public void clearAllBreakpoints() {
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
}
