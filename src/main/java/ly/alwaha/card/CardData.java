package ly.alwaha.card;

/** The text printed on the card. ACC Name and all four name fields use fullName. */
public record CardData(String fullName, String branch, String accountNumber) {

    public CardData {
        fullName = fullName == null ? null : fullName.strip();
        branch = branch == null ? null : branch.strip();
        accountNumber = accountNumber == null ? null : accountNumber.strip();
    }

    /**
     * Rejects the card rather than guessing or leaving a field blank.
     * accountDigits = the number of account boxes printed on the template (one digit per box).
     */
    public void validate(int accountDigits) throws RejectedException {
        if (fullName == null || fullName.isEmpty()) throw new RejectedException("fullName is missing");
        if (branch == null || branch.isEmpty()) throw new RejectedException("branch is missing");
        if (accountNumber == null || !accountNumber.matches("\\d{" + accountDigits + "}")) {
            throw new RejectedException("accountNumber must be exactly " + accountDigits + " digits");
        }
    }
}
