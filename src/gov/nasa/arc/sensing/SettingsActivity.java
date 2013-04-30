package gov.nasa.arc.sensing;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity implements OnSharedPreferenceChangeListener {

	/**
	 * Checks that a preference is a valid numerical value
	 */
	Preference.OnPreferenceChangeListener changeListener = new OnPreferenceChangeListener() {

		@Override
		public boolean onPreferenceChange(Preference preference, Object newValue) {
			// Check that the string is an integer
			if (newValue != null && newValue.toString().length() > 0 && newValue.toString().matches("\\d*")) {
				return true;
			}
			// If now create a message to the user
			Toast.makeText(SettingsActivity.this, "Invalid Input", Toast.LENGTH_SHORT).show();
			return false;
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.udp_preferences);

		// Add a validator so the ports and IP address are not empty and only accepts numbers
		Preference ipAddressPreference = getPreferenceScreen().findPreference(this.getString(R.string.ipAddressKey));
		ipAddressPreference.setOnPreferenceChangeListener(changeListener);
		
		Preference sensorPortPreference = getPreferenceScreen().findPreference(this.getString(R.string.sensorPortKey));
		sensorPortPreference.setOnPreferenceChangeListener(changeListener);
		
		Preference cameraPortPreference = getPreferenceScreen().findPreference(this.getString(R.string.cameraPortKey));
		cameraPortPreference.setOnPreferenceChangeListener(changeListener);
	}

	@Override
	protected void onResume() {
		super.onResume();

		// Set up a listener whenever a key changes
		getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Unregister the listener whenever a key changes
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		setResult(RESULT_OK);
	}
}
