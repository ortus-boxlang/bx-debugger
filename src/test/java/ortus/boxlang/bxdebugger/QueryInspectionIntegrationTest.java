package ortus.boxlang.bxdebugger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.lsp4j.debug.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Query;
import ortus.boxlang.runtime.types.QueryColumnType;
import ortus.boxlang.runtime.types.Struct;

@Timeout( 40 )
class QueryInspectionIntegrationTest {

	@TempDir
	Path										directory;
	private BoxDebugServer						server;
	private int									frame;
	private int									thread;
	private Path								script;
	private final CompletableFuture<Boolean>	gcReleased	= new CompletableFuture<>();

	@BeforeEach
	void pause() throws Exception {
		script = directory.resolve( "query.bxs" );
		Files.writeString( script,
		    "q = createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery').init(3);\nprintln('ready');\n"
		        + "gcHelper = createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery');\n"
		        + "for (i=0; i<100 && !gcHelper.wasCollected(); i++) { gcHelper.forceGC(); sleep(50); }\n"
		        + "println('GC_COLLECTED=' & gcHelper.wasCollected());\ngcHelper.waitUntilTermination();\n" );
		server = new BoxDebugServer();
		var stops = new LinkedBlockingQueue<StoppedEventArguments>();
		server.connect( new IBoxLangDebugClient() {

			@Override
			public void output( OutputEventArguments event ) {
				if ( event.getOutput().contains( "GC_COLLECTED=" ) )
					gcReleased.complete( event.getOutput().contains( "GC_COLLECTED=true" ) );
			}

			@Override
			public void stopped( StoppedEventArguments event ) {
				stops.add( event );
			}
		} );
		Source source = new Source();
		source.setPath( script.toString() );
		SourceBreakpoint breakpoint = new SourceBreakpoint();
		breakpoint.setLine( 2 );
		SetBreakpointsArguments args = new SetBreakpointsArguments();
		args.setSource( source );
		args.setBreakpoints( new SourceBreakpoint[] { breakpoint } );
		server.setBreakpoints( args ).get( 5, TimeUnit.SECONDS );
		server.launch( Map.of( "program", script.toString() ) ).get( 10, TimeUnit.SECONDS );
		server.configurationDone( new ConfigurationDoneArguments() ).get( 5, TimeUnit.SECONDS );
		var stop = stops.poll( 10, TimeUnit.SECONDS );
		assertNotNull( stop );
		thread = stop.getThreadId();
		StackTraceArguments stack = new StackTraceArguments();
		stack.setThreadId( stop.getThreadId() );
		frame = server.stackTrace( stack ).get( 5, TimeUnit.SECONDS ).getStackFrames()[ 0 ].getId();
	}

	@AfterEach
	void cleanup() throws Exception {
		if ( server != null ) {
			DisconnectArguments args = new DisconnectArguments();
			args.setTerminateDebuggee( true );
			server.disconnect( args ).get( 3, TimeUnit.SECONDS );
		}
	}

	private EvaluateResponse evaluate( String expression ) throws Exception {
		EvaluateArguments args = new EvaluateArguments();
		args.setFrameId( frame );
		args.setExpression( expression );
		args.setContext( "watch" );
		return server.evaluate( args ).get( 10, TimeUnit.SECONDS );
	}

	private Variable[] variables( int reference, Integer start, Integer count, String filter ) throws Exception {
		VariablesArguments args = new VariablesArguments();
		args.setVariablesReference( reference );
		args.setStart( start );
		args.setCount( count );
		args.setFilter( filter == null ? null : VariablesArgumentsFilter.valueOf( filter.toUpperCase( Locale.ROOT ) ) );
		return server.variables( args ).get( 10, TimeUnit.SECONDS ).getVariables();
	}

