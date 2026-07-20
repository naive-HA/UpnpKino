package acab.naiveha.upnpkino

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.collections.joinToString
import kotlin.text.toIntOrNull

class UpnpMessages(val context: Context, val upnpService: UpnpService) {
    fun isXmlValid(xml: String): Boolean {
        // XML 1.0 specifications for illegal characters.
        // Valid characters are: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
        fun isIllegal(c: Char): Boolean {
            val code = c.code
            return !((code == 0x9) || (code == 0xA) || (code == 0xD) ||
                    (code in 0x20..0xD7FF) ||
                    (code in 0xE000..0xFFFD) ||
                    (code in 0x10000..0x10FFFF))
        }
        if (xml.any { isIllegal(it) }) {
            Log.w("UpnpMessages", "isXmlValid: illegal characters found in XML")
            return false
        }
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next()
            }
            true
        } catch (e: Exception) {
            Log.w("UpnpMessages", "isXmlValid: invalid XML structure", e)
            false
        }
    }
    fun parseUpnpMulticastRequest(request: String): List<String> {
        val lines = request.split("\n")
        if ("M-SEARCH" in lines[0]) {
            val rootDevice = listOf(
                "HTTP/1.1 200 OK",
                "CACHE-CONTROL: max-age=1800",
                "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
                "EXT:",
                "ST: upnp:rootdevice",
                "USN: uuid:${upnpService.configuration.uuid}::upnp:rootdevice",
                "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
                "",
                "").joinToString("\r\n")
            val blank = listOf(
                "HTTP/1.1 200 OK",
                "CACHE-CONTROL: max-age=1800",
                "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
                "EXT:",
                "ST: uuid:${upnpService.configuration.uuid}",
                "USN: uuid:${upnpService.configuration.uuid}",
                "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
                "",
                "").joinToString("\r\n")
            val mediaServer = listOf(
                "HTTP/1.1 200 OK",
                "CACHE-CONTROL: max-age=1800",
                "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
                "EXT:",
                "ST: urn:schemas-upnp-org:device:MediaServer:1",
                "USN: uuid:${upnpService.configuration.uuid}::urn:schemas-upnp-org:device:MediaServer:1",
                "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
                "",
                "").joinToString("\r\n")

            val contentDirectory = listOf(
                "HTTP/1.1 200 OK",
                "CACHE-CONTROL: max-age=1800",
                "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
                "EXT:",
                "ST: urn:schemas-upnp-org:service:ContentDirectory:1",
                "USN: uuid:${upnpService.configuration.uuid}::urn:schemas-upnp-org:service:ContentDirectory:1",
                "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
                "",
                "").joinToString("\r\n")
            return listOf(rootDevice, blank, mediaServer, contentDirectory)
        } else if ("NOTIFY" in lines[0] || "HTTP/1.1 200 OK" in lines[0]) {
            for (line in lines) {
                if (line.uppercase().contains("LOCATION:")) {
                    val location = line.substringAfter("http://").trim().removeSuffix("/")
                    upnpService.dlnaController.registerDevice(location)
                    break
                }
            }
        }
        return emptyList()
    }
    fun draftNotifyMessage(message: String): List<String> {
        val rootDevice = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
            "NT: upnp:rootdevice",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:${upnpService.configuration.uuid}::upnp:rootdevice",
            "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val blank = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
            "NT: uuid:${upnpService.configuration.uuid}",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:${upnpService.configuration.uuid}",
            "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val mediaServer = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
            "NT: urn:schemas-upnp-org:device:MediaServer:1",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:${upnpService.configuration.uuid}::urn:schemas-upnp-org:device:MediaServer:1",
            "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val contentDirectory = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/",
            "NT: urn:schemas-upnp-org:service:ContentDirectory:1",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:${upnpService.configuration.uuid}::urn:schemas-upnp-org:service:ContentDirectory:1",
            "SERVER: ${upnpService.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        return listOf(rootDevice, blank, mediaServer, contentDirectory)
    }
    fun draftMsearchMessage(): List<String> {
        val mSearch = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "ST: ssdp:all",
            "MX: 5",
            "MAN: \"ssdp:discover\"",
            "",
            "").joinToString("\r\n")
        return listOf(mSearch)
    }
    //to do : add 256X256px icon
    fun draftUpnpDescription(): String {
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">",
            "<specVersion>",
            "<major>1</major>",
            "<minor>0</minor>",
            "</specVersion>",
            "<device>",
            "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>",
            "<friendlyName>${upnpService.configuration.deviceName}</friendlyName>",
            "<manufacturer>${BuildConfig.APPLICATION_MANUFACTURER}</manufacturer>",
            "<manufacturerURL>${BuildConfig.APPLICATION_URL}</manufacturerURL>",
            "<modelDescription>${BuildConfig.APPLICATION_ID}</modelDescription>",
            "<modelName>${BuildConfig.APPLICATION_NAME}</modelName>",
            "<modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>",
            "<modelURL>${BuildConfig.APPLICATION_URL}</modelURL>",
            "<serialNumber>${BuildConfig.VERSION_NAME}</serialNumber>",
            "<UDN>uuid:${upnpService.configuration.uuid}</UDN>",
            "<UPC>${BuildConfig.VERSION_NAME}</UPC>",
            "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>",
            "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">M-DMS-1.50</dlna:X_DLNADOC>",
            "<sec:ProductCap xmlns:sec=\"http://www.sec.co.kr/dlna\">smi,getCaptionInfo.sec</sec:ProductCap>",
            "<sec:X_ProductCap xmlns:sec=\"http://www.sec.co.kr/dlna\">smi,getCaptionInfo.sec</sec:X_ProductCap>",
            "<iconList>",
            "<icon>",
            "<mimetype>image/png</mimetype>",
            "<width>128</width>",
            "<height>128</height>",
            "<depth>32</depth>",
            "<url>/icon</url>",
            "</icon>",
            "</iconList>",
            "<serviceList>",
            "<service>",
            "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>",
            "<SCPDURL>/${upnpService.configuration.uuid}/ContentDirectory/scpd.xml</SCPDURL>",
            "<controlURL>/${upnpService.configuration.uuid}/ContentDirectory/control.xml</controlURL>",
            "<eventSubURL>/${upnpService.configuration.uuid}/ContentDirectory/event.xml</eventSubURL>",
            "</service>",
            "<service>",
            "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>",
            "<SCPDURL>/${upnpService.configuration.uuid}/ConnectionManager/scpd.xml</SCPDURL>",
            "<controlURL>/${upnpService.configuration.uuid}/ConnectionManager/control.xml</controlURL>",
            "<eventSubURL>/${upnpService.configuration.uuid}/ConnectionManager/event.xml</eventSubURL>",
            "</service>",
            "<service>",
            "<serviceType>urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1</serviceType>",
            "<serviceId>urn:microsoft.com:serviceId:X_MS_MediaReceiverRegistrar</serviceId>",
            "<SCPDURL>/${upnpService.configuration.uuid}/MediaReceiverRegistrar/scpd.xml</SCPDURL>",
            "<controlURL>/${upnpService.configuration.uuid}/MediaReceiverRegistrar/control.xml</controlURL>",
            "<eventSubURL>/${upnpService.configuration.uuid}/MediaReceiverRegistrar/event.xml</eventSubURL>",
            "</service>",
            "</serviceList>",
            "</device>",
            "</root>").joinToString("")
    }
    fun draftContentDirectoryScpdDescription(): String {
        return listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">",
            "<specVersion>",
            "<major>1</major>",
            "<minor>0</minor>",
            "</specVersion>",
            "<actionList>",
            "<action>",
            "<name>Browse</name>",
            "<argumentList>",
            "<argument>",
            "<name>ObjectID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>BrowseFlag</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Filter</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>StartingIndex</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>RequestedCount</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>SortCriteria</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Result</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>NumberReturned</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>TotalMatches</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>UpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetSortCapabilities</name>",
            "<argumentList>",
            "<argument>",
            "<name>SortCaps</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SortCapabilities</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetSystemUpdateID</name>",
            "<argumentList>",
            "<argument>",
            "<name>Id</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SystemUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetSearchCapabilities</name>",
            "<argumentList>",
            "<argument>",
            "<name>SearchCaps</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SearchCapabilities</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>Search</name>",
            "<argumentList>",
            "<argument>",
            "<name>ContainerID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>SearchCriteria</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_SearchCriteria</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Filter</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>StartingIndex</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>RequestedCount</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>SortCriteria</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Result</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>NumberReturned</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>TotalMatches</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>UpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>UpdateObject</name>",
            "<argumentList>",
            "<argument>",
            "<name>ObjectID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>CurrentTagValue</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_TagValueList</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>NewTagValue</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_TagValueList</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "</actionList>",
            "<serviceStateTable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_BrowseFlag</name>",
            "<dataType>string</dataType>",
            "<allowedValueList>",
            "<allowedValue>BrowseMetadata</allowedValue>",
            "<allowedValue>BrowseDirectChildren</allowedValue>",
            "</allowedValueList>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>ContainerUpdateIDs</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>SystemUpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Count</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_SortCriteria</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_SearchCriteria</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>SortCapabilities</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Index</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ObjectID</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_UpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Result</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>SearchCapabilities</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Filter</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_TagValueList</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "</serviceStateTable>",
            "</scpd>").joinToString("")
    }
    fun draftConnectionManagerScpdDescription(): String {
        return listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">",
            "<specVersion>",
            "<major>1</major>",
            "<minor>0</minor>",
            "</specVersion>",
            "<actionList>",
            "<action>",
            "<name>GetCurrentConnectionInfo</name>",
            "<argumentList>",
            "<argument>",
            "<name>ConnectionID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>RcsID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>AVTransportID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>ProtocolInfo</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>PeerConnectionManager</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>PeerConnectionID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Direction</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Status</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetProtocolInfo</name>",
            "<argumentList>",
            "<argument>",
            "<name>Source</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SourceProtocolInfo</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Sink</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SinkProtocolInfo</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetCurrentConnectionIDs</name>",
            "<argumentList>",
            "<argument>",
            "<name>ConnectionIDs</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>CurrentConnectionIDs</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "</actionList>",
            "<serviceStateTable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ProtocolInfo</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ConnectionStatus</name>",
            "<dataType>string</dataType>",
            "<allowedValueList>",
            "<allowedValue>OK</allowedValue>",
            "<allowedValue>ContentFormatMismatch</allowedValue>",
            "<allowedValue>InsufficientBandwidth</allowedValue>",
            "<allowedValue>UnreliableChannel</allowedValue>",
            "<allowedValue>Unknown</allowedValue>",
            "</allowedValueList>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_AVTransportID</name>",
            "<dataType>i4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_RcsID</name>",
            "<dataType>i4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ConnectionID</name>",
            "<dataType>i4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ConnectionManager</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>SourceProtocolInfo</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>SinkProtocolInfo</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Direction</name>",
            "<dataType>string</dataType>",
            "<allowedValueList>",
            "<allowedValue>Input</allowedValue>",
            "<allowedValue>Output</allowedValue>",
            "</allowedValueList>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>CurrentConnectionIDs</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "</serviceStateTable>",
            "</scpd>").joinToString("")
    }
    fun draftMediaReceiverRegistrarScpdDescription(): String {
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
            "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">",
            "<specVersion>",
            "<major>1</major>",
            "<minor>0</minor>",
            "</specVersion>",
            "<actionList>",
            "<action>",
            "<name>GetAuthorizationGrantedUpdateID</name>",
            "<argumentList>",
            "<argument>",
            "<name>AuthorizationGrantedUpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>AuthorizationGrantedUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>IsValidated</name>",
            "<argumentList>",
            "<argument>",
            "<name>DeviceID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_DeviceID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Result</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetValidationSucceededUpdateID</name>",
            "<argumentList>",
            "<argument>",
            "<name>ValidationSucceededUpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>ValidationSucceededUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetAuthorizationDeniedUpdateID</name>",
            "<argumentList>",
            "<argument>",
            "<name>AuthorizationDeniedUpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>AuthorizationDeniedUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>IsAuthorized</name>",
            "<argumentList>",
            "<argument>",
            "<name>DeviceID</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_DeviceID</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>Result</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>GetValidationRevokedUpdateID</name>",
            "<argumentList>",
            "<argument>",
            "<name>ValidationRevokedUpdateID</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>ValidationRevokedUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "<action>",
            "<name>RegisterDevice</name>",
            "<argumentList>",
            "<argument>",
            "<name>RegistrationReqMsg</name>",
            "<direction>in</direction>",
            "<relatedStateVariable>A_ARG_TYPE_RegistrationReqMsg</relatedStateVariable>",
            "</argument>",
            "<argument>",
            "<name>RegistrationRespMsg</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>A_ARG_TYPE_RegistrationRespMsg</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "</actionList>",
            "<serviceStateTable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_RegistrationReqMsg</name>",
            "<dataType>bin.base64</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Result</name>",
            "<dataType>int</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_DeviceID</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_RegistrationRespMsg</name>",
            "<dataType>bin.base64</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\"><name>ValidationRevokedUpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>ValidationSucceededUpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>AuthorizationDeniedUpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>AuthorizationGrantedUpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "</serviceStateTable>",
            "</scpd>").joinToString("")
    }
    fun draftContentDirectoryControlDescription(request: String): String {
        when (parseUpnpHttpRequest(request)) {
            "Browse" -> {
                return draftBrowseResponse(request)
            }
            "GetSortCapabilities" -> {
                return listOf(
                    "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
                    "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">",
                    "<s:Body>",
                    "<u:GetSortCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">",
                    "<SortCaps>",
                    "</SortCaps>",
                    "</u:GetSortCapabilitiesResponse>",
                    "</s:Body>",
                    "</s:Envelope>").joinToString("")
            }
            "GetSystemUpdateID" -> {
                return listOf(
                    "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
                    "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">",
                    "<s:Body>",
                    "<u:GetSystemUpdateIDResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">",
                    "<Id>0</Id>",
                    "</u:GetSystemUpdateIDResponse>",
                    "</s:Body>",
                    "</s:Envelope>").joinToString("")
            }
        }
        return String()
    }
    fun parseDlnaResponse(xml: String): Map<String, String> {
        val responseData = mutableMapOf<String, String>()
        if (!isXmlValid(xml)) {
            Log.w("UpnpMessages", "parseDlnaResponse: invalid XML input")
            return responseData
        }
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name.substringAfter(":")
                    if (name in listOf("TrackDuration", "RelTime", "CurrentURI", "CurrentTransportState", "CurrentTransportStatus", "CurrentSpeed")) {
                        val text = parser.nextText()
                        responseData[name] = text
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("UpnpMessages", "parseDlnaResponse: error parsing XML", e)
        }
        return responseData
    }

    fun parseUpnpHttpRequest(request: String): String {
        if (!isXmlValid(request)) {
            Log.w("UpnpMessages", "parseUpnpHttpRequest: invalid XML input")
            return String()
        }
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(request))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.name == "s:Body") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            return parser.name.substringAfter("u:")
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("UpnpMessages", "parseUpnpHttpRequest: error parsing XML", e)
        }
        return String()
    }
    private fun draftBrowseResponse(request: String): String {
        var objectID = ""
        var browseFlag = ""
        var filter = ""
        var startingIndex = ""
        var requestedCount = 5000
        var sortCriteria = ""
        if (!isXmlValid(request)) {
            return upnpError("Cannot process the request. Malformed request")
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(request))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "ObjectID") {
                    objectID = parser.nextText()
                    if (objectID.isEmpty()) {
                        return upnpError("Cannot process the request. Malformed request")
                    }
                }
                if (parser.name == "BrowseFlag") {
                    browseFlag = parser.nextText()
                }
                if (parser.name == "Filter") {
                    filter = parser.nextText()
                }
                if (parser.name == "StartingIndex") {
                    startingIndex = parser.nextText()
                }
                if (parser.name == "RequestedCount") {
                    requestedCount = parser.nextText().toIntOrNull() ?: 5000
                }
                if (parser.name == "SortCriteria") {
                    sortCriteria = parser.nextText()
                }
            }
            eventType = parser.next()
        }
        var metadata = String()
        var totalMatches = 0
        val sharedMediaCollection = UpnpRepository.kinoService.sharedMediaCollection.value
        val node = sharedMediaCollection[objectID] ?: return upnpError("Cannot process the request. Malformed request")
        if (browseFlag == "BrowseDirectChildren" && node is Configuration.MediaNode.Container) {
            val objectContents = node.children
            for (id in objectContents) {
                if (sharedMediaCollection[id] is Configuration.MediaNode.Container && totalMatches != requestedCount) {
                    metadata += metadataValues(id, objectID)
                    totalMatches += 1
                }
            }
            for (id in objectContents) {
                if (sharedMediaCollection[id] is Configuration.MediaNode.Item && totalMatches != requestedCount) {
                    metadata += metadataValues(id, objectID)
                    totalMatches += 1
                }
            }
        } else if (browseFlag == "BrowseMetadata") {
            metadata += metadataValues(
                objectID,
                node.parent
            )
            totalMatches = 1
        }
        if (totalMatches > 0 && metadata.isNotEmpty()) {
            return listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
                "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">",
                "<s:Body>",
                "<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">",
                "<Result>",
                "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:sec=\"http://www.sec.co.kr/\"&gt;",
                "${metadata}&lt;/DIDL-Lite&gt;",
                "</Result>",
                "<NumberReturned>$totalMatches</NumberReturned>",
                "<TotalMatches>$totalMatches</TotalMatches>",
                "<UpdateID>0</UpdateID>",
                "</u:BrowseResponse>",
                "</s:Body>",
                "</s:Envelope>").joinToString("")
        }
        return upnpError("Cannot process the request. The specified ObjectID is invalid")
    }
    private fun metadataValues(objectID: String, parentID: String): String {
        val sharedMediaCollection = UpnpRepository.kinoService.sharedMediaCollection.value
        val node = sharedMediaCollection[objectID] ?: return ""
        var metadata = String()
        if (node is Configuration.MediaNode.Container) {
            metadata += listOf(
                "&lt;container id=\"$objectID\" parentID=\"$parentID\" childCount=\"${node.children.size}\" restricted=\"1\" searchable=\"1\"&gt;",
                "&lt;dc:title&gt;${node.name.xmlEscape()}&lt;/dc:title&gt;",
                "&lt;upnp:writeStatus&gt;NOT_WRITABLE&lt;/upnp:writeStatus&gt;",
                "&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;",
                "&lt;/container&gt;").joinToString("")
        } else if (node is Configuration.MediaNode.Item) {
            val fileExtension = node.name.substringAfterLast('.', "").lowercase()
            metadata += listOf(
                "&lt;item id=\"$objectID\" parentID=\"$parentID\" restricted=\"0\"&gt;",
                "&lt;dc:title&gt;${node.name.xmlEscape()}&lt;/dc:title&gt;",
                "&lt;dc:creator/&gt;",
                "&lt;upnp:class&gt;object.item.${(if (fileExtension in Constants.musicExtensions) "audioItem.musicTrack" else "videoItem")}&lt;/upnp:class&gt;",
                "&lt;upnp:longDescription/&gt;",
                "&lt;upnp:albumArtURI&gt;http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/${upnpService.configuration.uuid}/icon&lt;/upnp:albumArtURI&gt;",
                "&lt;upnp:albumArtURI xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" dlna:profileID=\"PNG_TN\"&gt;http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/${upnpService.configuration.uuid}/icon&lt;/upnp:albumArtURI&gt;",
                if (fileExtension in Constants.musicExtensions) "&lt;upnp:album/&gt;" else "",
                if (fileExtension in Constants.musicExtensions) "&lt;upnp:artist role=\"Performer\"/&gt;" else "",
                "&lt;res protocolInfo=\"http-get:*:${node.mimeType}:*\" size=\"${node.size}\" duration=\"${node.duration}\" resolution=\"${node.resolution}\"&gt;${node.url.xmlEscape()}&lt;/res&gt;",
                "&lt;/item&gt;").joinToString("")
        }
        return metadata
    }
    private fun upnpError(message: String): String {
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
            "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">",
            "<s:Body>",
            "<s:Fault>",
            "<faultcode>s:Client</faultcode>",
            "<faultstring>UPnPError</faultstring>",
            "<detail>",
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">",
            "<errorCode>720</errorCode>",
            "<errorDescription>$message</errorDescription>",
            "</UPnPError>",
            "</detail>",
            "</s:Fault>",
            "</s:Body>",
            "</s:Envelope>").joinToString("")

    }
    data class UpnpDeviceDescription(
        val friendlyName: String,
        val urlBase: String?,
        val controlUrls: Map<String, String>,
        val eventUrls: Map<String, String>
    )

    fun parseUpnpDescription(description: String): UpnpDeviceDescription? {
        var friendlyName = ""
        var urlBase: String? = null
        val controlUrls = mutableMapOf<String, String>()
        val eventUrls = mutableMapOf<String, String>()
        if (!isXmlValid(description)) {
            Log.w("UpnpMessages", "parseUpnpDescription: invalid XML input")
            return null
        }
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(description))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "friendlyName" -> friendlyName = parser.nextText()
                        "URLBase" -> urlBase = parser.nextText()
                        "service" -> {
                            var serviceType = ""
                            var controlURL = ""
                            var eventSubURL = ""
                            while (!(eventType == XmlPullParser.END_TAG && parser.name == "service")) {
                                eventType = parser.next()
                                if (eventType == XmlPullParser.START_TAG) {
                                    when (parser.name) {
                                        "serviceType" -> serviceType = parser.nextText()
                                        "controlURL" -> controlURL = parser.nextText()
                                        "eventSubURL" -> eventSubURL = parser.nextText()
                                    }
                                }
                                when (serviceType) {
                                    Constants.Dlna.URN.AV_TRANSPORT -> {
                                        controlUrls["AVTransport"] = controlURL
                                        eventUrls["AVTransport"] = eventSubURL
                                    }
                                    Constants.Dlna.URN.RENDERING_CONTROL -> {
                                        controlUrls["RenderingControl"] = controlURL
                                        eventUrls["RenderingControl"] = eventSubURL
                                    }
                                    Constants.Dlna.URN.CONNECTION_MANAGER -> {
                                        controlUrls["ConnectionManager"] = controlURL
                                        eventUrls["ConnectionManager"] = eventSubURL
                                    }
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("UpnpMessages", "parseUpnpDescription: error parsing XML", e)
        }
        if (friendlyName.isEmpty() || controlUrls.isEmpty() || eventUrls.isEmpty()){
            Log.w("UpnpMessages", "parseUpnpDescription: missing critical fields (name=$friendlyName, controls=${controlUrls.size}, events=${eventUrls.size})")
            return null
        }
        return UpnpDeviceDescription(friendlyName, urlBase, controlUrls, eventUrls)
    }
    fun draftDlnaMessage(action: String, args: Map<String, String>, mediaCollection: Map<String, Configuration.MediaNode>): String{
        var fields = String()
        when (action){
            "SetAVTransportURI" -> {
                val fileId = args["fileId"]
                val mediaFile = fileId?.let { mediaCollection[it] } as? Configuration.MediaNode.Item
                val fileExtension = mediaFile?.name?.substringAfterLast(".")
                val itemUrl = mediaFile?.url?.xmlEscape() ?: ""
                fields = listOf(
                    "<CurrentURI>$itemUrl</CurrentURI>",
                    "<CurrentURIMetaData>",
                    "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;",
                    "&lt;item id=\"0\" parentID=\"-1\" restricted=\"0\"&gt;",
                    "&lt;dc:title&gt;${mediaFile?.name?.xmlEscape()}&lt;/dc:title&gt;",
                    "&lt;upnp:class&gt;object.item.${(if (fileExtension in Constants.musicExtensions) "audioItem.musicTrack" else "videoItem")}&lt;/upnp:class&gt;",
                    "&lt;res protocolInfo=\"http-get:*:${mediaFile?.mimeType}:DLNA.ORG_OP=01\"&gt;$itemUrl&lt;/res&gt;",
                    "&lt;/item&gt;",
                    "&lt;/DIDL-Lite&gt;",
                    "</CurrentURIMetaData>").joinToString("")
            }
            "Play" -> {
                fields = "<Speed>1</Speed>"
            }
            "Seek" -> {
                val target = args["Target"]
                fields = listOf(
                    "<Unit>REL_TIME</Unit>",
                    "<Target>$target</Target>").joinToString("")
            }
            "Pause" -> {
                fields = ""
            }
            "Stop" -> {
                fields = ""
            }
            "GetMediaInfo" -> {
                fields = ""
            }
            "GetPositionInfo" -> {
                fields = "" // Retrieves current playback position
            }
            "GetTransportInfo" -> {
                fields = "" // Queries the current transport state (e.g., Playing, Paused, Stopped, Transitioning)
            }
            "SetBrightness" -> {
                val target = args["DesiredBrightness"]
                fields = "<DesiredBrightness>$target</DesiredBrightness>"
            }
        }
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">",
            "<s:Body>",
            "<u:$action xmlns:u=\"${Constants.Dlna.getURN(action)}\">",
            "<InstanceID>0</InstanceID>",
            fields,
            "</u:$action>",
            "</s:Body>",
            "</s:Envelope>").joinToString("")
    }
    fun parseDlnaEvent(xml: String): Map<String, String> {
        val eventData = mutableMapOf<String, String>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "LastChange") {
                    var lastChangeXml = parser.nextText()
                    if (lastChangeXml.contains("&lt;") || lastChangeXml.contains("&gt;")) {
                        lastChangeXml = lastChangeXml.replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                    }
                    parseLastChange(lastChangeXml, eventData)
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
        }
        return eventData
    }

    private fun parseLastChange(xml: String, eventData: MutableMap<String, String>) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name.substringAfter(":")
                    val value = parser.getAttributeValue(null, "val")
                    if (value != null) {
                        eventData[name] = value
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
        }
    }
    internal fun String.xmlEscape(): String {
        return this.replace("&", "&amp;amp;")
            .replace("<", "&amp;lt;")
            .replace(">", "&amp;gt;")
            .replace("\"", "&amp;quot;")
            .replace("'", "&amp;apos;")
    }
}
