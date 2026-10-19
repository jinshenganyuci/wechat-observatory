package cc.wechat.observatory.wechat;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class WeChat8079MentionsTest {
    private static byte[] buffer(String reserved, String source) {
        byte[] first = reserved.getBytes(StandardCharsets.UTF_8);
        byte[] third = source.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(10 + first.length + third.length)
                .put((byte) '{').putShort((short) first.length).put(first).putInt(7)
                .putShort((short) third.length).put(third).put((byte) '}').array();
    }

    @Test public void decodesOnlyVerifiedNativeSourceField() {
        String mention = "<msgsource><atuserlist>wxid_bot</atuserlist></msgsource>";
        assertEquals(new LinkedHashSet<>(Arrays.asList("wxid_bot")), WeChat8079Mentions.read(buffer("reserved", mention)));
        assertTrue(WeChat8079Mentions.read(buffer(mention, "")).isEmpty());
        byte[] valid = buffer("", mention);
        for (int length = 0; length < valid.length; length++) {
            assertTrue("truncated at " + length, WeChat8079Mentions.read(Arrays.copyOf(valid, length)).isEmpty());
        }
        valid[1] = (byte) 0xff;
        assertTrue(WeChat8079Mentions.read(valid).isEmpty());
    }

    @Test public void readsExactIdsFromNativeSourceIncludingCdata() {
        assertEquals(new LinkedHashSet<>(Arrays.asList("wxid_bot", "wxid_friend")),
                WeChat8079Mentions.parseSource("<msgsource><atuserlist><![CDATA[wxid_bot, wxid_friend,wxid_bot]]></atuserlist><silence>0</silence></msgsource>"));
    }

    @Test public void textAndNestedLookalikesCannotSupplyMentions() {
        for (String source : new String[]{null, "", "@机器人 hello", "<msg><atuserlist>wxid_bot</atuserlist></msg>",
                "<msgsource><text><atuserlist>wxid_bot</atuserlist></text></msgsource>",
                "<msgsource><atuserlist>notify@all,@机器人</atuserlist></msgsource>"}) {
            assertTrue(String.valueOf(source), WeChat8079Mentions.parseSource(source).isEmpty());
        }
    }

    @Test public void rejectsMalformedAmbiguousAndEntitySources() {
        for (String source : new String[]{"<msgsource><atuserlist>wxid_bot</atuserlist>",
                "<msgsource><atuserlist>wxid_bot</atuserlist><atuserlist>wxid_other</atuserlist></msgsource>",
                "<!DOCTYPE msgsource [<!ENTITY bot 'wxid_bot'>]><msgsource><atuserlist>&bot;</atuserlist></msgsource>",
                "<msgsource><atuserlist>wxid_bot</atuserlist></msgsource><msgsource/>",
                "<msgsource><atuserlist><b>wxid_bot</b></atuserlist></msgsource>"}) {
            assertTrue(source, WeChat8079Mentions.parseSource(source).isEmpty());
        }
    }

    @Test public void boundsSourceAndListAndDoesNotDecodeAbsentBuffer() throws Exception {
        assertTrue(WeChat8079Mentions.read(null).isEmpty());
        assertTrue(WeChat8079Mentions.read(new byte[0]).isEmpty());
        StringBuilder source = new StringBuilder("<msgsource><atuserlist>");
        for (int i=0; i<513; i++) source.append("wxid_").append(i).append(',');
        source.append("</atuserlist></msgsource>");
        assertTrue(WeChat8079Mentions.parseSource(source.toString()).isEmpty());
        assertTrue(WeChat8079Mentions.parseSource(new String(new char[32769])).isEmpty());
    }
}
