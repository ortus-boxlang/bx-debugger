package ortus.boxlang.bxdebugger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArgumentsFilter;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import ortus.boxlang.runtime.dynamic.casters.StringCaster;

public class VariableManager {

	private static final Logger					LOGGER			= Logger.getLogger( VariableManager.class.getName() );
	private static final AtomicInteger			variableIds		= new AtomicInteger();
	private static final java.util.Set<String>	BOXED_TYPES		= java.util.Set.of( "java.lang.Boolean", "java.lang.Character", "java.lang.Byte",
	    "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double" );
	private VMController						vmController;
	private Map<Integer, Value>					variables		= new ConcurrentHashMap<>();
	private Map<Integer, String>				evaluateNames	= new ConcurrentHashMap<>();
	private volatile boolean					expired;

	private record QueryRow( List<String> columns, String expression, int number ) {
	}

	private final Map<Integer, QueryRow>			queryRows	= new ConcurrentHashMap<>();
	private final java.util.Set<ObjectReference>	retained	= new java.util.HashSet<>();

	synchronized void retain( Value value ) {
		checkActive();
		if ( value instanceof ObjectReference object && !retained.contains( object ) ) {
			object.disableCollection();
			retained.add( object );
		}
	}

	public VariableManager( VMController vmController ) {
		this.vmController = vmController;
	}

	public int put( Value value ) {
		return put( value, null );
	}

	public synchronized int put( Value value, String evaluateName ) {
		retain( value );
		int variableId = variableIds.incrementAndGet();
		variables.put( variableId, value );
		if ( evaluateName != null ) {
			evaluateNames.put( variableId, evaluateName );
		}
		return variableId;
	}

	public Value getValue( int id ) {
		return variables.get( id );
	}

	public boolean contains( int id ) {
		return !expired && variables.containsKey( id );
	}

	public void checkActive() {
		if ( expired ) {
			throw new IllegalArgumentException( "Expired variables reference: thread has resumed" );
		}
	}

	public List<Variable> getVariablesFor( int id ) {
		return getVariablesFor( id, null, null, null );
	}

	public List<Variable> getVariablesFor( int id, Integer start, Integer count, VariablesArgumentsFilter filter ) {
		checkActive();
		int	offset	= start == null ? 0 : start;
		int	limit	= count == null ? 0 : count;
		if ( offset < 0 || limit < 0 )
			throw new IllegalArgumentException( "Variable start and count must be non-negative" );
		var variable = variables.get( id );
		if ( variable == null ) {
			throw new IllegalArgumentException( "Unknown or expired variables reference " + id );
		}
		String			parentEvaluateName	= evaluateNames.get( id );

		List<Variable>	result				= List.of();
		QueryRow		row					= queryRows.get( id );
		if ( row != null ) {
			result = queryCells( ( ArrayReference ) variable, row, offset, limit, filter );
		} else if ( isQuery( variable ) ) {
			result = queryVariables( ( ObjectReference ) variable, parentEvaluateName, offset, limit, filter );
		} else if ( variable instanceof ArrayReference array ) {
			result = nativeArrayVariables( array, parentEvaluateName, offset, limit, filter );
		} else if ( isStruct( variable ) ) {
			result = gerVariablesFromStruct( ( ObjectReference ) variable, parentEvaluateName, offset, limit, filter );
		} else if ( isArray( variable ) ) {
			result = gerVariablesFromArray( ( ObjectReference ) variable, parentEvaluateName, offset, limit, filter );
		} else if ( isPOJO( variable ) ) {
			result = gerVariablesFromPojo( ( ObjectReference ) variable, parentEvaluateName, offset, limit, filter );
		}
		checkActive();
		return result;
	}

	public synchronized void clear() {
		expired = true;
		variables.clear();
		evaluateNames.clear();
		queryRows.clear();
		List<ObjectReference> pins = List.copyOf( retained );
		retained.clear();
		// Release remotely without making continue/disconnect wait for one JDI command per pinned object.
		if ( !pins.isEmpty() )
			Thread.startVirtualThread( () -> {
				for ( ObjectReference object : pins ) {
					try {
						object.enableCollection();
					} catch ( RuntimeException error ) {
						LOGGER.fine( "Unable to release stopped value: " + error );
					}
				}
			} );
	}

