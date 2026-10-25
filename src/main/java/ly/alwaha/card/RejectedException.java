package ly.alwaha.card;

/** Input that can never produce a valid card. It goes to failed/, and retrying won't help. */
public class RejectedException extends Exception {
    public RejectedException(String reason) {
        super(reason);
    }
}
