package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;

public class BareJDWPConnection implements IVMConnection {

	private static final Logger					LOGGER	= Logger.getLogger( BareJDWPConnection.class.getName() );

	private VirtualMachine						vm;
	private com.sun.tools.attach.VirtualMachine	attachVm;
	private String								hostname;
	private int									port;

	public BareJDWPConnection( String hostname, int port ) throws Exception {
		this.hostname	= hostname;
		this.port		= port;
		this.vm			= attachToVM( hostname, port );

		// IVMConnection.loadDebugAgent( this );
	}

	@Override
	public VirtualMachine getVirtualMachine() {
		return this.vm;
	}

	@Override
	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException {

		return null;
	}

	private VirtualMachine attachToVM( String hostname, int port ) throws Exception {

		// Find socket attaching connector
		com.sun.jdi.connect.AttachingConnector				connector	= Bootstrap.virtualMachineManager()
		    .attachingConnectors()
		    .stream()
		    .filter( c -> "com.sun.jdi.SocketAttach".equals( c.name() ) )
		    .findFirst()
		    .orElseThrow( () -> new RuntimeException( "SocketAttach connector not found" ) );

		Map<String, com.sun.jdi.connect.Connector.Argument>	cArgs		= connector.defaultArguments();
		cArgs.get( "hostname" ).setValue( hostname );
		cArgs.get( "port" ).setValue( String.valueOf( port ) );

		final int	maxAttempts	= 8;
		int			attempt		= 1;
		while ( true ) {
			try {
				VirtualMachine vm = connector.attach( cArgs );
				LOGGER.info( "Attached to target VM at " + hostname + ":" + port );

				return vm;
			} catch ( Exception ex ) {
				if ( attempt >= maxAttempts ) {
					throw new RuntimeException( ex );
				}
				try {
					Thread.sleep( 200L * attempt );
				} catch ( InterruptedException ie ) {
					Thread.currentThread().interrupt();
					throw new RuntimeException( ex );
				}
				attempt++;
			}
		}
	}
}
