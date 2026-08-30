package nurgling.widgets.db;

import java.awt.datatransfer.StringSelection;

/** Copying setup commands and invite codes out of the client, which is how both are delivered. */
public class DbClipboard {
    public static boolean copy(String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
            return true;
        } catch (java.awt.HeadlessException | IllegalStateException e) {
            /* Another application can be holding the clipboard; that is not a reason to lose the
             * text, so the caller falls back to writing it to a file. */
            System.err.println("[DbClipboard] copy failed: " + e.getMessage());
            return false;
        }
    }
}
