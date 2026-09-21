package ortus.boxlang.bxdebugger;

/**
 * Body of the custom <code>boxlang.dump</code> DAP event.
 */
public class BoxLangDumpEventBody {

	private String	html;
	private String	label;
	private String	timestamp;

	public BoxLangDumpEventBody() {
	}

	public BoxLangDumpEventBody( String html, String label, String timestamp ) {
		this.html		= html;
		this.label		= label;
		this.timestamp	= timestamp;
	}

	public String getHtml() {
		return html;
	}

	public void setHtml( String html ) {
		this.html = html;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel( String label ) {
		this.label = label;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp( String timestamp ) {
		this.timestamp = timestamp;
	}
}
