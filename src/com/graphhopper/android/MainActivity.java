package com.graphhopper.android;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mapsforge.android.maps.MapActivity;
import org.mapsforge.android.maps.MapView;
import org.mapsforge.android.maps.Projection;
import org.mapsforge.android.maps.overlay.ListOverlay;
import org.mapsforge.android.maps.overlay.Marker;
import org.mapsforge.android.maps.overlay.PolygonalChain;
import org.mapsforge.android.maps.overlay.Polyline;
import org.mapsforge.core.model.GeoPoint;
import org.mapsforge.map.reader.header.FileOpenResult;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.graphhopper.util.StopWatch;

public class MainActivity extends MapActivity {

	private MapView mapView;
	private GraphHopperAPI hopper;
	private GeoPoint start;
	private GeoPoint end;
	private Spinner localSpinner;
	private Button localButton;
	private Spinner remoteSpinner;
	private Button remoteButton;
	private ListOverlay pathOverlay = new ListOverlay();
	private volatile boolean prepareInProgress = false;
	private volatile boolean shortestPathRunning = false;
	private String currentArea = "berlin";
	private String fileListURL = "https://graphhopper.googlecode.com/files/files.txt";
	private String downloadURL;
	private String mapsFolder;
	private String mapFile;
	private SimpleOnGestureListener gestureListener = new SimpleOnGestureListener() {
		// why does this fail? public boolean onDoubleTap(MotionEvent e) {};
		public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
			if (!initFiles()) {
				return false;
			}

			if (shortestPathRunning) {
				logUser("Calculation still in progress");
				return false;
			}
			float x = motionEvent.getX();
			float y = motionEvent.getY();
			Projection p = mapView.getProjection();
			GeoPoint tmpPoint = p.fromPixels((int) x, (int) y);

			if (start != null && end == null) {
				end = tmpPoint;
				shortestPathRunning = true;
				Marker marker = createMarker(tmpPoint, R.drawable.flag_red);
				if (marker != null) {
					pathOverlay.getOverlayItems().add(marker);
					mapView.redraw();
				}

				calcPath(start.latitude, start.longitude, end.latitude,
						end.longitude);
			} else {
				start = tmpPoint;
				end = null;
				pathOverlay.getOverlayItems().clear();
				Marker marker = createMarker(start, R.drawable.flag_green);
				if (marker != null) {
					pathOverlay.getOverlayItems().add(marker);
					mapView.redraw();
				}
			}
			return true;
		}
	};
	private GestureDetector gestureDetector = new GestureDetector(
			gestureListener);	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		mapView = new MapView(this) {
			@Override
			public boolean onTouchEvent(MotionEvent event) {
				if (gestureDetector.onTouchEvent(event)) {
					return true;
				}
				return super.onTouchEvent(event);
			}
		};
		mapView.setClickable(true);
		mapView.setBuiltInZoomControls(true);

		final EditText input = new EditText(this);
		input.setText(currentArea);
		mapsFolder = Environment.getExternalStorageDirectory()
				.getAbsolutePath() + "/graphhopper/maps/";
		localSpinner = (Spinner) findViewById(R.id.locale_area_spinner);
		localButton = (Button) findViewById(R.id.locale_button);
		remoteSpinner = (Spinner) findViewById(R.id.remote_area_spinner);
		remoteButton = (Button) findViewById(R.id.remote_button);
		// TODO get user confirmation to download
		// if (AndroidHelper.isFastDownload(this))
		chooseAreaFromRemote();
		chooseAreaFromLocal();
	}

	private boolean initFiles() {
		// only return true if already loaded
		if (hopper != null) {
			return true;
		}
		if (prepareInProgress) {
			logUser("Preparation still in progress");
			return false;
		}
		prepareInProgress = true;
		installMapAndGraph();
		return false;
	}

	private void chooseAreaFromLocal() {
		List<String> nameList = new ArrayList<String>();
		for (String file : new File(mapsFolder).list(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String filename) {
				return filename != null
						&& (filename.endsWith(".ghz") || filename
								.endsWith("-gh"));
			}
		})) {
			nameList.add(file);
		}
		if (nameList.isEmpty())			
			return;
		
		chooseArea(localButton, localSpinner, nameList, new MySpinnerListener() {
			@Override
			public void onSelect(String selected) {
				initFiles();
			}
		});
	}

	private void chooseAreaFromRemote() {
		try {
			String filesList = mapsFolder + "files.txt";
			AndroidHelper.download(fileListURL, filesList);
			List<String> nameList = AndroidHelper.readFile(new FileReader(
					filesList));
			chooseArea(remoteButton, remoteSpinner, nameList, new MySpinnerListener() {
				@Override
				public void onSelect(String selected) {
					if (selected == null
							|| new File(mapsFolder + currentArea + ".ghz")
									.exists()
							|| new File(mapsFolder + currentArea + "-gh")
									.exists()) {
						downloadURL = null;
					} else
						downloadURL = selected;					
					initFiles();
				}
			});
		} catch (Exception ex) {
			logUser("Problem while fetching remote area list: " + ex.getMessage());			
		}
	}

	private void chooseArea(Button button, final Spinner spinner, List<String> nameList,
			final MySpinnerListener mylistener) {
		final Map<String, String> nameToFullName = new LinkedHashMap<String, String>(
				nameList.size());
		for (String fullName : nameList) {
			String tmp = Helper.pruneFileEnd(fullName);
			if (tmp.endsWith("-gh"))
				tmp = tmp.substring(0, tmp.length() - 3);
			tmp = AndroidHelper.getFileName(tmp);
			nameToFullName.put(tmp, fullName);
		}
		nameList.clear();
		nameList.addAll(nameToFullName.keySet());
		ArrayAdapter<String> spinnerArrayAdapter = new ArrayAdapter<String>(
				this, android.R.layout.simple_spinner_dropdown_item, nameList);
		spinner.setAdapter(spinnerArrayAdapter);
		button.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Object o = spinner.getSelectedItem();
				if (o != null && o.toString().length() > 0) {
					currentArea = o.toString();
					mylistener.onSelect(nameToFullName.get(currentArea));
				} else
					mylistener.onSelect(null);
			}
		});
		// trigger spinner popup
		// spinner.performClick();
	}

	public interface MySpinnerListener {
		void onSelect(String selected);
	}

	/**
	 * Download & Unzipping
	 */
	void installMapAndGraph() {
		if (downloadURL != null)
			logUser("Downloading " + downloadURL);

		new AsyncTask<Void, Void, Object>() {
			Throwable error;

			protected Object doInBackground(Void... _ignore) {
				if (downloadURL != null) {
					final String localFile = mapsFolder
							+ AndroidHelper.getFileName(downloadURL);
					try {
						log("downloading " + downloadURL + " to " + localFile);
						AndroidHelper.download(downloadURL, localFile);
					} catch (Throwable t) {
						error = t;
						return null;
					}
				}

				File compressed = new File(mapsFolder + currentArea + ".ghz");
				if (compressed.exists() && !compressed.isDirectory()) {
					try {
						boolean deleteZipped = true;
						Helper.unzip(compressed.getAbsolutePath(), mapsFolder
								+ currentArea + "-gh", deleteZipped);
					} catch (Exception ex) {
						error = ex;
					}
				}
				return null;
			}

			protected void onPostExecute(Object _ignore) {
				if (error == null) {
					logUser("Finished downloading&unzipping. Now loading map.");
				} else {
					logUser("An error happend while retrieving maps:"
							+ error.getMessage());
					return;
				}

				mapFile = mapsFolder + currentArea + "-gh/" + currentArea
						+ ".map";
				FileOpenResult fileOpenResult = mapView.setMapFile(new File(
						mapFile));
				if (!fileOpenResult.isSuccess()) {
					logUser(fileOpenResult.getErrorMessage());
					// finish();
					return;
				}
				setContentView(mapView);
				mapView.getOverlays().clear();
				mapView.getOverlays().add(pathOverlay);
				prepareGraph();
			}
		}.execute();
	}

	void prepareGraph() {
		logUser("loading graph (" + Helper.VERSION + "|"
				+ Helper.VERSION_FILE + ") ... ");
		new AsyncTask<Void, Void, Path>() {
			Throwable error;

			protected Path doInBackground(Void... v) {
				try {
					GraphHopper tmpHopp = new GraphHopper().forAndroid();
					tmpHopp.contractionHierarchies(true);
					tmpHopp.load(mapsFolder + currentArea);
					log("found graph with " + tmpHopp.getGraph().nodes()
							+ " nodes");
					hopper = tmpHopp;
				} catch (Throwable t) {
					error = t;
				}
				return null;
			}

			protected void onPostExecute(Path o) {
				if (error == null) {
					logUser("Finished loading graph");
				} else {
					logUser("An error happend while creating graph:"
							+ error.getMessage());
				}
				prepareInProgress = false;
			}
		}.execute();
	}

	private Polyline createPolyline(GHResponse response) {
		int points = response.points().size();
		List<GeoPoint> geoPoints = new ArrayList<GeoPoint>(points);
		PointList tmp = response.points();
		for (int i = 0; i < response.points().size(); i++) {
			geoPoints.add(new GeoPoint(tmp.latitude(i), tmp.longitude(i)));
		}
		PolygonalChain polygonalChain = new PolygonalChain(geoPoints);
		Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		paintStroke.setStyle(Paint.Style.STROKE);
		paintStroke.setColor(Color.BLUE);
		paintStroke.setAlpha(128);
		paintStroke.setStrokeWidth(8);
		paintStroke
				.setPathEffect(new DashPathEffect(new float[] { 25, 15 }, 0));

		return new Polyline(polygonalChain, paintStroke);
	}

	private Marker createMarker(GeoPoint p, int resource) {
		Drawable drawable = getResources().getDrawable(resource);
		return new Marker(p, Marker.boundCenterBottom(drawable));
	}

	public void calcPath(final double fromLat, final double fromLon,
			final double toLat, final double toLon) {

		log("calculating path ...");
		new AsyncTask<Void, Void, GHResponse>() {
			float time;

			protected GHResponse doInBackground(Void... v) {
				StopWatch sw = new StopWatch().start();
				GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon)
						.algorithm("dijkstrabi").minPathPrecision(1);
				GHResponse resp = hopper.route(req);
				time = sw.stop().getSeconds();
				return resp;
			}

			protected void onPostExecute(GHResponse res) {
				log("from:" + fromLat + "," + fromLon + " to:" + toLat + ","
						+ toLon + " found path with distance:" + res.distance()
						/ 1000f + ", nodes:" + res.points().size() + ", time:"
						+ time + " " + res.debugInfo());
				logUser("the route is " + (int) (res.distance() / 100) / 10f
						+ "km long");

				pathOverlay.getOverlayItems().add(createPolyline(res));
				mapView.redraw();
				shortestPathRunning = false;
			}
		}.execute();
	}

	private void log(String str) {
		Log.i("GH", str);
	}

	private void logUser(String str) {
		Toast.makeText(this, str, Toast.LENGTH_LONG).show();
	}

	private static final int NEW_MENU_ID = Menu.FIRST + 1;

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		menu.add(0, NEW_MENU_ID, 0, "Google");
		// menu.add(0, NEW_MENU_ID + 1, 0, "Other");
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case NEW_MENU_ID:
			if (start == null || end == null) {
				logUser("tap screen to set start and end of route");
				break;
			}
			Intent intent = new Intent(Intent.ACTION_VIEW);
			// get rid of the dialog
			intent.setClassName("com.google.android.apps.maps",
					"com.google.android.maps.MapsActivity");
			intent.setData(Uri.parse("http://maps.google.com/maps?saddr="
					+ start.latitude + "," + start.longitude + "&daddr="
					+ end.latitude + "," + end.longitude));
			startActivity(intent);
			break;
		}
		return true;
	}
}
