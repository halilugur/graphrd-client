package org.graphrd;

/** What the client throws: transport failures and error answers. */
public class GraphRdException extends RuntimeException {

    public GraphRdException(String message) {
        super(message);
    }

    public GraphRdException(Throwable cause) {
        super(cause);
    }
}