	@Test
	void temporaryQueriesSurviveTargetGcOnlyUntilResume() throws Exception {
		String	factory	= "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery')";
		var		query	= evaluate( factory + ".ephemeralQuery()" );
		evaluate( "1" ); // Replace any incidental worker-local reference to the previous result.
		evaluate( factory + ".forceGC()" );
		assertEquals( "false", evaluate( factory + ".wasCollected()" ).getResult(), "A live handle must retain its target object" );
		var row = variables( query.getVariablesReference(), 0, 1, "indexed" )[ 0 ];
		evaluate( factory + ".forceGC()" );
		assertEquals( "1", variables( row.getVariablesReference(), 0, 1, "named" )[ 0 ].getValue() );
		Source source = new Source();
		source.setPath( script.toString() );
		SetBreakpointsArguments clear = new SetBreakpointsArguments();
		clear.setSource( source );
		clear.setBreakpoints( new SourceBreakpoint[ 0 ] );
		server.setBreakpoints( clear ).get( 5, TimeUnit.SECONDS );
		ContinueArguments resume = new ContinueArguments();
		resume.setThreadId( thread );
		server.continue_( resume ).get( 2, TimeUnit.SECONDS );
		assertTrue( gcReleased.get( 10, TimeUnit.SECONDS ), "Pins must be released after the stop ends" );
		assertThrows( ExecutionException.class, () -> variables( query.getVariablesReference(), 0, 1, null ) );
	}

	@Test
	void largeArrayAndStructPagesAvoidFullValueMaterialization() throws Exception {
		String	arrayFactory	= "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingArray')";
		var		array			= evaluate( arrayFactory + ".init(10000)" );
		var		page			= variables( array.getVariablesReference(), 50, 2, "indexed" );
		assertEquals( 2, page.length );
		assertEquals( "50", page[ 0 ].getValue() );
		assertEquals( "0", evaluate( arrayFactory + ".fullCopies()" ).getResult() );
		assertEquals( "2", evaluate( arrayFactory + ".sliceSize()" ).getResult() );
		String	structFactory	= "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingStruct')";
		var		struct			= evaluate( structFactory + ".init(10000)" );
		assertEquals( "0", evaluate( structFactory + ".valueReads()" ).getResult() );
		assertEquals( 2, variables( struct.getVariablesReference(), 50, 2, "named" ).length );
		assertEquals( "2", evaluate( structFactory + ".valueReads()" ).getResult() );
		System.out.println( "10,000-element array: full copies=0, slice size=2; struct: value reads=2" );
	}

	@Test
	void fixedQueryPagesReadOnlyRequestedRowsRegardlessOfQuerySize() throws Exception {
		for ( int size : new int[] { 3, 10000 } ) {
			var query = evaluate( "q = createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery').init(" + size + ")" );
			assertEquals( size, query.getIndexedVariables() );
			var metadata = variables( query.getVariablesReference(), null, null, "named" );
			assertEquals( 2, metadata.length );
			assertEquals( Integer.toString( size ), metadata[ 0 ].getValue() );
			assertEquals( "id, label, payload", metadata[ 1 ].getValue() );
			assertEquals( "0", evaluate( "q.getRowReads()" ).getResult(), "Summary and columns must not read rows" );
			var page = variables( query.getVariablesReference(), 1, 2, "indexed" );
			assertEquals( List.of( "2", "3" ), Arrays.stream( page ).map( Variable::getName ).toList() );
			assertEquals( "2", evaluate( "q.getRowReads()" ).getResult() );
			variables( page[ 0 ].getVariablesReference(), 0, 1, "named" );
			assertEquals( "2", evaluate( "q.getRowReads()" ).getResult(), "Cells reuse the fetched row" );
			assertEquals( 0, variables( query.getVariablesReference(), Integer.MAX_VALUE, Integer.MAX_VALUE, "indexed" ).length );
			System.out.println( "Query size=" + size + ": summary row reads=0; page size=2, row reads=2" );
		}
		var empty = evaluate( "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery').init(0)" );
		assertEquals( 0, empty.getIndexedVariables() );
		assertEquals( 0, variables( empty.getVariablesReference(), null, 0, "indexed" ).length );
		assertEquals( 2, variables( empty.getVariablesReference(), 0, 0, null ).length );
	}

