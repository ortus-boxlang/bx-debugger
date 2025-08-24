package ortus.boxlang.moduleslug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.Variable;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

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

	public List<Variable> getVariablesFor( int id ) {

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

}
