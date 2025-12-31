package ortus.boxlang.moduleslug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

public class DebugServerTestUtils {

	public static Scope findScope( ScopesResponse args, String name ) {
		for ( var scope : args.getScopes() ) {
			if ( scope.getName().equalsIgnoreCase( name ) ) {
				return scope;
			}
		}

		return null;
	}

	public static Thread createDebuggerThread( int port, CountDownLatch serverStartupLatch ) {
		var debuggerThread = new Thread( () -> {
			try {
				serverStartupLatch.countDown();
				System.setProperty( "BOX_DEBUGGER_FALSEEXIT", "true" );
				BoxDebugger.main( new String[] { String.valueOf( port ) } );
			} catch ( Exception e ) {
				e.printStackTrace();
			}
		} );

		debuggerThread.setDaemon( true );
		debuggerThread.start();

		return debuggerThread;
	}

	public static void getServerProxy( int port, BiConsumer<IDebugProtocolServer, TestDebugClient> consumer ) throws IOException {
		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", port ) );
			assertTrue( clientSocket.isConnected(), "Should be connected to debug server" );

			// Create the DAP client
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			// Get the remote proxy (debug server)
			IDebugProtocolServer			server		= launcher.getRemoteProxy();

			// Start listening for messages
			launcher.startListening();

			consumer.accept( server, testClient );
		}
	}
}
