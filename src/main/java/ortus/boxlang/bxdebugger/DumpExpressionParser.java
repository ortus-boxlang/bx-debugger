package ortus.boxlang.bxdebugger;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and extracts information from BoxLang <code>writeDump</code> /
 * <code>dump</code> evaluate expressions using regex.
 *
 * <p>
 * Supported call forms:
 * <ul>
 * <li>{@code writeDump( myVar )}</li>
 * <li>{@code dump( myVar )}</li>
 * <li>{@code writeDump( var=myVar )}</li>
 * <li>{@code writeDump( var=myVar, top=3 )}</li>
 * </ul>
 */
public class DumpExpressionParser {

	/**
	 * Matches the entire expression as either a writeDump or dump call.
	 * Captures everything inside the outer parentheses as group 1.
	 */
	private static final Pattern	DUMP_CALL_PATTERN	= Pattern.compile(
	    "^\\s*(?:write)?dump\\s*\\((.*)\\)\\s*$",
	    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
	);

	/**
	 * Matches a named {@code var=<expr>} argument anywhere in the arg list.
	 * Captures the value expression as group 1.
	 */
	private static final Pattern	VAR_ARG_PATTERN		= Pattern.compile(
	    "(?:^|,)\\s*var\\s*=\\s*([^,]+)",
	    Pattern.CASE_INSENSITIVE
	);

	/**
	 * Matches a named {@code top=<integer>} argument anywhere in the arg list.
	 * Captures the integer value as group 1.
	 */
	private static final Pattern	TOP_ARG_PATTERN		= Pattern.compile(
	    "(?:^|,)\\s*top\\s*=\\s*(\\d+)",
	    Pattern.CASE_INSENSITIVE
	);

	/**
	 * Returns true if the expression is a top-level {@code writeDump} or
	 * {@code dump} function call.
	 *
	 * @param expression the raw evaluate expression
	 *
	 * @return true if this is a dump call
	 */
	public boolean isDumpCall( String expression ) {
		if ( expression == null || expression.isBlank() ) {
			return false;
		}
		return DUMP_CALL_PATTERN.matcher( expression.trim() ).matches();
	}

	/**
	 * Extracts the human-readable label for the dump — the source text of the
	 * variable / expression being dumped.
	 *
	 * <ul>
	 * <li>For {@code writeDump( var=myVar, top=2 )} returns {@code "myVar"}.</li>
	 * <li>For {@code writeDump( myVar )} returns {@code "myVar"}.</li>
	 * <li>Falls back to {@code "unknown"} when nothing can be determined.</li>
	 * </ul>
	 *
	 * @param expression the raw evaluate expression
	 *
	 * @return the label string
	 */
	public String extractLabel( String expression ) {
		return extractVarExpression( expression ).orElse( "unknown" ).trim();
	}

	/**
	 * Extracts the integer value of the {@code top} named argument, if present.
	 *
	 * @param expression the raw evaluate expression
	 *
	 * @return an Optional containing the top value, or empty if not specified
	 */
	public Optional<Integer> extractTopArg( String expression ) {
		String argsText = extractArgsText( expression );
		if ( argsText == null ) {
			return Optional.empty();
		}
		Matcher m = TOP_ARG_PATTERN.matcher( argsText );
		if ( m.find() ) {
			try {
				return Optional.of( Integer.parseInt( m.group( 1 ).trim() ) );
			} catch ( NumberFormatException e ) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	/**
	 * Extracts the raw source text of the variable / expression to pass to
	 * the injected {@code writeDump} call.
	 *
	 * @param expression the raw evaluate expression
	 *
	 * @return Optional containing the var expression source text
	 */
	public Optional<String> extractVarExpression( String expression ) {
		String argsText = extractArgsText( expression );
		if ( argsText == null ) {
			return Optional.empty();
		}

		// Check for named var= argument first
		Matcher namedMatcher = VAR_ARG_PATTERN.matcher( argsText );
		if ( namedMatcher.find() ) {
			return Optional.of( namedMatcher.group( 1 ).trim() );
		}

		// Fall back to first positional argument (everything before first comma,
		// or the whole args text if there is no comma)
		String firstArg = argsText.split( "," )[ 0 ].trim();
		if ( !firstArg.isEmpty() ) {
			return Optional.of( firstArg );
		}

		return Optional.empty();
	}

	/**
	 * Returns the text inside the outer parentheses of the dump call, or
	 * {@code null} if the expression doesn't match.
	 */
	private String extractArgsText( String expression ) {
		if ( expression == null ) {
			return null;
		}
		Matcher m = DUMP_CALL_PATTERN.matcher( expression.trim() );
		if ( !m.matches() ) {
			return null;
		}
		return m.group( 1 );
	}
}
