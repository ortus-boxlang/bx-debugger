package ortus.boxlang.moduleslug;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.Variable;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import ortus.boxlang.runtime.dynamic.casters.StringCaster;

public class VariableManager {

	private static final Logger	LOGGER		= Logger.getLogger( VariableManager.class.getName() );
	private int					variableId	= 0;

	private Map<Integer, Value>	variables	= new WeakHashMap<>();

	public int put( Value value ) {
		variableId++;
		variables.put( variableId, value );
		return variableId;
	}

	public Value getValue( int id ) {
		return variables.get( id );
	}

	public List<Variable> getVariablesFor( ThreadReference invokeThread, int id ) {
		var variable = variables.get( id );

		if ( isStruct( variable ) ) {
			return gerVariablesFromStruct( invokeThread, ( ObjectReference ) variable );
		} else if ( isArray( variable ) ) {
			return gerVariablesFromArray( invokeThread, ( ObjectReference ) variable );
		} else if ( isPOJO( variable ) ) {
			return gerVariablesFromPojo( invokeThread, ( ObjectReference ) variable );
		}

		return List.of();
	}

	public void clear() {
		variables.clear();
	}

	public int getVariableId( Value value ) {
		for ( Map.Entry<Integer, Value> entry : variables.entrySet() ) {
			if ( entry.getValue().equals( value ) ) {
				return entry.getKey();
			}
		}
		return -1;
	}

	public Scope convertScopeToDAPScope( ThreadReference invokeThread, Value scopeValue ) {
		Scope	scope	= new Scope();

		String	name	= Util.invokeAsync(
		    invokeThread,
		    ( ObjectReference ) scopeValue,
		    "getName",
		    "()Lortus/boxlang/runtime/scopes/Key;",
		    new ArrayList<Value>()
		)
		    .thenCompose( key -> Util.invokeAsync(
		        invokeThread,
		        ( ObjectReference ) key,
		        "getName",
		        "()Ljava/lang/String;",
		        new ArrayList<Value>()
		    ) )
		    .thenApply( nameValue -> {
			    if ( nameValue instanceof StringReference ref ) {
				    return ref.value();
			    }

			    return "Unknown Scope";
		    } )
		    .exceptionally( e -> {
			    LOGGER.severe( "Error getting scope name: " + e.getMessage() );
			    return "Unknown Scope";
		    } )
		    .join();

		scope.setName( name );
		scope.setVariablesReference( variableId++ );

		this.variables.put( scope.getVariablesReference(), scopeValue );

		return scope;
	}

	private boolean isStruct( Value value ) {
		if ( ! ( value.type() instanceof ClassType ) ) {
			return false;
		}

		return ( ( ClassType ) value.type() ).allInterfaces()
		    .stream().anyMatch( ( i ) -> i.name().equalsIgnoreCase( "ortus.boxlang.runtime.types.IStruct" ) );
	}

	private List<Variable> gerVariablesFromArray( ThreadReference invokeThread, ObjectReference array ) {
		ArrayReference table;
		try {
			table = ( ArrayReference ) Util.invokeAsync(
			    invokeThread,
			    ( ObjectReference ) array,
			    "toArray",
			    "()[Ljava/lang/Object;",
			    new ArrayList<Value>()
			).get();
		} catch ( InterruptedException e ) {
			LOGGER.severe( "Interrupted getting array values: " + e.getMessage() );

			return new ArrayList<Variable>();
		} catch ( ExecutionException e ) {
			LOGGER.severe( "Error getting array values: " + e.getMessage() );

			return new ArrayList<Variable>();
		}
		List<Variable> vars = new ArrayList<Variable>();

		for ( int i = 0; i < table.length(); i++ ) {
			vars.add( convertValueToVariable( invokeThread, Integer.toString( i + 1 ), table.getValue( i ) ) );
		}

		return vars;
	}

	private List<Variable> gerVariablesFromPojo( ThreadReference invokeThread, ObjectReference pojo ) {
		// in this case we want to get the properties of the POJO
		List<Variable> vars = new ArrayList<Variable>();

		for ( Field field : pojo.referenceType().allFields() ) {
			// TODO allow static fields through a setting
			if ( field.isStatic() ) {
				continue;
			}
			Value val = pojo.getValue( field );
			vars.add( convertValueToVariable( invokeThread, field.name(), val ) );
		}

		return vars;
	}

	private List<Variable> gerVariablesFromStruct( ThreadReference invokeThread, ObjectReference struct ) {
		try {
			return Util.invokeAsync(
			    invokeThread,
			    struct,
			    "entrySet",
			    "()Ljava/util/Set;",
			    new ArrayList<Value>()
			).thenCompose( ref -> Util.invokeAsync(
			    invokeThread,
			    ( ObjectReference ) ref,
			    "toArray",
			    "()[Ljava/lang/Object;",
			    new ArrayList<Value>()
			) ).thenApply( ref -> {
				return ( ( ArrayReference ) ref ).getValues();
			} ).thenApply( values -> {
				return values.stream()
				    .filter( entry -> entry != null )
				    .map( entry -> {
					    try {
						    String keyName = getNameFromEntry( invokeThread, entry ).join();
						    Value val	= getValueFromEntry( invokeThread, entry ).join();
						    return convertValueToVariable( invokeThread, keyName, val );

					    } catch ( Exception e ) {
						    LOGGER.severe( "Error getting key name from struct entry: " + e.getMessage() );

						    Variable var = new Variable();
						    var.setName( "UnknownKey" );
						    var.setValue( "Error getting key name from struct entry: " + e.getMessage() );
						    return var;
					    }
				    } ).toList();
			} ).get();
		} catch ( Exception e ) {
			LOGGER.severe( "Error getting variables from struct: " + e.getMessage() );
			return new ArrayList<Variable>();
		}
	}

