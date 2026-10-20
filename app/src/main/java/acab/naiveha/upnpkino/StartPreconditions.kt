package acab.naiveha.upnpkino

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.net.Inet4Address
import java.net.NetworkInterface

object StartPreconditions {
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val LOCAL_NETWORK_MIN_SDK = 37

    sealed class Outcome {
        class Ready(val ipAddress: String) : Outcome()
        class Blocked(@param:StringRes val message: Int) : Outcome()
    }

    fun hasLocalNetworkPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_MIN_SDK || ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun check(context: Context, preferences: Preferences): Outcome {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Suppress("DEPRECATION")
        val wifiNetwork = connectivityManager.allNetworks.find { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)
            val networkInterface = linkProperties?.let { NetworkInterface.getByName(it.interfaceName) }

            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            networkInterface?.supportsMulticast() == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        } ?: return Outcome.Blocked(R.string.error_no_wifi)

        val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
        val address = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
        val ipAddress = address?.hostAddress ?: return Outcome.Blocked(R.string.error_no_ip)

        if (!hasLocalNetworkPermission(context)) return Outcome.Blocked(R.string.error_local_network_permission_open_app)

        val movieUri = preferences.getLocalMovieFolderUri()
        val musicUri = preferences.getLocalMusicFolderUri()
        if (movieUri == null && musicUri == null) return Outcome.Blocked(R.string.error_no_folder)
        folderProblem(context, movieUri, R.string.error_movies_folder_access_lost, R.string.error_movies_folder_missing)
            ?.let { return Outcome.Blocked(it) }
        folderProblem(context, musicUri, R.string.error_music_folder_access_lost, R.string.error_music_folder_missing)
            ?.let { return Outcome.Blocked(it) }

        @Suppress("DEPRECATION")
        val hasVpn = connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (hasVpn) {
            Constants.vibrate(context)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Warning: VPN detected. Potential connectivity issues",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        return Outcome.Ready(ipAddress)
    }

    @StringRes
    private fun folderProblem(context: Context, uri: Uri?, @StringRes accessLost: Int, @StringRes missing: Int): Int? {
        if (uri == null) return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!granted) return accessLost
        val exists = try {
            DocumentFile.fromTreeUri(context, uri)?.exists() == true
        } catch (e: Exception) {
            false
        }
        return if (exists) null else missing
    }
}
