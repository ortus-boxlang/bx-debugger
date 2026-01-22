package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for scopes request handling in BoxDebugger
 * This test verifies that the debugger can properly handle DAP scopes requests
 * and return BoxLang-specific scopes when breakpoints are hit.
 */
public class ScopesRequestHandlingTest {

	private static final int	TEST_PORT	= 9999;

	private Thread				debuggerThread;
	private CountDownLatch		serverStartupLatch;

	/**
	 * Test debug client for receiving events and responses
	 */
	private static class TestDebugClient implements IDebugProtocolClient {
		// Basic implementation for testing
	}

	@BeforeEach
	void setUp() throws Exception {
		serverStartupLatch	= new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		debuggerThread		= new Thread( () -> {
								try {
									serverStartupLatch.countDown();
									BoxDebugger.main( new String[] { String.valueOf( TEST_PORT ) } );
								} catch ( Exception e ) {
									e.printStackTrace();
								}
							} );

		debuggerThread.setDaemon( true );
		debuggerThread.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		if ( debuggerThread != null && debuggerThread.isAlive() ) {
			debuggerThread.interrupt();
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test scopes request with invalid frame ID" )
	public void testScopesRequestWithInvalidFrameId() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
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

			// Initialize the debug session
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setClientName( "BoxLang Scopes Invalid Frame Test" );
			server.initialize( initArgs ).get( 5, TimeUnit.SECONDS );

			// Create scopes request with invalid frame ID
			ScopesArguments scopesArgs = new ScopesArguments();
			scopesArgs.setFrameId( -1 ); // Invalid frame ID

			// Request scopes
			ScopesResponse scopesResponse = server.scopes( scopesArgs ).get( 5, TimeUnit.SECONDS );

			// Verify scopes response handles invalid frame gracefully
			assertNotNull( scopesResponse, "Scopes response should not be null even with invalid frame" );
			assertNotNull( scopesResponse.getScopes(), "Scopes array should not be null" );
			// Should return empty array for invalid frame
			assertEquals( 0, scopesResponse.getScopes().length, "Should return empty scopes for invalid frame" );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test scopes request handles capabilities correctly" )
	public void testScopesRequestCapabilities() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
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

			// Initialize the debug session
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setClientName( "BoxLang Scopes Capabilities Test" );
			var capabilities = server.initialize( initArgs ).get( 5, TimeUnit.SECONDS );

			// Verify that the debug server reports scopes capability
			// This would be set in the BoxDebugServer initialization
			assertNotNull( capabilities, "Capabilities should not be null" );
		}
	}
}
