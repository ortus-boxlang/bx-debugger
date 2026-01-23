package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Stream;

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
		String override = System.getProperty( "boxlang.debugger.agentjarpath" );
		if ( override != null && !override.isBlank() ) {
			return override;
		}
		try {
			Path jarPath = Paths.get( IVMConnection.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
			Path searchDir = Files.isDirectory( jarPath ) ? jarPath : jarPath.getParent();
			Optional<Path> agentJar = findAgentJar( searchDir );
			if ( agentJar.isEmpty() ) {
				Path buildLibs = Paths.get( "" ).toAbsolutePath().resolve( "build" ).resolve( "libs" );
				agentJar = findAgentJar( buildLibs );
			}
			if ( agentJar.isPresent() ) {
				return agentJar.get().toString();
			}
		} catch ( URISyntaxException e ) {
			e.printStackTrace();
		}

		throw new IllegalStateException( "Debug agent JAR not found" );
	}

	private static Optional<Path> findAgentJar( Path directory ) {
		if ( directory == null || !Files.isDirectory( directory ) ) {
			return Optional.empty();
		}
		try ( Stream<Path> files = Files.list( directory ) ) {
			return files
			    .filter( path -> path.getFileName().toString().endsWith( "-agent.jar" ) )
			    .sorted()
			    .reduce( ( first, second ) -> second );
		} catch ( IOException e ) {
			return Optional.empty();
		}
	}

}
