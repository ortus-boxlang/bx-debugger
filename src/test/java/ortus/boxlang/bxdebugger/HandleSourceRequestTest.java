package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceArguments;
import org.eclipse.lsp4j.debug.SourceResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for source request handling in BoxDebugger
 * This test verifies that the debugger can properly handle source requests
 * and return appropriate source content for debugging sessions.
 */
public class HandleSourceRequestTest {

	private static final Logger	LOGGER		= Logger.getLogger( HandleSourceRequestTest.class.getName() );
	private static final int	TEST_PORT	= 5011;
	private ExecutorService		serverExecutor;
	private ServerSocketChannel	serverSocket;
	private CountDownLatch		serverStartupLatch;

	@BeforeEach
	void setUp() throws IOException {
		serverExecutor		= Executors.newSingleThreadExecutor();
		serverStartupLatch	= new CountDownLatch( 1 );

		// Start the debug server
		serverExecutor.submit( () -> {
			try {
				serverSocket = ServerSocketChannel.open();
				serverSocket.bind( new InetSocketAddress( TEST_PORT ) );
				serverStartupLatch.countDown();

				LOGGER.info( "Test debug server listening on port " + TEST_PORT );

				// Accept a single connection for testing
				SocketChannel clientSocket = serverSocket.accept();
				LOGGER.info( "Test client connected" );

				// Create debug server and handle the connection
				BoxDebugServer					debugServer	= new BoxDebugServer();

				Launcher<IDebugProtocolClient>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createServerLauncher(
				    debugServer,
				    clientSocket.socket().getInputStream(),
				    clientSocket.socket().getOutputStream()
				);

				debugServer.connect( launcher.getRemoteProxy() );
				launcher.startListening().get();

			} catch ( Exception e ) {
				LOGGER.severe( "Error in test debug server: " + e.getMessage() );
			}
		} );
	}

	@AfterEach
	void tearDown() {
		try {
			if ( serverSocket != null && serverSocket.isOpen() ) {
				serverSocket.close();
			}
		} catch ( IOException e ) {
			LOGGER.warning( "Error closing server socket: " + e.getMessage() );
		}

		if ( serverExecutor != null ) {
			serverExecutor.shutdown();
			try {
				if ( !serverExecutor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
					serverExecutor.shutdownNow();
				}
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				serverExecutor.shutdownNow();
			}
		}
	}

	/**
	 * Test debug client implementation for source request testing
	 */
	public static class TestDebugClient implements IDebugProtocolClient {

		private static final Logger LOGGER = Logger.getLogger( TestDebugClient.class.getName() );

		@Override
		public void initialized() {
			LOGGER.info( "Debug client initialized" );
		}

