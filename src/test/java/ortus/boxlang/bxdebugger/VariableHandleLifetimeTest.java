package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.stream.IntStream;

import com.sun.jdi.ClassType;
import com.sun.jdi.ObjectReference;
import org.junit.jupiter.api.Test;

class VariableHandleLifetimeTest {

	@Test
	void parallelAllocationsAcrossStopsHaveUniqueReferences() {
		VariableManager	first	= new VariableManager( new VMController( null, null ) );
		VariableManager	second	= new VariableManager( new VMController( null, null ) );
		ObjectReference	value	= mock( ObjectReference.class );
		int[]			ids		= IntStream.range( 0, 1000 ).parallel()
		    .map( i -> ( i % 2 == 0 ? first : second ).put( value, "value" ) ).toArray();
		assertEquals( ids.length, IntStream.of( ids ).distinct().count() );
		for ( int i = 0; i < ids.length; i++ ) {
			assertSame( value, ( i % 2 == 0 ? first : second ).getValue( ids[ i ] ) );
		}
		first.clear();
		assertThrows( IllegalArgumentException.class, () -> first.put( value ) );
		assertNotNull( second.getValue( ids[ 1 ] ) );
	}

	@Test
	void handlesSurviveGarbageCollectionUntilExplicitlyCleared() throws Exception {
		VariableManager	variables	= new VariableManager( new VMController( null, null ) );
		ObjectReference	value		= mock( ObjectReference.class );
		ClassType		type		= mock( ClassType.class );
		when( value.type() ).thenReturn( type );
		when( type.name() ).thenReturn( "example.Object" );
		when( type.allInterfaces() ).thenReturn( List.of() );
		when( value.referenceType() ).thenReturn( type );
		when( type.allFields() ).thenReturn( List.of() );
		int[]					ids			= IntStream.range( 0, 256 ).map( i -> variables.put( value, "value" ) ).toArray();
		WeakReference<Object>	sentinel	= new WeakReference<>( new Object() );
		for ( int i = 0; i < 50 && sentinel.get() != null; i++ ) {
			System.gc();
			Thread.sleep( 10 );
		}
		assertNull( sentinel.get(), "Test requires a collection to exercise weak-key loss" );
		for ( int id : ids ) {
			assertSame( value, variables.getValue( id ) );
			assertNotNull( variables.getVariablesFor( id ) );
		}
		variables.clear();
		assertNull( variables.getValue( ids[ 255 ] ) );
		verify( value ).disableCollection();
		verify( value, timeout( 2000 ) ).enableCollection();
	}
}