	public int getVariableId( Value value ) {
		for ( Map.Entry<Integer, Value> entry : variables.entrySet() ) {
			if ( entry.getValue().equals( value ) ) {
				return entry.getKey();
			}
		}
		return -1;
	}

	public Scope convertScopeToDAPScope( Value scopeValue ) {
		Scope	scope	= new Scope();

		String	name	= this.vmController.invoke( ( ObjectReference ) scopeValue, "getName", new ArrayList<String>(), new ArrayList<Value>() )
		    .thenCompose( key -> this.vmController.invoke( ( ObjectReference ) key, "getName", new ArrayList<String>(), new ArrayList<Value>() ) )
		    .thenApply( nameValue -> {
							    if ( nameValue instanceof StringReference ref ) {
								    return ref.value();
							    }

							    throw new IllegalStateException( "Scope name is unavailable" );
						    } )
		    .join();

		scope.setName( name );
		// Qualify scopes so local/argument shadowing cannot change the value of a variables-scope child.
		String	scopeParentEvaluateName	= name.toLowerCase( java.util.Locale.ROOT );
		int		ref						= put( scopeValue, scopeParentEvaluateName );
		scope.setVariablesReference( ref );
		scope.setNamedVariables( size( ( ObjectReference ) scopeValue ) );
		scope.setIndexedVariables( 0 );

		LOGGER.info( "Scope created with name: " + name + " and variablesReference: " + scope.getVariablesReference() );

		return scope;
	}

	private static int pageEnd( int size, int start, int count ) {
		return count == 0 ? size : ( int ) Math.min( size, ( long ) start + count );
	}

	private boolean isQuery( Value value ) {
		return isInstanceOf( value, "ortus.boxlang.runtime.types.Query" );
	}

	private boolean isInstanceOf( Value value, String className ) {
		if ( value.type() instanceof ClassType type ) {
			for ( ; type != null; type = type.superclass() ) {
				if ( type.name().equalsIgnoreCase( className ) )
					return true;
			}
		}
		return false;
	}

	private int size( ObjectReference value ) {
		Value result = vmController.invoke( value, "size", List.of(), List.of() ).join();
		return ( ( IntegerValue ) findValueOfPropertyByName( ( ObjectReference ) result, "value" ) ).intValue();
	}

	private List<String> queryColumns( ObjectReference query ) {
		ObjectReference	columns	= ( ObjectReference ) vmController.invoke( query, "getColumnArray", List.of(), List.of() ).join();
		ArrayReference	names	= ( ArrayReference ) vmController.invoke( columns, "toArray", List.of(), List.of() ).join();
		return names.getValues().stream().map( value -> ( ( StringReference ) value ).value() ).toList();
	}

	private static String stringLiteral( String value ) {
		return "\"" + value.replace( "\"", "\"\"" ).replace( "#", "##" ) + "\"";
	}

	private static String queryRowExpression( String query, int row ) {
		return query == null || query.isEmpty() ? null : "queryRowData((" + query + "), " + row + ")";
	}

	private static String memberExpression( String parent, String key ) {
		if ( parent == null || parent.isEmpty() )
			return null;
		boolean	identifier	= key.matches( "[A-Za-z_][A-Za-z_0-9]*" );
		String	base		= parent.matches( "[A-Za-z_][A-Za-z_0-9.]*" ) ? parent : "(" + parent + ")";
		return identifier ? base + "." + key : base + "[" + stringLiteral( key ) + "]";
	}

