package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class TabSizingTest {
    @Test fun activeGetsPriorityAndFourTabsFitInAPhoneStrip() {
        val widths=tabWidths(262f,4,true)
        assertEquals(106f,widths.active,0.01f)
        assertEquals(48f,widths.inactive,0.01f)
        assertEquals(262f,widths.active+3*widths.inactive+12f,0.01f)
    }
    @Test fun roomyStripsKeepTheActiveCapAndReadableInactiveTitles() {
        val widths=tabWidths(500f,4,true)
        assertEquals(160f,widths.active,0.01f)
        assertTrue(widths.inactive>=80f)
        assertTrue(widths.active+3*widths.inactive+12f<=500.01f)
    }
    @Test fun overflowKeepsTouchTargetsAndAnActiveTitle() {
        val widths=tabWidths(262f,6,true)
        assertEquals(160f,widths.active,0.01f)
        assertEquals(48f,widths.inactive,0.01f)
        assertTrue(widths.active+5*widths.inactive+20f>262f)
    }
    @Test fun allTabsFitWheneverTheActiveTitleAndIconTargetsHaveRoom() {
        for(count in 2..12) for(space in listOf(200f,262f,320f,500f,900f)) {
            val widths=tabWidths(space,count,true)
            assertTrue(widths.active in 48f..160f);assertTrue(widths.inactive>=48f)
            if(104f+(count-1)*52f<=space) assertTrue(widths.active+(count-1)*(widths.inactive+4f)<=space+.01f)
        }
    }
    @Test fun anUnselectedStripSharesSpaceAndEmptyListsNeedNone() {
        val widths=tabWidths(300f,5,false)
        assertEquals(0f,widths.active,0f)
        assertTrue(5*widths.inactive+16f<=300f)
        assertTrue(300f-(5*widths.inactive+16f)<5f)
        assertEquals(TabWidths(0f,0f),tabWidths(300f,0,false))
    }
    @Test fun aSingleTabIsCappedAndTinyWindowsPreserveATouchTarget() {
        assertEquals(160f,tabWidths(500f,1,true).active,0f)
        assertEquals(48f,tabWidths(20f,1,true).active,0f)
    }
    @Test fun fractionalPixelDensitiesDoNotClipTheLastTouchTarget() {
        for(density in listOf(1.2f,1.5f,2.625f,2.75f,3.5f)) for(count in 2..6) {
            val available=104f+(count-1)*52f+4f
            val widths=tabWidths(available,count,true,density)
            val used=(widths.active*density).roundToInt()+(count-1)*((widths.inactive*density).roundToInt()+(4*density).roundToInt())
            assertTrue("$density: $used",used<=(available*density).roundToInt())
            assertTrue((widths.inactive*density).roundToInt()>=(48*density).roundToInt())
        }
    }
}
