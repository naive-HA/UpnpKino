package acab.naiveha.upnpkino

import android.content.Context

import fi.iki.elonen.NanoHTTPD

open class ServiceServer(
    private val context: Context, val upnpservice: UpnpService) : NanoHTTPD(upnpservice.configuration.getIpAddress(), upnpservice.configuration.getServiceServerPort()) {

    fun start(onStarted: (port: Int, status: String) -> Unit) {
        try {
            super.start()
            onStarted(super.listeningPort, "Control server started successfully")
        } catch (e: Exception) {
            onStarted(-1, "Control server failed to start. ${e.toString()}")
        }
    }

    override fun stop(){
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.getMethod()
        if (Method.GET == method) {
            when (session.getUri()){
                "/" -> {
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/xml;charset=utf-8",
                        upnpservice.upnpMessages.draftServiceServerDescription())
                }
                "/icon" -> {
                    try {
                        val inputStream = context.resources.openRawResource(R.raw.icon)
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "image/png",
                            inputStream,
                            inputStream.available().toLong())
                    } catch (e: Exception) {
                        return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "SERVER INTERNAL ERROR: IOException")
                    }
                }
            }
        } else if(Method.POST == method){
            when (session.getUri()){
                "/control" -> {
                    val payload = hashMapOf<String, String>()
                    try {
                        session.parseBody(payload)
                    } catch (e: Exception) {
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "404 Not found")
                    }
                    if (payload.isNotEmpty()) {

                        val response = upnpservice.upnpMessages.draftServiceServerControlResponse(payload["postData"]!!)
                                ?: return newFixedLengthResponse(
                                    Response.Status.NOT_FOUND,
                                    MIME_PLAINTEXT,
                                    "404 Not found"
                                )
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "text/xml;charset=utf-8",
                            response)
                    }
                }
            }
        }
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "404 Not found")
    }
}