	private List<Variable> queryVariables( ObjectReference query, String expression, int start, int count, VariablesArgumentsFilter filter ) {
		int				rows	= size( query );
		List<String>	columns	= queryColumns( query );
		int				named	= filter == VariablesArgumentsFilter.INDEXED ? 0 : 2;
		int				total	= named + ( filter == VariablesArgumentsFilter.NAMED ? 0 : rows );
		List<Variable>	result	= new ArrayList<>();
		for ( int index = start; index < pageEnd( total, start, count ); index++ ) {
			if ( index < named ) {
				Variable item = new Variable();
				item.setName( index == 0 ? "recordCount" : "columns" );
				item.setType( index == 0 ? "numeric" : "String" );
				item.setValue( index == 0 ? Integer.toString( rows ) : String.join( ", ", columns ) );
				item.setEvaluateName( memberExpression( expression, index == 0 ? "recordCount" : "columnList" ) );
				result.add( item );
			} else {
				int				row		= index - named;
				ArrayReference	values	= ( ArrayReference ) vmController.invoke( query, "getRow", List.of( "int" ),
				    List.of( InvokeTools.createIntegerRef( vmController, row ) ) ).join();
				Variable		item	= new Variable();
				item.setName( Integer.toString( row + 1 ) );
				item.setType( "Struct" );
				item.setValue( "{}" );
				item.setNamedVariables( columns.size() );
				item.setIndexedVariables( 0 );
				String rowExpression = queryRowExpression( expression, row + 1 );
				item.setEvaluateName( rowExpression );
				synchronized ( this ) {
					int reference = put( values, rowExpression );
					queryRows.put( reference, new QueryRow( columns, expression, row + 1 ) );
					item.setVariablesReference( reference );
				}
				result.add( item );
			}
		}
		return result;
	}

	private List<Variable> queryCells( ArrayReference values, QueryRow row, int start, int count, VariablesArgumentsFilter filter ) {
		if ( filter == VariablesArgumentsFilter.INDEXED || start >= row.columns().size() )
			return List.of();
		List<Value>		page	= values.getValues( start, pageEnd( row.columns().size(), start, count ) - start );
		List<Variable>	result	= new ArrayList<>();
		for ( int i = 0; i < page.size(); i++ ) {
			String	column			= row.columns().get( start + i );
			String	expression		= queryRowExpression( row.expression(), row.number() );
			// Query pseudo-properties (recordCount, columnList, etc.) can also be real column names.
			String	cellExpression	= expression == null ? null : "structFind(" + expression + ", " + stringLiteral( column ) + ")";
			result.add( convertValueToVariable( column, page.get( i ), cellExpression ) );
		}
		return result;
	}

	private static String indexedExpression( String parent, int index ) {
		if ( parent == null || parent.isEmpty() )
			return null;
		String base = parent.matches( "[A-Za-z_][A-Za-z_0-9.]*" ) ? parent : "(" + parent + ")";
		return base + "[" + index + "]";
	}

	private List<Variable> nativeArrayVariables( ArrayReference array, String expression, int start, int count, VariablesArgumentsFilter filter ) {
		if ( filter == VariablesArgumentsFilter.NAMED || start >= array.length() )
			return List.of();
		List<Value> page = array.getValues( start, pageEnd( array.length(), start, count ) - start );
		return arrayPage( page, expression, start );
	}

	private List<Variable> arrayPage( List<Value> page, String expression, int start ) {
		List<Variable> result = new ArrayList<>();
		for ( int i = 0; i < page.size(); i++ ) {
			int index = start + i + 1;
			result.add( convertValueToVariable( Integer.toString( index ), page.get( i ), indexedExpression( expression, index ) ) );
		}
		return result;
	}

	private boolean isStruct( Value value ) {
		if ( ! ( value.type() instanceof ClassType ) ) {
			return false;
		}

		return ( ( ClassType ) value.type() ).allInterfaces()
		    .stream().anyMatch( ( i ) -> i.name().equalsIgnoreCase( "ortus.boxlang.runtime.types.IStruct" ) );
	}

	private List<Variable> gerVariablesFromArray( ObjectReference array, String expression, int start, int count, VariablesArgumentsFilter filter ) {
		if ( filter == VariablesArgumentsFilter.NAMED )
			return List.of();
		return arrayPage( arrayValues( array, start, count ), expression, start );
	}

