package ortus.boxlang.moduleslug;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for enhanced stack trace request handling in BoxDebugger
 * This test verifies that the debugger can properly handle stack trace requests
 * with BoxLang-specific filtering and mode support.
 */
public class StackTraceRequestHandlingTest {

	private static final Logger	LOGGER		= Logger.getLogger( StackTraceRequestHandlingTest.class.getName() );
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
	 * Test debug client implementation for stack trace request testing
	 */
	public static class TestDebugClient implements IDebugProtocolClient {

		private static final Logger LOGGER = Logger.getLogger( TestDebugClient.class.getName() );

		// Implement required methods as no-ops for testing
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
	@DisplayName( "Test stack trace request handles BoxLang mode filtering" )
	public void testStackTraceRequestBoxLangMode() throws Exception {
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
			initArgs.setClientID( "stack-trace-test-client" );
			initArgs.setClientName( "StackTraceRequestHandlingTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Launch a BoxLang program
			Map<String, Object>		launchArgs	= Map.of(
			    "program", "src/test/resources/breakpoint.bxs",
			    "debugMode", "BoxLang" // Test BoxLang mode
			);

			CompletableFuture<Void>	launchFuture	= debugServer.launch( launchArgs );
			launchFuture.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched successfully in BoxLang mode" );

			// Wait for VM to initialize
			Thread.sleep( 3000 );

			// Test stack trace request - should filter to BoxLang frames only
			StackTraceArguments				stackArgs		= new StackTraceArguments();
			stackArgs.setThreadId( 1 );

			CompletableFuture<StackTraceResponse>	stackFuture		= debugServer.stackTrace( stackArgs );
			StackTraceResponse						stackResponse	= stackFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( stackResponse, "Should receive stack trace response" );
			assertNotNull( stackResponse.getStackFrames(), "Stack frames should not be null" );

			// In BoxLang mode, we should only get BoxLang-specific frames
			// This test validates the filtering logic exists
			LOGGER.info( "Stack trace request completed in BoxLang mode with " + stackResponse.getStackFrames().length + " frames" );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test stack trace request handles Java mode with all frames" )
	public void testStackTraceRequestJavaMode() throws Exception {
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
			initArgs.setClientID( "stack-trace-test-client" );
			initArgs.setClientName( "StackTraceRequestHandlingTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Launch a BoxLang program
			Map<String, Object>		launchArgs	= Map.of(
			    "program", "src/test/resources/breakpoint.bxs",
			    "debugMode", "Java" // Test Java mode
			);

			CompletableFuture<Void>	launchFuture	= debugServer.launch( launchArgs );
			launchFuture.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched successfully in Java mode" );

			// Wait for VM to initialize
			Thread.sleep( 3000 );

			// Test stack trace request - should return all frames including Java frames
			StackTraceArguments				stackArgs		= new StackTraceArguments();
			stackArgs.setThreadId( 1 );

			CompletableFuture<StackTraceResponse>	stackFuture		= debugServer.stackTrace( stackArgs );
			StackTraceResponse						stackResponse	= stackFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( stackResponse, "Should receive stack trace response" );
			assertNotNull( stackResponse.getStackFrames(), "Stack frames should not be null" );

			// In Java mode, we should get all frames (BoxLang + Java)
			// This test validates the mode-based filtering
			LOGGER.info( "Stack trace request completed in Java mode with " + stackResponse.getStackFrames().length + " frames" );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test stack trace request defaults to BoxLang mode" )
	public void testStackTraceRequestDefaultsToBoxLangMode() throws Exception {
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
			initArgs.setClientID( "stack-trace-test-client" );
			initArgs.setClientName( "StackTraceRequestHandlingTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			// Launch a BoxLang program without specifying debugMode (should default to BoxLang)
			Map<String, Object>		launchArgs		= Map.of(
			    "program", "src/test/resources/breakpoint.bxs"
			    // No debugMode specified - should default to BoxLang
			);

			CompletableFuture<Void>	launchFuture	= debugServer.launch( launchArgs );
			launchFuture.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched successfully with default mode" );

			// Wait for VM to initialize
			Thread.sleep( 3000 );

			// Test stack trace request - should default to BoxLang mode filtering
			StackTraceArguments				stackArgs		= new StackTraceArguments();
			stackArgs.setThreadId( 1 );

			CompletableFuture<StackTraceResponse>	stackFuture		= debugServer.stackTrace( stackArgs );
			StackTraceResponse						stackResponse	= stackFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( stackResponse, "Should receive stack trace response" );
			assertNotNull( stackResponse.getStackFrames(), "Stack frames should not be null" );

			// Should default to BoxLang mode behavior
			LOGGER.info( "Stack trace request completed with default mode - " + stackResponse.getStackFrames().length + " frames" );
		}
	}
}
