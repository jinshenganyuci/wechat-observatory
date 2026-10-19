package cc.wechat.observatory.wechat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Verified against 8.0.79/3200: im.c8.parseBuff() reads msgSource as LVBuffer field 3. */
public final class WeChat8079Mentions {
    private WeChat8079Mentions() {}

    public static Set<String> read(byte[] lvbuffer) {
        if (lvbuffer == null || lvbuffer.length < 9 || lvbuffer.length > 1024 * 1024
                || lvbuffer[0] != '{' || lvbuffer[lvbuffer.length - 1] != '}') {
            return Collections.emptySet();
        }
        try {
            // e2.i/g/e: big-endian short-length UTF-8 strings, then an int, then msgSource.
            // Read only these verified fields; neither chat text nor a label is evidence.
            ByteBuffer buffer = ByteBuffer.wrap(lvbuffer);
            buffer.position(1);
            buffer.limit(lvbuffer.length - 1);
            int reservedLength = buffer.getShort();
            if (reservedLength < 0) return Collections.emptySet();
            buffer.position(buffer.position() + reservedLength);
            buffer.getInt();
            int sourceLength = buffer.getShort();
            if (sourceLength < 0 || sourceLength > buffer.remaining()) return Collections.emptySet();
            byte[] source = new byte[sourceLength];
            buffer.get(source);
            return parseSource(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString());
        } catch (Exception malformed) {
            return Collections.emptySet();
        }
    }

    static Set<String> parseSource(String source) {
        if (source == null || source.isEmpty() || source.length() > 32768) return Collections.emptySet();
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(source));
            Set<String> mentions = new LinkedHashSet<>();
            boolean root = false;
            boolean closed = false;
            boolean found = false;
            for (int token = parser.nextToken(); token != XmlPullParser.END_DOCUMENT; token = parser.nextToken()) {
                if (token == XmlPullParser.DOCDECL || token == XmlPullParser.ENTITY_REF) return Collections.emptySet();
                if (token == XmlPullParser.END_TAG && parser.getDepth() == 1) closed = true;
                if (token != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1) {
                    if (root || !"msgsource".equals(parser.getName())) return Collections.emptySet();
                    root = true;
                } else if (parser.getDepth() == 2 && "atuserlist".equals(parser.getName())) {
                    if (found) return Collections.emptySet();
                    found = true;
                    for (String value : parser.nextText().split(",")) {
                        String wxid = value.trim();
                        // @all is not an explicit mention of the bot. IDs, never labels.
                        if (wxid.matches("[A-Za-z0-9_-]{1,128}")) mentions.add(wxid);
                        if (mentions.size() > 512) return Collections.emptySet();
                    }
                }
            }
            return root && closed ? mentions : Collections.emptySet();
        } catch (Exception malformed) {
            return Collections.emptySet();
        }
    }
}
