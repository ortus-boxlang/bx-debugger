package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class BoxLangStackFrameTest {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource( strings = { "boxgenerated/templates//app/handlers/Handler.cfc" } )
	void fallsBackToTheSourceNameWhenThePathIsMissingOrPackagePrefixed( String path ) {
		Source source = new Source();
		source.setName( "/app/handlers/Handler.cfc" );
		source.setPath( path );
		StackFrame frame = new StackFrame();
		frame.setName( "run" );
		frame.setSource( source );

		BoxLangStackFrame converted = BoxLangStackFrame.fromJavaFrame( frame );

		assertTrue( converted.isBoxLangFrame() );
		assertEquals( "/app/handlers/Handler.cfc", converted.getSource().getPath() );
		assertEquals( path, source.getPath() );
	}

	@Test
	void mappingAResponseDoesNotMutateTheCachedSource() {
		Source source = new Source();
		source.setName( "Handler.cfc" );
		source.setPath( "/app/handlers/Handler.cfc" );
		source.setSourceReference( 7 );
		StackFrame frame = new StackFrame();
		frame.setName( "run" );
		frame.setSource( source );
		PathMappingService mapping = new PathMappingService( "/workspace", "/app", null );

		for ( int request = 0; request < 2; request++ ) {
			BoxLangStackFrame converted = BoxLangStackFrame.fromJavaFrame( frame );
			converted.getSource().setPath( mapping.toLocalPath( converted.getSource().getPath() ) );
			assertEquals( "/workspace/handlers/Handler.cfc", converted.getSource().getPath() );
			assertEquals( 7, converted.getSource().getSourceReference() );
			assertEquals( "/app/handlers/Handler.cfc", source.getPath() );
		}
	}

	@ParameterizedTest
	@ValueSource( strings = { "cfc", "CFC", "bX", "BXS", "bxm", "cf", "cfs", "cfm", "cfml" } )
	void retainsLanguageFramesAndTheirLocations( String extension ) {
		Source source = new Source();
		source.setName( "Handler." + extension );
		source.setPath( "/app/handlers/Handler." + extension );
		StackFrame frame = new StackFrame();
		frame.setId( 42 );
		frame.setName( "run" );
		frame.setLine( 17 );
		frame.setColumn( 1 );
		frame.setSource( source );

		BoxLangStackFrame converted = BoxLangStackFrame.fromJavaFrame( frame );

		assertTrue( converted.isBoxLangFrame() );
		assertEquals( 42, converted.getId() );
		assertEquals( 17, converted.getLine() );
		assertEquals( "/app/handlers/Handler." + extension, converted.getSource().getPath() );
	}
}
