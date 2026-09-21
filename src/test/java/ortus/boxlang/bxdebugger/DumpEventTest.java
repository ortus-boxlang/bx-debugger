package ortus.boxlang.bxdebugger;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test for the {@code boxlang.dump} DAP event.
 *
 * Verifies that sending a {@code writeDump(expr)} evaluate request at a
 * breakpoint fires the custom event and returns the confirmation response.
 */
public class DumpEventTest {

	private static final int	TEST_PORT	= 9988;
	private static final int	TIMEOUT		= 30;

	private Thread				debuggerThread;
	private CountDownLatch		serverStartupLatch;

	@BeforeEach
	void setUp() {
		serverStartupLatch	= new CountDownLatch( 1 );
		debuggerThread		= DebugServerTestUtils.createDebuggerThread( TEST_PORT, serverStartupLatch );
	}

	@AfterEach
	void tearDown() {
		if ( debuggerThread != null && debuggerThread.isAlive() ) {
			debuggerThread.interrupt();
		}
	}

	@Test
	@Timeout( value = TIMEOUT, unit = TimeUnit.SECONDS )
	@DisplayName( "writeDump evaluate fires boxlang.dump event and returns confirmation string" )
	public void testWriteDumpEvaluateFiresDumpEvent() throws Exception {
		assertTrue( serverStartupLatch.await( TIMEOUT, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 500 );

		var breakpointFile = Paths.get( "src/test/resources/dumpTest.bxs" ).toAbsolutePath();
		assertTrue( breakpointFile.toFile().exists(), "dumpTest.bxs should exist" );

		DebugServerTestUtils.getServerProxy( TEST_PORT, ( server, client ) -> {
			try {
				// INITIALIZE
				InitializeRequestArguments initArgs = new InitializeRequestArguments();
				initArgs.setClientID( "test-client" );
				initArgs.setAdapterID( "boxlang" );
				CompletableFuture<Capabilities> initResponse = server.initialize( initArgs );
				initResponse.get( TIMEOUT, TimeUnit.SECONDS );

				// SET BREAKPOINT on line 7 (inside run() function, after myStruct is defined)
				SetBreakpointsArguments	breakpointArgs	= new SetBreakpointsArguments();
				Source					source			= new Source();
				source.setPath( breakpointFile.toString() );
				breakpointArgs.setSource( source );
				SourceBreakpoint bp = new SourceBreakpoint();
				bp.setLine( 7 );
				breakpointArgs.setBreakpoints( new SourceBreakpoint[] { bp } );
				server.setBreakpoints( breakpointArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				// LAUNCH
				Map<String, Object> launchArgs = new HashMap<>();
				launchArgs.put( "program", breakpointFile.toString() );
				server.launch( launchArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				// CONFIGURATION DONE
				server.configurationDone( new ConfigurationDoneArguments() ).get( TIMEOUT, TimeUnit.SECONDS );

				// WAIT FOR BREAKPOINT
				CompletableFuture<BoxLangDumpEventBody>		dumpEventFuture	= client.waitForDumpEvent();
				CompletableFuture<StoppedEventArguments>	stopped			= client.waitForStoppedEvent();
				StoppedEventArguments						stoppedArgs		= stopped.get( TIMEOUT, TimeUnit.SECONDS );

				// GET STACK FRAME
				StackTraceArguments							stackTraceArgs	= new StackTraceArguments();
				stackTraceArgs.setThreadId( stoppedArgs.getThreadId() );
				CompletableFuture<StackTraceResponse>	stackTraceResponse	= server.stackTrace( stackTraceArgs );
				StackTraceResponse						stackTraceResult	= stackTraceResponse.get( TIMEOUT, TimeUnit.SECONDS );
				int										frameId				= stackTraceResult.getStackFrames()[ 0 ].getId();

				// SEND DUMP EVALUATE REQUEST
				EvaluateArguments						evalArgs			= new EvaluateArguments();
				evalArgs.setExpression( "writeDump( myStruct )" );
				evalArgs.setContext( "repl" );
				evalArgs.setFrameId( frameId );

				EvaluateResponse evalResponse = server.evaluate( evalArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				// ASSERT EVALUATE RESPONSE
				assertNotNull( evalResponse, "Evaluate response should not be null" );
				assertThat( evalResponse.getResult() ).isEqualTo( "Variable 'myStruct' dumped to editor" );
				assertThat( evalResponse.getVariablesReference() ).isEqualTo( 0 );

				// ASSERT DUMP EVENT WAS FIRED
				BoxLangDumpEventBody dumpEvent = dumpEventFuture.get( TIMEOUT, TimeUnit.SECONDS );
				assertNotNull( dumpEvent, "Dump event body should not be null" );
				assertThat( dumpEvent.getLabel() ).isEqualTo( "myStruct" );
				assertThat( dumpEvent.getHtml() ).isNotNull();
				assertThat( dumpEvent.getHtml() ).isNotEmpty();
				assertThat( dumpEvent.getTimestamp() ).isNotNull();

			} catch ( Exception e ) {
				throw new RuntimeException( e );
			}
		} );
	}

	@Test
	@Timeout( value = TIMEOUT, unit = TimeUnit.SECONDS )
	@DisplayName( "Non-dump evaluate expression still works normally" )
	public void testNonDumpEvaluateUnaffected() throws Exception {
		assertTrue( serverStartupLatch.await( TIMEOUT, TimeUnit.SECONDS ), "Server should signal startup" );
		Thread.sleep( 500 );

		var breakpointFile = Paths.get( "src/test/resources/dumpTest.bxs" ).toAbsolutePath();

		DebugServerTestUtils.getServerProxy( TEST_PORT, ( server, client ) -> {
			try {
				InitializeRequestArguments initArgs = new InitializeRequestArguments();
				initArgs.setClientID( "test-client" );
				initArgs.setAdapterID( "boxlang" );
				server.initialize( initArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				SetBreakpointsArguments	breakpointArgs	= new SetBreakpointsArguments();
				Source					source			= new Source();
				source.setPath( breakpointFile.toString() );
				breakpointArgs.setSource( source );
				SourceBreakpoint bp = new SourceBreakpoint();
				bp.setLine( 7 );
				breakpointArgs.setBreakpoints( new SourceBreakpoint[] { bp } );
				server.setBreakpoints( breakpointArgs ).get( TIMEOUT, TimeUnit.SECONDS );

				Map<String, Object> launchArgs = new HashMap<>();
				launchArgs.put( "program", breakpointFile.toString() );
				server.launch( launchArgs ).get( TIMEOUT, TimeUnit.SECONDS );
				server.configurationDone( new ConfigurationDoneArguments() ).get( TIMEOUT, TimeUnit.SECONDS );

				CompletableFuture<StoppedEventArguments>	stopped			= client.waitForStoppedEvent();
				StoppedEventArguments						stoppedArgs		= stopped.get( TIMEOUT, TimeUnit.SECONDS );

				StackTraceArguments							stackTraceArgs	= new StackTraceArguments();
				stackTraceArgs.setThreadId( stoppedArgs.getThreadId() );
				StackTraceResponse	stackTraceResult	= server.stackTrace( stackTraceArgs ).get( TIMEOUT, TimeUnit.SECONDS );
				int					frameId				= stackTraceResult.getStackFrames()[ 0 ].getId();

				// Regular evaluate — should NOT fire dump event
				EvaluateArguments	evalArgs			= new EvaluateArguments();
				evalArgs.setExpression( "trivial" );
				evalArgs.setContext( "repl" );
				evalArgs.setFrameId( frameId );

				EvaluateResponse evalResponse = server.evaluate( evalArgs ).get( TIMEOUT, TimeUnit.SECONDS );
				assertNotNull( evalResponse );

				// No dump events should have been received
				assertThat( client.getDumpEvents() ).isEmpty();

			} catch ( Exception e ) {
				throw new RuntimeException( e );
			}
		} );
	}
}
