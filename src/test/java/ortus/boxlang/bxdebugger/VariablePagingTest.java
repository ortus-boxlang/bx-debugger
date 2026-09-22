package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.eclipse.lsp4j.debug.VariablesArgumentsFilter;
import org.junit.jupiter.api.Test;
import com.sun.jdi.*;

class VariablePagingTest {

	@Test
	void nativePagesUseOneBulkReadAndNeverReadUnrequestedValues() {
		VariableManager	manager		= new VariableManager( new VMController( null, null ) );
		ArrayReference	array		= mock( ArrayReference.class );
		ArrayType		arrayType	= mock( ArrayType.class );
		when( array.type() ).thenReturn( arrayType );
		when( array.length() ).thenReturn( 10000 );
		IntegerValue number = mock( IntegerValue.class );
		when( number.intValue() ).thenReturn( 51 );
		when( array.getValues( 50, 2 ) ).thenReturn( Arrays.asList( number, null ) );
		var page = manager.getVariablesFor( manager.put( array, "items" ), 50, 2, VariablesArgumentsFilter.INDEXED );
		assertEquals( List.of( "51", "52" ), page.stream().map( value -> value.getName() ).toList() );
		assertEquals( "items[51]", page.get( 0 ).getEvaluateName() );
		assertEquals( "null", page.get( 1 ).getValue() );
		verify( array ).getValues( 50, 2 );
		verify( array, never() ).getValue( anyInt() );
		verify( array, never() ).getValues();

		ObjectReference	object	= mock( ObjectReference.class );
		ClassType		type	= mock( ClassType.class );
		when( object.type() ).thenReturn( type );
		when( object.referenceType() ).thenReturn( type );
		when( type.name() ).thenReturn( "Example" );
		Field	first	= mock( Field.class );
		Field	second	= mock( Field.class );
		when( second.name() ).thenReturn( "second" );
		when( second.isPublic() ).thenReturn( true );
		when( type.allFields() ).thenReturn( List.of( first, second ) );
		when( object.getValues( List.of( second ) ) ).thenReturn( Map.of( second, number ) );
		var fields = manager.getVariablesFor( manager.put( object, "" ), 1, 1, VariablesArgumentsFilter.NAMED );
		assertEquals( 1, fields.size() );
		assertEquals( "51", fields.get( 0 ).getValue() );
		assertNull( fields.get( 0 ).getEvaluateName() );
		verify( object ).getValues( List.of( second ) );
		verify( object, never() ).getValue( any() );
		manager.clear();
	}
}
