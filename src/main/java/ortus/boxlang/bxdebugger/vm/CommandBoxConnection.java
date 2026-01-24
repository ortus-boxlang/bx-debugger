package ortus.boxlang.bxdebugger.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.tools.attach.AttachNotSupportedException;

import ortus.boxlang.runtime.types.util.JSONUtil;

public class CommandBoxConnection implements IVMConnection {

	private static final Logger					LOGGER	= Logger.getLogger( CommandBoxConnection.class.getName() );

	private VirtualMachine						vm;
	private com.sun.tools.attach.VirtualMachine	attachVm;
	private String								serverName;
	private CommandBoxServerInfo				serverInfo;

	private record CommandBoxServerInfo( String host, Integer port, Long pid ) {
	}

	public CommandBoxConnection( String serverName ) throws Exception {
		this.serverName	= serverName;
		this.serverInfo	= parseCommandBoxServerInfo( serverName );
		this.vm			= attachToVM( serverInfo.host, serverInfo.port );

		IVMConnection.startDebuggerService( this );
	}

	@Override
	public VirtualMachine getVirtualMachine() {
		return this.vm;
	}

	@Override
	public com.sun.tools.attach.VirtualMachine getAttachVirtualMachine() throws AttachNotSupportedException, IOException {

		if ( attachVm == null ) {
			attachVm = com.sun.tools.attach.VirtualMachine.attach( String.valueOf( serverInfo.pid ) );
		}

		return attachVm;
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

	private CommandBoxServerInfo parseCommandBoxServerInfo( String serverName ) throws IOException {
		ProcessBuilder pb = new ProcessBuilder( "box", "server", "info", serverName, "--json" );
		pb.redirectErrorStream( true );
		StringBuilder jsonOut = new StringBuilder();
		try {
			Process p = pb.start();
			try ( BufferedReader r = new BufferedReader( new InputStreamReader( p.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
				String line;
				while ( ( line = r.readLine() ) != null ) {
					jsonOut.append( line );
				}
			}
			int exit = p.waitFor();
			if ( exit != 0 ) {
				throw new IOException( "CommandBox server info command failed with exit code " + exit );
			}
		} catch ( Exception e ) {
			throw new IOException( "Failed to retrieve CommandBox server info: " + e.getMessage(), e );
		}

		Map<String, Object>	infoData	= ( Map ) JSONUtil.fromJSON( jsonOut.toString().trim() );

		Long				pid			= getPID( ( String ) infoData.get( "pidfile" ) );
		Integer				port		= getPort( ( String ) infoData.get( "JVMargs" ) );

		return new CommandBoxServerInfo( "localhost", port, pid );
	}

	private Long getPID( String PIDFilePath ) throws IOException {
		return Long.parseLong( Files.readString( Paths.get( PIDFilePath ) ).trim() );
	}

	private Integer getPort( String jvmArgs ) {
		Matcher m = Pattern.compile( "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=([^\s,]+)" ).matcher( jvmArgs );
		if ( m.find() ) {
			String		addr	= m.group( 1 );
			// address might be host:port or just port
			String[]	parts	= addr.split( ":" );
			String		portStr	= parts.length == 2 ? parts[ 1 ] : parts[ 0 ];
			return Integer.parseInt( portStr );
		}
		return null;
	}

}
