package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.sun.jdi.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;

public interface IVMConnection {

	public static void loadDebugAgent( IVMConnection connection ) throws IOException {
		try {
			VirtualMachine						vm			= connection.getVirtualMachine();
			com.sun.tools.attach.VirtualMachine	attachVM	= connection.getAttachVirtualMachine();
			vm.resume();
			attachVM.loadAgent( getDebugAgentPath() );
			attachVM.detach();
			// vm.suspend();

			java.util.logging.Logger.getLogger( IVMConnection.class.getName() ).info( "Debug agent loaded successfully" );

		} catch ( Throwable e ) {
			java.util.logging.Logger.getLogger( IVMConnection.class.getName() ).warning( "Failed to start debug exec thread: " + e.getMessage() );
		}

	}

	public VirtualMachine getVirtualMachine();

	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException;

	private static String getDebugAgentPath() {
		if ( System.getProperty( "boxlang.debugger.agentjarpath" ) != null ) {
			return System.getProperty( "boxlang.debugger.agentjarpath" );
		}
		// Get the path to the JAR file containing this class
		Path jarPath;
		try {
			jarPath = Paths.get( IVMConnection.class.getProtectionDomain().getCodeSource().getLocation().toURI() );

			// Get the parent directory of the JAR
			Path	jarDir			= jarPath.getParent();

			// Create a path relative to the JAR directory
			// Path relativePath = jarDir.resolve( "bx-debugger-agent.jar" );
			Path	relativePath	= Paths.get( "C:\\Users\\jacob\\Dev\\ortus-boxlang\\bx-debugger\\build\\libs\\bx-debugger-1.0.0-snapshot-agent.jar" );

			return relativePath.toString();
		} catch ( URISyntaxException e ) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		System.exit( 1 );
		return null;
	}

}
