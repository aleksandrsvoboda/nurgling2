package nurgling.conf;

import org.json.*;

import java.util.*;

/**
 * A remembered account on the login screen.
 *
 * Only the account name and, once the auth server has issued one, a login
 * token are kept. Passwords are deliberately never persisted: a token is
 * scoped to this client and can be revoked from the account settings page,
 * a password cannot.
 */
public class NLoginData implements JConf
{
    public String name = "";
    public byte[] token;
    public boolean isTokenUsed = false;

    /**
     * Set when this entry came from a config written by an older client that
     * stored the password in plain text. The password is dropped on load; the
     * flag exists only so the login screen can tell the user once that their
     * saved logins need re-entering.
     */
    public boolean hadLegacyPassword = false;

    public NLoginData(String name)
    {
        this.name = name;
    }

    public NLoginData(String name, byte[] token)
    {
        this.name = name;
        this.token = token;
        this.isTokenUsed = true;
    }

    public NLoginData(HashMap<String, Object> values)
    {
        name = (String) values.get("user");
        /* Older clients wrote the password out here. Note that it was present
         * and then ignore it - it is never read back into the object, and
         * toJson() no longer emits it, so the next config save scrubs it. */
        hadLegacyPassword = values.get("pass") != null;
        if(values.get("isToken")!=null)
            isTokenUsed = (Boolean) values.get("isToken");
        if(isTokenUsed)
        {
            ArrayList<Object> buft = (ArrayList<Object>) values.get("token");
            if(buft == null)
            {
                /* Flagged as token-backed but the token is missing; treat the
                 * entry as name-only rather than carrying a null around. */
                isTokenUsed = false;
            }
            else
            {
                token = new byte[buft.size()];
                int count = 0;
                for (Object b : buft)
                {
                    token[count++] = ((Integer) b).byteValue();
                }
            }
        }
    }

    @Override
    public boolean equals(Object other)
    {
        if (other == null) return false;
        if (other == this) return true;
        if (!(other instanceof NLoginData)) return false;
        /* The account name identifies the entry - there is only ever one
         * remembered login per account. */
        return ((NLoginData) other).name.equals(name);
    }

    @Override
    public int hashCode()
    {
        return name.hashCode();
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject object = new JSONObject();
        object.put("type", "NLoginData");
        object.put("user", name);
        if(isTokenUsed && token != null)
        {
            object.put("isToken", true);
            JSONArray jtoken = new JSONArray();
            for (Byte b : token)
            {
                jtoken.put(b);
            }
            object.put("token", jtoken);
        }
        return object;
    }
}
