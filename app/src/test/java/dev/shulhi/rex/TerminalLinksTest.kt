package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    private fun cells(text: String)=text.mapIndexed {i,c->TerminalCell(i,0,c.toString(),-1,0,0,1)}
    private fun links(text: String)=TerminalLinks.row(cells(text),0,200)
    @Test fun webLinksKeepPathsQueriesFragmentsAndLocalAddresses() {
        val urls=listOf("https://github.com/a/b?q=x&y=2#readme","http://localhost:8080/path","http://100.64.0.1:3000/","http://[::1]:8080/","https://example.com/λ")
        assertEquals(urls,links(urls.joinToString(" ")).map {it.url})
    }
    @Test fun punctuationAndMarkdownDoNotBecomePartOfTheUrl() {
        assertEquals(listOf("https://example.com/a_(b)","https://example.com/x","https://www.example.com"),
            links("(https://example.com/a_(b)). [docs](https://example.com/x), 'www.example.com'").map {it.url})
    }
    @Test fun onlyWebSchemesWithHostsAreOpened() {
        for(value in listOf("javascript:alert(1)","file:///etc/passwd","intent://example.com","https://","https://example.com/%zz"))
            assertNull(value,TerminalLinks.webUrl(value))
        assertTrue(links("nothttps://example.com ftp://www.example.com user@www.example.com").isEmpty())
    }
    @Test fun sparseAndWideCellsKeepAccurateTapColumns() {
        val prefix=listOf(TerminalCell(0,0,"🦖",-1,0,0,2),TerminalCell(1,0,"",-1,0,0,0))
        val url="https://example.com"
        val link=TerminalLinks.row(prefix+cells(url).map {it.copy(x=it.x+4)},0,100).single()
        assertEquals(4,link.start);assertEquals(4+url.length,link.end)
        assertFalse(link.contains(3));assertTrue(link.contains(4));assertFalse(link.contains(link.end))
    }
    @Test fun hiddenTextAndGapsAreNotClickable() {
        assertTrue(TerminalLinks.row(cells("https://example.com").map {it.copy(attributes=16)},0,100).isEmpty())
        val row=cells("https://example.com")
        val link=TerminalLinks.row(row,0,100).single()
        assertEquals("https://example.com",link.url)
        assertFalse(link.contains(90))
    }
    @Test fun osc8LabelsUseStoredDestinationAndOverrideVisibleUrlText() {
        val row=cells("https://shown.example").map {it.copy(hyperlink="https://actual.example/docs")}
        val link=TerminalLinks.row(row,0,100).single()
        assertEquals("https://actual.example/docs",link.url);assertTrue(link.explicit)
        assertEquals(0,link.start);assertEquals(row.size,link.end)
        assertTrue(TerminalLinks.row(row.map {it.copy(hyperlink="javascript:alert(1)")},0,100).isEmpty())
        assertTrue(TerminalLinks.row(row.map {it.copy(attributes=16)},0,100).isEmpty())
    }

}
