package net.osmand.plus.mapmarkers;

import android.support.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.plus.MapMarkersHelper.MapMarker;
import net.osmand.plus.MapMarkersHelper.MarkersSyncGroup;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.api.SQLiteAPI.SQLiteConnection;
import net.osmand.plus.api.SQLiteAPI.SQLiteCursor;
import net.osmand.plus.helpers.SearchHistoryHelper;

import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

public class MapMarkersDbHelper {

	private static final int DB_VERSION = 6;
	public static final String DB_NAME = "map_markers_db";

	private static final String MARKERS_TABLE_NAME = "map_markers";
	private static final String MARKERS_COL_ID = "marker_id";
	private static final String MARKERS_COL_LAT = "marker_lat";
	private static final String MARKERS_COL_LON = "marker_lon";
	private static final String MARKERS_COL_DESCRIPTION = "marker_description";
	private static final String MARKERS_COL_ACTIVE = "marker_active";
	private static final String MARKERS_COL_ADDED = "marker_added";
	private static final String MARKERS_COL_VISITED = "marker_visited";
	private static final String MARKERS_COL_GROUP_NAME = "group_name";
	private static final String MARKERS_COL_GROUP_KEY = "group_key";
	private static final String MARKERS_COL_COLOR = "marker_color";
	private static final String MARKERS_COL_NEXT_KEY = "marker_next_key";

	private static final String MARKERS_TABLE_CREATE = "CREATE TABLE IF NOT EXISTS " +
			MARKERS_TABLE_NAME + " (" +
			MARKERS_COL_ID + " TEXT PRIMARY KEY, " +
			MARKERS_COL_LAT + " double, " +
			MARKERS_COL_LON + " double, " +
			MARKERS_COL_DESCRIPTION + " TEXT, " +
			MARKERS_COL_ACTIVE + " int, " + // 1 = true, 0 = false
			MARKERS_COL_ADDED + " long, " +
			MARKERS_COL_VISITED + " long, " +
			MARKERS_COL_GROUP_NAME + " TEXT, " +
			MARKERS_COL_GROUP_KEY + " TEXT, " +
			MARKERS_COL_COLOR + " int, " +
			MARKERS_COL_NEXT_KEY + " TEXT);";

	private static final String MARKERS_TABLE_SELECT = "SELECT " +
			MARKERS_COL_ID + ", " +
			MARKERS_COL_LAT + ", " +
			MARKERS_COL_LON + ", " +
			MARKERS_COL_DESCRIPTION + ", " +
			MARKERS_COL_ACTIVE + ", " +
			MARKERS_COL_ADDED + ", " +
			MARKERS_COL_VISITED + ", " +
			MARKERS_COL_GROUP_NAME + ", " +
			MARKERS_COL_GROUP_KEY + ", " +
			MARKERS_COL_COLOR + ", " +
			MARKERS_COL_NEXT_KEY +
			" FROM " + MARKERS_TABLE_NAME;

	private static final String GROUPS_TABLE_NAME = "map_markers_groups";
	private static final String GROUPS_COL_ID = "group_id";
	private static final String GROUPS_COL_NAME = "group_name";
	private static final String GROUPS_COL_TYPE = "group_type";

	private static final String GROUPS_TABLE_CREATE = "CREATE TABLE IF NOT EXISTS " +
			GROUPS_TABLE_NAME + " (" +
			GROUPS_COL_ID + " TEXT PRIMARY KEY, " +
			GROUPS_COL_NAME + " TEXT, " +
			GROUPS_COL_TYPE + " int);";

	private static final String GROUPS_TABLE_SELECT = "SELECT " +
			GROUPS_COL_ID + ", " +
			GROUPS_COL_NAME + ", " +
			GROUPS_COL_TYPE +
			" FROM " + GROUPS_TABLE_NAME;

	public static final String TAIL_NEXT_VALUE = "tail_next";
	public static final String HISTORY_NEXT_VALUE = "history_next";

	private final OsmandApplication context;

	public MapMarkersDbHelper(OsmandApplication context) {
		this.context = context;
	}

	private SQLiteConnection openConnection(boolean readonly) {
		SQLiteConnection conn = context.getSQLiteAPI().getOrCreateDatabase(DB_NAME, readonly);
		int version = conn.getVersion();
		if (version == 0 || DB_VERSION != version) {
			if (readonly) {
				conn.close();
				conn = context.getSQLiteAPI().getOrCreateDatabase(DB_NAME, false);
			}
			version = conn.getVersion();
			conn.setVersion(DB_VERSION);
			if (version == 0) {
				onCreate(conn);
			} else {
				onUpgrade(conn, version, DB_VERSION);
			}
		}
		return conn;
	}

	private void onCreate(SQLiteConnection db) {
		db.execSQL(MARKERS_TABLE_CREATE);
		db.execSQL(GROUPS_TABLE_CREATE);
		saveExistingMarkersToDb();
	}

	private void onUpgrade(SQLiteConnection db, int oldVersion, int newVersion) {
		db.execSQL("DROP TABLE IF EXISTS " + MARKERS_TABLE_NAME);
		db.execSQL("DROP TABLE IF EXISTS " + GROUPS_TABLE_NAME);
		onCreate(db);
	}

