package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.debug.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout( 60 )
class ConcurrentStopIntegrationTest {

	private final BoxDebugServer								server		= new BoxDebugServer();
	private final LinkedBlockingQueue<StoppedEventArguments>	stops		= new LinkedBlockingQueue<>();
	private final LinkedBlockingQueue<ContinuedEventArguments>	continued	= new LinkedBlockingQueue<>();

	@Test
	void independentWorkerStopsKeepTheirOwnVariablesThroughContinueAndStep() throws Exception {
		server.setFalseExit( true );
		server.connect( new IBoxLangDebugClient() {

			@Override
			public void stopped( StoppedEventArguments event ) {
				stops.add( event );
			}

			@Override
			public void continued( ContinuedEventArguments event ) {
				continued.add( event );
			}
		} );
		try {
			assertEquals( true, server.initialize( new InitializeRequestArguments() ).get( 5, TimeUnit.SECONDS )
			    .getSupportsSingleThreadExecutionRequests() );
			Path program = Path.of( "src/test/resources/ticket02-threads.bxs" ).toAbsolutePath();
			setBreakpoints( program, 2 );
			server.launch( Map.of( "program", program.toString() ) ).get( 15, TimeUnit.SECONDS );
			server.configurationDone( new ConfigurationDoneArguments() ).get( 10, TimeUnit.SECONDS );
			int bootstrap = stoppedThread();
			setBreakpoints( program.resolveSibling( "Ticket02Threads.cfc" ), 5 );
			resume( bootstrap, false );
			continued.clear();
			int	first	= stoppedThread();
			int	second	= stoppedThread();
			assertNotEquals( first, second );
			int	firstFrame	= frame( first );
			int	secondFrame	= frame( second );
			assertNotEquals( firstFrame, secondFrame );
			int		firstRef	= evaluate( firstFrame, "payload" ).getVariablesReference();
			int		secondRef	= evaluate( secondFrame, "payload" ).getVariablesReference();
			String	firstLabel	= label( firstRef );
			String	secondLabel	= label( secondRef );
			assertNotEquals( firstLabel, secondLabel );

			assertEquals( false, resume( first, true ).getAllThreadsContinued() );
			assertEquals( false, continued.poll( 5, TimeUnit.SECONDS ).getAllThreadsContinued() );
			assertEquals( first, stoppedThread() );
			assertThrows( ExecutionException.class, () -> variables( firstRef ) );
			assertThrows( ExecutionException.class, () -> evaluate( firstFrame, "payload" ) );
			assertEquals( secondLabel, label( secondRef ) );
			int newFrame = frame( first );
			assertNotEquals( firstFrame, newFrame );
			assertEquals( firstLabel, label( evaluate( newFrame, "payload" ).getVariablesReference() ) );

			NextArguments step = new NextArguments();
			step.setThreadId( first );
			step.setSingleThread( true );
			server.next( step ).get( 5, TimeUnit.SECONDS );
			assertEquals( false, continued.poll( 5, TimeUnit.SECONDS ).getAllThreadsContinued() );
			assertEquals( first, stoppedThread() );
			assertThrows( ExecutionException.class, () -> evaluate( newFrame, "payload" ) );
			assertEquals( secondLabel, label( secondRef ) );

			setBreakpoints( program.resolveSibling( "Ticket02Threads.cfc" ) );
			assertEquals( true, resume( first, false ).getAllThreadsContinued() );
			assertEquals( true, continued.poll( 5, TimeUnit.SECONDS ).getAllThreadsContinued() );
			assertThrows( ExecutionException.class, () -> variables( secondRef ) );
			assertThrows( ExecutionException.class, () -> evaluate( secondFrame, "payload" ) );
		} finally {
			DisconnectArguments disconnect = new DisconnectArguments();
			disconnect.setTerminateDebuggee( true );
			server.disconnect( disconnect ).get( 10, TimeUnit.SECONDS );
		}
	}

	private int stoppedThread() throws Exception {
		StoppedEventArguments stop = stops.poll( 10, TimeUnit.SECONDS );
		assertNotNull( stop, "Expected a worker breakpoint/step stop" );
		return stop.getThreadId();
	}

	private int frame( int thread ) throws Exception {
		StackTraceArguments args = new StackTraceArguments();
		args.setThreadId( thread );
		return server.stackTrace( args ).get( 5, TimeUnit.SECONDS ).getStackFrames()[ 0 ].getId();
	}

	private void setBreakpoints( Path file, int... lines ) throws Exception {
		Source source = new Source();
		source.setPath( file.toString() );
		SetBreakpointsArguments args = new SetBreakpointsArguments();
		args.setSource( source );
		args.setBreakpoints( Arrays.stream( lines ).mapToObj( line -> {
			SourceBreakpoint bp = new SourceBreakpoint();
			bp.setLine( line );
			return bp;
		} ).toArray( SourceBreakpoint[]::new ) );
		server.setBreakpoints( args ).get( 10, TimeUnit.SECONDS );
	}

	private ContinueResponse resume( int thread, boolean single ) throws Exception {
		ContinueArguments args = new ContinueArguments();
		args.setThreadId( thread );
		args.setSingleThread( single );
		return server.continue_( args ).get( 5, TimeUnit.SECONDS );
	}

	private EvaluateResponse evaluate( int frame, String expression ) throws Exception {
		EvaluateArguments args = new EvaluateArguments();
		args.setFrameId( frame );
		args.setExpression( expression );
		return server.evaluate( args ).get( 10, TimeUnit.SECONDS );
	}

	private Variable[] variables( int reference ) throws Exception {
		VariablesArguments args = new VariablesArguments();
		args.setVariablesReference( reference );
		return server.variables( args ).get( 10, TimeUnit.SECONDS ).getVariables();
	}

	private String label( int reference ) throws Exception {
		return Arrays.stream( variables( reference ) ).filter( v -> v.getName().equalsIgnoreCase( "label" ) ).findFirst().orElseThrow().getValue();
	}
}
