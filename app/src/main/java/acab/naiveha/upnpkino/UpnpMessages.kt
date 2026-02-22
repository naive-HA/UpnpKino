package acab.naiveha.upnpkino

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.collections.get
import kotlin.collections.joinToString
import kotlin.text.toIntOrNull

class UpnpMessages(val context: Context, val upnpservice: UpnpService) {
    fun parseControlServerRequest(request: String): List<String>{
        val lines = request.split("\n")
        val requestMethod = lines[0].split(" ")[0]
        if (requestMethod == "M-SEARCH") {
            return draftControlServerMSearchResponse()
        }
        return emptyList()
    }

    fun draftControlServerMSearchResponse() : List<String>{
        val rootDevice = listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "EXT:",
            "ST: upnp:rootdevice",
            "USN: uuid:::upnp:rootdevice",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val blank = listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "EXT:",
            "ST: uuid:",
            "USN: uuid:",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val mediaServer = listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "EXT:",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
            "USN: uuid:::urn:schemas-upnp-org:device:MediaServer:1",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")

        val contentDirectory = listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "EXT:",
            "ST: urn:schemas-upnp-org:service:ContentDirectory:1",
            "USN: uuid:::urn:schemas-upnp-org:service:ContentDirectory:1",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")

       return listOf(rootDevice, blank, mediaServer, contentDirectory)
    }

    fun draftControlServerNotifyMessage(message: String): List<String> {
        val rootDevice = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "NT: upnp:rootdevice",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:::upnp:rootdevice",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val blank = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "NT: uuid:",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val mediaServer = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "NT: urn:schemas-upnp-org:device:MediaServer:1",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:::urn:schemas-upnp-org:device:MediaServer:1",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        val contentDirectory = listOf(
            "NOTIFY * HTTP/1.1",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "NT: urn:schemas-upnp-org:service:ContentDirectory:1",
            "HOST: 239.255.255.250:1900",
            "NTS: ssdp:$message",
            "USN: uuid:::urn:schemas-upnp-org:service:ContentDirectory:1",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
        return listOf(rootDevice, blank, mediaServer, contentDirectory)
    }

    fun draftServiceServerDescription() : String{
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">",
            "<specVersion>",
            "<major>1</major>",
            "<minor>0</minor>",
            "</specVersion>",
            "<device>",
            "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>",
            "<friendlyName>${upnpservice.configuration.deviceName}</friendlyName>",
            "<manufacturer>${BuildConfig.APPLICATION_MANUFACTURER}</manufacturer>",
            "<manufacturerURL>${BuildConfig.APPLICATION_URL}</manufacturerURL>",
            "<modelDescription>${BuildConfig.APPLICATION_ID}</modelDescription>",
            "<modelName>${BuildConfig.APPLICATION_NAME}</modelName>",
            "<modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>",
            "<modelURL>${BuildConfig.APPLICATION_URL}</modelURL>",
            "<serialNumber>${BuildConfig.VERSION_NAME}</serialNumber>",
            "<UDN>uuid:</UDN>",
            "<UPC>${BuildConfig.VERSION_NAME}</UPC>",
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
            "<SCPDURL>/scpd</SCPDURL>",
            "<controlURL>/control</controlURL>",
            "<eventSubURL>/event</eventSubURL>",
            "</service>",
            "</serviceList>",
            "</device>",
            "</root>").joinToString("")
    }

