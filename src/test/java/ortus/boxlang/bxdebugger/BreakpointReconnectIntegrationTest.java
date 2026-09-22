package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.*;

import org.eclipse.lsp4j.debug.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout( 90 )
class BreakpointReconnectIntegrationTest {
	@TempDir Path directory;

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource( booleans = { false, true } )
	void reattachesToTheSameHttpApplicationBeforeAndAfterRecompilation( boolean cfc ) throws Exception {
		Path entry = directory.resolve( "page.bxs" );
		Path template = cfc ? directory.resolve( "Handler.cfc" ) : entry;
		String code = cfc ? "component {\nfunction run() {\nmarker = 'original';\nprintln(marker);\n}\n}\n"
		    : "marker = 'original';\nprintln(marker);\n";
		Files.writeString( template, code );
		if ( cfc ) Files.writeString( entry, "new Handler().run();\n" );
		int stopLine = cfc ? 4 : 2;
		int jdwp;
		try ( ServerSocket port = new ServerSocket( 0 ) ) { jdwp = port.getLocalPort(); }
		Process process = new ProcessBuilder(
		    Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString(),
		    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + jdwp,
		    "-cp", System.getProperty( "java.class.path" ), WebApp.class.getName(), entry.toString() )
		    .redirectErrorStream( true ).start();
		CompletableFuture<Integer> httpPort = new CompletableFuture<>();
		CompletableFuture.runAsync( () -> {
			try ( var reader = process.inputReader() ) {
				String line;
				while ( ( line = reader.readLine() ) != null ) {
					if ( line.startsWith( "HTTP_PORT=" ) ) httpPort.complete( Integer.parseInt( line.substring( 10 ) ) );
					System.out.println( "[web-app] " + line );
				}
			} catch ( Exception e ) { httpPort.completeExceptionally( e ); }
		} );
		try {
			URI uri = URI.create( "http://127.0.0.1:" + httpPort.get( 25, TimeUnit.SECONDS ) + "/" );
			try ( HttpClient http = HttpClient.newHttpClient() ) {
				var request = HttpRequest.newBuilder( uri ).timeout( java.time.Duration.ofSeconds( 20 ) ).build();
				assertEquals( 200, http.send( request, HttpResponse.BodyHandlers.ofString() ).statusCode() );
				for ( int session = 0; session < 3; session++ ) {
					var stops = new LinkedBlockingQueue<StoppedEventArguments>();
					BoxDebugServer server = new BoxDebugServer();
					server.setFalseExit( true );
					server.connect( new IBoxLangDebugClient() {
						@Override public void stopped( StoppedEventArguments event ) { stops.add( event ); }
					} );
					try {
						server.attach( Map.of( "serverPort", jdwp ) ).get( 15, TimeUnit.SECONDS );
						Source source = new Source();
						source.setPath( template.toString() );
						SourceBreakpoint line = new SourceBreakpoint();
						line.setLine( stopLine );
						SetBreakpointsArguments breakpoints = new SetBreakpointsArguments();
						breakpoints.setSource( source );
						breakpoints.setBreakpoints( new SourceBreakpoint[] { line } );
						var bound = server.setBreakpoints( breakpoints ).get( 5, TimeUnit.SECONDS ).getBreakpoints()[ 0 ];
						assertTrue( bound.isVerified(), "Loaded template should bind on attach" );
						server.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );
						if ( session == 2 ) {
							Files.writeString( template, code.replace( "original", "recompiled" ) );
							Files.setLastModifiedTime( template, FileTime.fromMillis( System.currentTimeMillis() + 2000 ) );
						}
						var response = http.sendAsync( request, HttpResponse.BodyHandlers.ofString() );
						var stop = stops.poll( 15, TimeUnit.SECONDS );
						assertNotNull( stop, "Expected an actual breakpoint stop in session " + session );
						StackTraceArguments stack = new StackTraceArguments();
						stack.setThreadId( stop.getThreadId() );
						var frame = server.stackTrace( stack ).get( 5, TimeUnit.SECONDS ).getStackFrames()[ 0 ];
						assertEquals( template.toString(), frame.getSource().getPath() );
						assertEquals( stopLine, frame.getLine() );
						EvaluateArguments evaluate = new EvaluateArguments();
						evaluate.setFrameId( frame.getId() );
						evaluate.setExpression( "marker" );
						assertTrue( server.evaluate( evaluate ).get( 10, TimeUnit.SECONDS ).getResult()
						    .contains( session == 2 ? "recompiled" : "original" ) );
						breakpoints.setBreakpoints( new SourceBreakpoint[ 0 ] );
						server.setBreakpoints( breakpoints ).get( 5, TimeUnit.SECONDS );
						ContinueArguments resume = new ContinueArguments();
						resume.setThreadId( stop.getThreadId() );
						server.continue_( resume ).get( 5, TimeUnit.SECONDS );
						assertEquals( 200, response.get( 10, TimeUnit.SECONDS ).statusCode() );
					} finally {
						DisconnectArguments detach = new DisconnectArguments();
						detach.setTerminateDebuggee( false );
						server.disconnect( detach ).get( 10, TimeUnit.SECONDS );
					}
					assertTrue( process.isAlive(), "Detach must leave the same application running" );
				}
			}
		} finally {
			process.destroyForcibly();
			process.waitFor( 10, TimeUnit.SECONDS );
		}
	}

	public static class WebApp {
		public static void main( String[] args ) throws Exception {
			var runtime = ortus.boxlang.runtime.BoxRuntime.getInstance( true );
			var http = com.sun.net.httpserver.HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
			http.createContext( "/", exchange -> {
				try {
					runtime.executeTemplate( args[ 0 ] );
					exchange.sendResponseHeaders( 200, -1 );
				} catch ( Exception error ) {
					error.printStackTrace();
					exchange.sendResponseHeaders( 500, -1 );
				} finally { exchange.close(); }
			} );
			http.start();
			System.out.println( "HTTP_PORT=" + http.getAddress().getPort() );
		}
	}
}
