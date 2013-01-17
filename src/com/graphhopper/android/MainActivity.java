package com.graphhopper.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.mapsforge.android.maps.MapActivity;
import org.mapsforge.android.maps.MapView;
import org.mapsforge.android.maps.Projection;
import org.mapsforge.android.maps.overlay.ListOverlay;
import org.mapsforge.android.maps.overlay.Marker;
import org.mapsforge.android.maps.overlay.PolygonalChain;
import org.mapsforge.android.maps.overlay.Polyline;
import org.mapsforge.core.model.GeoPoint;
import org.mapsforge.map.reader.header.FileOpenResult;

import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.view.Window;
import android.widget.EditText;
import android.widget.Toast;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperAPI;
import com.graphhopper.GraphHopperWeb;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPoint;

public class MainActivity extends MapActivity {

	private MapView mapView;
	private GraphHopperAPI hopper;
	private GeoPoint start;
	private GeoPoint end;
	private ListOverlay pathOverlay = new ListOverlay();
	private volatile boolean prepareGraphInProgress = false;
	private volatile boolean shortestPathRunning = false;
	private String currentArea = "berlin";
	private static String GRAPH_FOLDER;
	private static String MAP_FILE;
	private SimpleOnGestureListener listener = new SimpleOnGestureListener() {
		// why does this fail? public boolean onDoubleTap(MotionEvent e) {};
		public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
			if (!initGraph()) {
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
	private GestureDetector gestureDetector = new GestureDetector(listener);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		super.onCreate(savedInstanceState);
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
		new AlertDialog.Builder(this)
				.setTitle("Routing area")
				.setMessage("On which area you want to route?")
				.setView(input)
				.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						initFiles(input.getText().toString());
					}
				})
				.setNegativeButton("Cancel",
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int whichButton) {
								initFiles(currentArea);
							}
						}).show();
	}

	private void initFiles(String area) {
		if (hopper != null && currentArea.equals(area)) {
			return;
		}
		currentArea = area;

		GRAPH_FOLDER = Environment.getExternalStorageDirectory()
				.getAbsolutePath() + "/graphhopper/maps/" + area;

		// TODO make unzipping async!
		File compressed = new File(GRAPH_FOLDER + ".ghz");
		if (compressed.exists() && !compressed.isDirectory()) {
			try {
				boolean deleteZipped = true;
				logUser("now unzipping");
				Helper.unzip(compressed.getAbsolutePath(),
						GRAPH_FOLDER + "-gh", deleteZipped);
			} catch (IOException ex) {
				logUser("Couldn't extract graph files " + ex.getMessage());
			}
		}

		MAP_FILE = Environment.getExternalStorageDirectory().getAbsolutePath()
				+ "/graphhopper/maps/" + area + "-gh/" + area + ".map";
		if (!new File(MAP_FILE).exists()) {
			MAP_FILE = Environment.getExternalStorageDirectory()
					.getAbsolutePath() + "/graphhopper/maps/" + area + ".map";
		}

		FileOpenResult fileOpenResult = mapView.setMapFile(new File(MAP_FILE));
		if (!fileOpenResult.isSuccess()) {
			logUser(fileOpenResult.getErrorMessage());
			finish();
			return;
		}
		setContentView(mapView);
		mapView.getOverlays().clear();
		mapView.getOverlays().add(pathOverlay);
		initGraph();
	}

	private boolean initGraph() {
		// only return true if already loaded
		if (hopper != null) {
			return true;
		}
		if (prepareGraphInProgress) {
			logUser("Graph preparation still in progress");
			return false;
		}
		prepareGraphInProgress = true;
		logUser("loading graph (" + Helper.VERSION + "|" + Helper.VERSION_FILE
				+ ") ... ");
		new AsyncTask<Void, Void, Path>() {
			Throwable error;

			protected Path doInBackground(Void... v) {
				try {
					boolean web = false;
					if (web) {
						// web access to the graphhopper service!
						hopper = new GraphHopperWeb();
						hopper.load("http://217.92.216.224:8080/api");
					} else {
						GraphHopper tmpHopp = new GraphHopper().forAndroid();
						tmpHopp.contractionHierarchies(true);
						tmpHopp.load(GRAPH_FOLDER);
						log("found graph with " + tmpHopp.getGraph().nodes()
								+ " nodes");
						hopper = tmpHopp;
					}
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
				prepareGraphInProgress = false;
			}
		}.execute();
		return false;
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
				log("query graph");
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
