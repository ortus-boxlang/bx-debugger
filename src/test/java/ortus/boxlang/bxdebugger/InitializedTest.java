package ortus.boxlang.bxdebugger;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
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
public class InitializedTest {

	private static final int	TEST_PORT	= 9999;
	private static final int	TIMEOUT		= 9999;

	private Thread				debuggerThread;
	private CountDownLatch		serverStartupLatch;

	@BeforeEach
	void setUp() throws Exception {
		serverStartupLatch	= new CountDownLatch( 1 );

		// Start the debug server in a separate thread
		debuggerThread		= DebugServerTestUtils.createDebuggerThread( TEST_PORT, serverStartupLatch );
	}

	@AfterEach
	void tearDown() throws Exception {
		if ( debuggerThread != null && debuggerThread.isAlive() ) {
			debuggerThread.interrupt();
		}
	}

	@Test
	@Timeout( value = TIMEOUT, unit = TimeUnit.SECONDS )
	@DisplayName( "Test initialized event sent after capabilities" )
	public void testSendsInitialized() throws Exception {
		// Wait for server to start
		assertTrue( serverStartupLatch.await( TIMEOUT, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 500 );

		Path breakpointFile = Paths.get( "src/test/resources/main.bxs" ).toAbsolutePath();
		assertTrue( breakpointFile.toFile().exists(), "breakpoint.bxs should exist" );

		DebugServerTestUtils.getServerProxy( TEST_PORT, ( server, client ) -> {
			try {

				// INITIALIZE
				InitializeRequestArguments initArgs = new InitializeRequestArguments();
				initArgs.setClientID( "test-client" );
				initArgs.setClientName( "BoxLang Debug Integration Test" );
				initArgs.setAdapterID( "boxlang" );

				CompletableFuture<Capabilities>	initResponse	= server.initialize( initArgs );
				Capabilities					capabilities	= initResponse.get( TIMEOUT, TimeUnit.SECONDS );

				client.waitForInitializedEvent().get( TIMEOUT, TimeUnit.SECONDS );

				assertThat( capabilities ).isNotNull();
			} catch ( Exception e ) {
				fail( e );
			}
		} );
	}

}
