package cc.wechat.observatory.wechat;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountSessionTest {
    @Test public void echoMustMatchBothSessionAndGeneration() {
        AccountSession session = new AccountSession("installation", "session", 3);
        assertTrue(session.acceptsEcho("session", 3));
        assertFalse(session.acceptsEcho("previous-session", 3));
        assertFalse(session.acceptsEcho("session", 2));
        assertFalse(session.acceptsEcho("", 0)); // An old server does not establish a session.
    }

    @Test(expected = IllegalArgumentException.class)
    public void generationMustBePositive() {
        new AccountSession("installation", "session", 0);
    }
}