    fun draftScpdDescription(): String{
        return listOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
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
            "</argument><argument>",
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
            "</argument><argument>",
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
            "<name>GetSortCapabilities</name>",
            "<argumentList><argument>",
            "<name>SortCaps</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SortCapabilities</relatedStateVariable>",
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
            "<name>GetSystemUpdateID</name>",
            "<argumentList><argument>",
            "<name>Id</name>",
            "<direction>out</direction>",
            "<relatedStateVariable>SystemUpdateID</relatedStateVariable>",
            "</argument>",
            "</argumentList>",
            "</action>",
            "</actionList>",
            "<serviceStateTable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Count</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"yes\">",
            "<name>SystemUpdateID</name>",
            "<dataType>ui4</dataType>",
            "<defaultValue>0</defaultValue>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_UpdateID</name>",
            "<dataType>ui4</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>SortCapabilities</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_ObjectID</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Index</name>",
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
            "<name>A_ARG_TYPE_URI</name>",
            "<dataType>uri</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_SortCriteria</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_Filter</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_SearchCriteria</name>",
            "<dataType>string</dataType>",
            "</stateVariable>",
            "<stateVariable sendEvents=\"no\">",
            "<name>A_ARG_TYPE_BrowseFlag</name>",
            "<dataType>string</dataType>",
            "<allowedValueList>",
            "<allowedValue>BrowseMetadata</allowedValue>",
            "<allowedValue>BrowseDirectChildren</allowedValue>",
            "</allowedValueList>",
            "</stateVariable>",
            "</serviceStateTable>",
            "</scpd>").joinToString("")
    }

    private fun parseServiceServerRequest(request: String): String{
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(request))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "s:Body") {
                    parser.next()
                    return parser.name.substringAfter("u:")
                }
            }
            eventType = parser.next()
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
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(request))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "ObjectID") {
                    objectID = parser.nextText()
                    if (objectID.isEmpty()) {
                        return ""
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

        var folders = String()
        var files = String()
        var totalMatches = 0
        if (upnpservice.configuration.sharedTree.keys.contains(objectID)) {
            val objectContents = upnpservice.configuration.sharedTree[objectID]?.children
            if (objectContents != null) {
                for (id in objectContents){
                    if(upnpservice.configuration.sharedTree[id]?.type == "container"){
                        folders += listOf(
                            "&lt;container id=\"$id\" parentID=\"$objectID\" childCount=\"${upnpservice.configuration.sharedTree[id]?.size}\" restricted=\"1\" searchable=\"1\"&gt;",
                            "&lt;dc:title&gt;${upnpservice.configuration.sharedTree[id]?.name}&lt;/dc:title&gt;",
                            "&lt;dc:creator&gt;${BuildConfig.APPLICATION_ID}&lt;/dc:creator&gt;",
                            "&lt;upnp:writeStatus&gt;NOT_WRITABLE&lt;/upnp:writeStatus&gt;",
                            "&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;",
                            "&lt;/container&gt;").joinToString("")
                    } else {
                        val fileExtension = upnpservice.configuration.sharedTree[id]?.name?.substringAfterLast('.', "")?.lowercase()
                        files += listOf(
                            "&lt;item id=\"$id\" parentID=\"$objectID\" restricted=\"0\"&gt;",
                            "&lt;dc:title&gt;${upnpservice.configuration.sharedTree[id]?.name}&lt;/dc:title&gt;",
                            "&lt;dc:creator&gt;${BuildConfig.APPLICATION_ID}&lt;/dc:creator&gt;",
                            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;",
                            "&lt;res protocolInfo=\"http-get:*:${Constants.mimeType[fileExtension]}:*\" size=\"${upnpservice.configuration.sharedTree[id]?.size}\" duration=\"${upnpservice.configuration.sharedTree[id]?.duration}\" resolution=\"${upnpservice.configuration.sharedTree[id]?.resolution}\"&gt;http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getMediaServerPort()}/$id.$fileExtension&lt;/res&gt;",
                            "&lt;/item&gt;").joinToString("")
                    }
                    totalMatches += 1
                    if (totalMatches == requestedCount){
                        break
                    }
                }
            }
        }
        if (folders.isNotEmpty() or files.isNotEmpty()){
            return listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>",
                "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">",
                "<s:Body>",
                "<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">",
                "<Result>",
                "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:sec=\"http://www.sec.co.kr/\"&gt;",
                "${folders + files}&lt;/DIDL-Lite&gt;",
                "</Result>",
                "<NumberReturned>$totalMatches</NumberReturned>",
                "<TotalMatches>$totalMatches</TotalMatches>",
                "<UpdateID>0</UpdateID>",
                "</u:BrowseResponse>",
                "</s:Body>",
                "</s:Envelope>").joinToString("")
        }
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
            "<errorDescription>Cannot process the request. The specified ObjectID is invalid.</errorDescription>",
            "</UPnPError>",
            "</detail>",
            "</s:Fault>",
            "</s:Body>",
            "</s:Envelope>").joinToString("")
    }

    fun draftServiceServerControlResponse(request: String) : String? {
        when (parseServiceServerRequest(request)){
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
}