	private List<Value> arrayValues( ObjectReference array, int start, int count ) {
		int size = size( array );
		if ( start >= size )
			return List.of();
		Value			slice	= vmController.invoke( array, "subList", List.of( "int", "int" ), List.of(
		    InvokeTools.createIntegerRef( vmController, start ), InvokeTools.createIntegerRef( vmController, pageEnd( size, start, count ) ) ) ).join();
		// Use BoxLang's public wrapper instead of reflecting on private JDK sublist implementations.
		ObjectReference	wrapped	= ( ObjectReference ) vmController
		    .invokeStatic( "ortus.boxlang.runtime.types.Array", "fromList", List.of( "java.util.List" ), List.of( slice ) ).join();
		ArrayReference	page	= ( ArrayReference ) vmController.invoke( wrapped, "toArray", List.of(), List.of() ).join();
		return page.getValues();
	}

	private List<Field> instanceFields( ObjectReference object ) {
		return object.referenceType().allFields().stream().filter( field -> !field.isStatic() ).toList();
	}

	private List<Variable> gerVariablesFromPojo( ObjectReference pojo, String expression, int start, int count, VariablesArgumentsFilter filter ) {
		if ( filter == VariablesArgumentsFilter.INDEXED )
			return List.of();
		List<Field> fields = instanceFields( pojo ).stream().skip( start ).limit( count == 0 ? Long.MAX_VALUE : count ).toList();
		if ( fields.isEmpty() )
			return List.of();
		Map<Field, Value> values = pojo.getValues( fields );
		return fields.stream().map( field -> convertValueToVariable( field.name(), values.get( field ),
		    field.isPublic() ? memberExpression( expression, field.name() ) : null ) ).toList();
	}

	private List<Variable> gerVariablesFromStruct( ObjectReference struct, String expression, int start, int count, VariablesArgumentsFilter filter ) {
		if ( filter == VariablesArgumentsFilter.INDEXED )
			return List.of();
		// ponytail: supported runtimes snapshot all key names (not values); a runtime page API can remove this O(n) target-side step.
		Value			keys	= vmController.invoke( struct, "getKeysAsStrings", List.of(), List.of() ).join();
		ObjectReference	array	= ( ObjectReference ) vmController
		    .invokeStatic( "ortus.boxlang.runtime.types.Array", "fromList", List.of( "java.util.List" ), List.of( keys ) ).join();
		List<Variable>	result	= new ArrayList<>();
		for ( Value key : arrayValues( array, start, count ) ) {
			String	name	= ( ( StringReference ) key ).value();
			Value	value	= vmController.invoke( struct, "get", List.of( "java.lang.String" ), List.of( key ) ).join();
			String	child	= name.equalsIgnoreCase( "$bx" ) && expression != null && !expression.isEmpty()
			    ? "structFind((" + expression + "), " + stringLiteral( name ) + ")"
			    : memberExpression( expression, name );
			result.add( convertValueToVariable( name, value, child ) );
		}
		return result;
	}

	private boolean isOfType( Value val, String type ) {
		return val.type().name().equalsIgnoreCase( type );
	}

	private boolean hasSuperClass( Value val, String type ) {
		return val instanceof ObjectReference
		    && val.type() instanceof ClassType ctype
		    && ctype.superclass() != null && ctype.superclass().name().equalsIgnoreCase( type );
	}

	public Variable convertValueToVariable( String name, Value val ) {
		return convertValueToVariable( name, val, name );
	}

