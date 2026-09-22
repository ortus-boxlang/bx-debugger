package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.debug.DebugLauncher;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout( 60 )
class EvaluateRequestHandlingTest {

	private IDebugProtocolServer								server;
	private Socket												clientSocket;
	private Socket												adapterSocket;
	private final LinkedBlockingQueue<BoxLangDumpEventBody>		dumps	= new LinkedBlockingQueue<>();
	private final LinkedBlockingQueue<StoppedEventArguments>	stops	= new LinkedBlockingQueue<>();
	private StackFrame[]										frames;
	private int													threadId;

	@BeforeEach
	void stopInsideFunction() throws Exception {
		BoxDebugServer adapter = new BoxDebugServer();
		adapter.setFalseExit( true );
		try ( ServerSocket listener = new ServerSocket( 0, 1, InetAddress.getLoopbackAddress() ) ) {
			clientSocket	= new Socket( listener.getInetAddress(), listener.getLocalPort() );
			adapterSocket	= listener.accept();
		}
		IBoxLangDebugClient	client			= new IBoxLangDebugClient() {

												@Override
												public void stopped( StoppedEventArguments event ) {
													stops.add( event );
												}

												@Override
												public void boxlangDump( BoxLangDumpEventBody event ) {
													dumps.add( event );
												}
											};
		var					adapterLauncher	= DebugLauncher.createLauncher( adapter, IBoxLangDebugClient.class,
		    adapterSocket.getInputStream(), adapterSocket.getOutputStream() );
		adapter.connect( adapterLauncher.getRemoteProxy() );
		adapterLauncher.startListening();
		var clientLauncher = DebugLauncher.createLauncher( client, IDebugProtocolServer.class,
		    clientSocket.getInputStream(), clientSocket.getOutputStream() );
		clientLauncher.startListening();
		server = clientLauncher.getRemoteProxy();
		Path	program	= Path.of( "src/test/resources/ticket01-frames.bxs" ).toAbsolutePath();
		Source	source	= new Source();
		source.setPath( program.toString() );
		SourceBreakpoint breakpoint = new SourceBreakpoint();
		breakpoint.setLine( 2 );
		SetBreakpointsArguments breakpoints = new SetBreakpointsArguments();
		breakpoints.setSource( source );
		breakpoints.setBreakpoints( new SourceBreakpoint[] { breakpoint } );
		server.setBreakpoints( breakpoints ).get( 10, TimeUnit.SECONDS );
		Path workspace = Path.of( "build/ticket01-workspace" ).toAbsolutePath();
		server.launch( Map.of( "program", program.toString(), "localRoot", workspace.toString(),
		    "remoteRoot", program.getParent().toString() ) ).get( 15, TimeUnit.SECONDS );
		server.configurationDone( new ConfigurationDoneArguments() ).get( 10, TimeUnit.SECONDS );
		StoppedEventArguments stop = stops.poll( 15, TimeUnit.SECONDS );
		assertNotNull( stop, "Expected the bootstrap stop after loading the CFC" );
		// The bootstrap stop is one-shot, even if its line has multiple executable locations.
		breakpoints.setBreakpoints( new SourceBreakpoint[ 0 ] );
		server.setBreakpoints( breakpoints ).get( 10, TimeUnit.SECONDS );
		// Bind after class loading to isolate these tests from class-prepare races (ticket 03).
		source.setPath( workspace.resolve( "Ticket01Frames.cfc" ).toString() );
		SourceBreakpoint cfcBreakpoint = new SourceBreakpoint();
		cfcBreakpoint.setLine( 9 );
		breakpoints.setBreakpoints( new SourceBreakpoint[] { cfcBreakpoint } );
		server.setBreakpoints( breakpoints ).get( 10, TimeUnit.SECONDS );
		ContinueArguments resume = new ContinueArguments();
		resume.setThreadId( stop.getThreadId() );
		server.continue_( resume ).get( 5, TimeUnit.SECONDS );
		stop = stops.poll( 15, TimeUnit.SECONDS );
		assertNotNull( stop, "Expected a real CFC breakpoint stop" );
		StackTraceArguments stack = new StackTraceArguments();
		threadId = stop.getThreadId();
		stack.setThreadId( threadId );
		frames = server.stackTrace( stack ).get( 5, TimeUnit.SECONDS ).getStackFrames();
		assertTrue( frames.length > 0 );
		assertEquals( 9, frames[ 0 ].getLine() );
		assertEquals( source.getPath(), frames[ 0 ].getSource().getPath() );
		StackFrame[] repeated = server.stackTrace( stack ).get( 5, TimeUnit.SECONDS ).getStackFrames();
		assertEquals( source.getPath(), repeated[ 0 ].getSource().getPath() );
	}

