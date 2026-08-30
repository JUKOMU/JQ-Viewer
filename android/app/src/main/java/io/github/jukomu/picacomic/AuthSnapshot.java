package io.github.jukomu.picacomic;

/** Process-only authentication snapshot.  No password or token is retained here. */
public final class AuthSnapshot {

    public enum State {
        SIGNED_OUT("signed_out"),
        AUTHENTICATING("authenticating"),
        SIGNED_IN("signed_in"),
        EXPIRED("expired");

        private final String wireValue;

        State(String wireValue) {
            this.wireValue = wireValue;
        }

        public String getWireValue() {
            return wireValue;
        }
    }

    public final State state;
    public final PicacomicUser user;

    private AuthSnapshot(State state, PicacomicUser user) {
        this.state = state;
        this.user = user;
    }

    public static AuthSnapshot signedOut() {
        return new AuthSnapshot(State.SIGNED_OUT, null);
    }

    public static AuthSnapshot authenticating() {
        return new AuthSnapshot(State.AUTHENTICATING, null);
    }

    public static AuthSnapshot signedIn(PicacomicUser user) {
        if (user == null) throw new IllegalArgumentException("user is required");
        return new AuthSnapshot(State.SIGNED_IN, user);
    }

    public static AuthSnapshot expired() {
        return new AuthSnapshot(State.EXPIRED, null);
    }
}
