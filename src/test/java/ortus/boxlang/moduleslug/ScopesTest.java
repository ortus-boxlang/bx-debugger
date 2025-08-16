package ortus.boxlang.moduleslug;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
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
public class ScopesTest {

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
	@DisplayName( "Test scopes request returns server scope" )
	public void testScopesRequestReturnsServerScope() throws Exception {
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

				// SET BREAKPOINTS

				SetBreakpointsArguments			breakpointArgs	= new SetBreakpointsArguments();

				Source							source			= new Source();
				source.setPath( breakpointFile.toString() );
				source.setName( "breakpoint.bxs" );
				breakpointArgs.setSource( source );

				SourceBreakpoint functionBreakpoint = new SourceBreakpoint();
				functionBreakpoint.setLine( 2 );
				functionBreakpoint.setCondition( null ); // No condition

				SourceBreakpoint scriptBreakpoint = new SourceBreakpoint();
				functionBreakpoint.setLine( 5 );
				functionBreakpoint.setCondition( null ); // No condition

				breakpointArgs.setBreakpoints( new SourceBreakpoint[] { functionBreakpoint, scriptBreakpoint } );

				CompletableFuture<SetBreakpointsResponse>	breakpointResponse	= server.setBreakpoints( breakpointArgs );
				SetBreakpointsResponse						breakpointResult	= breakpointResponse.get( TIMEOUT, TimeUnit.SECONDS );

				assertNotNull( breakpointResult, "Should receive breakpoint response" );
				assertNotNull( breakpointResult.getBreakpoints(), "Should have breakpoints in response" );
				assertThat( breakpointResult.getBreakpoints().length ).isGreaterThan( 0 );

				// TODO figure out why the function breakpoint returns line 0
				List.of( breakpointResult.getBreakpoints() ).stream()
				    .filter( b -> b.getLine() == 0 )
				    .findFirst()
				    .ifPresentOrElse( b -> {
					    assertThat( b.getLine() ).isEqualTo( 0 );
					    assertThat( b.isVerified() ).isFalse();
				    }, () -> {
					    fail( "Function breakpoint for line 2 not found" );
				    } );

				List.of( breakpointResult.getBreakpoints() ).stream()
				    .filter( b -> b.getLine() == 5 )
				    .findFirst()
				    .ifPresentOrElse( b -> {
					    assertThat( b.getLine() ).isEqualTo( 5 );
					    assertThat( b.isVerified() ).isFalse();
				    }, () -> {
					    fail( "Function breakpoint for line 5 not found" );
				    } );

				// LAUNCH
				Map<String, Object> launchArgs = new HashMap<>();

				// Configure launch arguments (these would be specific to BoxLang)
				launchArgs.put( "program", breakpointFile.toString() );
				launchArgs.put( "type", "boxlang" );
				launchArgs.put( "name", "Debug breakpoint.bxs" );
				CompletableFuture<Void> launchResponse = server.launch( launchArgs );
				launchResponse.get( TIMEOUT, TimeUnit.SECONDS ); // Wait for launch to complete

				// CONFIGURATION DONE
				ConfigurationDoneArguments	configArgs			= new ConfigurationDoneArguments();
				CompletableFuture<Void>		configDoneResult	= server.configurationDone( configArgs );
				configDoneResult.get( TIMEOUT, TimeUnit.SECONDS );

				// GET THREADS
				CompletableFuture<ThreadsResponse> threadResponse = server.threads();
				threadResponse.get( TIMEOUT, TimeUnit.SECONDS ); // Wait for launch to complete

				// WAIT FOR BREAKPOINT
				CompletableFuture<StoppedEventArguments>	stopped			= client.waitForStoppedEvent();
				StoppedEventArguments						stoppedArgs		= stopped.get( TIMEOUT, TimeUnit.SECONDS ); // Wait for stopped event

				// GET STACK TRACE
				StackTraceArguments							stackTraceArgs	= new StackTraceArguments();
				stackTraceArgs.setThreadId( stoppedArgs.getThreadId() );
				CompletableFuture<StackTraceResponse>	stackTraceResponse	= server.stackTrace( stackTraceArgs );
				StackTraceResponse						stackTraceResult	= stackTraceResponse.get( TIMEOUT, TimeUnit.SECONDS ); // Wait for stack trace
				assertThat( stackTraceResult.getStackFrames()[ 0 ].getSource().getPath().toString() ).isEqualTo( breakpointFile.toString() );                                                                                                                // response
				assertThat( stackTraceResult.getStackFrames()[ 0 ].getName() ).isEqualTo( "_invoke" );                                                                                                                // response
				assertThat( stackTraceResult.getStackFrames()[ 0 ].getLine() ).isEqualTo( 5 );                                                                                                                // response

				// SCOPES
				ScopesArguments scopesArguments = new ScopesArguments();
				scopesArguments.setFrameId( stackTraceResult.getStackFrames()[ 0 ].getId() );
				CompletableFuture<ScopesResponse>	scopesResponse	= server.scopes( scopesArguments );
				ScopesResponse						scopesResult	= scopesResponse.get( TIMEOUT, TimeUnit.SECONDS );

				Scope								variablesScope	= DebugServerTestUtils.findScope( scopesResult, "variables" );
				assertThat( variablesScope ).isNotNull();
				Scope serverScope = DebugServerTestUtils.findScope( scopesResult, "server" );
				assertThat( serverScope ).isNotNull();
				Scope requestScope = DebugServerTestUtils.findScope( scopesResult, "request" );
				assertThat( requestScope ).isNotNull();

				// TODO get variables of variables scope in script
				// TODO send continue
				// TODO hit next breakpoint
				// TODO get stack frame
				// TODO get scopes
				// TODO get variables of variables scope in function
				// TODO continue
				// TODO send eval to change variable
				// TODO get changed output of script

				assertThat( capabilities ).isNotNull();
			} catch ( Exception e ) {
				fail( e );
			}
		} );
	}

}