	private void saveExistingMarkersToDb() {
		OsmandSettings settings = context.getSettings();

		List<LatLon> ips = settings.getMapMarkersPoints();
		List<String> desc = settings.getMapMarkersPointDescriptions(ips.size());
		List<Integer> colors = settings.getMapMarkersColors(ips.size());
		int colorIndex = 0;
		for (int i = 0; i < ips.size(); i++) {
			if (colors.size() > i) {
				colorIndex = colors.get(i);
			}
			MapMarker marker = new MapMarker(ips.get(i), PointDescription.deserializeFromString(desc.get(i), ips.get(i)),
					colorIndex, false, i);
			marker.history = false;
			addMarker(marker, true);
		}

		ips = settings.getMapMarkersHistoryPoints();
		desc = settings.getMapMarkersHistoryPointDescriptions(ips.size());
		colors = settings.getMapMarkersHistoryColors(ips.size());
		for (int i = 0; i < ips.size(); i++) {
			if (colors.size() > i) {
				colorIndex = colors.get(i);
			}
			MapMarker marker = new MapMarker(ips.get(i), PointDescription.deserializeFromString(desc.get(i), ips.get(i)),
					colorIndex, false, i);
			marker.history = true;
			addMarker(marker, true);
		}
	}

	public void addGroup(String id, String name, int type) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				db.execSQL("INSERT INTO " + GROUPS_TABLE_NAME + " VALUES (?, ?, ?)", new Object[]{id, name, type});
			} finally {
				db.close();
			}
		}
	}

	@Nullable
	public MarkersSyncGroup getGroup(String id) {
		MarkersSyncGroup res = null;
		SQLiteConnection db = openConnection(true);
		if (db != null) {
			try {
				SQLiteCursor query = db.rawQuery(GROUPS_TABLE_SELECT + " WHERE " + GROUPS_COL_ID + " = ?", new String[]{id});
				if (query.moveToFirst()) {
					res = readSyncGroup(query);
				}
				query.close();
			} finally {
				db.close();
			}
		}
		return res;
	}

	private MarkersSyncGroup readSyncGroup(SQLiteCursor query) {
		String id = query.getString(0);
		String name = query.getString(1);
		int type = query.getInt(2);

		return new MarkersSyncGroup(id, name, type);
	}

	public void addMarker(MapMarker marker) {
		addMarker(marker, false);
	}

	private void addMarker(MapMarker marker, boolean saveExisting) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				insertLast(db, marker, saveExisting);
			} finally {
				db.close();
			}
		}
	}

	private void insertLast(SQLiteConnection db, MapMarker marker, boolean saveExisting) {
		long currentTime;
		if (saveExisting) {
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.MONTH, -1);
			currentTime = cal.getTimeInMillis();
		} else {
			currentTime = System.currentTimeMillis();
		}
		if (marker.id == null) {
			marker.id = String.valueOf(currentTime) + String.valueOf(new Random().nextInt(900) + 100);
		}
		marker.creationDate = currentTime;
		String descr = PointDescription.serializeToString(marker.getOriginalPointDescription());
		int active = marker.history ? 0 : 1;
		long visited = saveExisting ? currentTime : 0;

		PointDescription pointDescription = marker.getOriginalPointDescription();
		if (pointDescription != null && !pointDescription.isSearchingAddress(context)) {
			SearchHistoryHelper.getInstance(context)
					.addNewItemToHistory(marker.getLatitude(), marker.getLongitude(), pointDescription);
		}

		if (!marker.history) {
			db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " + MARKERS_COL_NEXT_KEY + " = ? " +
					"WHERE " + MARKERS_COL_NEXT_KEY + " = ?", new Object[]{marker.id, TAIL_NEXT_VALUE});
		}

		db.execSQL("INSERT INTO " + MARKERS_TABLE_NAME + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				new Object[]{marker.id, marker.getLatitude(), marker.getLongitude(), descr, active,
						currentTime, visited, marker.groupName, marker.groupKey, marker.colorIndex,
						marker.history ? HISTORY_NEXT_VALUE : TAIL_NEXT_VALUE});
	}

	public List<MapMarker> getActiveMarkers() {
		List<MapMarker> res = new LinkedList<>();
		HashMap<String, MapMarker> markers = new LinkedHashMap<>();
		SQLiteConnection db = openConnection(true);
		if (db != null) {
			try {
				SQLiteCursor query = db.rawQuery(MARKERS_TABLE_SELECT + " WHERE " + MARKERS_COL_ACTIVE + " = ?",
						new String[]{String.valueOf(1)});
				if (query.moveToFirst()) {
					do {
						MapMarker marker = readItem(query);
						markers.put(marker.id, marker);
					} while (query.moveToNext());
				}
				query.close();
			} finally {
				db.close();
			}
			buildLinkedList(markers, res);
		}
		return res;
	}

	private MapMarker readItem(SQLiteCursor query) {
		String id = query.getString(0);
		double lat = query.getDouble(1);
		double lon = query.getDouble(2);
		String desc = query.getString(3);
		boolean active = query.getInt(4) == 1;
		long added = query.getLong(5);
		long visited = query.getLong(6);
		String groupName = query.getString(7);
		String groupKey = query.getString(8);
		int colorIndex = query.getInt(9);
		String nextKey = query.getString(10);

		LatLon latLon = new LatLon(lat, lon);
		MapMarker marker = new MapMarker(latLon, PointDescription.deserializeFromString(desc, latLon),
				colorIndex, false, 0);
		marker.id = id;
		marker.history = !active;
		marker.creationDate = added;
		marker.visitedDate = visited;
		marker.groupName = groupName;
		marker.groupKey = groupKey;
		marker.nextKey = nextKey;

		return marker;
	}

	private void buildLinkedList(HashMap<String, MapMarker> markers, List<MapMarker> res) {
		if (!markers.isEmpty()) {
			int count = 1;
			for (MapMarker marker : markers.values()) {
				if (!markers.keySet().contains(marker.nextKey) || count == markers.size()) {
					res.add(0, marker);
					markers.remove(marker.id);
					break;
				}
				count++;
			}
			buildLinkedList(markers, res);
		}
	}

	public void updateMarker(MapMarker marker) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				String descr = PointDescription.serializeToString(marker.getOriginalPointDescription());
				db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " +
								MARKERS_COL_LAT + " = ?, " +
								MARKERS_COL_LON + " = ?, " +
								MARKERS_COL_DESCRIPTION + " = ?, " +
								MARKERS_COL_COLOR + " = ? " +
								"WHERE " + MARKERS_COL_ID + " = ?",
						new Object[]{marker.getLatitude(), marker.getLongitude(), descr, marker.colorIndex, marker.id});
			} finally {
				db.close();
			}
		}
	}

	public void changeActiveMarkerPosition(MapMarker moved, @Nullable MapMarker next) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " + MARKERS_COL_NEXT_KEY + " = ? " +
						"WHERE " + MARKERS_COL_ID + " = ?", new Object[]{next == null ? TAIL_NEXT_VALUE : next.id, moved.id});
			} finally {
				db.close();
			}
		}
	}

	public void moveMarkerToHistory(MapMarker marker) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				marker.visitedDate = System.currentTimeMillis();

				db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " +
						MARKERS_COL_ACTIVE + " = ?, " +
						MARKERS_COL_VISITED + " = ?, " +
						MARKERS_COL_NEXT_KEY + " = ? " +
						"WHERE " + MARKERS_COL_ID + " = ?", new Object[]{0, marker.visitedDate, HISTORY_NEXT_VALUE, marker.id});
			} finally {
				db.close();
			}
		}
	}

	public void moveAllActiveMarkersToHistory() {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				long visitedDate = System.currentTimeMillis();
				db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " +
						MARKERS_COL_ACTIVE + " = ?, " +
						MARKERS_COL_VISITED + " = ?, " +
						MARKERS_COL_NEXT_KEY + " = ? " +
						"WHERE " + MARKERS_COL_ACTIVE + " = ?", new Object[]{0, visitedDate, HISTORY_NEXT_VALUE, 1});
			} finally {
				db.close();
			}
		}
	}

	public void restoreMapMarkerFromHistory(MapMarker marker) {
		SQLiteConnection db = openConnection(false);
		if (db != null) {
			try {
				db.execSQL("UPDATE " + MARKERS_TABLE_NAME + " SET " +
								MARKERS_COL_ACTIVE + " = ? " +
								"WHERE " + MARKERS_COL_ID + " = ? " +
								"AND " + MARKERS_COL_ACTIVE + " = ?",
						new Object[]{1, marker.id, 0});
			} finally {
				db.close();
			}
		}
	}

	public List<MapMarker> getMarkersHistory() {
		List<MapMarker> markers = new LinkedList<>();
		SQLiteConnection db = openConnection(true);
		if (db != null) {
			try {
				SQLiteCursor query = db.rawQuery(MARKERS_TABLE_SELECT + " WHERE " + MARKERS_COL_ACTIVE + " = ?",
						new String[]{String.valueOf(0)});
				if (query.moveToFirst()) {
					do {
						markers.add(readItem(query));
					} while (query.moveToNext());
				}
				query.close();
			} finally {
				db.close();
			}
		}
		return markers;
	}

	public void removeMarkerFromHistory(MapMarker marker) {
		SQLiteConnection db = openConnection(true);
		if (db != null) {
			try {
				db.execSQL("DELETE FROM " + MARKERS_TABLE_NAME +
								" WHERE " + MARKERS_COL_ID + " = ?" +
								" AND " + MARKERS_COL_ACTIVE + " = ?",
						new Object[]{marker.id, 0});
			} finally {
				db.close();
			}
		}
	}

	public void clearAllMarkersHistory() {
		SQLiteConnection db = openConnection(true);
		if (db != null) {
			try {
				db.execSQL("DELETE FROM " + MARKERS_TABLE_NAME + " WHERE " + MARKERS_COL_ACTIVE + " = ?",
						new Object[]{0});
			} finally {
				db.close();
			}
		}
	}
}
