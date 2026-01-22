package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.tools.attach.AttachNotSupportedException;

public class LaunchedConnection implements IVMConnection {

	private static final Logger					LOGGER	= Logger.getLogger( LaunchedConnection.class.getName() );

	private VirtualMachine						vm;
	private com.sun.tools.attach.VirtualMachine	attachVm;

	public LaunchedConnection( String program ) throws Exception {
		this.vm = startupVM( program );
		// Note: Do NOT call loadDebugAgent here - it resumes the VM
		// The caller should set up class prepare events first, then call loadDebugAgent
	}

	@Override
	public VirtualMachine getVirtualMachine() {
		return vm;
	}

	@Override
	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException {

		if ( attachVm == null ) {
			attachVm = com.sun.tools.attach.VirtualMachine.attach( String.valueOf( vm.process().pid() ) );
		}

		return attachVm;
	}

	private VirtualMachine startupVM( String program ) throws Exception {
		// Use JDI to launch the program with debugging enabled
		LaunchingConnector				launchingConnector	= Bootstrap.virtualMachineManager().defaultConnector();
		Map<String, Connector.Argument>	arguments			= launchingConnector.defaultArguments();

		// Set up the command line arguments
		String							classpath			= System.getProperty( "java.class.path" );
		arguments.get( "options" ).setValue( "-cp \"" + classpath + "\"" );

		StringBuilder command = new StringBuilder();
		command.append( "ortus.boxlang.runtime.BoxRunner " + program );

		arguments.get( "main" ).setValue( command.toString() );

		// Launch the VM with a couple of quick retries to avoid transient failures
		final int	maxAttempts	= 3;
		int			attempt		= 1;
		while ( true ) {
			try {
				VirtualMachine vm = launchingConnector.launch( arguments );

				return vm;
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
	}
}
