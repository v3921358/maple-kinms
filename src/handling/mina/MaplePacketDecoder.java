/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package handling.mina;

import client.MapleClient;
import constants.ServerConstants;
import handling.RecvPacketOpcode;
import tools.MapleAESOFB;
import tools.MapleCustomEncryption;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.FileoutputUtil;
import tools.HexTool;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericLittleEndianAccessor;

public class MaplePacketDecoder extends CumulativeProtocolDecoder {

    public static final String DECODER_STATE_KEY = MaplePacketDecoder.class.getName() + ".STATE";
    private static Logger log = LoggerFactory.getLogger(MaplePacketDecoder.class);

    public static class DecoderState {

        public int packetlength = -1;
    }

    @Override
    protected boolean doDecode(IoSession session, ByteBuffer in, ProtocolDecoderOutput out) throws Exception {
        DecoderState decoderState = (DecoderState) session.getAttribute(DECODER_STATE_KEY);

        if (decoderState == null) {
            decoderState = new DecoderState();
            session.setAttribute(DECODER_STATE_KEY, decoderState);
        }

        final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);

        if (decoderState.packetlength == -1) {
            if (in.remaining() >= 4) {
                final int packetHeader = in.getInt();
                if (!client.getReceiveCrypto().checkPacket(packetHeader)) {
                    session.close();
                    String note = "时间：" + FileoutputUtil.CurrentReadable_Time() + " "
                            + "|| packetHeader：" + packetHeader + ""
                            + "|| 玩家名字：" + client.getPlayer().getName() + ""
                            + "|| 玩家地图：" + client.getPlayer().getMapId() + "\r\n";
                    FileoutputUtil.packetLog("logs\\客户端包掉线.log", note);
                    return false;
                }
                decoderState.packetlength = MapleAESOFB.getPacketLength(packetHeader);
            } else if ((in.remaining() < 4) && (decoderState.packetlength == -1)) {
                log.trace("解码…没有足够的数据/就是所谓的包不完整");
                return false;
            }
        }
        if (in.remaining() >= decoderState.packetlength) {//079
            byte[] decryptedPacket = new byte[decoderState.packetlength];
            in.get(decryptedPacket, 0, decoderState.packetlength);
            decoderState.packetlength = -1;

            client.getReceiveCrypto().crypt(decryptedPacket);
            MapleCustomEncryption.decryptData(decryptedPacket);
            out.write(decryptedPacket);
            return true;
        }
        /* if (in.remaining() >= decoderState.packetlength) {
            final byte decryptedPacket[] = new byte[decoderState.packetlength];
            in.get(decryptedPacket, 0, decoderState.packetlength);
            decoderState.packetlength = -1;

            client.getReceiveCrypto().crypt(decryptedPacket);
//	    MapleCustomEncryption.decryptData(decryptedPacket);
            out.write(decryptedPacket);
            return true;
        }*/
        return false;
    }

    private String lookupSend(int val) {
        for (RecvPacketOpcode op : RecvPacketOpcode.values()) {
            if (op.getValue() == val) {
                return op.name();
            }
        }
        return "UNKNOWN";
    }

    private int readFirstShort(byte[] arr) {
        return new GenericLittleEndianAccessor(new ByteArrayByteStream(arr)).readShort();
    }
}
