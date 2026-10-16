package cc.wechat.observatory;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class ModuleConfigStoreMigrationTest {
    @Test public void readsStringMapWithEscapedCharactersAndUnicode() throws Exception {
        Map<String, String> values = parse("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<map><string name='api_key'>dummy&amp;&lt;&quot;&apos;</string>"
                + "<string name='bridge_url'><![CDATA[https://example.invalid/网关]]></string></map>");
        assertEquals("dummy&<\"'", values.get("api_key"));
        assertEquals("https://example.invalid/网关", values.get("bridge_url"));
    }

    @Test public void currentSnapshotWinsWithoutReadingOldPrivateFile() throws Exception {
        Properties values = ModuleConfigStore.legacySnapshot(Collections.singletonMap("api_key", "current-dummy"),
                () -> { throw new AssertionError("must not read older private source"); });
        assertEquals("current-dummy", values.getProperty("api_key"));
    }

    @Test public void revisionOnlyClearNeverResurrectsAnOldKey() throws Exception {
        Properties values = ModuleConfigStore.legacySnapshot(
                Collections.singletonMap(ModuleConfigStore.REVISION, "cleared"),
                () -> { throw new AssertionError("clear is authoritative"); });
        assertTrue(values.isEmpty());
    }

    @Test public void emptyCurrentSnapshotImportsOnlyConfigurationKeys() throws Exception {
        Properties values = ModuleConfigStore.legacySnapshot(Collections.emptyMap(), () -> parse(
                "<map><string name='api_key'>private-dummy</string>"
                        + "<string name='__installation_id'>must-not-import</string></map>"));
        assertEquals("private-dummy", values.getProperty("api_key"));
        assertEquals(1, values.size());
    }

    @Test public void malformedOrIncompleteXmlAbortsMigration() throws Exception {
        for (String xml : new String[]{"", "<map><string name='api_key'>dummy", "<map><int name='api_key' value='1'/></map>"}) {
            try {
                ModuleConfigStore.legacySnapshot(Collections.emptyMap(), () -> parse(xml));
                fail("invalid legacy input must not produce an empty migration");
            } catch (IOException expected) { }
        }
    }

    @Test public void declarationsAndDuplicateKeysAreRejected() throws Exception {
        for (String xml : new String[]{
                "<!DOCTYPE map [<!ENTITY old 'dummy'>]><map><string name='api_key'>&old;</string></map>",
                "<!DOCTYPE map SYSTEM 'file:///not-read'><map/>",
                "<map><string name='api_key'>one</string><string name='api_key'>two</string></map>"}) {
            try {
                parse(xml);
                fail("ambiguous or declared XML must be rejected");
            } catch (IOException expected) { }
        }
    }

    @Test public void invalidPropertiesEncodingIsAnIoFailure() throws Exception {
        byte[] bytes = "api_key=\\uINVALID\n".getBytes(StandardCharsets.ISO_8859_1);
        try {
            ModuleConfigStore.loadProperties(new ByteArrayInputStream(bytes), new Properties());
            fail("a corrupt private file must not crash the settings UI or trigger fallback");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    private static Map<String, String> parse(String xml) throws IOException {
        XmlPullParser parser = new KXmlParser();
        try {
            parser.setInput(new StringReader(xml));
            return ModuleConfigStore.parseLegacyXml(parser);
        } catch (org.xmlpull.v1.XmlPullParserException invalid) {
            throw new IOException(invalid);
        }
    }
}
