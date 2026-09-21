package ortus.boxlang.bxdebugger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.sun.jdi.Value;

import ortus.boxlang.runtime.dynamic.casters.StringCaster;

public class VariableManager {

	private static final Logger			LOGGER			= Logger.getLogger( VariableManager.class.getName() );
	private static final AtomicInteger	variableIds		= new AtomicInteger();
	private VMController				vmController;
	private Map<Integer, Value>			variables		= new ConcurrentHashMap<>();
	private Map<Integer, String>		evaluateNames	= new ConcurrentHashMap<>();
	private volatile boolean			expired;

	public VariableManager( VMController vmController ) {
		this.vmController = vmController;
	}

	public int put( Value value ) {
		return put( value, null );
	}

	public synchronized int put( Value value, String evaluateName ) {
		checkActive();
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
		checkActive();
		var variable = variables.get( id );
		if ( variable == null ) {
			throw new IllegalArgumentException( "Unknown or expired variables reference " + id );
		}
		String			parentEvaluateName	= evaluateNames.getOrDefault( id, "" );

		List<Variable>	result				= List.of();
		if ( isStruct( variable ) ) {
			result = gerVariablesFromStruct( ( ObjectReference ) variable, parentEvaluateName );
		} else if ( isArray( variable ) ) {
			result = gerVariablesFromArray( ( ObjectReference ) variable, parentEvaluateName );
		} else if ( isPOJO( variable ) ) {
			result = gerVariablesFromPojo( ( ObjectReference ) variable, parentEvaluateName );
		}
		checkActive();
		return result;
	}

	public synchronized void clear() {
		expired = true;
		variables.clear();
		evaluateNames.clear();
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

							    return "Unknown Scope";
						    } )
		    .exceptionally( e -> {
			    LOGGER.severe( "Error getting scope name: " + e.getMessage() );
			    return "Unknown Scope";
		    } )
		    .join();

		scope.setName( name );
		// Named scopes (server, application, request, etc.) require scope-qualified expressions.
		// The variables scope is the unqualified local scope, so no prefix is needed there.
		String	scopeParentEvaluateName	= name.equalsIgnoreCase( "variables" ) ? "" : name.toLowerCase();
		int		ref						= put( scopeValue, scopeParentEvaluateName );
		scope.setVariablesReference( ref );

		LOGGER.info( "Scope created with name: " + name + " and variablesReference: " + scope.getVariablesReference() );

		return scope;
	}

	private boolean isStruct( Value value ) {
		if ( ! ( value.type() instanceof ClassType ) ) {
			return false;
		}

		return ( ( ClassType ) value.type() ).allInterfaces()
		    .stream().anyMatch( ( i ) -> i.name().equalsIgnoreCase( "ortus.boxlang.runtime.types.IStruct" ) );
	}

	private List<Variable> gerVariablesFromArray( ObjectReference array, String parentEvaluateName ) {
		ArrayReference table;
		try {
			table = ( ArrayReference ) this.vmController.invoke( array, "toArray", new ArrayList<String>(), new ArrayList<Value>() ).get();
		} catch ( InterruptedException e ) {
			LOGGER.severe( "Interrupted getting array values: " + e.getMessage() );

			return new ArrayList<Variable>();
		} catch ( ExecutionException e ) {
			LOGGER.severe( "Error getting array values: " + e.getMessage() );

			return new ArrayList<Variable>();
		}
		List<Variable> vars = new ArrayList<Variable>();

		for ( int i = 0; i < table.length(); i++ ) {
			String	indexName			= Integer.toString( i + 1 );
			String	childEvaluateName	= parentEvaluateName.isEmpty() ? indexName : parentEvaluateName + "[" + indexName + "]";
			vars.add( convertValueToVariable( indexName, table.getValue( i ), childEvaluateName ) );
		}

		return vars;
	}

	private List<Variable> gerVariablesFromPojo( ObjectReference pojo, String parentEvaluateName ) {
		// in this case we want to get the properties of the POJO
		List<Variable> vars = new ArrayList<Variable>();

		for ( Field field : pojo.referenceType().allFields() ) {
			// TODO allow static fields through a setting
			if ( field.isStatic() ) {
				continue;
			}
			Value	val					= pojo.getValue( field );
			String	childEvaluateName	= parentEvaluateName.isEmpty() ? field.name() : parentEvaluateName + "." + field.name();
			vars.add( convertValueToVariable( field.name(), val, childEvaluateName ) );
		}

		return vars;
	}

	private List<Variable> gerVariablesFromStruct( ObjectReference struct, String parentEvaluateName ) {
		try {
			return this.vmController.invoke( struct, "entrySet", new ArrayList<String>(), new ArrayList<Value>() )
			    .thenCompose( ref -> this.vmController.invoke( ( ObjectReference ) ref, "toArray", new ArrayList<String>(), new ArrayList<Value>() ) )
			    .thenApply( ref -> {
				    return ( ( ArrayReference ) ref ).getValues();
			    } ).thenApply( values -> {
				    return values.stream()
				        .filter( entry -> entry != null )
				        .map( entry -> {
					        try {
						        String keyName		= getNameFromEntry( entry ).join();
						        Value val			= getValueFromEntry( entry ).join();
						        String childEvaluateName = parentEvaluateName.isEmpty() ? keyName : parentEvaluateName + "." + keyName;
						        return convertValueToVariable( keyName, val, childEvaluateName );

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

	private CompletableFuture<String> getNameFromEntry( Value entry ) {
		return this.vmController.invoke( ( ObjectReference ) entry, "getKey", new ArrayList<String>(), new ArrayList<Value>() )
		    .thenApply( keyValue -> {
			    return this.vmController.invoke( ( ObjectReference ) keyValue, "getOriginalValue", new ArrayList<String>(), new ArrayList<Value>() )
			        .thenApply( originalValue -> {
				        if ( originalValue instanceof StringReference strRef ) {
					        return strRef.value();
				        }
				        return "UnknownKey";
			        } );
		    } ).thenCompose( nameFuture -> nameFuture );
	}

	private CompletableFuture<Value> getValueFromEntry( Value entry ) {
		return this.vmController.invoke( ( ObjectReference ) entry, "getValue", new ArrayList<String>(), new ArrayList<Value>() );
	}

	private boolean isOfType( Value val, String type ) {
		return val.type().name().equalsIgnoreCase( type );
	}

	private boolean hasSuperClass( Value val, String type ) {
		return val instanceof ObjectReference
		    && val.type() instanceof ClassType ctype
		    && ctype.superclass().name().equalsIgnoreCase( type );
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
		} else if ( val instanceof BigDecimal bigDecimal ) {
			var.setValue( StringCaster.cast( bigDecimal.doubleValue() ) );
			var.setType( "numeric" );
		} else if ( isOfType( val, "ortus.boxlang.runtime.types.DateTime" ) ) {
			var.setValue( castToDateTimeString( val ) );
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
			var.setVariablesReference( put( val, evaluateName ) );
		} else if ( isStruct( val ) ) {
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
		} else if ( val != null ) {
			var.setType( val.type().name() );
			var.setValue( val.type().name() );
			var.setVariablesReference( put( val, evaluateName ) );
		}

		checkActive();
		return var;
	}

	private String castToDateTimeString( Value value ) {
		return this.vmController.invoke( ( ObjectReference ) value, "toISOString", new ArrayList<String>(), new ArrayList<Value>() )
		    .handle( ( val, e ) -> {
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
