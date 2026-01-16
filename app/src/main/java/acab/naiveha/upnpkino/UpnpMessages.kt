package acab.naiveha.upnpkino

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.collections.get
import kotlin.text.toIntOrNull

class UpnpMessages(val context: Context, val upnpservice: UpnpService) {
    fun parseControlServerRequest(request: String): List<String>{
        val lines = request.split("\n")
        val requestMethod = lines[0].split(" ")[0]
        if (requestMethod == "M-SEARCH") {
            val response = draftControlServerMSearchResponse()
            return listOf(response, response)
        }
        return emptyList()
    }

    fun draftControlServerMSearchResponse() : String{
        return listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getServiceServerPort()}/",
            "EXT:",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
            "USN: uuid:::urn:schemas-upnp-org:device:MediaServer:1",
            "SERVER: ${upnpservice.configuration.osName} UPnP/1.0 ${context.getString(R.string.app_name)}/${BuildConfig.VERSION_NAME}",
            "",
            "").joinToString("\r\n")
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
        return """<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion>
<major>${BuildConfig.VERSION_CODE}</major>
<minor>${BuildConfig.VERSION_NAME}</minor>
</specVersion>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
<friendlyName>${upnpservice.configuration.deviceName}</friendlyName>
<manufacturer>${BuildConfig.APPLICATION_MANUFACTURER}</manufacturer>
<manufacturerURL>${BuildConfig.APPLICATION_URL}</manufacturerURL>
<modelDescription>${BuildConfig.APPLICATION_ID}</modelDescription>
<modelName>${BuildConfig.APPLICATION_NAME}</modelName>
<modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>
<modelURL>${BuildConfig.APPLICATION_URL}</modelURL>
<serialNumber>${BuildConfig.VERSION_NAME}</serialNumber>
<UDN>uuid:</UDN>
<UPC>${BuildConfig.VERSION_NAME}</UPC>
<iconList>
<icon>
<mimetype>image/png</mimetype>
<width>128</width>
<height>128</height>
<depth>32</depth>
<url>/icon</url>
</icon>
</iconList>
<serviceList>
<service>
<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
<SCPDURL>/</SCPDURL>
<controlURL>/control</controlURL>
<eventSubURL>/event</eventSubURL>
</service>
</serviceList>
</device>
</root>""".trimMargin()
    }

    private fun parseServiceServerRequest(request: String): List<String> {
        var objectID = ""
        var browseFlag = ""
        var filter = ""
        var startingIndex = ""
        var requestedCount = ""
        var sortCriteria = ""
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(request))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "ObjectID") {
                    objectID = parser.nextText()
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
                    requestedCount = parser.nextText()
                }
                if (parser.name == "SortCriteria") {
                    sortCriteria = parser.nextText()
                }
            }
            eventType = parser.next()
        }
        return listOf(objectID, browseFlag, filter, startingIndex, requestedCount)
    }

    fun draftServiceServerControlResponse(request: String) : String? {
        val (browseObjectId, browseFlag, filter, startingIndexStr, requestedCountStr) = parseServiceServerRequest(request)
        if (browseObjectId.isEmpty()) {
            return null
        }
        val requestedCount = requestedCountStr.toIntOrNull() ?: 5000
        var folders = String()
        var files = String()
        if (upnpservice.configuration.sharedTree.keys.contains(browseObjectId)) {
            val objectContents = upnpservice.configuration.sharedTree[browseObjectId]?.children
            if (objectContents != null) {
                var i = 0
                for (id in objectContents){
                    if(upnpservice.configuration.sharedTree[id]?.type == "container"){
                        folders += """&lt;container id="$id" parentID="$browseObjectId" childCount="${upnpservice.configuration.sharedTree[id]?.size}" restricted="1" searchable="1"&gt;
&lt;dc:title&gt;${upnpservice.configuration.sharedTree[id]?.name}&lt;/dc:title&gt;
&lt;dc:creator&gt;${BuildConfig.APPLICATION_ID}&lt;/dc:creator&gt;
&lt;upnp:writeStatus&gt;NOT_WRITABLE&lt;/upnp:writeStatus&gt;
&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;
&lt;/container&gt;
"""
                    } else {
                        val fileExtension = upnpservice.configuration.sharedTree[id]?.name?.substringAfterLast('.', "")?.lowercase()
                        files += """&lt;item id="$id" parentID="$browseObjectId" restricted="0"&gt;
&lt;dc:title&gt;${upnpservice.configuration.sharedTree[id]?.name}&lt;/dc:title&gt;
&lt;dc:creator&gt;${BuildConfig.APPLICATION_ID}&lt;/dc:creator&gt;
&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
&lt;res protocolInfo="http-get:*:${Constants.mimeType[fileExtension]}:*" size="${upnpservice.configuration.sharedTree[id]?.size}" duration="${upnpservice.configuration.sharedTree[id]?.duration}" resolution="${upnpservice.configuration.sharedTree[id]?.resolution}"&gt;http://${upnpservice.configuration.getIpAddress()}:${upnpservice.configuration.getMediaServerPort()}/$id.$fileExtension&lt;/res&gt;
&lt;/item&gt;
"""
                    }
                    i += 1
                    if (i == requestedCount){
                        break
                    }
                }
            }
        }
        if (folders.isNotEmpty() or files.isNotEmpty()){
            return """<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body>
<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<Result>
&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:sec="http://www.sec.co.kr/"&gt;
${folders + files}&lt;/DIDL-Lite&gt;
</Result>
<NumberReturned>${upnpservice.configuration.sharedTree[browseObjectId]?.size}</NumberReturned>
<TotalMatches>${upnpservice.configuration.sharedTree[browseObjectId]?.size}</TotalMatches>
<UpdateID>0</UpdateID>
</u:BrowseResponse>
</s:Body>
</s:Envelope>""".trimMargin()
        }
        return """<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body>
<s:Fault>
<faultcode>s:Client</faultcode>
<faultstring>UPnPError</faultstring>
<detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
<errorCode>720</errorCode>
<errorDescription>Cannot process the request. The specified ObjectID is invalid.</errorDescription>
</UPnPError>
</detail>
</s:Fault>
</s:Body>
</s:Envelope>"""
    }
}