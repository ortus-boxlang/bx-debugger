package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Test class for threads request handling in BoxDebugger
 * This test verifies that the debugger can properly handle threads requests
 * and return appropriate thread information from the debugged JVM.
 */
public class ThreadsRequestTest {

	private static final Logger	LOGGER		= Logger.getLogger( ThreadsRequestTest.class.getName() );
	private static final int	TEST_PORT	= 5010;
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
				java.lang.Thread.currentThread().interrupt();
				serverExecutor.shutdownNow();
			}
		}
	}

	/**
	 * Test debug client implementation for threads request testing
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
	@DisplayName( "Test threads request returns empty list without active VM" )
	public void testThreadsRequestWithoutVM() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		java.lang.Thread.sleep( 2000 );

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
			initArgs.setClientID( "threads-request-test-client" );
			initArgs.setClientName( "ThreadsRequestTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );
			LOGGER.info( "Received capabilities from debug server" );

			// Test threads request without an active VM
			CompletableFuture<ThreadsResponse>	threadsFuture	= debugServer.threads();
			ThreadsResponse						threadsResponse	= threadsFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( threadsResponse, "Should receive threads response" );
			assertNotNull( threadsResponse.getThreads(), "Threads array should not be null" );
			assertEquals( 0, threadsResponse.getThreads().length, "Should have no threads without active VM" );

			LOGGER.info( "Threads request completed successfully with empty response" );
		}
	}

	@Test
	@Timeout( value = 45, unit = TimeUnit.SECONDS )
	@DisplayName( "Test threads request with active VM returns thread information" )
	public void testThreadsRequestWithActiveVM() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );
		java.lang.Thread.sleep( 2000 );

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
			initArgs.setClientID( "threads-request-test-client" );
			initArgs.setClientName( "ThreadsRequestTest" );

			CompletableFuture<Capabilities>	initFuture		= debugServer.initialize( initArgs );
			Capabilities					capabilities	= initFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Should receive capabilities" );

			String testProgram = "src/test/resources/breakpoint.bxs";

			SetBreakpointsArguments breakpointArgs = new SetBreakpointsArguments();
			Source source = new Source();
			source.setPath( testProgram );
			breakpointArgs.setSource( source );

			SourceBreakpoint breakpoint = new SourceBreakpoint();
			breakpoint.setLine( 5 );
			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { breakpoint } );
			debugServer.setBreakpoints( breakpointArgs ).get( 5, TimeUnit.SECONDS );

			// Launch a BoxLang program to get an active VM
			Map<String, Object>		launchArgs		= Map.of(
			    "program", testProgram
			);

			CompletableFuture<Void>	launchFuture	= debugServer.launch( launchArgs );
			launchFuture.get( 10, TimeUnit.SECONDS );

			LOGGER.info( "Program launched successfully" );

			debugServer.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );

			// Wait a moment for the VM to be fully initialized
			java.lang.Thread.sleep( 1000 );

			// Test threads request with active VM
			CompletableFuture<ThreadsResponse>	threadsFuture	= debugServer.threads();
			ThreadsResponse						threadsResponse	= threadsFuture.get( 5, TimeUnit.SECONDS );

			assertNotNull( threadsResponse, "Should receive threads response" );
			assertNotNull( threadsResponse.getThreads(), "Threads array should not be null" );
			assertTrue( threadsResponse.getThreads().length > 0, "Should have at least one thread with active VM" );

			// Verify thread information
			Thread[] threads = threadsResponse.getThreads();
			for ( Thread thread : threads ) {
				assertNotNull( thread.getId(), "Thread should have an ID" );
				assertNotNull( thread.getName(), "Thread should have a name" );
				LOGGER.info( "Found thread: ID=" + thread.getId() + ", Name=" + thread.getName() );
			}

			LOGGER.info( "Threads request completed successfully with " + threads.length + " threads" );
		}
	}
}
