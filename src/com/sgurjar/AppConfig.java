package com.sgurjar;

import org.jvyaml.YAML;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
this class is java port of http://search.cpan.org/dist/YAML-AppConfig/
it uses jvyaml for yaml parsing make sure jvyaml jar file is in your classpath,
I've tested it with jvyaml-0.2.1 but latest version should work too, if not
then let us know.
https://jvyaml.dev.java.net/

Author: Satyendra Gurjar
Version: 0.1

**/
public class AppConfig
{
    private Map       config;
    private HashMap   seen        = new HashMap(); // For finding circular references.
    private ArrayList scope_stack = new ArrayList(); // For implementing dynamic variables. list of maps
    boolean           no_resolve;

    public AppConfig(final String filename) throws IOException
    {
        this(filename, false);
    }

    public AppConfig(final String filename, final boolean no_resolve)
        throws IOException
    {
        this(new FileReader(filename), no_resolve);
    }

    public AppConfig(final Reader data) throws IOException
    {
        this(data, false);
    }

    public AppConfig(final Reader data, final boolean no_resolve)
        throws IOException
    {
        try {
            this.no_resolve       = no_resolve;
            this.config           = (Map) (YAML.load(data));
            seen.clear();
        } finally {
            if (data != null) {
                data.close();
            }
        }
    }

    public Map config()
    {
        return config;
    }

    public Iterator config_keys()
    {
        return config.keySet().iterator();
    }

    public Object get(final String key)
    {
        return _get(key, false /*no_resolve*/);
    }

    public Object get(final String key, final boolean no_resolve)
    {
        return _get(key, no_resolve);
    }

    /*
    # Inner get so we can clear the seen hash above.  Listed here for readability.
    */
    private Object _get(final String key, final boolean no_resolve)
    {
        Object exists = _scope_has(key);

        if (exists == null) {
            return null;
        }

        if (this.no_resolve || no_resolve) {
            return this.config.get(key);
        }

        if (seen.containsKey(key)) {
            throw new AssertionError("Circular reference in " + key + ".");
        }

        seen.put(key, Boolean.TRUE);

        Object value = _resolve_refs(_get_from_scope(key));
        seen.remove(key);

        return value;
    }

    /*
    # void _resolve_refs(Scalar $value)
    #
    # Recurses on $value until a non-reference scalar is found, in which case we
    # defer to _resolve_scalar.  In this manner things like hashes and arrays are
    # traversed depth-first.
    */
    private Object _resolve_refs(final Object value)
    {
        if (value instanceof HashMap) {
            HashMap mapval = (HashMap) value;
            mapval = (HashMap) mapval.clone(); // do a deep copy

            ArrayList hidden = _push_scope(mapval);

            for (Iterator it = mapval.keySet().iterator(); it.hasNext();) {
                Object key = it.next();
                mapval.put(key, _resolve_refs(mapval.get(key)));
            }

            _pop_scope(hidden);

            return mapval;
        } else if (value instanceof ArrayList) {
            ArrayList listval = (ArrayList) value;
            listval = (ArrayList) listval.clone(); // do a deep copy

            for (int i = 0; i < listval.size(); i++) {
                listval.set(i, _resolve_refs(listval.get(i)));
            }

            return listval;
        } else {
            return _resolve_scalar(String.valueOf(value));
        }
    }

    /*
    # List _push_scope(HashRef scope)
    #
    # Pushes a new scope onto the stack.  Variables in this scope are hidden from
    # the seen stack.  This allows us to reference variables in the current scope
    # even if they have the same name as a variable higher up in chain.  The
    # hidden variables are returned.
    */
    private ArrayList _push_scope(final HashMap scope)
    {
        scope_stack.add(0, scope.clone()); // push on the top of the stack

        ArrayList hidden = new ArrayList();

        for (Iterator it = scope.keySet().iterator(); it.hasNext();) {
            Object key = it.next();

            if (seen.get(key) != null) {
                hidden.add(key);
                seen.remove(key);
            }
        }

        return hidden;
    }

    /*
    # void _pop_scope(@hidden)
    #
    # Removes the currently active scope from the stack and unhides any variables
    # passed in via @hidden, which is usually returned from _push_scope.
    */
    private void _pop_scope(final ArrayList hidden)
    {
        if (!hidden.isEmpty()) {
            hidden.remove(0); // pop top elemnt
        }

        for (int i = 0; i < hidden.size(); i++) {
            seen.put(hidden.get(i), Boolean.TRUE); // # Unhide
        }
    }

