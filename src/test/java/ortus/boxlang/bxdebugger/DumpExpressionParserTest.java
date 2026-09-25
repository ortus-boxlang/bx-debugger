package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class DumpExpressionParserTest {

	private DumpExpressionParser parser;

	@BeforeEach
	void setUp() {
		parser = new DumpExpressionParser();
	}

	// -------------------------------------------------------------------------
	// isDumpCall
	// -------------------------------------------------------------------------

	@Test
	@DisplayName( "isDumpCall returns true for writeDump( var )" )
	void isDumpCall_writeDump_positional() {
		assertTrue( parser.isDumpCall( "writeDump( myVar )" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns true for dump( var )" )
	void isDumpCall_dump_positional() {
		assertTrue( parser.isDumpCall( "dump( myVar )" ) );
	}

	@Test
	@DisplayName( "isDumpCall is case-insensitive" )
	void isDumpCall_caseInsensitive() {
		assertTrue( parser.isDumpCall( "WRITEDUMP( myVar )" ) );
		assertTrue( parser.isDumpCall( "WriteDump( myVar )" ) );
		assertTrue( parser.isDumpCall( "DUMP( myVar )" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns true for writeDump with named var argument" )
	void isDumpCall_namedVarArg() {
		assertTrue( parser.isDumpCall( "writeDump( var=myStruct )" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns true for writeDump with multiple named args" )
	void isDumpCall_multipleNamedArgs() {
		assertTrue( parser.isDumpCall( "writeDump( var=myStruct, top=3 )" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns false for a plain variable" )
	void isDumpCall_plainVar() {
		assertFalse( parser.isDumpCall( "myVar" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns false for a different function" )
	void isDumpCall_otherFunction() {
		assertFalse( parser.isDumpCall( "someFunc( myVar )" ) );
	}

	@Test
	@DisplayName( "isDumpCall returns false for null" )
	void isDumpCall_null() {
		assertFalse( parser.isDumpCall( null ) );
	}

	@Test
	@DisplayName( "isDumpCall returns false for blank string" )
	void isDumpCall_blank() {
		assertFalse( parser.isDumpCall( "   " ) );
	}

	// -------------------------------------------------------------------------
	// extractLabel
	// -------------------------------------------------------------------------

	@Test
	@DisplayName( "extractLabel returns positional arg as-is" )
	void extractLabel_positional() {
		assertEquals( "myVar", parser.extractLabel( "writeDump( myVar )" ) );
	}

	@Test
	@DisplayName( "extractLabel returns named var arg value" )
	void extractLabel_namedVar() {
		assertEquals( "myStruct", parser.extractLabel( "writeDump( var=myStruct )" ) );
	}

	@Test
	@DisplayName( "extractLabel handles complex expression" )
	void extractLabel_complexExpression() {
		assertEquals( "getPageContext()", parser.extractLabel( "writeDump( getPageContext() )" ) );
	}

	@Test
	@DisplayName( "extractLabel extracts var arg when other named args are present" )
	void extractLabel_namedVarWithOtherArgs() {
		assertEquals( "myStruct", parser.extractLabel( "writeDump( var=myStruct, top=3 )" ) );
	}

	@Test
	@DisplayName( "extractLabel handles dot-notation expression" )
	void extractLabel_dotNotation() {
		assertEquals( "myStruct.nested", parser.extractLabel( "writeDump( myStruct.nested )" ) );
	}

	// -------------------------------------------------------------------------
	// extractTopArg
	// -------------------------------------------------------------------------

	@Test
	@DisplayName( "extractTopArg returns empty when no top argument" )
	void extractTopArg_absent() {
		assertEquals( Optional.empty(), parser.extractTopArg( "writeDump( myVar )" ) );
	}

	@Test
	@DisplayName( "extractTopArg returns value when top argument is present" )
	void extractTopArg_present() {
		assertEquals( Optional.of( 3 ), parser.extractTopArg( "writeDump( var=myVar, top=3 )" ) );
	}

	@Test
	@DisplayName( "extractTopArg is case-insensitive for argument name" )
	void extractTopArg_caseInsensitive() {
		assertEquals( Optional.of( 5 ), parser.extractTopArg( "writeDump( var=myVar, TOP=5 )" ) );
	}

	// -------------------------------------------------------------------------
	// extractVarExpression
	// -------------------------------------------------------------------------

	@Test
	@DisplayName( "extractVarExpression returns positional arg" )
	void extractVarExpression_positional() {
		assertEquals( Optional.of( "myVar" ), parser.extractVarExpression( "writeDump( myVar )" ) );
	}

	@Test
	@DisplayName( "extractVarExpression returns named var arg value" )
	void extractVarExpression_namedVar() {
		assertEquals( Optional.of( "myStruct" ), parser.extractVarExpression( "writeDump( var=myStruct, top=2 )" ) );
	}

	@Test
	@DisplayName( "extractVarExpression returns empty for non-dump expression" )
	void extractVarExpression_nonDump() {
		assertEquals( Optional.empty(), parser.extractVarExpression( "myVar" ) );
	}

	@Test
	@DisplayName( "extractVarExpression returns empty for null" )
	void extractVarExpression_null() {
		assertEquals( Optional.empty(), parser.extractVarExpression( null ) );
	}
}
