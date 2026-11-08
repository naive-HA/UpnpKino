package acab.naiveha.upnpkino

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class UpnpMessagesTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockUpnpService: UpnpService

    @Mock
    private lateinit var mockConfiguration: Configuration

    private lateinit var upnpMessages: UpnpMessages

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockUpnpService.configuration).thenReturn(mockConfiguration)
        `when`(mockConfiguration.getIpAddress()).thenReturn("192.168.1.100")
        `when`(mockConfiguration.getHttpServerPort()).thenReturn(8080)
        `when`(mockConfiguration.uuid).thenReturn("test-uuid")
        
        upnpMessages = UpnpMessages(mockContext, mockUpnpService)
    }

    @Test
    fun testXmlEscape() {
        // We need to use reflection because xmlEscape is private
        val method = UpnpMessages::class.java.getDeclaredMethod("xmlEscape", String::class.java)
        method.isAccessible = true
        
        val input = "Title with & < > \" ' and characters like é"
        val expected = "Title with &amp;amp; &amp;lt; &amp;gt; &amp;quot; &amp;apos; and characters like ."
        
        val result = method.invoke(upnpMessages, input) as String
        assertEquals(expected, result)
    }

    @Test
    fun testUrlEscape() {
        // We need to use reflection because urlEscape is private
        val method = UpnpMessages::class.java.getDeclaredMethod("urlEscape", String::class.java)
        method.isAccessible = true
        
        val input = "File Name With Spaces and é.mp4"
        val expected = "File%20Name%20With%20Spaces%20and%20..mp4"
        
        val result = method.invoke(upnpMessages, input) as String
        assertEquals(expected, result)
    }
}
