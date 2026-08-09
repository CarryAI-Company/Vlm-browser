package co.carryai.chromeclone;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the origin-rule format passed to WebViewCompat.addDocumentStartJavaScript.
 *
 * The API only accepts bare origins (scheme://host, e.g. "https://localhost",
 * "https://*.carryai.co"). Any rule containing a path component ("https://x/*")
 * throws IllegalArgumentException, and because the whole call fails atomically,
 * one bad rule silently disables document-start bridge injection — which was an
 * observed production bug (parse-time getDisplayMedia feature detection broke
 * on the PCF demo). These tests pin the rule format so it cannot regress.
 */
public class DocumentStartRulesTest {

    private static final Set<String> RULES = MainActivity.buildDocumentStartRules();

    @Test
    public void rules_containBareOriginsForAllowedHosts() {
        assertTrue("carryai.co https origin missing", RULES.contains("https://carryai.co"));
        assertTrue("carryai.co http origin missing", RULES.contains("http://carryai.co"));
        assertTrue("localhost origin missing", RULES.contains("https://localhost"));
        assertTrue("127.0.0.1 origin missing", RULES.contains("https://127.0.0.1"));
    }

    @Test
    public void rules_containSubdomainWildcardOrigins() {
        // 2026-pcf-demo.carryai.co must match via the *.carryai.co origin.
        assertTrue("subdomain https origin missing", RULES.contains("https://*.carryai.co"));
        assertTrue("subdomain http origin missing", RULES.contains("http://*.carryai.co"));
    }

    @Test
    public void rules_haveNoPathComponents() {
        // The API rejects any rule containing a path — including "/*".
        for (String rule : RULES) {
            // Origin = scheme://host[:port] only. Nothing after the authority.
            String afterScheme = rule.substring(rule.indexOf("://") + 3);
            assertFalse("rule contains a path component (breaks "
                            + "addDocumentStartJavaScript): " + rule,
                    afterScheme.contains("/"));
        }
    }

    @Test
    public void rules_useOnlyHttpOrHttpsSchemes() {
        // file: URLs have no host, so no file: rule can be valid; file pages
        // rely on the evaluateJavascript fallback injection instead.
        for (String rule : RULES) {
            assertTrue("rule must start with http:// or https://: " + rule,
                    rule.startsWith("http://") || rule.startsWith("https://"));
        }
    }

    @Test
    public void rules_areNonEmptyAndSized() {
        assertFalse("rules must not be empty", RULES.isEmpty());
        // 3 hosts: localhost + 127.0.0.1 (2 schemes each) + carryai.co
        // (2 schemes + 2 wildcard schemes) = 8 rules.
        assertEquals("unexpected rule count: " + RULES, 8, RULES.size());
    }
}
