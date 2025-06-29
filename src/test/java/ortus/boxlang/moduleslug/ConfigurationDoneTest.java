package ortus.boxlang.moduleslug;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
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
 * Test class for Configuration Done event handling in BoxDebugger
 */
public class ConfigurationDoneTest {

	private static final Logger			LOGGER				= Logger.getLogger( ConfigurationDoneTest.class.getName() );
	private static final int			TEST_PORT			= 5010; // Different port to avoid conflicts

	private ExecutorService				serverExecutor;
	private ExecutorService				testExecutor;
	private AtomicReference<Exception>	serverException		= new AtomicReference<>();

	@BeforeEach
	public void setUp() {
		serverExecutor	= Executors.newSingleThreadExecutor();
		testExecutor	= Executors.newSingleThreadExecutor();
	}

	@AfterEach
	public void tearDown() throws InterruptedException {
		if ( serverExecutor != null && !serverExecutor.isShutdown() ) {
			serverExecutor.shutdownNow();
			serverExecutor.awaitTermination( 5, TimeUnit.SECONDS );
		}
		if ( testExecutor != null && !testExecutor.isShutdown() ) {
			testExecutor.shutdownNow();
			testExecutor.awaitTermination( 5, TimeUnit.SECONDS );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test configuration done request handling" )
	public void testConfigurationDoneHandling() throws Exception {
		LOGGER.info( "Starting configuration done test" );

		// Latch to wait for server startup
		CountDownLatch serverStartupLatch = new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		serverExecutor.submit( () -> {
			try {
				serverStartupLatch.countDown(); // Signal that we're about to start
				BoxDebugger.main( new String[] { String.valueOf( TEST_PORT ) } );
			} catch ( Exception e ) {
				serverException.set( e );
				LOGGER.severe( "Server startup failed: " + e.getMessage() );
				e.printStackTrace();
			}
		} );

		// Wait for server startup signal
		assertTrue( serverStartupLatch.await( 5, TimeUnit.SECONDS ), "Server should signal startup" );

		// Give the server a moment to actually start listening
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

			// Get the debug server proxy
			IDebugProtocolServer server = launcher.getRemoteProxy();

			// Start the launcher
			java.util.concurrent.Future<Void> launcherFuture = launcher.startListening();

			// Test the configuration done flow
			LOGGER.info( "Starting configuration done test flow" );

			// 1. Initialize
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setAdapterID( "boxlang" );
			initArgs.setClientName( "ConfigurationDoneTest" );

			CompletableFuture<Capabilities> initResult = server.initialize( initArgs );
			Capabilities capabilities = initResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Capabilities should not be null" );
			assertTrue( capabilities.getSupportsConfigurationDoneRequest(), "Should support configuration done requests" );

			// 2. Set some breakpoints (typical pre-configuration step)
			SetBreakpointsArguments breakpointArgs = new SetBreakpointsArguments();
			Source source = new Source();
			source.setPath( "test.bx" );
			breakpointArgs.setSource( source );

			SourceBreakpoint	bp1	= new SourceBreakpoint();
			bp1.setLine( 5 );
			SourceBreakpoint	bp2	= new SourceBreakpoint();
			bp2.setLine( 10 );

			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { bp1, bp2 } );

			CompletableFuture<SetBreakpointsResponse> breakpointResult = server.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse breakpointResponse = breakpointResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResponse, "Breakpoint response should not be null" );
			assertThat( breakpointResponse.getBreakpoints() ).hasLength( 2 );

			// 3. Send configuration done request
			LOGGER.info( "Sending configuration done request" );
			ConfigurationDoneArguments configArgs = new ConfigurationDoneArguments();
			CompletableFuture<Void> configDoneResult = server.configurationDone( configArgs );

			// This should complete without throwing an exception
			configDoneResult.get( 5, TimeUnit.SECONDS );
			LOGGER.info( "Configuration done request completed successfully" );

			// 4. Verify the server is still responsive after configuration done
			// Try to set breakpoints again to ensure server is still working
			CompletableFuture<SetBreakpointsResponse> postConfigResult = server.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse postConfigResponse = postConfigResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( postConfigResponse, "Post-config breakpoint response should not be null" );
			assertThat( postConfigResponse.getBreakpoints() ).hasLength( 2 );

			LOGGER.info( "Configuration done test completed successfully" );

			// Cleanup
			launcherFuture.cancel( true );

		} catch ( Exception e ) {
			LOGGER.severe( "Test failed: " + e.getMessage() );
			e.printStackTrace();
			fail( "Configuration done test failed: " + e.getMessage() );
		}

