package server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.io.Serializable;

import constants.GameConstants;
import client.inventory.ItemLoader;
import client.inventory.IItem;
import client.MapleClient;
import client.inventory.MapleInventoryType;
import database.DatabaseConnection;
import database.DatabaseException;
import java.util.EnumMap;
import scripting.NPCScriptManager;
import tools.MaplePacketCreator;
import tools.Pair;

/**
 *
 * @author zjj
 */
public class MapleStorage implements Serializable {

    private static final long serialVersionUID = 9179541993413738569L;
    private int id;
    private int accountId;
    private List<IItem> items;
    private int meso;
    private byte slots;
    private boolean changed = false;
    private Map<MapleInventoryType, List<IItem>> typeItems = new EnumMap<>(MapleInventoryType.class);

    private MapleStorage(int id, byte slots, int meso, int accountId) {
        this.id = id;
        this.slots = slots;
        this.items = new LinkedList<>();
        this.meso = meso;
        this.accountId = accountId;
    }

    /**
     *
     * @param id
     * @return
     * @throws SQLException
     */
    public static int create(int id) throws SQLException {
        Connection con = DatabaseConnection.getConnection();
        PreparedStatement ps = con.prepareStatement("INSERT INTO storages (accountid, slots, meso) VALUES (?, ?, ?)", DatabaseConnection.RETURN_GENERATED_KEYS);
        ps.setInt(1, id);
        ps.setInt(2, 4);
        ps.setInt(3, 0);
        ps.executeUpdate();

        int storageid;
        ResultSet rs = ps.getGeneratedKeys();
        if (rs.next()) {
            storageid = rs.getInt(1);
            ps.close();
            rs.close();
            return storageid;
        }
        ps.close();
        rs.close();
        throw new DatabaseException("Inserting char failed.");
    }

