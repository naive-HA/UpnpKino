package acab.naiveha.upnpkino

open class UpnpNodeModel(
    open val friendlyName: String,
    open val urlBase: String?,
    open val serviceUrns: Map<String, String>,
    open val controlUrls: Map<String, String>,
    open val eventUrls: Map<String, String>
)