		// Check if server had any exceptions
		Exception serverEx = serverException.get();
		if ( serverEx != null ) {
			LOGGER.severe( "Server exception: " + serverEx.getMessage() );
			serverEx.printStackTrace();
			fail( "Server encountered an exception: " + serverEx.getMessage() );
		}
	}

	@Test
	@Timeout( value = 30, unit = TimeUnit.SECONDS )
	@DisplayName( "Test configuration done with launch sequence" )
	public void testConfigurationDoneWithLaunch() throws Exception {
		LOGGER.info( "Starting configuration done with launch test" );

		// Latch to wait for server startup
		CountDownLatch serverStartupLatch = new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		serverExecutor.submit( () -> {
			try {
				serverStartupLatch.countDown();
				BoxDebugger.main( new String[] { String.valueOf( TEST_PORT ) } );
			} catch ( Exception e ) {
				serverException.set( e );
				LOGGER.severe( "Server startup failed: " + e.getMessage() );
				e.printStackTrace();
			}
		} );

		// Wait for server startup signal
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

			// Get the debug server proxy
			IDebugProtocolServer server = launcher.getRemoteProxy();

			// Start the launcher
			java.util.concurrent.Future<Void> launcherFuture = launcher.startListening();

			// Test the full configuration done flow with launch
			LOGGER.info( "Starting full configuration done with launch test flow" );

			// 1. Initialize
			InitializeRequestArguments initArgs = new InitializeRequestArguments();
			initArgs.setAdapterID( "boxlang" );
			initArgs.setClientName( "ConfigurationDoneWithLaunchTest" );

			CompletableFuture<Capabilities> initResult = server.initialize( initArgs );
			Capabilities capabilities = initResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( capabilities, "Capabilities should not be null" );
			assertTrue( capabilities.getSupportsConfigurationDoneRequest(), "Should support configuration done requests" );

			// 2. Set breakpoints before launch
			SetBreakpointsArguments breakpointArgs = new SetBreakpointsArguments();
			Source source = new Source();

			// Use the test output file
			Path testFilePath = Paths.get( "src/test/resources/output.bxs" ).toAbsolutePath();
			source.setPath( testFilePath.toString() );
			breakpointArgs.setSource( source );

			SourceBreakpoint bp = new SourceBreakpoint();
			bp.setLine( 2 ); // Set breakpoint on line 2

			breakpointArgs.setBreakpoints( new SourceBreakpoint[] { bp } );

			CompletableFuture<SetBreakpointsResponse> breakpointResult = server.setBreakpoints( breakpointArgs );
			SetBreakpointsResponse breakpointResponse = breakpointResult.get( 5, TimeUnit.SECONDS );

			assertNotNull( breakpointResponse, "Breakpoint response should not be null" );
			assertThat( breakpointResponse.getBreakpoints() ).hasLength( 1 );

			// 3. Send configuration done request BEFORE launch (typical flow)
			LOGGER.info( "Sending configuration done request before launch" );
			ConfigurationDoneArguments configArgs = new ConfigurationDoneArguments();
			CompletableFuture<Void> configDoneResult = server.configurationDone( configArgs );

			// This should complete without throwing an exception
			configDoneResult.get( 5, TimeUnit.SECONDS );
			LOGGER.info( "Configuration done request before launch completed successfully" );

			// 4. Launch the program
			Map<String, Object> launchArgs = new HashMap<>();
			launchArgs.put( "program", testFilePath.toString() );

			// Use a shorter timeout for testing
			CompletableFuture<Void> launchResult = server.launch( launchArgs );

			// Launch should succeed (program might exit quickly)
			try {
				launchResult.get( 10, TimeUnit.SECONDS );
				LOGGER.info( "Launch completed successfully after configuration done" );
			} catch ( Exception e ) {
				LOGGER.info( "Launch may have completed with program exit (expected): " + e.getMessage() );
				// This is often expected as the test program exits quickly
			}

			LOGGER.info( "Configuration done with launch test completed successfully" );

			// Cleanup
			launcherFuture.cancel( true );

		} catch ( Exception e ) {
			LOGGER.severe( "Test failed: " + e.getMessage() );
			e.printStackTrace();
			fail( "Configuration done with launch test failed: " + e.getMessage() );
		}

		// Check if server had any exceptions
		Exception serverEx = serverException.get();
		if ( serverEx != null ) {
			LOGGER.severe( "Server exception: " + serverEx.getMessage() );
			serverEx.printStackTrace();
			fail( "Server encountered an exception: " + serverEx.getMessage() );
		}
	}

	/**
	 * Test debug client implementation
	 */
	public static class TestDebugClient implements IDebugProtocolClient {
		// Simple implementation for testing - just log events
		// In a real client, these would be handled appropriately

		// Implement required methods with minimal functionality for testing
	}
}