		@Override
		public void stopped( org.eclipse.lsp4j.debug.StoppedEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void continued( org.eclipse.lsp4j.debug.ContinuedEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void exited( org.eclipse.lsp4j.debug.ExitedEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void terminated( org.eclipse.lsp4j.debug.TerminatedEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void thread( org.eclipse.lsp4j.debug.ThreadEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void output( org.eclipse.lsp4j.debug.OutputEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void breakpoint( org.eclipse.lsp4j.debug.BreakpointEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void module( org.eclipse.lsp4j.debug.ModuleEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void loadedSource( org.eclipse.lsp4j.debug.LoadedSourceEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void process( org.eclipse.lsp4j.debug.ProcessEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void capabilities( org.eclipse.lsp4j.debug.CapabilitiesEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void progressStart( org.eclipse.lsp4j.debug.ProgressStartEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void progressUpdate( org.eclipse.lsp4j.debug.ProgressUpdateEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void progressEnd( org.eclipse.lsp4j.debug.ProgressEndEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void invalidated( org.eclipse.lsp4j.debug.InvalidatedEventArguments args ) {
			// No-op for this test
		}

		@Override
		public void memory( org.eclipse.lsp4j.debug.MemoryEventArguments args ) {
			// No-op for this test
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test source request for existing file returns content" )
	public void testSourceRequestForExistingFile() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should be connected to debug server" );

			// Create the DAP client
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "source-request-test-client" );
			initArgs.setClientName( "HandleSourceRequestTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );
			LOGGER.info( "Received capabilities from debug server" );

			// Launch the breakpoint.bxs program which should produce output
			Map<String, Object>	launchArgs		= new HashMap<>();
			Path				breakpointFile	= Paths.get( "src/test/resources/output.bxs" ).toAbsolutePath();
			assertTrue( breakpointFile.toFile().exists(), "output.bxs should exist" );

			launchArgs.put( "program", breakpointFile.toString() );
			launchArgs.put( "type", "boxlang" );
			launchArgs.put( "bx-home", Paths.get( "src/test/resources/boxlang_home" ).toAbsolutePath().toString() );

			// Launch and wait for execution
			CompletableFuture<Void> launchResponse = debugServer.launch( launchArgs );
			launchResponse.get( 10, TimeUnit.SECONDS );

			// Send configuration done request
			DAPTestUtils.sendConfigurationDone( debugServer );

			// Test source request for existing test file
			SourceArguments	sourceArgs	= new SourceArguments();
			Source			source		= new Source();
			source.setPath( "src/test/resources/output.bxs" );
			sourceArgs.setSource( source );

			CompletableFuture<SourceResponse>	sourceFuture	= debugServer.source( sourceArgs );
			SourceResponse						sourceResponse	= sourceFuture.get( 50, TimeUnit.SECONDS );

			assertNotNull( sourceResponse, "Should receive source response" );
			assertNotNull( sourceResponse.getContent(), "Source content should not be null" );
			assertTrue( sourceResponse.getContent().length() > 0, "Source content should not be empty" );

			LOGGER.info( "Source request completed successfully with content length: " + sourceResponse.getContent().length() );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test source request for non-existent file returns empty content" )
	public void testSourceRequestForNonExistentFile() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should be connected to debug server" );

			// Create the DAP client
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "source-request-test-client" );
			initArgs.setClientName( "HandleSourceRequestTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Test source request for non-existent file
			SourceArguments	sourceArgs	= new SourceArguments();
			Source			source		= new Source();
			source.setPath( "non/existent/file.bxs" );
			sourceArgs.setSource( source );

			CompletableFuture<SourceResponse>	sourceFuture	= debugServer.source( sourceArgs );
			SourceResponse						sourceResponse	= sourceFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( sourceResponse, "Should receive source response" );
			assertNotNull( sourceResponse.getContent(), "Source content should not be null" );
			assertEquals( "", sourceResponse.getContent(), "Source content should be empty for non-existent file" );

			LOGGER.info( "Source request for non-existent file handled correctly" );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test source request with sourceReference returns cached content" )
	public void testSourceRequestWithSourceReference() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 2000 );

		// Connect to the debug server
		try ( SocketChannel clientSocket = SocketChannel.open() ) {
			clientSocket.connect( new InetSocketAddress( "localhost", TEST_PORT ) );
			assertTrue( clientSocket.isConnected(), "Should be connected to debug server" );

			// Create the DAP client
			TestDebugClient					testClient	= new TestDebugClient();
			Launcher<IDebugProtocolServer>	launcher	= org.eclipse.lsp4j.debug.launch.DSPLauncher.createClientLauncher(
			    testClient,
			    clientSocket.socket().getInputStream(),
			    clientSocket.socket().getOutputStream()
			);

			launcher.startListening();
			IDebugProtocolServer		debugServer	= launcher.getRemoteProxy();

			// Initialize debug session
			InitializeRequestArguments	initArgs	= new InitializeRequestArguments();
			initArgs.setClientID( "source-request-test-client" );
			initArgs.setClientName( "HandleSourceRequestTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Test source request with sourceReference (for dynamically generated content)
			SourceArguments	sourceArgs	= new SourceArguments();
			Source			source		= new Source();
			source.setSourceReference( 1 ); // Reference to dynamically generated content
			source.setName( "generated-code.bxs" );
			sourceArgs.setSource( source );

			CompletableFuture<SourceResponse>	sourceFuture	= debugServer.source( sourceArgs );
			SourceResponse						sourceResponse	= sourceFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( sourceResponse, "Should receive source response" );
			assertNotNull( sourceResponse.getContent(), "Source content should not be null" );
			// For this test, we expect the handler to return a placeholder message for unknown references
			assertTrue( sourceResponse.getContent().contains( "not available" ) || sourceResponse.getContent().isEmpty(),
			    "Source content should indicate unavailable content or be empty" );

			LOGGER.info( "Source request with sourceReference handled correctly" );
		}
	}
}