	public Variable convertValueToVariable( String name, Value val, String evaluateName ) {
		checkActive();
		Variable var = new Variable();
		var.setType( "null" );
		var.setValue( "" );
		var.setName( name );
		var.setEvaluateName( evaluateName );

		if ( val == null ) {
			var.setValue( "null" );
			var.setType( "null" );
		} else if ( val instanceof StringReference stringRef ) {
			var.setValue( "\"" + stringRef.value() + "\"" );
			var.setType( "String" );
		} else if ( val instanceof IntegerValue integerVal ) {
			var.setValue( Integer.toString( integerVal.intValue() ) );
			var.setType( "numeric" );
		} else if ( val instanceof DoubleValue doubleVal ) {
			var.setValue( StringCaster.cast( doubleVal.doubleValue() ) );
			var.setType( "numeric" );
		} else if ( val instanceof LongValue longValue ) {
			var.setValue( StringCaster.cast( longValue.longValue() ) );
			var.setType( "numeric" );
		} else if ( val instanceof BooleanValue booleanValue ) {
			var.setType( "boolean" );
			var.setValue( Boolean.toString( booleanValue.booleanValue() ) );
		} else if ( val instanceof CharValue character ) {
			var.setType( "String" );
			var.setValue( "\"" + character.value() + "\"" );
		} else if ( val instanceof PrimitiveValue primitive ) {
			var.setType( "numeric" );
			var.setValue( primitive.toString() );
		} else if ( val instanceof ArrayReference array ) {
			var.setType( val.type().name() );
			var.setValue( "[" + array.length() + " elements]" );
			var.setIndexedVariables( array.length() );
			var.setNamedVariables( 0 );
			var.setVariablesReference( put( val, evaluateName ) );
		} else if ( isQuery( val ) ) {
			int				rows	= size( ( ObjectReference ) val );
			List<String>	columns	= queryColumns( ( ObjectReference ) val );
			var.setType( "Query" );
			var.setValue( "Query (" + rows + " rows, " + columns.size() + " columns)" );
			var.setNamedVariables( 2 );
			var.setIndexedVariables( rows );
			var.setVariablesReference( put( val, evaluateName ) );
		} else if ( isOfType( val, "ortus.boxlang.runtime.types.DateTime" ) ) {
			var.setValue( invokeString( val, "toISOString" ) );
			var.setType( "DateTime" );
		} else if ( BOXED_TYPES.contains( val.type().name() ) ) {
			Value primitive = findValueOfPropertyByName( ( ObjectReference ) val, "value" );
			if ( ! ( primitive instanceof PrimitiveValue ) )
				throw new IllegalStateException( "Unavailable boxed value: " + val.type().name() );
			var = convertValueToVariable( name, primitive, evaluateName );
		} else if ( isOfType( val, "java.math.BigDecimal" ) || isOfType( val, "java.math.BigInteger" ) ) {
			var.setValue( invokeString( val, "toString" ) );
			var.setType( "numeric" );
		} else if ( isArray( val ) ) {
			var.setIndexedVariables( size( ( ObjectReference ) val ) );
			var.setNamedVariables( 0 );
			var.setType( "array" );
			var.setValue( "[]" );
			var.setVariablesReference( put( val, evaluateName ) );
		} else if ( isStruct( val ) ) {
			var.setNamedVariables( size( ( ObjectReference ) val ) );
			var.setIndexedVariables( 0 );
			var.setType( "Struct" );
			var.setValue( "{}" );
			var.setVariablesReference( put( val, evaluateName ) );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.Closure" ) ) {
			var.setType( "closure" );
			var.setValue( "closure" );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.Lambda" ) ) {
			var.setType( "lambda" );
			var.setValue( "lambda" );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.UDF" ) ) {
			var.setType( "function" );
			var.setValue( "() => {}" );
		} else if ( val instanceof ObjectReference object ) {
			var.setNamedVariables( instanceFields( object ).size() );
			var.setIndexedVariables( 0 );
			var.setType( val.type().name() );
			var.setValue( val.type().name() );
			var.setVariablesReference( put( val, evaluateName ) );
		}

		checkActive();
		return var;
	}

	private String invokeString( Value value, String method ) {
		Value result = vmController.invoke( ( ObjectReference ) value, method, List.of(), List.of() ).join();
		if ( result instanceof StringReference text )
			return text.value();
		throw new IllegalStateException( "Unavailable string value from " + value.type().name() + "." + method );
	}

	private static Value findValueOfPropertyByName( ObjectReference object, String name ) {
		for ( Field field : object.referenceType().allFields() ) {
			if ( field.name().equalsIgnoreCase( name ) ) {
				return object.getValue( field );
			}
		}

		return null;
	}

	private boolean isArray( Value value ) {
		return isInstanceOf( value, "ortus.boxlang.runtime.types.Array" );
	}

	private boolean isPOJO( Value value ) {
		if ( ! ( value.type() instanceof ClassType ) ) {
			return false;
		}

		if ( value instanceof ObjectReference objRef ) {
			return true;
		}

		return false;
	}

}