    /*
    # void _resolve_scalar(String $value)
    #
    # This function should only be called with strings (or numbers), not
    # references.  $value is treated as a string and is searched for $foo type
    # variables, which are then resolved.  The new string with variables resolved
    # is returned.
    */
    static final Pattern SPLIT_SCALAR_RE     = Pattern.compile("((?<!\\\\)\\$(?:\\{\\w+\\}|\\w+))");
    static final Pattern SCALAR_VAR_RE       = Pattern.compile("^(?<!\\\\)\\$(?:\\{(\\w+)\\}|(\\w+))$");
    static final Pattern UNESCAPE_SLASHES_RE = Pattern.compile("(\\\\*)\\\\(\\$(?:\\{(\\w+)\\}|(\\w+)))");

    private Object _resolve_scalar(final String value)
    {
        if (value == null) {
            throw new AssertionError("value is null");
        }

        ArrayList parts = split_include_delim(value, SPLIT_SCALAR_RE); // # Empty strings are useless, discard them

        for (int i = 0; i < parts.size(); i++) {
            String  part = (String) (parts.get(i));
            Matcher m    = SCALAR_VAR_RE.matcher(part);

            if (m.find()) {
                String name = (m.group(1) != null) ? m.group(1) : m.group(2);

                if (null != _scope_has(name)) {
                    part = String.valueOf(_get(name, false));
                    parts.set(i, part);
                }
            } else {
                // # Unescape slashes.  Example: \\\$foo -> \\$foo, ditto with ${foo}
                // $part =~ s/(\\*)\\(\$(?:{(\w+)}|(\w+)))/$1$2/g;
                Matcher m2 = UNESCAPE_SLASHES_RE.matcher(part);

                if (m2.find()) {
                    part = m2.group(1) + m2.group(2);
                    parts.set(i, part);
                }
            }
        }

        // return $parts[0] if @parts == 1 and ref $parts[0]; # Preserve references
        if (parts.size() == 1) {
            return parts.get(0);
        }

        // return join "", map { defined($_) ? $_ : "" } @parts;
        String joined = "";

        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) == null) {
                continue;
            }

            joined += parts.get(i);
        }

        return joined;
    }

    /*
    # Scalar _get_from_scope(String key)
    #
    # Given a key this method returns its value as it's defined in the inner most
    # enclosing scope containing the key.  That is to say, this method implements
    # the dyanmic scoping lookup for key.
    */
    private Object _get_from_scope(final String key)
    {
        ArrayList scope_stack = _scope_stack();

        for (int i = 0; i < scope_stack.size(); i++) {
            Map    scope  = (Map) scope_stack.get(i);
            Object exists = scope.get(key);

            if (exists != null) {
                return exists;
            }
        }

        return null;
    }

    /*
    # Object _scope_has(String key)
    #
    # This method returns true if the key is in any scope enclosing the current
    # scope or in the current scope.  False otherwise.
    */
    private Object _scope_has(final String key)
    {
        ArrayList scope_stack = _scope_stack();
        int       size        = scope_stack.size();

        for (int i = 0; i < size; i++) {
            Map    scope  = (Map) scope_stack.get(i);
            Object exists = scope.get(key);

            if (exists != null) {
                return exists;
            }
        }

        return null;
    }

    /*
    # Returns the list of currently active scopes. The list is ordered from inner
    # most scope to outer most scope. The global scope is always the last scope
    # in the list.
    */
    private ArrayList _scope_stack()
    {
        ArrayList l = new ArrayList();
        l.addAll(this.scope_stack);
        l.add(this.config);

        return l;
    }

    // java has no way of spilting with including delim
    // he did the actual work -> http://snippets.dzone.com/posts/show/6453
    // this discards empty strings too
    private ArrayList split_include_delim(String val, Pattern regex)
    {
        ArrayList ret = new ArrayList();

        if (val == null) {
            val = "";
        }

        int     last_match = 0;
        Matcher m          = regex.matcher(val);
        String  s          = null;

        while (m.find()) {
            s = val.substring(last_match, m.start());

            if (s.length() != 0) {
                ret.add(s); // empty strings are no good
            }

            s = m.group();

            if (s.length() != 0) {
                ret.add(s); // empty strings are no good
            }

            last_match = m.end();
        }

        s = val.substring(last_match);

        if (s.length() != 0) {
            ret.add(s); // empty strings are no good
        }

        return ret;
    }
}