	@Test
	void scopeWatchAndReplShareExpansionAndAllHandlesExpireOnResume() throws Exception {
		ScopesArguments args = new ScopesArguments();
		args.setFrameId( frame );
		Scope		scope	= Arrays.stream( server.scopes( args ).get( 5, TimeUnit.SECONDS ).getScopes() )
		    .filter( value -> value.getName().equalsIgnoreCase( "variables" ) ).findFirst().orElseThrow();
		Variable	scoped	= Arrays.stream( variables( scope.getVariablesReference(), null, null, "named" ) )
		    .filter( value -> value.getName().equalsIgnoreCase( "q" ) ).findFirst().orElseThrow();
		assertEquals( "Query", scoped.getType() );
		assertEquals( "Query", evaluate( scoped.getEvaluateName() ).getType() );
		assertEquals( 3, scoped.getIndexedVariables() );
		var references = new ArrayList<Integer>();
		references.add( scoped.getVariablesReference() );
		for ( String context : List.of( "watch", "repl" ) ) {
			EvaluateArguments eval = new EvaluateArguments();
			eval.setFrameId( frame );
			eval.setContext( context );
			eval.setExpression( "q" );
			references.add( server.evaluate( eval ).get( 5, TimeUnit.SECONDS ).getVariablesReference() );
		}
		for ( int reference : List.copyOf( references ) ) {
			var rows = variables( reference, null, null, "indexed" );
			assertEquals( 3, rows.length );
			var cells = variables( rows[ 0 ].getVariablesReference(), null, null, "named" );
			assertEquals( "1", cells[ 0 ].getValue() );
			references.add( rows[ 0 ].getVariablesReference() );
			references.add( cells[ 2 ].getVariablesReference() );
			assertEquals( 5, variables( reference, null, 0, null ).length );
			assertEquals( "1", variables( reference, 2, 1, null )[ 0 ].getName() );
		}
		ContinueArguments resume = new ContinueArguments();
		resume.setThreadId( thread );
		server.continue_( resume ).get( 5, TimeUnit.SECONDS );
		for ( int reference : references ) {
			var error = assertThrows( ExecutionException.class, () -> variables( reference, 0, 1, null ) );
			assertTrue( error.getMessage().contains( "expired" ), error.toString() );
		}
	}

	@Test
	void rowFailuresAreErrorsAndDoNotPoisonTheSession() throws Exception {
		var	query	= evaluate( "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$FailingQuery').init()" );
		var	error	= assertThrows( ExecutionException.class, () -> variables( query.getVariablesReference(), 0, 1, "indexed" ) );
		assertTrue( error.getMessage().contains( "row unavailable" ), error.toString() );
		assertEquals( "2", evaluate( "1+1" ).getResult() );
	}

	@Test
	void scalarCellsRetainTheirTypesAndNumericPrecision() throws Exception {
		var			query		= evaluate( "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery').mixedQuery()" );
		var			rows		= variables( query.getVariablesReference(), null, null, "indexed" );
		String[]	expected	= { "2", "1.25", "12345678901234567890.123400", "1234567890123456789012345", "1E+100", "false", "null" };
		for ( int i = 0; i < expected.length; i++ ) {
			var cell = variables( rows[ i ].getVariablesReference(), 0, 1, "named" )[ 0 ];
			assertEquals( expected[ i ], cell.getValue() );
			assertEquals( 0, cell.getVariablesReference() );
			assertEquals( i < 5 ? "numeric" : i == 5 ? "boolean" : "null", cell.getType() );
		}
	}

	@Test
	void namedContainersPageTheirChildrenAndExposeOnlyValidExpressions() throws Exception {
		for ( String expression : List.of( "{first:1, middle:2, last:3}",
		    "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$Plain').init()" ) ) {
			var container = evaluate( expression );
			assertEquals( 3, container.getNamedVariables() );
			var all = variables( container.getVariablesReference(), null, null, "named" );
			assertEquals( 3, all.length );
			var page = variables( container.getVariablesReference(), 1, 1, null );
			assertEquals( 1, page.length );
			assertEquals( all[ 1 ].getName(), page[ 0 ].getName() );
			assertEquals( all[ 1 ].getValue(), page[ 0 ].getValue() );
			assertEquals( 0, variables( container.getVariablesReference(), 0, 1, "indexed" ).length );
			assertEquals( 0, variables( container.getVariablesReference(), 3, 1, "named" ).length );
			for ( var value : all ) {
				if ( value.getName().equals( "hidden" ) )
					assertNull( value.getEvaluateName() );
				else
					assertEquals( value.getValue(), evaluate( value.getEvaluateName() ).getResult() );
			}
		}
		assertEquals( 0, evaluate( "createObject('java', 'java.lang.Object').init()" ).getNamedVariables() );
	}