    /**
     *
     * @param id
     * @return
     */
    public static MapleStorage loadStorage(int id) {
        MapleStorage ret = null;
        int storeId;
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT * FROM storages WHERE accountid = ?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                storeId = rs.getInt("storageid");
                ret = new MapleStorage(storeId, rs.getByte("slots"), rs.getInt("meso"), id);
                rs.close();
                ps.close();

                for (Pair<IItem, MapleInventoryType> mit : ItemLoader.STORAGE.loadItems(false, id).values()) {
                    ret.items.add(mit.getLeft());
                }
            } else {
                storeId = create(id);
                ret = new MapleStorage(storeId, (byte) 4, 0, id);
                rs.close();
                ps.close();
            }
        } catch (SQLException ex) {
            System.err.println("Error loading storage" + ex);
        }
        return ret;
    }

    /**
     *
     */
    public void saveToDB() {
        if (!changed) {
            return;
        }
        try {
            Connection con = DatabaseConnection.getConnection();

            PreparedStatement ps = con.prepareStatement("UPDATE storages SET slots = ?, meso = ? WHERE storageid = ?");
            ps.setInt(1, slots);
            ps.setInt(2, meso);
            ps.setInt(3, id);
            ps.executeUpdate();
            ps.close();

            List<Pair<IItem, MapleInventoryType>> listing = new ArrayList<>();
            for (final IItem item : items) {
                listing.add(new Pair<>(item, GameConstants.getInventoryType(item.getItemId())));
            }
            ItemLoader.STORAGE.saveItems(listing, accountId);
        } catch (SQLException ex) {
            System.err.println("Error saving storage" + ex);
        }
    }

    /**
     *
     * @param slot
     * @return
     */
    public IItem takeOut(byte slot) {
        if (slot >= items.size() || slot < 0) {
            return null;
        }
        changed = true;
        IItem ret = items.remove(slot);
        MapleInventoryType type = GameConstants.getInventoryType(ret.getItemId());
        typeItems.put(type, new ArrayList<>(filterItems(type)));
        return ret;
    }

    /**
     *
     * @param item
     */
    public void store(IItem item) {
        changed = true;
        items.add(item);
        MapleInventoryType type = GameConstants.getInventoryType(item.getItemId());
        typeItems.put(type, new ArrayList<>(filterItems(type)));
    }

    /**
     *
     * @return
     */
    public List<IItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    private List<IItem> filterItems(MapleInventoryType type) {
        List<IItem> ret = new LinkedList<>();

        for (IItem item : items) {
            if (GameConstants.getInventoryType(item.getItemId()) == type) {
                ret.add(item);
            }
        }
        return ret;
    }

    /**
     *
     * @param type
     * @param slot
     * @return
     */
    public byte getSlot(MapleInventoryType type, byte slot) {
        // MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        byte ret = 0;
        final List<IItem> it = typeItems.get(type);
        if (slot >= it.size() || slot < 0) {
            return -1;
        }
        for (IItem item : items) {
            if (item == it.get(slot)) {
                return ret;
            }
            ret++;
        }
        return -1;
    }

    /**
     *
     * @param c
     * @param npcId
     */
    public void sendStorage(MapleClient c, int npcId) {
        if (!Boolean.parseBoolean(ServerProperties.getProperty("KinMS.仓库", "false"))) {
            c.getSession().write(MaplePacketCreator.getNPCTalk(1052017, (byte) 0, "仓库暂时无法使用", "00 00", (byte) 0));
            NPCScriptManager.getInstance().dispose(c);
            return;
        }

        // sort by inventorytype to avoid confusion
        Collections.sort(items, new Comparator<IItem>() {

            @Override
            public int compare(IItem o1, IItem o2) {
                if (GameConstants.getInventoryType(o1.getItemId()).getType() < GameConstants.getInventoryType(o2.getItemId()).getType()) {
                    return -1;
                } else if (GameConstants.getInventoryType(o1.getItemId()) == GameConstants.getInventoryType(o2.getItemId())) {
                    return 0;
                } else {
                    return 1;
                }
            }
        });
        for (MapleInventoryType type : MapleInventoryType.values()) {
            typeItems.put(type, new ArrayList<>(items));
        }
        c.getSession().write(MaplePacketCreator.getStorage(npcId, slots, items, meso));
    }

    /**
     *
     * @param c
     * @param type
     */
    public void sendStored(MapleClient c, MapleInventoryType type) {
        c.getSession().write(MaplePacketCreator.storeStorage(slots, type, typeItems.get(type)));
    }

    /**
     *
     * @param c
     * @param type
     */
    public void sendTakenOut(MapleClient c, MapleInventoryType type) {
        c.getSession().write(MaplePacketCreator.takeOutStorage(slots, type, typeItems.get(type)));
    }

    /**
     *
     * @return
     */
    public int getMeso() {
        return meso;
    }

    /**
     *
     * @param itemId
     * @return
     */
    public IItem findById(int itemId) {
        for (IItem item : items) {
            if (item.getItemId() == itemId) {
                return item;
            }
        }
        return null;
    }

    /**
     *
     * @param meso
     */
    public void setMeso(int meso) {
        if (meso < 0) {
            return;
        }
        changed = true;
        this.meso = meso;
    }

    /**
     *
     * @param c
     */
    public void sendMeso(MapleClient c) {
        c.getSession().write(MaplePacketCreator.mesoStorage(slots, meso));
    }

    /**
     *
     * @return
     */
    public boolean isFull() {
        return items.size() >= slots;
    }

    /**
     *
     * @return
     */
    public int getSlots() {
        return slots;
    }

    /**
     *
     * @param gain
     */
    public void increaseSlots(byte gain) {
        changed = true;
        this.slots += gain;
    }

    /**
     *
     * @param set
     */
    public void setSlots(byte set) {
        changed = true;
        this.slots = set;
    }

    /**
     *
     */
    public void close() {
        typeItems.clear();
    }
}
