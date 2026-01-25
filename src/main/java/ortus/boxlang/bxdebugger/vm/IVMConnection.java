package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.util.List;

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
	 * Check if the DebuggerService is already started in the target VM.
	 * BoxLang now starts the DebuggerService automatically when debugMode=true,
	 * so this method just detects if it's running by looking for the invoker thread.
	 *
	 * @param vm The target virtual machine
	 *
	 * @return true if the service is started (invoker thread exists)
	 */
	public static boolean isDebuggerServiceStarted( VirtualMachine vm ) {
		try {
			List<ReferenceType> classes = vm.classesByName( DEBUGGER_SERVICE_CLASS );
			if ( classes.isEmpty() ) {
				return false;
			}

			// Check if the invoker thread exists - its presence indicates the service is running
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
	 *             BoxLang now starts the DebuggerService automatically when debugMode=true.
	 */
	@Deprecated
	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException;

}
