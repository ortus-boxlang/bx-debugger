package ortus.boxlang.bxdebugger.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		if ( serverInfo.port == null )
			throw new IOException( "CommandBox server has no JDWP port" );
		this.vm = new BareJDWPConnection( serverInfo.host, serverInfo.port ).getVirtualMachine();
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

	private CommandBoxServerInfo parseCommandBoxServerInfo( String serverName ) throws IOException {
		ProcessBuilder pb = new ProcessBuilder( "box", "server", "info", serverName, "--json" );
		pb.redirectErrorStream( true );
		java.nio.file.Path	output	= Files.createTempFile( "bx-commandbox-info-", ".json" );
		Process				process	= null;
		String				json;
		try {
			process = pb.redirectOutput( output.toFile() ).start();
			if ( !process.waitFor( 10, java.util.concurrent.TimeUnit.SECONDS ) ) {
				throw new IOException( "CommandBox discovery timed out after 10 seconds" );
			}
			if ( process.exitValue() != 0 )
				throw new IOException( "CommandBox discovery failed: " + Files.readString( output ) );
			json = Files.readString( output );
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new IOException( "CommandBox discovery interrupted", e );
		} finally {
			if ( process != null && process.isAlive() ) {
				process.descendants().forEach( ProcessHandle::destroyForcibly );
				process.destroyForcibly();
			}
			if ( process != null ) {
				try {
					process.waitFor( 2, java.util.concurrent.TimeUnit.SECONDS );
				} catch ( InterruptedException interrupted ) {
					Thread.currentThread().interrupt();
				}
			}
			try {
				Files.deleteIfExists( output );
			} catch ( IOException cleanupFailure ) {
				LOGGER.fine( "Discovery output still in use: " + cleanupFailure.getMessage() );
				output.toFile().deleteOnExit();
			}
		}

		Map<String, Object>	infoData	= ( Map ) JSONUtil.fromJSON( json.trim() );

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