	@AfterEach
	void disconnect() throws Exception {
		DisconnectArguments args = new DisconnectArguments();
		args.setTerminateDebuggee( true );
		try {
			if ( server != null ) {
				server.disconnect( args ).get( 10, TimeUnit.SECONDS );
			}
		} finally {
			if ( clientSocket != null )
				clientSocket.close();
			if ( adapterSocket != null )
				adapterSocket.close();
		}
	}

	@ParameterizedTest
	@ValueSource( strings = { "watch", "repl" } )
	void evaluatedStructCanBeExpandedIntoItsValues( String context ) throws Exception {
		EvaluateResponse result = evaluate( frames[ 0 ].getId(), "payload", context );
		assertTrue( result.getVariablesReference() > 0, "Struct evaluation must expose its children" );
		assertEquals( "Struct", result.getType() );
		Variable answer = Arrays.stream( variables( result.getVariablesReference() ) )
		    .filter( value -> value.getName().equalsIgnoreCase( "answer" ) ).findFirst().orElseThrow();
		assertEquals( "42", answer.getValue() );
	}

	@Test
	void evaluatedArraysAndScalarsExposeAppropriateReferences() throws Exception {
		EvaluateResponse array = evaluate( frames[ 0 ].getId(), "payload.items" );
		assertEquals( "array", array.getType() );
		assertTrue( array.getVariablesReference() > 0 );
		Variable[] items = variables( array.getVariablesReference() );
		assertEquals( 2, items.length );
		assertEquals( "1", items[ 0 ].getName() );
		assertEquals( "\"one\"", items[ 0 ].getValue() );
		assertEquals( "payload.items[1]", items[ 0 ].getEvaluateName() );
		assertEquals( "\"two\"", items[ 1 ].getValue() );

		EvaluateResponse scalar = evaluate( frames[ 0 ].getId(), "payload.answer" );
		assertEquals( "numeric", scalar.getType() );
		assertEquals( "42", scalar.getResult() );
		assertEquals( 0, scalar.getVariablesReference() );
		EvaluateResponse nil = evaluate( frames[ 0 ].getId(), "null" );
		assertEquals( "null", nil.getType() );
		assertEquals( "null", nil.getResult() );
		assertEquals( 0, nil.getVariablesReference() );
	}

	@Test
	void evaluationUsesTheSelectedCallerFrame() throws Exception {
		StackFrame caller = Arrays.stream( frames ).filter( frame -> frame.getLine() == 4 ).findFirst().orElseThrow();
		assertEquals( "\"caller\"", evaluate( caller.getId(), "marker" ).getResult() );
		assertEquals( "\"callee\"", evaluate( frames[ 0 ].getId(), "marker" ).getResult() );
	}

	@ParameterizedTest
	@NullSource
	@ValueSource( ints = { -1, Integer.MAX_VALUE } )
	void missingOrUnknownFramesFailExplicitly( Integer frameId ) throws Exception {
		ExecutionException error = assertThrows( ExecutionException.class, () -> evaluate( frameId, "marker" ) );
		assertEvaluationFailure( error, "frame" );
		assertEquals( "\"callee\"", evaluate( frames[ 0 ].getId(), "marker" ).getResult() );
		ContinueArguments resume = new ContinueArguments();
		resume.setThreadId( threadId );
		server.continue_( resume ).get( 5, TimeUnit.SECONDS );
	}

	@Test
	void aResumedFrameCannotBeEvaluated() throws Exception {
		ContinueArguments args = new ContinueArguments();
		args.setThreadId( threadId );
		server.continue_( args ).get( 5, TimeUnit.SECONDS );
		ExecutionException error = assertThrows( ExecutionException.class, () -> evaluate( frames[ 0 ].getId(), "marker" ) );
		assertEvaluationFailure( error, "frame" );
	}