	@Test
	void escapedColumnAndStructKeysHaveUsableEvaluateNames() throws Exception {
		var	query	= evaluate( "alias = createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery').escapedQuery()" );
		var	row		= variables( query.getVariablesReference(), 0, 1, "indexed" )[ 0 ];
		assertEquals( "Struct", evaluate( row.getEvaluateName() ).getType() );
		var cell = variables( row.getVariablesReference(), 0, 1, "named" )[ 0 ];
		assertEquals( "Struct", evaluate( cell.getEvaluateName() ).getType() );
		var value = variables( cell.getVariablesReference(), 0, 1, "named" )[ 0 ];
		assertEquals( "true", evaluate( value.getEvaluateName() ).getResult() );
		var reserved = variables( row.getVariablesReference(), 1, 1, "named" )[ 0 ];
		assertEquals( "123", evaluate( reserved.getEvaluateName() ).getResult() );
	}

	@Test
	void boxLangArraysFetchOnlyTheirRequestedSliceAndPropagateFailure() throws Exception {
		var array = evaluate( "[11, javacast('null', ''), {value: 3}, 44]" );
		assertEquals( 4, array.getIndexedVariables() );
		var page = variables( array.getVariablesReference(), 2, 1, "indexed" );
		assertEquals( 1, page.length );
		assertEquals( "3", page[ 0 ].getName() );
		assertEquals( "3", variables( page[ 0 ].getVariablesReference(), 0, 1, "named" )[ 0 ].getValue() );
		assertEquals( 2, variables( array.getVariablesReference(), 2, 0, null ).length );
		assertEquals( 0, variables( array.getVariablesReference(), 0, 2, "named" ).length );
		assertEquals( 0, variables( array.getVariablesReference(), 9, null, "indexed" ).length );
		assertThrows( ExecutionException.class, () -> variables( array.getVariablesReference(), -1, 1, null ) );
		var	failed	= evaluate( "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$FailingArray').init()" );
		var	error	= assertThrows( ExecutionException.class, () -> variables( failed.getVariablesReference(), 0, 1, "indexed" ) );
		assertTrue( error.getMessage().contains( "page unavailable" ), error.toString() );
	}

	@Test
	void nativeArraysPageNullsAndNestedValuesWithOneBasedExpressions() throws Exception {
		String	factory	= "createObject('java', 'ortus.boxlang.bxdebugger.QueryInspectionIntegrationTest$CountingQuery')";
		var		array	= evaluate( factory + ".javaValues()" );
		assertEquals( 3, array.getIndexedVariables() );
		assertEquals( 0, array.getNamedVariables() );
		var page = variables( array.getVariablesReference(), 1, 1, "indexed" );
		assertEquals( 1, page.length );
		assertEquals( "2", page[ 0 ].getName() );
		assertEquals( "null", page[ 0 ].getValue() );
		var all = variables( array.getVariablesReference(), 0, 0, null );
		assertEquals( 3, all.length );
		assertEquals( "7", evaluate( all[ 0 ].getEvaluateName() ).getResult() );
		var nested = variables( all[ 2 ].getVariablesReference(), null, null, "indexed" );
		assertEquals( "true", nested[ 0 ].getValue() );
		assertEquals( 0, nested[ 0 ].getVariablesReference() );
		assertEquals( 0, variables( array.getVariablesReference(), 8, 3, null ).length );
		assertEquals( 0, variables( array.getVariablesReference(), null, null, "named" ).length );
		var empty = evaluate( factory + ".emptyArray()" );
		assertEquals( 0, empty.getIndexedVariables() );
		assertEquals( 0, variables( empty.getVariablesReference(), null, null, null ).length );
	}

	@Test
	void queryEvaluationExpandsThroughPagedRowsToCells() throws Exception {
		var query = evaluate( "q" );
		assertEquals( "Query", query.getType() );
		assertEquals( 3, query.getIndexedVariables() );
		assertEquals( 2, query.getNamedVariables() );
		assertTrue( query.getResult().contains( "3" ) );
		var rows = variables( query.getVariablesReference(), 1, 1, "indexed" );
		assertEquals( 1, rows.length );
		assertEquals( "2", rows[ 0 ].getName() );
		assertEquals( 3, rows[ 0 ].getNamedVariables() );
		var cells = variables( rows[ 0 ].getVariablesReference(), null, null, "named" );
		assertEquals( List.of( "id", "label", "payload" ), Arrays.stream( cells ).map( Variable::getName ).toList() );
		assertEquals( "2", cells[ 0 ].getValue() );
		assertEquals( "null", cells[ 1 ].getValue() );
		assertTrue( cells[ 2 ].getVariablesReference() > 0 );
		assertEquals( "2", evaluate( cells[ 0 ].getEvaluateName() ).getResult() );
	}

