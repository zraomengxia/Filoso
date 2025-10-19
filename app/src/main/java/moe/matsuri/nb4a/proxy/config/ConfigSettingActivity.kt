package moe.matsuri.nb4a.proxy.config

import android.os.Bundle
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.ui.EditConfigPreference

class ConfigSettingActivity :
    ProfileSettingsActivity<ConfigBean>(),
    OnPreferenceDataStoreChangeListener {

    private var beanType: Int = 0

    override fun createEntity() = ConfigBean()

    override fun ConfigBean.init() {
        // CustomBean to input
        beanType = type
        DataStore.profileName = name
        DataStore.serverConfig = config
    }

    override fun ConfigBean.serialize() {
        // CustomBean from input
        type = beanType
        name = DataStore.profileName
        config = DataStore.serverConfig
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent?.getIntExtra("type", 0)?.apply { beanType = this }
        super.onCreate(savedInstanceState)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
        if (key == "isOutboundOnly") {
            beanType = if (store.getBoolean(key, false)) 1 else 0
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.config_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!

        findPreference<SwitchPreference>("isOutboundOnly")!!.isChecked = beanType == 1
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

}