	@Test
	void continueInvalidatesFramesAndVariableChildren() throws Exception {
		int				ref			= evaluate( frames[ 0 ].getId(), "payload" ).getVariablesReference();
		Variable[]		children	= variables( ref );
		int				childRef	= Arrays.stream( children ).filter( v -> v.getVariablesReference() > 0 )
		    .findFirst().orElseThrow().getVariablesReference();
		ScopesArguments	scopes		= new ScopesArguments();
		scopes.setFrameId( frames[ 0 ].getId() );
		int					scopeRef	= server.scopes( scopes ).get( 10, TimeUnit.SECONDS ).getScopes()[ 0 ].getVariablesReference();
		ContinueArguments	resume		= new ContinueArguments();
		resume.setThreadId( threadId );
		resume.setSingleThread( true );
		server.continue_( resume ).get( 5, TimeUnit.SECONDS );
		for ( int expired : new int[] { ref, childRef, scopeRef } ) {
			ExecutionException error = assertThrows( ExecutionException.class, () -> variables( expired ) );
			assertInstanceOf( ResponseErrorException.class, error.getCause() );
			assertTrue( error.getCause().getMessage().contains( "reference" ) );
		}
		assertThrows( ExecutionException.class, () -> server.scopes( scopes ).get( 5, TimeUnit.SECONDS ) );
		StackTraceArguments stack = new StackTraceArguments();
		stack.setThreadId( threadId );
		assertTrue( Arrays.stream( server.stackTrace( stack ).get( 5, TimeUnit.SECONDS ).getStackFrames() )
		    .noneMatch( frame -> frame.getId() == frames[ 0 ].getId() ), "A subsequent stop must not reuse the resumed frame" );
	}

	@ParameterizedTest
	@ValueSource( strings = { "expiredFrame", "missingVariable" } )
	void failedDumpDoesNotEmitSuccessAndSessionRemainsUsable( String failure ) throws Exception {
		boolean				expired	= failure.equals( "expiredFrame" );
		ExecutionException	error	= assertThrows( ExecutionException.class,
		    () -> evaluate( expired ? -1 : frames[ 0 ].getId(),
		        expired ? "writeDump(marker)" : "writeDump(nonexistentTicket01Variable)", "repl" ) );
		assertEvaluationFailure( error, expired ? "frame" : "Dump produced no HTML" );
		assertTrue( dumps.isEmpty(), "Failed dumps must not emit a success event" );
		assertEquals( "\"callee\"", evaluate( frames[ 0 ].getId(), "marker" ).getResult() );
		EvaluateResponse response = evaluate( frames[ 0 ].getId(), "writeDump(marker)", "repl" );
		assertTrue( response.getResult().contains( "dumped" ) );
		BoxLangDumpEventBody dump = dumps.poll( 5, TimeUnit.SECONDS );
		assertNotNull( dump );
		assertTrue( dump.getHtml().contains( "callee" ) );
		assertTrue( dumps.isEmpty() );
	}

	private void assertEvaluationFailure( ExecutionException error, String detail ) {
		ResponseError response = assertInstanceOf( ResponseErrorException.class, error.getCause() ).getResponseError();
		assertTrue( response.getMessage().startsWith( "Unable to evaluate expression: " ), response.getMessage() );
		assertTrue( response.getMessage().contains( detail ), response.getMessage() );
	}

	private EvaluateResponse evaluate( Integer frameId, String expression ) throws Exception {
		return evaluate( frameId, expression, "watch" );
	}

	private EvaluateResponse evaluate( Integer frameId, String expression, String context ) throws Exception {
		EvaluateArguments args = new EvaluateArguments();
		args.setFrameId( frameId );
		args.setExpression( expression );
		args.setContext( context );
		return server.evaluate( args ).get( 10, TimeUnit.SECONDS );
	}

	private Variable[] variables( int reference ) throws Exception {
		VariablesArguments args = new VariablesArguments();
		args.setVariablesReference( reference );
		return server.variables( args ).get( 10, TimeUnit.SECONDS ).getVariables();
	}
}