	public static class CountingArray extends ortus.boxlang.runtime.types.Array {

		private static int copies, slice;

		public CountingArray( int size ) {
			super( java.util.stream.IntStream.range( 0, size ).boxed().toArray() );
			copies	= 0;
			slice	= 0;
		}

		@Override
		public Object[] toArray() {
			copies++;
			return super.toArray();
		}

		@Override
		public List<Object> subList( int start, int end ) {
			slice = end - start;
			return super.subList( start, end );
		}

		public static int fullCopies() {
			return copies;
		}

		public static int sliceSize() {
			return slice;
		}
	}

	public static class CountingStruct extends Struct {

		private static int reads;

		public CountingStruct( int size ) {
			for ( int i = 0; i < size; i++ )
				put( "key" + i, i );
			reads = 0;
		}

		@Override
		public Object get( String name ) {
			reads++;
			return super.get( name );
		}

		public static int valueReads() {
			return reads;
		}
	}

	public static class FailingQuery extends CountingQuery {

		public FailingQuery() {
			super( 1 );
		}

		@Override
		public Object[] getRow( int index ) {
			throw new IllegalStateException( "row unavailable" );
		}
	}

	public static class Plain {

		public int		number	= 7;
		public String	title	= "plain";
		private String	hidden	= "private";
	}

	public static class FailingArray extends ortus.boxlang.runtime.types.Array {

		public FailingArray() {
			super( new Object[] { 1 } );
		}

		@Override
		public List<Object> subList( int from, int to ) {
			throw new IllegalStateException( "page unavailable" );
		}
	}

	public static class CountingQuery extends Query {

		private int											rowReads;
		private static java.lang.ref.WeakReference<Query>	ephemeral;

		public static Query ephemeralQuery() {
			Query query = new CountingQuery( 3 );
			ephemeral = new java.lang.ref.WeakReference<>( query );
			return query;
		}

		public static boolean wasCollected() {
			return ephemeral == null || ephemeral.get() == null;
		}

		public static void forceGC() {
			System.gc();
		}

		public static void waitUntilTermination() throws InterruptedException {
			new CountDownLatch( 1 ).await();
		}

		public static Object[] javaValues() {
			return new Object[] { 7, null, new boolean[] { true, false } };
		}

		public static Object[] emptyArray() {
			return new Object[ 0 ];
		}

		public static Query mixedQuery() {
			Query result = new Query();
			result.addColumn( Key.of( "value" ), QueryColumnType.OBJECT );
			for ( Object value : new Object[] { ( short ) 2, 1.25f, new java.math.BigDecimal( "12345678901234567890.123400" ),
			    new java.math.BigInteger( "1234567890123456789012345" ), new java.math.BigDecimal( "1E+100" ), false, null } )
				result.addRow( new Object[] { value } );
			return result;
		}

		public static Query escapedQuery() {
			Query result = new Query();
			result.addColumn( Key.of( "odd \"quote\" #hash# .[],'" ), QueryColumnType.OBJECT );
			result.addColumn( Key.of( "recordCount" ), QueryColumnType.INTEGER );
			result.addRow( new Object[] { Struct.of( "odd \"key\" #hash# .[],'", true ), 123 } );
			return result;
		}

		public CountingQuery( int count ) {
			addColumn( Key.of( "id" ), QueryColumnType.INTEGER );
			addColumn( Key.of( "label" ), QueryColumnType.VARCHAR );
			addColumn( Key.of( "payload" ), QueryColumnType.OBJECT );
			for ( int i = 1; i <= count; i++ )
				addRow( new Object[] { i, i == 2 ? null : "row-" + i, Struct.of( "active", true ) } );
		}

		@Override
		public Object[] getRow( int index ) {
			rowReads++;
			return super.getRow( index );
		}

		public int getRowReads() {
			return rowReads;
		}
	}
}
