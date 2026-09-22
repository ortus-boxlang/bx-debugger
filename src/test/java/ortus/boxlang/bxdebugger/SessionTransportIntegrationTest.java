package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.lang.Thread;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.debug.DebugLauncher;
import org.junit.jupiter.api.*;

@Timeout( 30 )
class SessionTransportIntegrationTest {

	private Process												target;
	private Thread												adapter;
	private Socket												socket;
	private IDebugProtocolServer								server;
	private int													targetPort;
	private final LinkedBlockingQueue<TerminatedEventArguments>	terminated	= new LinkedBlockingQueue<>();

	@BeforeEach
	void connect() throws Exception {
		int adapterPort;
		try ( ServerSocket port = new ServerSocket( 0 ) ) {
			targetPort = port.getLocalPort();
		}
		try ( ServerSocket port = new ServerSocket( 0 ) ) {
			adapterPort = port.getLocalPort();
		}
		target = new ProcessBuilder( Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString(),
		    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + targetPort,
		    "-cp", System.getProperty( "java.class.path" ), Sleeper.class.getName() ).redirectErrorStream( true ).start();
		var ready = new CompletableFuture<Void>();
		CompletableFuture.runAsync( () -> {
			try ( var reader = target.inputReader() ) {
				String line;
				while ( ( line = reader.readLine() ) != null )
					if ( line.contains( "Listening for transport" ) )
						ready.complete( null );
			} catch ( Exception error ) {
				ready.completeExceptionally( error );
			}
		} );
		ready.get( 5, TimeUnit.SECONDS );
		adapter = DebugServerTestUtils.createDebuggerThread( adapterPort, new CountDownLatch( 1 ) );
		for ( int attempt = 0; attempt < 50 && socket == null; attempt++ ) {
			try {
				socket = new Socket( "127.0.0.1", adapterPort );
			} catch ( java.io.IOException notReady ) {
				Thread.sleep( 50 );
			}
		}
		assertNotNull( socket );
		var launcher = DebugLauncher.createLauncher( new IBoxLangDebugClient() {

			@Override
			public void terminated( TerminatedEventArguments event ) {
				terminated.add( event );
			}
		}, IDebugProtocolServer.class, socket.getInputStream(), socket.getOutputStream() );
		launcher.startListening();
		server = launcher.getRemoteProxy();
		server.attach( Map.of( "serverPort", targetPort ) ).get( 5, TimeUnit.SECONDS );
		server.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );
	}

	@AfterEach
	void cleanup() throws Exception {
		if ( socket != null )
			socket.close();
		if ( target != null ) {
			target.destroyForcibly();
			target.waitFor( 5, TimeUnit.SECONDS );
		}
		if ( adapter != null ) {
			adapter.interrupt();
			adapter.join( 3000 );
		}
	}

	@Test
	void abruptTransportLossReleasesJdwpWithoutKillingTheApplication() throws Exception {
		socket.close();
		var	reconnect	= CompletableFuture.supplyAsync( () -> {
							try {
								return new ortus.boxlang.bxdebugger.vm.BareJDWPConnection( "127.0.0.1", targetPort ).getVirtualMachine();
							} catch ( Exception error ) {
								throw new CompletionException( error );
							}
						} );
		var	vm			= reconnect.get( 6, TimeUnit.SECONDS );
		try {
			assertTrue( target.isAlive() );
		} finally {
			vm.dispose();
		}
	}

	@Test
	void targetDeathTerminatesTheAttachedSession() throws Exception {
		target.destroyForcibly();
		assertNotNull( terminated.poll( 5, TimeUnit.SECONDS ), "Target death must reach the DAP client" );
		server.disconnect( new DisconnectArguments() ).get( 2, TimeUnit.SECONDS );
	}

	@Test
	void terminateRespondsAndKillsTheTarget() throws Exception {
		server.terminate( new TerminateArguments() ).get( 2, TimeUnit.SECONDS );
		assertTrue( target.waitFor( 5, TimeUnit.SECONDS ) );
		assertNotNull( terminated.poll( 2, TimeUnit.SECONDS ) );
	}

	public static class Sleeper {

		public static void main( String[] args ) throws Exception {
			new CountDownLatch( 1 ).await();
		}
	}
}
