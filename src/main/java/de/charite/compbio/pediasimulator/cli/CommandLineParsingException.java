package de.charite.compbio.pediasimulator.cli;

/**
 * Exception thrown on problems with the command line.
 *
 * @author <a href="mailto:max.schubach@charite.de">Max Schubach</a>
 */

public class CommandLineParsingException extends Exception {

	public CommandLineParsingException() {
		super();
	}

	public CommandLineParsingException(String msg) {
		super(msg);
	}

	public CommandLineParsingException(String msg, Throwable cause) {
		super(msg, cause);
	}

	private static final long serialVersionUID = 1L;

}