	private CompletableFuture<String> getNameFromEntry( ThreadReference invokeThread, Value entry ) {
		return Util.invokeAsync(
		    invokeThread,
		    ( ObjectReference ) entry,
		    "getKey",
		    "()Ljava/lang/Object;",
		    new ArrayList<Value>()
		).thenApply( keyValue -> {
			return Util.invokeAsync(
			    invokeThread,
			    ( ObjectReference ) keyValue,
			    "getOriginalValue",
			    "()Ljava/lang/Object;",
			    new ArrayList<Value>()
			).thenApply( originalValue -> {
				if ( originalValue instanceof StringReference strRef ) {
					return strRef.value();
				}
				return "UnknownKey";
			} );
		} ).thenCompose( nameFuture -> nameFuture );
	}

	private CompletableFuture<Value> getValueFromEntry( ThreadReference invokeThread, Value entry ) {
		return Util.invokeAsync(
		    invokeThread,
		    ( ObjectReference ) entry,
		    "getValue",
		    "()Ljava/lang/Object;",
		    new ArrayList<Value>()
		);
	}

	private boolean isOfType( Value val, String type ) {
		return val.type().name().equalsIgnoreCase( type );
	}

	private boolean hasSuperClass( Value val, String type ) {
		return val instanceof ObjectReference
		    && val.type() instanceof ClassType ctype
		    && ctype.superclass().name().equalsIgnoreCase( type );
	}

	private Variable convertValueToVariable( ThreadReference invokeThread, String name, Value val ) {
		Variable var = new Variable();
		var.setType( "null" );
		var.setValue( "" );
		var.setName( name );

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
		} else if ( val instanceof BigDecimal bigDecimal ) {
			var.setValue( StringCaster.cast( bigDecimal.doubleValue() ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "ortus.boxlang.runtime.types.DateTime" ) ) {
			var.setValue( castToDateTimeString( invokeThread, val ) );
			var.setType( "DateTime" );
		} else if ( isOfType( val, "java.lang.Boolean" ) ) {
			var.setValue( castToBooleanString( val ) );
			var.setType( "boolean" );
		} else if ( isOfType( val, "java.lang.integer" ) ) {
			var.setValue( castToIntString( val ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "java.lang.double" ) ) {
			var.setValue( castToDoubleString( val ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "java.lang.Long" ) ) {
			var.setValue( castToLongString( val ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "java.math.BigDecimal" ) ) {
			var.setValue( castToBigDecimalString( val ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "ortus.boxlang.runtime.types.array" ) ) {
			var.setType( "array" );
			var.setValue( "[]" );
			var.setVariablesReference( put( val ) );
		} else if ( isStruct( val ) ) {
			var.setType( "Struct" );
			var.setValue( "{}" );
			var.setVariablesReference( put( val ) );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.Closure" ) ) {
			var.setType( "closure" );
			var.setValue( "closure" );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.Lambda" ) ) {
			var.setType( "lambda" );
			var.setValue( "lambda" );
		} else if ( hasSuperClass( val, "ortus.boxlang.runtime.types.UDF" ) ) {
			var.setType( "function" );
			var.setValue( "() => {}" );
		} else if ( val != null ) {
			var.setType( val.type().name() );
			var.setValue( val.type().name() );
			var.setVariablesReference( put( val ) );
		}

		return var;
	}

	private String castToDateTimeString( ThreadReference invokeThread, Value value ) {
		return Util.invokeAsync(
		    invokeThread,
		    ( ObjectReference ) value,
		    "toISOString",
		    "()Ljava/lang/String;",
		    new ArrayList<Value>()
		).handle( ( val, e ) -> {
			if ( e != null ) {
				LOGGER.severe( "Error casting DateTime to string: " + e.getMessage() );
				return "Error casting DateTime to string: " + e.getMessage();
			}

			if ( val instanceof StringReference strRef ) {
				return strRef.value().toString();
			}

			return "Unknown DateTime";
		} ).join();
	}

	private String castToBooleanString( Value value ) {
		Value propVal = findValueOfPropertyByName( ( ObjectReference ) value, "value" );

		if ( propVal == null ) {
			return "Unable to find 'value' property";
		}

		return StringCaster.cast( ( ( BooleanValue ) propVal ).booleanValue() );
	}

	private String castToIntString( Value value ) {
		Value propVal = findValueOfPropertyByName( ( ObjectReference ) value, "value" );

		if ( propVal == null ) {
			return "Unable to find 'value' property";
		}

		return StringCaster.cast( ( ( IntegerValue ) propVal ).intValue() );
	}

	private String castToDoubleString( Value value ) {
		Value propVal = findValueOfPropertyByName( ( ObjectReference ) value, "value" );

		if ( propVal == null ) {
			return "Unable to find 'value' property";
		}

		return StringCaster.cast( ( ( DoubleValue ) propVal ).doubleValue() );
	}

	private String castToLongString( Value value ) {
		Value propVal = findValueOfPropertyByName( ( ObjectReference ) value, "value" );

		if ( propVal == null ) {
			return "Unable to find 'value' property";
		}

		return StringCaster.cast( ( ( LongValue ) propVal ).longValue() );
	}

	private String castToBigDecimalString( Value value ) {
		Value propVal = findValueOfPropertyByName( ( ObjectReference ) value, "doubleValue" );

		if ( propVal == null ) {
			return "Unable to find 'doubleValue' property";
		}

		return StringCaster.cast( ( ( DoubleValue ) propVal ).doubleValue() );
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
		return isOfType( value, "ortus.boxlang.runtime.types.array" );
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
