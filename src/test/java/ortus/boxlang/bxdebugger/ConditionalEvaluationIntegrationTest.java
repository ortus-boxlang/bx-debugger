package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import org.eclipse.lsp4j.debug.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

@Timeout( 40 )
class ConditionalEvaluationIntegrationTest {

	@TempDir
	Path directory;

	@Test
	void disconnectDoesNotWaitForAConditionRunningInTheTarget() throws Exception {
		Path script = directory.resolve( "slow-condition.bxs" );
		Files.writeString( script, "marker = 'conditional';\nprintln(marker);\n" );
		var				started	= new CompletableFuture<Void>();
		BoxDebugServer	server	= new BoxDebugServer();
		server.connect( new IBoxLangDebugClient() {

			@Override
			public void output( OutputEventArguments event ) {
				if ( event.getOutput().contains( "condition-running" ) )
					started.complete( null );
			}
		} );
		Source source = new Source();
		source.setPath( script.toString() );
		SourceBreakpoint breakpoint = new SourceBreakpoint();
		breakpoint.setLine( 2 );
		breakpoint.setCondition( "(function(){ println('condition-running'); sleep(20000); return true; })()" );
		SetBreakpointsArguments args = new SetBreakpointsArguments();
		args.setSource( source );
		args.setBreakpoints( new SourceBreakpoint[] { breakpoint } );
		try {
			server.setBreakpoints( args ).get( 5, TimeUnit.SECONDS );
			server.launch( Map.of( "program", script.toString() ) ).get( 10, TimeUnit.SECONDS );
			server.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );
			started.get( 10, TimeUnit.SECONDS );
		} finally {
			DisconnectArguments disconnect = new DisconnectArguments();
			disconnect.setTerminateDebuggee( true );
			server.disconnect( disconnect ).get( 2, TimeUnit.SECONDS );
		}
	}

	@ParameterizedTest
	@ValueSource( strings = { "true", "false", "null", "createObject('java', 'java.lang.Boolean').FALSE", "missingTicket04Variable", "sleep(6000)", "log",
	    "log-error" } )
	void firstUseEvaluationLeavesThePumpResponsive( String expression ) throws Exception {
		Path script = directory.resolve( "condition.bxs" );
		Files.writeString( script, "marker = 'conditional';\nprintln(marker);\nprintln(marker);\n" );
		var				stops	= new LinkedBlockingQueue<StoppedEventArguments>();
		var				output	= new LinkedBlockingQueue<String>();
		BoxDebugServer	server	= new BoxDebugServer();
		server.connect( new IBoxLangDebugClient() {

			@Override
			public void stopped( StoppedEventArguments event ) {
				stops.add( event );
			}

			@Override
			public void output( OutputEventArguments event ) {
				output.add( event.getOutput() );
			}
		} );
		Source source = new Source();
		source.setPath( script.toString() );
		SourceBreakpoint conditional = new SourceBreakpoint();
		conditional.setLine( 2 );
		if ( expression.startsWith( "log" ) )
			conditional.setLogMessage( expression.equals( "log" ) ? "value={marker}" : "value={missingTicket04Variable}" );
		else
			conditional.setCondition( expression );
		SourceBreakpoint sentinel = new SourceBreakpoint();
		sentinel.setLine( 3 );
		SetBreakpointsArguments args = new SetBreakpointsArguments();
		args.setSource( source );
		args.setBreakpoints( new SourceBreakpoint[] { conditional, sentinel } );
		try {
			server.setBreakpoints( args ).get( 5, TimeUnit.SECONDS );
			server.launch( Map.of( "program", script.toString() ) ).get( 10, TimeUnit.SECONDS );
			server.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );
			var stop = stops.poll( 15, TimeUnit.SECONDS );
			assertNotNull( stop, "First-use evaluation must not strand the event set" );
			StackTraceArguments stack = new StackTraceArguments();
			stack.setThreadId( stop.getThreadId() );
			var		frame	= server.stackTrace( stack ).get( 3, TimeUnit.SECONDS ).getStackFrames()[ 0 ];
			boolean	error	= expression.equals( "missingTicket04Variable" ) || expression.equals( "sleep(6000)" );
			assertEquals( expression.equals( "true" ) || error ? 2 : 3, frame.getLine() );
			if ( error )
				assertTrue( stop.getDescription().contains( "failed" ), stop.getDescription() );
			if ( expression.equals( "missingTicket04Variable" ) )
				assertTrue( stop.getDescription().toLowerCase( java.util.Locale.ROOT ).contains( "missingticket04variable" ), stop.getDescription() );
			if ( expression.equals( "log" ) )
				assertTrue( output.stream().anyMatch( text -> text.contains( "value=conditional" ) ), output.toString() );
			if ( expression.equals( "log-error" ) )
				assertTrue( output.stream().anyMatch( text -> text.contains( "<error:" ) ), output.toString() );
			EvaluateArguments eval = new EvaluateArguments();
			eval.setFrameId( frame.getId() );
			eval.setExpression( "marker" );
			assertTrue( server.evaluate( eval ).get( 5, TimeUnit.SECONDS ).getResult().contains( "conditional" ) );
		} finally {
			DisconnectArguments disconnect = new DisconnectArguments();
			disconnect.setTerminateDebuggee( true );
			server.disconnect( disconnect ).get( 2, TimeUnit.SECONDS );
		}
	}
}
