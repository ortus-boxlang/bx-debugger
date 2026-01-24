package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import com.sun.jdi.ClassType;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;

/**
 * Interface for VM connections used by the debugger.
 */
public interface IVMConnection {

	/**
	 * The fully qualified class name of the DebuggerService in BoxLang runtime
	 */
	public static final String DEBUGGER_SERVICE_CLASS = "ortus.boxlang.runtime.services.DebuggerService";

	/**
	 * Start the DebuggerService in the target VM via JDI.
	 * This replaces the old agent loading mechanism - the DebuggerService is now
	 * built into the BoxLang runtime and just needs to be started.
	 *
	 * @param connection The VM connection
	 *
	 * @throws IOException if an I/O error occurs
	 */
	public static void startDebuggerService( IVMConnection connection ) throws IOException {
		Logger logger = Logger.getLogger( IVMConnection.class.getName() );

		try {
			VirtualMachine		vm		= connection.getVirtualMachine();

			// Find the DebuggerService class
			List<ReferenceType>	classes	= vm.classesByName( DEBUGGER_SERVICE_CLASS );
			if ( classes.isEmpty() ) {
				logger.warning( "DebuggerService class not found in target VM. "
				    + "Ensure BoxLang runtime is loaded." );
				return;
			}

			ClassType		debuggerServiceClass	= ( ClassType ) classes.get( 0 );

			// Find the start() method
			List<Method>	startMethods			= debuggerServiceClass.methodsByName( "start" );
			if ( startMethods.isEmpty() ) {
				logger.severe( "DebuggerService.start() method not found" );
				return;
			}

			Method			startMethod		= startMethods.get( 0 );

			// Find a thread to use for invocation
			// We need a suspended thread to invoke methods via JDI
			ThreadReference	invokeThread	= findSuitableThread( vm );
			if ( invokeThread == null ) {
				logger.warning( "No suitable thread found to start DebuggerService. "
				    + "The service will start automatically when the invoker thread is needed." );
				return;
			}

			// Invoke DebuggerService.start()
			debuggerServiceClass.invokeMethod(
			    invokeThread,
			    startMethod,
			    Collections.emptyList(),
			    ObjectReference.INVOKE_SINGLE_THREADED
			);

			logger.info( "DebuggerService started successfully in target VM" );

		} catch ( IncompatibleThreadStateException e ) {
			// This is expected if no threads are suspended - the service will start
			// when the method entry event triggers
			logger.fine( "Could not start DebuggerService immediately: " + e.getMessage()
			    + ". Service will start on first method entry event." );
		} catch ( Exception e ) {
			logger.warning( "Could not start DebuggerService: " + e.getMessage()
			    + ". The service may start automatically when needed." );
		}
	}

	/**
	 * Check if the DebuggerService is already started in the target VM.
	 *
	 * @param vm The target virtual machine
	 *
	 * @return true if the service is started
	 */
	public static boolean isDebuggerServiceStarted( VirtualMachine vm ) {
		try {
			List<ReferenceType> classes = vm.classesByName( DEBUGGER_SERVICE_CLASS );
			if ( classes.isEmpty() ) {
				return false;
			}

			// Check if the invoker thread exists
			for ( ThreadReference thread : vm.allThreads() ) {
				if ( thread.name().equals( "BoxLang-DebuggerInvoker" ) ) {
					return true;
				}
			}
		} catch ( Exception e ) {
			// Ignore - service not started
		}
		return false;
	}

	/**
	 * Find a suitable suspended thread for JDI method invocation.
	 *
	 * @param vm The target virtual machine
	 *
	 * @return A suspended thread reference, or null if none available
	 */
	private static ThreadReference findSuitableThread( VirtualMachine vm ) {
		for ( ThreadReference thread : vm.allThreads() ) {
			try {
				if ( thread.isSuspended() && thread.frameCount() > 0 ) {
					return thread;
				}
			} catch ( IncompatibleThreadStateException e ) {
				// Thread not in suitable state, try next
			}
		}
		return null;
	}

	/**
	 * Get the virtual machine for this connection.
	 *
	 * @return The JDI VirtualMachine
	 */
	public VirtualMachine getVirtualMachine();

	/**
	 * Get the attach virtual machine for this connection.
	 * This is used for agent loading (deprecated - no longer needed).
	 *
	 * @return The attach VirtualMachine
	 *
	 * @throws AttachNotSupportedException if attach is not supported
	 * @throws IOException                 if an I/O error occurs
	 *
	 * @deprecated The agent loading mechanism is no longer used.
	 *             Use {@link #startDebuggerService(IVMConnection)} instead.
	 */
	@Deprecated
	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException;

}
