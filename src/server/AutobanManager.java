package server;

import client.MapleClient;
import handling.world.World;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import tools.FileoutputUtil;
import tools.MaplePacketCreator;

/**
 *
 * @author zjj
 */
public class AutobanManager implements Runnable {

    private static class ExpirationEntry implements Comparable<ExpirationEntry> {

        public long time;
        public int acc;
        public int points;

        public ExpirationEntry(long time, int acc, int points) {
            this.time = time;
            this.acc = acc;
            this.points = points;
        }

        @Override
        public int compareTo(AutobanManager.ExpirationEntry o) {
            return (int) (time - o.time);
        }

        @Override
        public boolean equals(Object oth) {
            if (!(oth instanceof AutobanManager.ExpirationEntry)) {
                return false;
            }
            final AutobanManager.ExpirationEntry ee = (AutobanManager.ExpirationEntry) oth;
            return (time == ee.time && points == ee.points && acc == ee.acc);
        }
    }
    private Map<Integer, Integer> points = new HashMap<>();
    private Map<Integer, List<String>> reasons = new HashMap<>();
    private Set<ExpirationEntry> expirations = new TreeSet<>();
    private static final int AUTOBAN_POINTS = 5000;
    private static AutobanManager instance = new AutobanManager();
    private final ReentrantLock lock = new ReentrantLock(true);

    /**
     *
     * @return
     */
    public static final AutobanManager getInstance() {
        return instance;
    }

    /**
     *
     * @param c
     * @param reason
     */
    public final void autoban(final MapleClient c, final String reason) {
        if (c.getPlayer().isGM() || c.getPlayer().isClone()) {
            c.getPlayer().dropMessage(5, "[WARNING] A/b triggled : " + reason);
            return;
        }
        addPoints(c, AUTOBAN_POINTS, 0, reason);
    }

    /**
     *
     * @param c
     * @param points
     * @param expiration
     * @param reason
     */
    public final void addPoints(final MapleClient c, final int points, final long expiration, final String reason) {
        lock.lock();
        try {
            List<String> reasonList;
            final int acc = c.getPlayer().getAccountID();

            if (this.points.containsKey(acc)) {
                final int SavedPoints = this.points.get(acc);
                if (SavedPoints >= AUTOBAN_POINTS) { // Already auto ban'd.
                    return;
                }
                this.points.put(acc, SavedPoints + points); // Add
                reasonList = this.reasons.get(acc);
                reasonList.add(reason);
            } else {
                this.points.put(acc, points);
                reasonList = new LinkedList<>();
                reasonList.add(reason);
                this.reasons.put(acc, reasonList);
            }

            if (this.points.get(acc) >= AUTOBAN_POINTS) { // See if it's sufficient to auto ban
                if (c.getPlayer().isGM() || c.getPlayer().isClone()) {
                    c.getPlayer().dropMessage(5, "[WARNING] A/b triggled : " + reason);
                    return;
                }
                final StringBuilder sb = new StringBuilder("a/b ");
                sb.append(c.getPlayer().getName());
                sb.append(" (IP ");
                sb.append(c.getSession().getRemoteAddress().toString());
                sb.append("): ");
                for (final String s : reasons.get(acc)) {
                    sb.append(s);
                    sb.append(", ");
                }
                World.Broadcast.broadcastMessage(MaplePacketCreator.serverNotice(0, "'" + c.getPlayer().getName() + "'非法使用外挂程序已经记录在案！稍后等GM处理！").getBytes()); 
                String note = "时间：" + FileoutputUtil.CurrentReadable_Time() + " "
                            + "|| 玩家名字：" + c.getPlayer().getName() + ""
                            + "|| 玩家地图：" + c.getPlayer().getMapId() + "\r\n";
                    FileoutputUtil.packetLog("log\\外挂检测A\\" + c.getPlayer().getName() + ".log", note);
//		Calendar cal = Calendar.getInstance();
//		cal.add(Calendar.DATE, 60);
//		c.getPlayer().tempban(sb.toString(), cal, 1, false);
                c.getPlayer().ban(sb.toString(), false, true, false);
                c.disconnect(true, false);
            } else {
                if (expiration > 0) {
                    expirations.add(new ExpirationEntry(System.currentTimeMillis() + expiration, acc, points));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public final void run() {
        final long now = System.currentTimeMillis();
        for (final ExpirationEntry e : expirations) {
            if (e.time <= now) {
                this.points.put(e.acc, this.points.get(e.acc) - e.points);
            } else {
                return;
            }
        }
    }
}
